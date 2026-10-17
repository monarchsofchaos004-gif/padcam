package com.padcam.cam

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 相機 → YUV → 旋轉 → 縮小 → JPEG（給 MJPEG 串流與快照）；麥克風 → PCM 16k 單聲道（給串流 WAV）。
 * 只在有人看的時候才開相機；沒人看約 12 秒自動關。系統會強制顯示相機/麥克風指示點，本 App 不隱藏。
 *
 * Camera → JPEG frames (MJPEG) and mic → 16 kHz mono PCM (streamed WAV). Camera opens lazily when a viewer
 * connects and closes ~12 s after the last viewer leaves. The OS camera/mic indicator is never hidden.
 */
object LiveCam {
    private const val TAG = "LiveCam"
    const val IDLE_MS = 12_000L
    const val AUDIO_RATE = 16000

    private val lock = Object()
    @Volatile var running = false; private set
    @Volatile var withAudio = false; private set
    @Volatile private var sessionStart = 0L
    @Volatile private var lastViewer = 0L
    @Volatile var longEdge = 480
    @Volatile var useBack = false

    private val frameLock = Object()
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var frameVer = 0L
    @Volatile private var lastJpegAt = 0L
    private val videoReaders = AtomicInteger(0)
    private val audioSubs = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var cam: CameraDevice? = null
    private var reader: ImageReader? = null
    private var session: CameraCaptureSession? = null
    private var audio: AudioRecord? = null
    private var audioThread: Thread? = null
    private var appCtx: Context? = null
    @Volatile private var rotateDeg = 0
    @Volatile var onStopped: (() -> Unit)? = null

    fun state(): org.json.JSONObject = org.json.JSONObject()
        .put("running", running).put("audio", withAudio)
        .put("viewers", videoReaders.get()).put("listeners", audioSubs.size)
        .put("elapsed_s", if (running) (now() - sessionStart) / 1000 else 0)

    private fun now() = System.currentTimeMillis()

    fun start(ctx: Context, withAud: Boolean): String = synchronized(lock) {
        appCtx = ctx.applicationContext
        lastViewer = now()
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return "no_camera_permission"
        if (withAud && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "no_mic_permission"
        if (!running) { sessionStart = now(); openCamera(); startWatchdog(); running = true }
        if (withAud) startAudio()
        return "ok"
    }

    fun stop() {
        synchronized(lock) {
            if (!running && cam == null) return
            running = false; withAudio = false
            try { session?.close() } catch (_: Exception) {}; session = null
            try { reader?.close() } catch (_: Exception) {}; reader = null
            try { cam?.close() } catch (_: Exception) {}; cam = null
            stopAudioLocked()
            try { camThread?.quitSafely() } catch (_: Exception) {}; camThread = null; camHandler = null
            latestJpeg = null
            Log.i(TAG, "stopped")
        }
        try { onStopped?.invoke() } catch (_: Exception) {}
    }

    fun touchViewer() { lastViewer = now() }
    fun addViewer() { videoReaders.incrementAndGet(); lastViewer = now() }
    fun removeViewer() { videoReaders.decrementAndGet(); lastViewer = now() }

    /** 等下一張 JPEG（最多 waitMs）；回 (frame, version)。 */
    fun nextFrame(sinceVer: Long, waitMs: Long): Pair<ByteArray?, Long> {
        synchronized(frameLock) {
            if (frameVer <= sinceVer) { try { frameLock.wait(waitMs) } catch (_: Exception) {} }
            return Pair(latestJpeg, frameVer)
        }
    }

    fun audioSubscribe(): LinkedBlockingQueue<ByteArray> {
        val q = LinkedBlockingQueue<ByteArray>(64); audioSubs.add(q)
        synchronized(lock) { withAudio = true; startAudio() }
        return q
    }

    fun audioUnsubscribe(q: LinkedBlockingQueue<ByteArray>) {
        audioSubs.remove(q)
        if (audioSubs.isEmpty()) synchronized(lock) { withAudio = false; stopAudioLocked() }
    }

    private fun openCamera() {
        val mgr = appCtx!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (useBack) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: mgr.cameraIdList.firstOrNull() ?: return
        val chars = mgr.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        // 抓 ≤1280x720 等級就夠（JPEG 會再縮），省 CPU
        val size = sizes.filter { it.width.toLong() * it.height <= 1280L * 720 }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: return
        rotateDeg = ((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) + 360) % 360
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val fpsRange = fpsRanges?.filter { it.upper <= 30 }?.maxByOrNull { it.upper } ?: fpsRanges?.firstOrNull()
        camThread = HandlerThread("padcam").apply { start() }; camHandler = Handler(camThread!!.looper)
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ r ->
                var img: Image? = null
                // 整段在 lock 底下做：stop() 關 reader 的同時還在讀 Image 的 buffer 會 SIGSEGV 整個程序死
                synchronized(lock) {
                    try {
                        if (!running || reader !== r) return@synchronized
                        val im = r.acquireLatestImage() ?: return@synchronized
                        img = im
                        val t = now()
                        if (t - lastJpegAt < 250 || videoReaders.get() == 0) return@synchronized
                        val w = im.width; val h = im.height
                        if (im.format != ImageFormat.YUV_420_888 || im.planes.size < 3) return@synchronized
                        val nv21 = toNv21(im, w, h)
                        val data: ByteArray; val ow: Int; val oh: Int
                        when (rotateDeg) {
                            90 -> { data = rotNv21(nv21, w, h, 90); ow = h; oh = w }
                            180 -> { data = rotNv21(nv21, w, h, 180); ow = w; oh = h }
                            270 -> { data = rotNv21(nv21, w, h, 270); ow = h; oh = w }
                            else -> { data = nv21; ow = w; oh = h }
                        }
                        lastJpegAt = t
                        makeJpeg(data, ow, oh)
                    } catch (e: Exception) { Log.w(TAG, "img: $e") } finally { try { img?.close() } catch (_: Exception) {} }
                }
            }, camHandler)
        }
        try {
            @Suppress("MissingPermission")
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    cam = c
                    try {
                        val surf = reader!!.surface
                        c.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                val rq = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                rq.addTarget(surf)
                                if (fpsRange != null) rq.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                                try { s.setRepeatingRequest(rq.build(), null, camHandler) } catch (e: Exception) { Log.w(TAG, "repeat: $e") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { Log.w(TAG, "session cfg fail") }
                        }, camHandler)
                    } catch (e: Exception) { Log.w(TAG, "session: $e") }
                }
                override fun onDisconnected(c: CameraDevice) { try { c.close() } catch (_: Exception) {}; if (cam == c) cam = null }
                override fun onError(c: CameraDevice, e: Int) { Log.w(TAG, "cam err $e"); try { c.close() } catch (_: Exception) {}; if (cam == c) cam = null }
            }, camHandler)
        } catch (e: Exception) { Log.w(TAG, "open: $e") }
    }

    private fun makeJpeg(nv21: ByteArray, w: Int, h: Int) {
        try {
            val scale = Math.max(1, Math.max(w, h) / longEdge)
            val (data, ow, oh) = if (scale <= 1) Triple(nv21, w, h) else downscaleNv21(nv21, w, h, scale)
            val bo = ByteArrayOutputStream()
            if (YuvImage(data, ImageFormat.NV21, ow, oh, null).compressToJpeg(Rect(0, 0, ow, oh), 72, bo))
                synchronized(frameLock) { latestJpeg = bo.toByteArray(); frameVer++; frameLock.notifyAll() }
        } catch (e: Exception) { Log.w(TAG, "jpeg: $e") }
    }

    private fun downscaleNv21(src: ByteArray, w: Int, h: Int, s: Int): Triple<ByteArray, Int, Int> {
        val nw = w / s / 2 * 2; val nh = h / s / 2 * 2
        val out = ByteArray(nw * nh + nw * nh / 2); val fs = w * h
        var o = 0
        for (y in 0 until nh) { val sy = y * s; val base = sy * w; for (x in 0 until nw) out[o++] = src[base + x * s] }
        val cfs = nw * nh; val cw = nw / 2
        for (y in 0 until nh / 2) { val sy = y * s; for (x in 0 until cw) { val sp = fs + sy * w + x * s * 2; out[cfs + y * nw + x * 2] = src[sp]; out[cfs + y * nw + x * 2 + 1] = src[sp + 1] } }
        return Triple(out, nw, nh)
    }

    private fun toNv21(image: Image, w: Int, h: Int): ByteArray {
        val ySize = w * h; val nv21 = ByteArray(ySize + ySize / 2)
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yBuf = yP.buffer; val yRow = yP.rowStride
        if (yRow == w) { yBuf.get(nv21, 0, ySize) } else { for (row in 0 until h) { yBuf.position(row * yRow); yBuf.get(nv21, row * w, w) } }
        val uBuf = uP.buffer; val vBuf = vP.buffer
        val uRow = uP.rowStride; val vRow = vP.rowStride; val uPix = uP.pixelStride; val vPix = vP.pixelStride
        val cw = w / 2; val ch = h / 2; var out = ySize
        for (r in 0 until ch) {
            var uIdx = r * uRow; var vIdx = r * vRow
            for (c in 0 until cw) { nv21[out++] = vBuf.get(vIdx); nv21[out++] = uBuf.get(uIdx); uIdx += uPix; vIdx += vPix }
        }
        return nv21
    }

    private fun rotNv21(src: ByteArray, w: Int, h: Int, deg: Int): ByteArray {
        val out = ByteArray(src.size); val fs = w * h; val cw = w / 2; val ch = h / 2
        when (deg) {
            90 -> { var i = 0; for (x in 0 until w) for (y in h - 1 downTo 0) out[i++] = src[y * w + x]
                var j = fs; for (x in 0 until cw) for (y in ch - 1 downTo 0) { val p = fs + y * w + x * 2; out[j++] = src[p]; out[j++] = src[p + 1] } }
            180 -> { var i = 0; for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) out[i++] = src[y * w + x]
                var j = fs; for (y in ch - 1 downTo 0) for (x in cw - 1 downTo 0) { val p = fs + y * w + x * 2; out[j++] = src[p]; out[j++] = src[p + 1] } }
            else -> { var i = 0; for (x in w - 1 downTo 0) for (y in 0 until h) out[i++] = src[y * w + x]
                var j = fs; for (x in cw - 1 downTo 0) for (y in 0 until ch) { val p = fs + y * w + x * 2; out[j++] = src[p]; out[j++] = src[p + 1] } }
        }
        return out
    }

    /** 呼叫端必須已持有 lock。 */
    private fun startAudio() {
        if (audio != null) return
        val ctx = appCtx ?: return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val min = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val a = try {
            @Suppress("MissingPermission")
            AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 2)
        } catch (e: Exception) { Log.w(TAG, "arec: $e"); return }
        if (a.state != AudioRecord.STATE_INITIALIZED) { try { a.release() } catch (_: Exception) {}; return }
        audio = a; a.startRecording()
        audioThread = Thread {
            val buf = ByteArray(3200)
            while (audio === a) {
                val n = try { a.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n <= 0) break
                val chunk = buf.copyOf(n)
                for (q in audioSubs) { if (!q.offer(chunk)) { q.poll(); q.offer(chunk) } }
            }
        }.apply { isDaemon = true; start() }
        Log.i(TAG, "audio on")
    }

    private fun stopAudioLocked() {
        val a = audio; audio = null
        try { a?.stop() } catch (_: Exception) {}; try { a?.release() } catch (_: Exception) {}
        audioThread = null
    }

    private fun startWatchdog() {
        Thread {
            while (running) {
                try { Thread.sleep(2000) } catch (_: Exception) {}
                if (!running) break
                if (videoReaders.get() == 0 && audioSubs.isEmpty() && now() - lastViewer > IDLE_MS) { stop(); break }
            }
        }.apply { isDaemon = true }.start()
    }
}
