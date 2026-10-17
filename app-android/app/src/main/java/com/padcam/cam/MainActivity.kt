package com.padcam.cam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface

/** 設定畫面：授權、啟停伺服器、顯示可用網址、連結密碼、埠、鏡頭、畫質、開機自啟。 */
class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvUrls: TextView
    private lateinit var etPort: EditText
    private lateinit var cbBack: CheckBox
    private lateinit var cbHd: CheckBox
    private lateinit var cbBoot: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 64) }
        tvStatus = TextView(this).apply { textSize = 15f }
        root.addView(tvStatus); root.addView(space())

        root.addView(btn("① 授權相機／麥克風／通知") { askPerms() })
        root.addView(btn("▶ 啟動看顧伺服器") { CamService.start(this); tvStatus.postDelayed({ refresh() }, 800) })
        root.addView(btn("■ 停止") { CamService.stop(this); tvStatus.postDelayed({ refresh() }, 800) })
        root.addView(space())

        root.addView(TextView(this).apply { text = "用手機瀏覽器開其中一個網址（同一個 WiFi，或兩邊都裝 Tailscale）："; textSize = 14f })
        tvUrls = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        root.addView(tvUrls)
        root.addView(btn("重新產生連結密碼（舊網址立刻失效）") { Prefs.regenToken(this); refresh() })
        root.addView(space())

        root.addView(TextView(this).apply { text = "埠（改完要重新啟動）："; textSize = 14f })
        etPort = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(Prefs.port(this@MainActivity).toString()) }
        root.addView(etPort)
        cbBack = CheckBox(this).apply { text = "用後鏡頭（預設前鏡頭）"; isChecked = Prefs.lens(this@MainActivity) == "back" }
        cbHd = CheckBox(this).apply { text = "較清楚畫質（960px，較耗電／流量）"; isChecked = Prefs.longEdge(this@MainActivity) >= 960 }
        cbBoot = CheckBox(this).apply { text = "開機自動啟動"; isChecked = Prefs.bootStart(this@MainActivity) }
        root.addView(cbBack); root.addView(cbHd); root.addView(cbBoot)
        root.addView(btn("儲存設定") {
            Prefs.setPort(this, etPort.text.toString().toIntOrNull() ?: 8080)
            Prefs.setLens(this, if (cbBack.isChecked) "back" else "front")
            Prefs.setLongEdge(this, if (cbHd.isChecked) 960 else 480)
            Prefs.setBootStart(this, cbBoot.isChecked)
            LiveCam.longEdge = Prefs.longEdge(this); LiveCam.useBack = cbBack.isChecked
            refresh()
        })
        root.addView(space())
        root.addView(TextView(this).apply {
            textSize = 12.5f
            text = "· 只有知道完整網址（含連結密碼）的人連得到；影像不經任何雲端。\n· 有人看的時候平板會亮系統的相機／麥克風指示點；沒人看約 12 秒自動關相機。\n· 建議平板插電、關閉本 App 的電池最佳化。"
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun askPerms() {
        val need = ArrayList<String>()
        for (p in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh()
    }

    private fun refresh() {
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val st = CamService.status
        tvStatus.text = buildString {
            append(if (cam) "✓ 相機已授權\n" else "✗ 相機未授權（點①）\n")
            append(if (mic) "✓ 麥克風已授權\n" else "✗ 麥克風未授權（點①；只看影像可不給）\n")
            append(if (CamService.instance != null) "✓ 伺服器執行中（$st）\n" else "· 伺服器未啟動\n")
            if (LiveCam.running) append("● 相機開著（${LiveCam.state().optInt("viewers")} 人在看）\n")
        }
        val port = Prefs.port(this); val tok = Prefs.token(this)
        val urls = ipv4s().joinToString("\n") { "http://$it:$port/$tok/" }
        tvUrls.text = if (urls.isEmpty()) "（找不到網路位址，請先連 WiFi）" else urls
    }

    private fun ipv4s(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address) out.add(a.hostAddress ?: continue)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply { text = label; setOnClickListener { onClick() } }
    private fun space(): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 28) }
}
