package com.philips.canlogger.model

import java.util.Locale

enum class Dir { RX, TX }

/**
 * 一条 CAN 帧。
 *
 * @param uptimeMs 开机单调时钟毫秒（SystemClock.elapsedRealtime），不受系统改时间影响，
 *                 日志第一列用的就是它，做差分算周期最可靠。
 * @param wallMs   墙钟毫秒，用于和视频/现场现象对齐。
 * @param id       CAN ID（标准 11 位 / 扩展 29 位，均按无符号存 Int）。
 * @param extended 是否扩展帧。
 * @param dlc      数据长度 0..8（CAN FD 未支持，出现 >8 时按实际字节数记录）。
 * @param data     数据字节。
 */
class CanFrame(
    val uptimeMs: Long,
    val wallMs: Long,
    val dir: Dir,
    val id: Int,
    val extended: Boolean,
    val dlc: Int,
    val data: ByteArray
) {
    val idText: String
        get() = if (extended) String.format(Locale.US, "%08Xx", id)
        else String.format(Locale.US, "%03X", id)

    fun dataHex(): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(data.size * 3)
        for (b in data) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    val wallText: String
        get() = TimeText.wall(wallMs)
}

/**
 * 原始字节（未识别协议 / 二进制帧）——原样保存，便于事后反推协议盒格式。
 */
class RawRecord(
    val uptimeMs: Long,
    val wallMs: Long,
    val dir: Dir,
    val bytes: ByteArray,
    val note: String
) {
    fun hex(): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}

object TimeText {
    private val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val dayFmt = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)

    fun wall(ms: Long): String = synchronized(fmt) { fmt.format(java.util.Date(ms)) }

    fun stamp(ms: Long): String = synchronized(fileFmt) { fileFmt.format(java.util.Date(ms)) }

    fun day(ms: Long): String = synchronized(dayFmt) { dayFmt.format(java.util.Date(ms)) }

    /** 00:12:34 */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}
