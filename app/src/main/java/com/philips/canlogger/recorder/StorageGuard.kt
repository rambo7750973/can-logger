package com.philips.canlogger.recorder

import android.content.Context
import android.os.Build
import android.os.Environment
import com.philips.canlogger.util.AppLog
import java.io.File

/**
 * 存储目录选择 + 空间保护。
 *
 * 目录优先级：
 *  1. /sdcard/CanLog            公共目录，方便拔卡/ADB 取（Android 11+ 需「全盘权限」）
 *  2. /sdcard/Android/data/<pkg>/files/CanLog   App 外部私有目录，无需权限
 *  3. /data/data/<pkg>/files/CanLog             内部存储兜底
 */
object StorageGuard {

    private const val MIN_FREE_BYTES = 200L * 1024 * 1024 // 剩余低于 200MB 停止录制

    fun resolveBaseDir(ctx: Context): File {
        val candidates = ArrayList<File>()

        @Suppress("DEPRECATION")
        val ext = Environment.getExternalStorageDirectory()
        candidates.add(File(ext, "CanLog"))
        ctx.getExternalFilesDir(null)?.let { candidates.add(File(it, "CanLog")) }
        candidates.add(File(ctx.filesDir, "CanLog"))

        for (c in candidates) {
            if (ensure(c)) {
                AppLog.i("日志目录: ${c.absolutePath}")
                return c
            }
        }
        // 极端情况：内部目录都不行
        val fallback = File(ctx.filesDir, "CanLog")
        fallback.mkdirs()
        return fallback
    }

    private fun ensure(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val probe = File(dir, ".wtest")
            probe.writeText("1")
            probe.delete()
            true
        } catch (e: Exception) {
            AppLog.w("目录不可写 ${dir.absolutePath}: ${e.message}")
            false
        }
    }

    fun freeBytes(dir: File): Long = try {
        dir.usableSpace
    } catch (e: Exception) {
        0L
    }

    /** 录制前检查，返回 null 表示可以开始，否则返回原因 */
    fun checkBeforeStart(dir: File): String? {
        if (!dir.exists() && !dir.mkdirs()) return "无法创建日志目录：${dir.absolutePath}"
        if (!dir.canWrite()) return "日志目录不可写：${dir.absolutePath}"
        val free = freeBytes(dir)
        if (free < MIN_FREE_BYTES) {
            return "存储剩余空间不足（${free / 1024 / 1024}MB），请清理后再录"
        }
        return null
    }

    /** 录制期间周期性检查，避免长时间录制耗尽剩余空间。 */
    fun checkDuringRecording(dir: File): String? {
        val free = freeBytes(dir)
        if (free < MIN_FREE_BYTES) {
            return "存储剩余空间不足（${free / 1024 / 1024}MB），已停止录制以保护日志"
        }
        return null
    }

    /**
     * 总量超限时删除最旧的日志（按修改时间），保留当前正在写的文件。
     */
    fun trim(dir: File, maxTotalBytes: Long, keep: File?) {
        try {
            val files = dir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".csv") || it.name.endsWith(".summary.txt")) }
                .sortedBy { it.lastModified() }
                .toList()

            var total = files.sumOf { it.length() }
            if (total <= maxTotalBytes) return

            for (f in files) {
                if (f == keep) continue
                val size = f.length()
                if (f.delete()) {
                    total -= size
                    AppLog.i("清理旧日志: ${f.name} (${size / 1024}KB)")
                }
                if (total <= maxTotalBytes) break
            }
        } catch (e: Exception) {
            AppLog.w("清理日志失败: ${e.message}")
        }
    }

    fun canWriteAllFiles(): Boolean {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    }

    /** 最近一次的日志文件（导出用） */
    fun newestLog(dir: File): File? = try {
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".log") }
            .maxByOrNull { it.lastModified() }
    } catch (e: Exception) {
        null
    }
}
