package com.philips.canlogger.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部可配置项集中在这里，UI 只负责读写。
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    companion object {
        private const val NAME = "canlog_prefs"
        const val TRANSPORT_SERIAL = "serial"
        const val TRANSPORT_USB = "usb"

        @Volatile
        private var inst: Prefs? = null

        fun of(ctx: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE))
                    .also { inst = it }
            }
    }

    var transport: String
        get() = sp.getString("transport", TRANSPORT_SERIAL) ?: TRANSPORT_SERIAL
        set(v) = sp.edit().putString("transport", v).apply()

    var port: String
        get() = sp.getString("port", "/dev/ttyS1") ?: "/dev/ttyS1"
        set(v) = sp.edit().putString("port", v).apply()

    var baud: Int
        get() = sp.getInt("baud", 115200)
        set(v) = sp.edit().putInt("baud", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", false)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    var csv: Boolean
        get() = sp.getBoolean("csv", false)
        set(v) = sp.edit().putBoolean("csv", v).apply()

    /** 逗号分隔的十六进制 ID 列表，空 = 不过滤 */
    var filterIds: String
        get() = sp.getString("filter_ids", "") ?: ""
        set(v) = sp.edit().putString("filter_ids", v).apply()

    var maxTotalMb: Int
        get() = sp.getInt("max_total_mb", 500)
        set(v) = sp.edit().putInt("max_total_mb", v).apply()

    var maxFileMb: Int
        get() = sp.getInt("max_file_mb", 50)
        set(v) = sp.edit().putInt("max_file_mb", v).apply()

    /** 上次使用的保存目录，用于导出时定位 */
    var lastDir: String?
        get() = sp.getString("last_dir", null)
        set(v) = sp.edit().putString("last_dir", v).apply()
}
