package com.philips.canlogger.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.philips.canlogger.util.AppLog
import java.io.File

/**
 * 日志导出：系统分享（微信/U盘App/邮件）+ 直接复制到 U 盘挂载点。
 */
object Exporter {

    fun share(ctx: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "导出日志")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        } catch (e: Exception) {
            AppLog.e("分享日志失败", e)
        }
    }

    /** 常见的 U 盘挂载点（不同车机 ROM 命名不统一，全部试一遍） */
    fun usbRoots(): List<File> {
        val fixed = listOf(
            "/mnt/usbhost1", "/mnt/usbhost0", "/mnt/usb", "/mnt/udisk", "/mnt/sda1", "/mnt/sdb1",
            "/storage/udisk", "/storage/usbdisk", "/storage/usb0", "/storage/sda1",
            "/mnt/media_rw"
        ).map { File(it) }

        val scanned = ArrayList<File>()
        try {
            File("/storage").listFiles()?.forEach { f ->
                val n = f.name.lowercase()
                if (f.isDirectory && !n.contains("emulated") && !n.contains("self") && n.length == 9) {
                    // 形如 /storage/1A2B-3C4D 的外接卡/U盘
                    scanned.add(f)
                }
            }
        } catch (_: Exception) {
        }

        return (fixed + scanned).filter { it.isDirectory && it.canWrite() }.distinct()
    }

    /**
     * 复制到第一个可写的 U 盘根目录。
     * @return 目标文件，失败返回 null
     */
    fun copyToUsb(ctx: Context, file: File): File? {
        for (root in usbRoots()) {
            try {
                val dir = File(root, "CanLog")
                if (!dir.exists() && !dir.mkdirs()) continue
                val dst = File(dir, file.name)
                file.inputStream().use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                AppLog.i("已复制到 U 盘: ${dst.absolutePath}")
                return dst
            } catch (e: Exception) {
                AppLog.w("复制到 ${root.absolutePath} 失败: ${e.message}")
            }
        }
        return null
    }
}
