package com.philips.canlogger.recorder

import com.philips.canlogger.model.CanFrame
import com.philips.canlogger.model.RawRecord
import com.philips.canlogger.model.TimeText
import com.philips.canlogger.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 会话式日志记录器。
 *
 * 设计要点：
 *  - 采集线程只做 `offer` 入队，绝不碰 IO；落盘在本类自己的线程里完成。
 *  - 每 200ms 或队列有数据就 flush，断电最多丢 1 批（<200ms）。
 *  - 单文件超过 maxFileBytes 自动切片（_part2、_part3…）。
 *  - 开始录制时写一个 `*.open` 标记文件，正常停止删除；异常退出后下次启动由
 *    [LogRecovery] 找到标记，给对应日志补 `# INCOMPLETE`，不会静默丢数据。
 */
class LogRecorder(
    private val baseDir: File,
    private val formatter: LogFormatter,
    private val portDesc: String,
    private val baud: Int,
    private val maxFileBytes: Long,
    private val maxTotalBytes: Long,
    private val vehicle: String = "Citroen C3-XR / Philips HU"
) {

    private sealed class Item {
        class Frame(val f: CanFrame) : Item()
        class Raw(val r: RawRecord) : Item()
    }

    private class IdStat {
        var count = 0L
        var firstTs = 0L
        var lastTs = 0L
        var lastWall = 0L
        var minInterval = Long.MAX_VALUE
        var maxInterval = 0L
        var totalInterval = 0L
        var lastData = ""
    }

    private val queue = LinkedBlockingQueue<Item>(20000)

    @Volatile
    private var running = false

    private var worker: Thread? = null

    private var logOut: FileOutputStream? = null
    private var csvOut: FileOutputStream? = null

    var logFile: File? = null
        private set

    private var csvFile: File? = null
    private var marker: File? = null

    private var sessionDir: File? = null
    private var sessionBase = ""
    private var part = 1
    private var fileBytes = 0L

    val stats = SessionStats()

    private val perId = HashMap<Int, IdStat>()

    val isRunning: Boolean get() = running

    fun start(): File {
        val now = System.currentTimeMillis()
        stats.startedAt = now
        stats.stoppedAt = 0

        val dir = File(baseDir, TimeText.day(now))
        dir.mkdirs()
        sessionDir = dir
        sessionBase = "can_" + TimeText.stamp(now)
        part = 1

        openFiles(now)

        marker = File(dir, "$sessionBase.open")
        try {
            marker?.writeText("started=${TimeText.wall(now)}\nport=$portDesc baud=$baud\n")
        } catch (e: Exception) {
            AppLog.w("写标记文件失败: ${e.message}")
        }

        running = true
        worker = Thread({ loop() }, "can-log-writer").also {
            it.isDaemon = true
            it.start()
        }

        StorageGuard.trim(baseDir, maxTotalBytes, logFile)
        AppLog.i("开始录制: ${logFile?.absolutePath}")
        return logFile!!
    }

    fun submit(f: CanFrame): Boolean {
        if (!running) return false
        if (!queue.offer(Item.Frame(f))) {
            stats.dropped++
            return false
        }
        return true
    }

    fun submitRaw(r: RawRecord): Boolean {
        if (!running) return false
        if (!queue.offer(Item.Raw(r))) {
            stats.dropped++
            return false
        }
        return true
    }

    fun noteReconnect() {
        stats.reconnects++
    }

    fun stop(reason: String) {
        if (!running) return
        running = false

        try {
            worker?.join(5000)
        } catch (_: InterruptedException) {
        }
        worker = null

        // 极少数情况下 worker 已退出但队列还有残留，这里直接补写
        drainRemaining()

        stats.stoppedAt = System.currentTimeMillis()
        writeLog(formatter.trailer(stats, reason).toByteArray(Charsets.US_ASCII))
        closeStreams()

        marker?.delete()
        marker = null

        writeSummary()
        StorageGuard.trim(baseDir, maxTotalBytes, null)
        AppLog.i("停止录制: reason=$reason frames=${stats.frames} dropped=${stats.dropped} bytes=${stats.bytes}")
    }

    // -------------------------------------------------------------- 内部实现

    private fun loop() {
        while (true) {
            val first = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            }

            if (first == null) {
                if (!running && queue.isEmpty()) break
                continue
            }

            val batch = ArrayList<Item>(1024)
            batch.add(first)
            queue.drainTo(batch, 8192)
            writeBatch(batch)

            if (fileBytes >= maxFileBytes) rotate()
        }
    }

    private fun drainRemaining() {
        val batch = ArrayList<Item>(1024)
        while (true) {
            val it = queue.poll() ?: break
            batch.add(it)
            if (batch.size >= 4096) {
                writeBatch(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) writeBatch(batch)
    }

    private fun writeBatch(batch: List<Item>) {
        val sb = StringBuilder(batch.size * 56)
        val csvSb = if (formatter.csv) StringBuilder(batch.size * 72) else null

        for (item in batch) {
            when (item) {
                is Item.Frame -> {
                    val f = item.f
                    sb.append(formatter.frame(f)).append('\n')
                    csvSb?.append(formatter.frameCsv(f))
                    stats.frames++
                    updatePerId(f)
                }

                is Item.Raw -> {
                    val r = item.r
                    sb.append(formatter.raw(r)).append('\n')
                    csvSb?.append(formatter.rawCsv(r))
                    stats.frames++
                }
            }
        }

        writeLog(sb.toString().toByteArray(Charsets.US_ASCII))
        csvSb?.let { writeCsv(it.toString().toByteArray(Charsets.US_ASCII)) }
    }

    private fun updatePerId(f: CanFrame) {
        val s = perId.getOrPut(f.id) { IdStat() }
        if (s.count > 0) {
            val d = f.uptimeMs - s.lastTs
            if (d >= 0) {
                s.totalInterval += d
                if (d < s.minInterval) s.minInterval = d
                if (d > s.maxInterval) s.maxInterval = d
            }
        } else {
            s.firstTs = f.uptimeMs
        }
        s.count++
        s.lastTs = f.uptimeMs
        s.lastWall = f.wallMs
        s.lastData = f.dataHex()
    }

    private fun openFiles(now: Long) {
        val dir = sessionDir ?: return
        val suffix = if (part > 1) "_part$part" else ""

        logFile = File(dir, "$sessionBase$suffix.log")
        logOut = FileOutputStream(logFile, false)
        fileBytes = 0
        val head = formatter.header(vehicle, portDesc, baud, if (stats.startedAt > 0) stats.startedAt else now, part)
        writeLog(head.toByteArray(Charsets.US_ASCII))

        if (formatter.csv) {
            csvFile = File(dir, "$sessionBase$suffix.csv")
            csvOut = FileOutputStream(csvFile, false)
            writeCsv(formatter.csvHeader().toByteArray(Charsets.US_ASCII))
        }
    }

    private fun rotate() {
        AppLog.i("日志切片: part=$part 已达 ${fileBytes / 1024 / 1024}MB")
        writeLog("# ---- PART END (${fileBytes / 1024}KB) ----\n".toByteArray(Charsets.US_ASCII))
        part++
        try {
            logOut?.flush()
            logOut?.close()
        } catch (_: Exception) {
        }
        try {
            csvOut?.flush()
            csvOut?.close()
        } catch (_: Exception) {
        }
        logOut = null
        csvOut = null
        openFiles(System.currentTimeMillis())
        StorageGuard.trim(baseDir, maxTotalBytes, logFile)
    }

    private fun writeLog(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val o = logOut ?: return
        try {
            o.write(bytes)
            o.flush()
            fileBytes += bytes.size
            stats.bytes += bytes.size
        } catch (e: Exception) {
            AppLog.e("写 CAN 日志失败: ${e.message}", e)
        }
    }

    private fun writeCsv(bytes: ByteArray) {
        val o = csvOut ?: return
        try {
            o.write(bytes)
            o.flush()
        } catch (e: Exception) {
            AppLog.e("写 CSV 失败: ${e.message}", e)
        }
    }

    private fun closeStreams() {
        try {
            logOut?.flush()
        } catch (_: Exception) {
        }
        try {
            logOut?.close()
        } catch (_: Exception) {
        }
        try {
            csvOut?.flush()
        } catch (_: Exception) {
        }
        try {
            csvOut?.close()
        } catch (_: Exception) {
        }
        logOut = null
        csvOut = null
    }

    private fun writeSummary() {
        val dir = sessionDir ?: return
        val f = File(dir, "$sessionBase.summary.txt")
        try {
            val sb = StringBuilder()
            sb.append("# summary for $sessionBase\n")
            sb.append("# port=$portDesc baud=$baud\n")
            sb.append("# start=${TimeText.wall(stats.startedAt)} stop=${TimeText.wall(stats.stoppedAt)}\n")
            sb.append("# frames=${stats.frames} dropped=${stats.dropped} reconnects=${stats.reconnects}\n")
            sb.append("# id,count,avg_ms,min_ms,max_ms,last_data,last_time\n")
            for ((id, s) in perId.entries.sortedByDescending { it.value.count }) {
                val avg = if (s.count > 1) s.totalInterval / (s.count - 1) else 0
                val min = if (s.minInterval == Long.MAX_VALUE) 0 else s.minInterval
                sb.append(
                    String.format(
                        Locale.US, "%X,%d,%d,%d,%d,%s,%s\n",
                        id, s.count, avg, min, s.maxInterval, s.lastData, TimeText.wall(s.lastWall)
                    )
                )
            }
            f.writeText(sb.toString())
            AppLog.i("汇总已生成: ${f.absolutePath}")
        } catch (e: Exception) {
            AppLog.w("写 summary 失败: ${e.message}")
        }
    }
}
