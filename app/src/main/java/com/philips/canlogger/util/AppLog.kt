package com.philips.canlogger.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 自身运行日志。
 *
 * 与 CAN 报文日志分开：这里只记录程序行为（打开串口、重连、异常等），
 * 滚动保留 [MAX_FILES] 份 × [MAX_BYTES]，避免撑爆车机存储。
 */
object AppLog {

    private const val TAG = "CanLogger"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 5

    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var dir: File? = null

    fun init(d: File) {
        synchronized(lock) {
            dir = d
            if (!d.exists()) d.mkdirs()
            rotateIfNeeded()
        }
    }

    fun i(msg: String) = write("I", msg, null)

    fun w(msg: String) = write("W", msg, null)

    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    private fun write(level: String, msg: String, t: Throwable?) {
        try {
            val pri = when (level) {
                "E" -> Log.ERROR
                "W" -> Log.WARN
                else -> Log.INFO
            }
            Log.println(pri, TAG, msg + (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        } catch (_: Throwable) {
        }

        val d = dir ?: return
        val line = buildString {
            append(synchronized(fmt) { fmt.format(Date()) })
            append(' ').append(level).append(' ').append(msg)
            if (t != null) {
                append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
                t.stackTrace.firstOrNull()?.let { append(" @").append(it) }
            }
        }
        synchronized(lock) {
            try {
                rotateIfNeeded()
                File(d, "app.log").appendText(line + "\n")
            } catch (_: Throwable) {
                // 日志自身失败不能影响主流程
            }
        }
    }

    private fun rotateIfNeeded() {
        val d = dir ?: return
        val f = File(d, "app.log")
        if (f.exists() && f.length() > MAX_BYTES) {
            for (i in MAX_FILES - 1 downTo 1) {
                val old = File(d, "app.log.$i")
                if (old.exists()) old.renameTo(File(d, "app.log.${i + 1}"))
            }
            f.renameTo(File(d, "app.log.1"))
        }
    }
}
