package com.philips.canlogger.transport

import com.philips.canlogger.model.CanFrame
import com.philips.canlogger.model.Dir
import com.philips.canlogger.model.RawRecord
import com.philips.canlogger.util.AppLog
import java.io.ByteArrayOutputStream

/**
 * 字节流 -> CAN 帧。
 *
 * 现实情况：不同协议盒的串口输出格式完全不一样，车载后装市场至少有十几种。
 * 这里采取「能认的解析成帧，认不出的原样保存」策略，保证第一次装车就能拿到完整数据：
 *
 *  - 文本行（自动识别多种常见写法）
 *      t7DF802010C00000000            candump 风格
 *      7DF#02010C00000000             socketcan 风格
 *      7DF 8 02 01 0C 00 00 00 00     「ID DLC 数据」
 *      7DF 02 01 0C 00 00 00 00
 *  - 二进制帧：0x2E 帧头（睿志诚等协议盒常用）按长度字段切帧后原样保存
 *  - 其它不可打印字节：按块原样保存（note=RAW）
 *
 * 保存的原始数据可以在 PROTOCOL.md 的指引下反推协议后扩展解析。
 */
class FrameParser(private val maxLineBytes: Int = 512) {

    interface Sink {
        fun onFrame(frame: CanFrame)
        fun onRaw(record: RawRecord)
    }

    private var buf = ByteArray(16384)
    private var len = 0
    private val line = ByteArrayOutputStream(512)

    private val reCandump = Regex("""^\([^)]*\)\s+\S+\s+(.+)$""")
    private val reHash = Regex("""^(?:0x)?([0-9A-Fa-f]{1,8})#([0-9A-Fa-f. ]*)$""")
    private val reCandumpT = Regex("""^[tT]([0-9A-Fa-f]+)$""")
    private val reSpace = Regex("""^(?:0x)?([0-9A-Fa-f]{1,8})[ ,;:]+((?:[0-9A-Fa-f]{1,2}[ ,;:]*)+)$""")

    fun feed(data: ByteArray, n: Int, uptimeMs: Long, wallMs: Long, sink: Sink) {
        if (n <= 0) return
        append(data, n)

        var i = 0
        loop@ while (i < len) {
            val b = buf[i].toInt() and 0xFF

            if (b == 0x2E && line.size() == 0) {
                // 0x2E 帧头：总长 = 2 + 长度字段
                if (len - i < 2) break@loop

                val plen = buf[i + 1].toInt() and 0xFF
                if (plen > 510) {
                    // 长度不合理，当普通垃圾字节处理
                    emitRaw(copyOf(i, 1), "RAW", uptimeMs, wallMs, sink)
                    i += 1
                } else {
                    val total = 2 + plen
                    if (len - i < total) break@loop
                    emitRaw(copyOf(i, total), "BIN2E", uptimeMs, wallMs, sink)
                    i += total
                }
            } else if (b == 0x0A || b == 0x0D) {
                flushLine(uptimeMs, wallMs, sink)
                i++
            } else if (b in 0x20..0x7E) {
                if (line.size() >= maxLineBytes) flushLine(uptimeMs, wallMs, sink)
                line.write(b)
                i++
            } else {
                flushLine(uptimeMs, wallMs, sink)

                var j = i
                while (j < len) {
                    val c = buf[j].toInt() and 0xFF
                    if (c == 0x2E || c == 0x0A || c == 0x0D || c in 0x20..0x7E) break
                    j++
                }
                if (j == i) j = i + 1
                emitRaw(copyOf(i, j - i), "RAW", uptimeMs, wallMs, sink)
                i = j
            }
        }

        shift(i)

        // 防御：缓冲区长期不被消费时强制丢弃，避免 OOM
        if (len > 65536) {
            AppLog.w("解析缓冲超过 64KB，强制清空")
            len = 0
        }
    }

    /** 停止录制时把残留的半行刷出来 */
    fun flush(uptimeMs: Long, wallMs: Long, sink: Sink) {
        flushLine(uptimeMs, wallMs, sink)
        if (len > 0) {
            emitRaw(copyOf(0, len), "RAW", uptimeMs, wallMs, sink)
            len = 0
        }
    }

    // ---------------------------------------------------------------- 文本解析

    private fun flushLine(uptimeMs: Long, wallMs: Long, sink: Sink) {
        val n = line.size()
        if (n == 0) return
        val bytes = line.toByteArray()
        line.reset()

        val text = String(bytes, Charsets.US_ASCII).trim()
        if (text.isEmpty()) return

        val frame = try {
            parseText(text, uptimeMs, wallMs)
        } catch (e: Exception) {
            AppLog.w("解析文本失败: $text -> ${e.message}")
            null
        }

        if (frame != null) {
            sink.onFrame(frame)
        } else {
            sink.onRaw(RawRecord(uptimeMs, wallMs, Dir.RX, bytes, "TEXT"))
        }
    }

    private fun parseText(raw: String, uptimeMs: Long, wallMs: Long): CanFrame? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        // "(123.456) can0 7DF#02010C" -> "7DF#02010C"
        reCandump.matchEntire(s)?.let { s = it.groupValues[1].trim() }

        // A. 7DF#02010C  /  7DF#8.02.01
        reHash.matchEntire(s)?.let { m ->
            val idStr = m.groupValues[1]
            val dataHex = m.groupValues[2].replace(".", "").replace(" ", "")
            return buildFrame(idStr, dataHex, uptimeMs, wallMs, forceExt = null)
        }

        // B. t7DF802010C... / T18FEF1008...
        reCandumpT.matchEntire(s)?.let { m ->
            val h = m.groupValues[1]
            val ext = s[0] == 'T'
            val idLen = if (ext) 8 else 3
            if (h.length < idLen) return null
            val payload = h.substring(idLen)
            val dataHex = if (payload.length % 2 == 1) payload.drop(1) else payload
            return buildFrame(h.substring(0, idLen), dataHex, uptimeMs, wallMs, forceExt = ext)
        }

        // C. 7DF 8 02 01 0C ... / 7DF 02 01 0C ... / 7DF,8,02,01
        reSpace.matchEntire(s)?.let { m ->
            val idStr = m.groupValues[1]
            val tokens = m.groupValues[2].trim()
                .split(Regex("[ ,;:]+"))
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            // 第一个 token 若为单个数字/字母（长度 1）视为 DLC
            val dataTokens = if (tokens[0].length == 1) tokens.drop(1) else tokens
            if (dataTokens.isEmpty()) return null
            return buildFrame(idStr, dataTokens.joinToString(""), uptimeMs, wallMs, forceExt = null)
        }

        return null
    }

    private fun buildFrame(
        idStr: String,
        dataHex: String,
        uptimeMs: Long,
        wallMs: Long,
        forceExt: Boolean?
    ): CanFrame? {
        val idLong = idStr.toLongOrNull(16) ?: return null
        if (idLong < 0 || idLong > 0x1FFFFFFFL) return null

        val clean = dataHex.replace(" ", "").replace(".", "")
        if (clean.length % 2 != 0) return null
        if (clean.length > 128) return null // CAN FD 上限 64 字节

        val bytes = ByteArray(clean.length / 2)
        for (k in bytes.indices) {
            val hi = hexVal(clean[2 * k])
            val lo = hexVal(clean[2 * k + 1])
            if (hi < 0 || lo < 0) return null
            bytes[k] = ((hi shl 4) or lo).toByte()
        }

        val extended = forceExt ?: (idStr.length > 3)
        return CanFrame(
            uptimeMs = uptimeMs,
            wallMs = wallMs,
            dir = Dir.RX,
            id = idLong.toInt(),
            extended = extended,
            dlc = bytes.size,
            data = bytes
        )
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    // ---------------------------------------------------------------- 缓冲管理

    private fun append(d: ByteArray, n: Int) {
        val need = len + n
        if (need > buf.size) {
            var ns = buf.size
            while (ns < need) ns = ns shl 1
            buf = buf.copyOf(ns)
        }
        System.arraycopy(d, 0, buf, len, n)
        len += n
    }

    private fun shift(n: Int) {
        if (n <= 0) return
        if (n >= len) {
            len = 0
            return
        }
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    private fun copyOf(from: Int, count: Int): ByteArray {
        val c = count.coerceAtMost(len - from).coerceAtLeast(0)
        return buf.copyOfRange(from, from + c)
    }

    private fun emitRaw(bytes: ByteArray, note: String, uptimeMs: Long, wallMs: Long, sink: Sink) {
        if (bytes.isEmpty()) return
        sink.onRaw(RawRecord(uptimeMs, wallMs, Dir.RX, bytes, note))
    }
}
