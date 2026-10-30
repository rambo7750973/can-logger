package com.philips.canlogger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.philips.canlogger.util.AppLog
import com.philips.canlogger.util.Prefs

/**
 * 车机开机后自动开始录制（可在设置里关闭，默认关闭）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = Prefs.of(context)
        if (!prefs.autoStart) {
            AppLog.i("开机事件收到，但未开启自动录制")
            return
        }

        AppLog.i("开机自动开始录制")
        try {
            val i = Intent(context, LogService::class.java).setAction(LogService.ACTION_START)
            ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            AppLog.e("开机启动服务失败", e)
        }
    }
}
