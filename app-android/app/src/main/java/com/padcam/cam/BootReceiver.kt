package com.padcam.cam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 開機自啟（可在設定關閉）。Android 14 起系統可能不允許從背景啟動相機類前景服務；失敗就等人手動開 App。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && Prefs.bootStart(context)) {
            try { CamService.start(context) } catch (e: Exception) { }
        }
    }
}
