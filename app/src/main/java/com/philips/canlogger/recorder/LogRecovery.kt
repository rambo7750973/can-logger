package com.philips.canlogger.recorder

import com.philips.canlogger.model.TimeText
import com.philips.canlogger.util.AppLog
import java.io.File

/**
 * 异常退出恢复。
 *
 * 录制中会留下 `<session>.open` 标记文件；正常停止时删除。
 * 若启动时还能看到标记，说明上次是断电/强杀，给对应日志补一行 `# INCOMPLETE`，
 * 并把已有内容补上 `# ---- END ----`，PC 端脚本就不会把残文件当成正常文件。
 */
object LogRecovery {

    fun recover(baseDir: File) {
        try {
            if (!baseDir.exists()) return
            val markers = baseDir.walkTopDown().filter { it.isFile && it.name.endsWith(".open") }.toList()
            for (m in markers) {
                val base = m.name.removeSuffix(".open")
                val log = File(m.parentFile, "$base.log")
                val now = TimeText.wall(System.currentTimeMillis())
                if (log.exists()) {
                    log.appendText(
                        "# INCOMPLETE 上次录制未正常结束（断电/强杀/崩溃），以下为已落盘数据\n" +
                            "# ---- END ----\n" +
                            "# recovered=$now\n"
                    )
                    AppLog.w("检测到未正常结束的录制: ${log.name}，已补 INCOMPLETE 标记")
                } else {
                    AppLog.w("标记 ${m.name} 找不到对应日志，已清理")
                }
                m.delete()
            }
        } catch (e: Exception) {
            AppLog.w("恢复异常日志失败: ${e.message}")
        }
    }
}
