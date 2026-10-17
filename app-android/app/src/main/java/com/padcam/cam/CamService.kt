package com.padcam.cam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 常駐前景服務＝一個很小的 HTTP 伺服器，跑在平板自己身上：
 *   http://<平板IP>:<埠>/<連結密碼>/            看顧網頁（手機瀏覽器直接開）
 *   .../video.mjpg   MJPEG 影像      .../audio.wav   串流 WAV 聲音
 *   .../snapshot.jpg 單張快照        .../state       狀態 JSON      .../stop  關相機
 * 不經任何雲端或第三方伺服器；同一個 WiFi、或兩邊都裝 Tailscale 就能看。
 */
class CamService : Service() {
    private var server: ServerSocket? = null
    private var token = ""
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        // Android 14 起：前景服務型別對應的執行期權限沒給就會直接 SecurityException → 先檢查，沒相機權限就不起
        val hasCam = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCam) { status = "no camera permission"; stopSelf(); return }
        val n = buildNotif("待命中（有人連進來才開相機）")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (hasMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(1, n, types)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else
                startForeground(1, n)
        } catch (e: Exception) { status = "foreground refused: $e"; stopSelf(); return }
        token = Prefs.token(this)
        LiveCam.longEdge = Prefs.longEdge(this)
        LiveCam.useBack = Prefs.lens(this) == "back"
        LiveCam.onStopped = { updateNotif("待命中（有人連進來才開相機）") }
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "padcam:serve").apply { acquire() }
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "padcam:wifi").apply { acquire() }
        } catch (e: Exception) { Log.w(TAG, "locks: $e") }
        Thread { serve(Prefs.port(this)) }.apply { isDaemon = true; start() }
    }

    private fun serve(port: Int) {
        try {
            val ss = ServerSocket(); ss.reuseAddress = true; ss.bind(InetSocketAddress("0.0.0.0", port)); server = ss
            status = "listening:$port"
            while (true) { val c = ss.accept(); Thread { handle(c) }.apply { isDaemon = true }.start() }
        } catch (e: Exception) { status = "server died: $e"; Log.e(TAG, "server died", e) }
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 15000
            val i = c.getInputStream().bufferedReader(Charsets.UTF_8)
            val req = i.readLine() ?: return
            while (true) { val l = i.readLine() ?: break; if (l.isEmpty()) break }   // 丟掉 headers
            val parts = req.split(" ")
            if (parts.size < 2) return
            val full = parts[1]
            val path = full.substringBefore('?'); val qs = full.substringAfter('?', "")
            val q = HashMap<String, String>()
            for (kv in qs.split('&')) { if (kv.isEmpty()) continue; val k = kv.substringBefore('='); val v = kv.substringAfter('=', ""); q[k] = URLDecoder.decode(v, "UTF-8") }
            val out = c.getOutputStream()
            val segs = path.trimStart('/').split('/', limit = 2)
            // 第一段必須＝連結密碼；錯了一律 404（不透露有沒有這個服務）
            if (segs.isEmpty() || segs[0] != token) { text(out, "not found", 404); return }
            val sub = if (segs.size > 1) segs[1] else ""
            if (sub.isEmpty() && !path.endsWith("/")) { redirect(out, "/$token/"); return }
            when (sub) {
                "" -> html(out, assets.open("viewer.html").readBytes())
                "video.mjpg" -> liveVideo(out)
                "audio.wav" -> liveAudio(out)
                "snapshot.jpg" -> snapshot(out)
                "state" -> json(out, LiveCam.state().put("ok", true).put("server", status))
                "stop" -> { LiveCam.stop(); json(out, LiveCam.state().put("ok", true)) }
                else -> text(out, "not found", 404)
            }
        } catch (e: Exception) {
            try { json(c.getOutputStream(), JSONObject().put("ok", false).put("err", e.toString()), 500) } catch (_: Exception) {}
        } finally { try { c.close() } catch (_: Exception) {} }
    }

    private fun liveVideo(o: OutputStream) {
        val r = LiveCam.start(applicationContext, false)
        if (r != "ok") { json(o, JSONObject().put("ok", false).put("err", r), 503); return }
        updateNotif("看顧中（有人在看）")
        LiveCam.addViewer()
        try {
            o.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=padframe\r\nCache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
            var ver = 0L
            while (true) {
                val (b, nv) = LiveCam.nextFrame(ver, 5000); ver = nv
                if (b == null) { Thread.sleep(50); continue }
                LiveCam.touchViewer()
                o.write("--padframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray())
                o.write(b); o.write("\r\n".toByteArray()); o.flush()
            }
        } catch (_: Exception) {} finally { LiveCam.removeViewer() }
    }

    private fun snapshot(o: OutputStream) {
        val r = LiveCam.start(applicationContext, false)
        if (r != "ok") { json(o, JSONObject().put("ok", false).put("err", r), 503); return }
        LiveCam.addViewer()
        try {
            var ver = 0L; var b: ByteArray? = null
            for (k in 0 until 40) { val (f, nv) = LiveCam.nextFrame(ver, 500); ver = nv; if (f != null) { b = f; break } }
            if (b == null) { json(o, JSONObject().put("ok", false).put("err", "no frame"), 503); return }
            o.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            o.write(b); o.flush()
        } catch (_: Exception) {} finally { LiveCam.removeViewer() }
    }

    private fun wavHeader(rate: Int): ByteArray {
        val big = 0x7fffffff; val bo = ByteArrayOutputStream()
        fun s(x: String) = bo.write(x.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) { bo.write(v and 0xff); bo.write((v shr 8) and 0xff); bo.write((v shr 16) and 0xff); bo.write((v shr 24) and 0xff) }
        fun le16(v: Int) { bo.write(v and 0xff); bo.write((v shr 8) and 0xff) }
        s("RIFF"); le32(big); s("WAVE"); s("fmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16); s("data"); le32(big)
        return bo.toByteArray()
    }

    private fun liveAudio(o: OutputStream) {
        val r = LiveCam.start(applicationContext, true)
        if (r != "ok") { json(o, JSONObject().put("ok", false).put("err", r), 503); return }
        val q = LiveCam.audioSubscribe()
        try {
            o.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nCache-Control: no-cache, no-store\r\nConnection: close\r\n\r\n".toByteArray())
            o.write(wavHeader(LiveCam.AUDIO_RATE)); o.flush()
            while (true) {
                val chunk = q.poll(5, TimeUnit.SECONDS) ?: continue
                LiveCam.touchViewer(); o.write(chunk); o.flush()
            }
        } catch (_: Exception) {} finally { LiveCam.audioUnsubscribe(q) }
    }

    private fun json(o: OutputStream, j: JSONObject, code: Int = 200) {
        val b = j.toString().toByteArray(Charsets.UTF_8)
        o.write("HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray()); o.write(b); o.flush()
    }

    private fun text(o: OutputStream, s: String, code: Int = 200) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.write("HTTP/1.1 $code ${if (code == 404) "Not Found" else "OK"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); o.write(b); o.flush()
    }

    private fun html(o: OutputStream, b: ByteArray) {
        o.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray()); o.write(b); o.flush()
    }

    private fun redirect(o: OutputStream, loc: String) {
        o.write("HTTP/1.1 302 Found\r\nLocation: $loc\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); o.flush()
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher).setContentIntent(pi).setOngoing(true).build()
    }

    private fun updateNotif(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(1, buildNotif(text)) } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        status = "off"
        try { LiveCam.stop() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CamService"
        const val CHANNEL = "padcam"
        @Volatile var instance: CamService? = null
        @Volatile var status = "off"

        fun start(ctx: Context) {
            val i = Intent(ctx, CamService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CamService::class.java)) }
    }
}
