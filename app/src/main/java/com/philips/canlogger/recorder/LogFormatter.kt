package com.philips.canlogger.recorder

import com.philips.canlogger.model.CanFrame
import com.philips.canlogger.model.Dir
import com.philips.canlogger.model.RawRecord
import com.philips.canlogger.model.TimeText
import java.util.Locale

/**
 * 日志格式化。格式一经定稿不要随意改，PC 端脚本依赖它。
 *
 * TXT（默认，人可读、可直接 grep）：
 *   0000012345 RX 7DF 8 02 01 0C 00 00 00 00 00
 *   0000012358 RX 18FEF100x 8 FF 00 00 00 00 00 00 00
 *   0000012361 RX RAW 2E 0C 24 ...
 *
 * CSV（勾选后额外输出一份）：
 *   uptime_ms,wall_time,dir,can_id,ext,dlc,data,raw
 */
class LogFormatter(val csv: Boolean) {

    fun header(vehicle: String, port: String, baud: Int, startedAt: Long, part: Int): String {
        return buildString {
            appendLine("# app=CanLogger v1.0")
            appendLine("# vehicle=$vehicle")
            appendLine("# port=$port baud=$baud 8N1")
            appendLine("# started=${TimeText.wall(startedAt)}")
            if (part > 1) appendLine("# part=$part")
            appendLine("# format=uptime_ms|dir|can_id|dlc|data")
            appendLine("# note=扩展帧 ID 结尾带 x; 未识别数据行为 RAW/TEXT/BIN2E")
            appendLine("# ---- BEGIN ----")
        }
    }

    fun csvHeader(): String = "uptime_ms,wall_time,dir,can_id,ext,dlc,data,raw\n"

    fun frame(f: CanFrame): String {
        val data = f.dataHex()
        return if (data.isEmpty()) {
            String.format(Locale.US, "%010d %s %s %d", f.uptimeMs, dirText(f.dir), f.idText, f.dlc)
        } else {
            String.format(Locale.US, "%010d %s %s %d %s", f.uptimeMs, dirText(f.dir), f.idText, f.dlc, data)
        }
    }

    fun raw(r: RawRecord): String =
        String.format(Locale.US, "%010d %s %s %s", r.uptimeMs, dirText(r.dir), r.note, r.hex())

    fun frameCsv(f: CanFrame): String = String.format(
        Locale.US, "%d,\"%s\",%s,%s,%d,%d,\"%s\",\"\"\n",
        f.uptimeMs, TimeText.wall(f.wallMs), dirText(f.dir),
        String.format(Locale.US, "%X", f.id), if (f.extended) 1 else 0, f.dlc, f.dataHex()
    )

    fun rawCsv(r: RawRecord): String = String.format(
        Locale.US, "%d,\"%s\",%s,,,,,\"%s\"\n",
        r.uptimeMs, TimeText.wall(r.wallMs), dirText(r.dir), r.hex()
    )

    fun trailer(stats: SessionStats, reason: String): String {
        val dur = TimeText.duration(stats.stoppedAt - stats.startedAt)
        return buildString {
            appendLine("# ---- END ----")
            appendLine("# frames=${stats.frames} dropped=${stats.dropped} bytes=${stats.bytes} duration=$dur reconnects=${stats.reconnects}")
            appendLine("# stopped=${TimeText.wall(stats.stoppedAt)} reason=$reason")
        }
    }

    private fun dirText(d: Dir) = if (d == Dir.RX) "RX" else "TX"
}

/** 一次录制的统计，写文件尾和 summary.txt 用 */
data class SessionStats(
    var startedAt: Long = 0,
    var stoppedAt: Long = 0,
    var frames: Long = 0,
    var dropped: Long = 0,
    var bytes: Long = 0,
    var reconnects: Int = 0
)
