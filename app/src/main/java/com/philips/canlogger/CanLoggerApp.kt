package com.philips.canlogger

import android.app.Application
import com.philips.canlogger.util.AppLog
import java.io.File

class CanLoggerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        AppLog.init(File(filesDir, "logs"))

        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("未捕获异常 @${t.name}", e)
            def?.uncaughtException(t, e)
        }
    }
}
