package com.padcam.cam

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** 本地設定：連結密碼(token)、埠、鏡頭、畫質、開機自啟。 */
object Prefs {
    private const val FILE = "padcam_prefs"

    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 連結密碼：網址路徑的第一段，不知道就連不到任何東西。首次自動產生。 */
    fun token(ctx: Context): String {
        val s = sp(ctx)
        var t = s.getString("token", null)
        if (t.isNullOrBlank()) { t = randomToken(); s.edit().putString("token", t).apply() }
        return t
    }

    fun regenToken(ctx: Context): String {
        val t = randomToken(); sp(ctx).edit().putString("token", t).apply(); return t
    }

    fun port(ctx: Context): Int = sp(ctx).getInt("port", 8080)
    fun setPort(ctx: Context, p: Int) { sp(ctx).edit().putInt("port", p.coerceIn(1024, 65535)).apply() }

    /** "front" 或 "back" */
    fun lens(ctx: Context): String = sp(ctx).getString("lens", "front") ?: "front"
    fun setLens(ctx: Context, v: String) { sp(ctx).edit().putString("lens", if (v == "back") "back" else "front").apply() }

    /** JPEG 長邊像素：480（省電省流量）／960（清楚） */
    fun longEdge(ctx: Context): Int = sp(ctx).getInt("edge", 480)
    fun setLongEdge(ctx: Context, v: Int) { sp(ctx).edit().putInt("edge", if (v >= 960) 960 else 480).apply() }

    fun bootStart(ctx: Context): Boolean = sp(ctx).getBoolean("boot", false)
    fun setBootStart(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("boot", v).apply() }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}
