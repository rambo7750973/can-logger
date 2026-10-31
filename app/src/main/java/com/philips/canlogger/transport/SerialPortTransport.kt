package com.philips.canlogger.transport

import com.philips.canlogger.util.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 板载串口通道（无 root）。
 *
 * 思路：
 * 1. 探测 /dev 下的 tty 节点，检查可读写权限；
 * 2. 用 toybox/busybox 的 `stty` 配置线路参数 —— 关键参数是 `min 0 time 5`，
 *    让 read() 每 0.5s 返回一次（返回 0），这样停止/重连不需要能被信号打断的阻塞读；
 * 3. 用 FileInputStream/FileOutputStream 直接读写该设备节点（避免 RAF 的 pread/seek 问题）。
 *
 * 无 root 时能否成功，取决于车机 ROM 是否把协议盒串口节点放开为 crw-rw-rw-。
 * 失败时会把每个候选节点的原因写进异常信息，直接展示给用户。
 */
class SerialPortTransport(
    private val candidates: List<String>,
    private val baud: Int
) : CanTransport {

    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var path: String = ""

    override val name: String
        get() = path.ifEmpty { "未打开" }

    override val isOpen: Boolean
        get() = input != null

    override fun open() {
        val errors = ArrayList<String>()

        for (p in candidates) {
            val f = File(p)
            if (!f.exists()) {
                errors.add("$p 不存在")
                continue
            }
            val r = f.canRead()
            val w = f.canWrite()
            if (!r || !w) {
                errors.add("$p 权限不足(r=$r,w=$w)")
                continue
            }

            val cfg = configureLine(p)
            try {
                val ins = FileInputStream(f)
                val outs = try {
                    FileOutputStream(f)
                } catch (e: Exception) {
                    try { ins.close() } catch (_: Exception) {}
                    throw e
                }
                input = ins
                output = outs
                path = p
                AppLog.i("串口已打开: $p @$baud 8N1, 线路配置=${cfg ?: "stty 不可用(沿用系统默认)"}")
                return
            } catch (e: Exception) {
                errors.add("$p 打开失败: ${e.message}")
                AppLog.e("打开 $p 失败", e)
            }
        }

        throw TransportException(
            "无法打开串口：" + errors.joinToString("；") +
                "。若提示权限不足且车机未 root：①让车机固件放开该 tty 节点权限；②改用 USB 转串口方案。"
        )
    }

    override fun read(buf: ByteArray): Int {
        val f = input ?: return -1
        return try {
            f.read(buf)
        } catch (e: Exception) {
            AppLog.w("串口读取异常: ${e.message}")
            -1
        }
    }

    override fun write(bytes: ByteArray): Boolean {
        val f = output ?: return false
        return try {
            f.write(bytes)
            f.flush()
            true
        } catch (e: Exception) {
            AppLog.w("串口写失败: ${e.message}")
            false
        }
    }

    override fun close() {
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
    }

    /**
     * 配置线路：波特率 / raw 模式 / 关闭回显与流控 / 读超时。
     * 返回实际生效的命令，全部失败返回 null（仍然尝试直接读，很多车机内核已配好）。
     */
    private fun configureLine(dev: String): String? {
        val base = "$baud raw -echo -echoe -echok -echoctl -echoke -ixon -ixoff -crtscts -opost -onlcr"
        val cmds = listOf(
            "stty -F $dev $base min 0 time 5",
            "toybox stty -F $dev $base min 0 time 5",
            "busybox stty -F $dev $base min 0 time 5",
            "stty -F $dev $base",
            "/system/bin/stty -F $dev $base min 0 time 5"
        )
        for (c in cmds) {
            if (exec(c)) return c
        }
        return null
    }

    private fun exec(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            if (code != 0) {
                AppLog.w("stty 失败($code): $cmd -> ${out.trim().take(200)}")
            }
            code == 0
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 串口节点探测：优先扫描 /dev 实际存在的 tty 设备，再补充常见固定节点。
 */
object SerialPorts {

    private val FIXED = listOf(
        "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3", "/dev/ttyS4",
        "/dev/ttyHS1", "/dev/ttyHS2",
        "/dev/ttyMT0", "/dev/ttyMT1",
        "/dev/ttyMSM0", "/dev/ttyMSM1",
        "/dev/ttyUSB0", "/dev/ttyACM0"
    )

    data class Info(
        val path: String,
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean
    ) {
        val usable: Boolean get() = exists && readable && writable

        fun label(): String = when {
            !exists -> "$path（无）"
            usable -> "$path  ✔"
            else -> "$path  ✖权限"
        }
    }

    fun probe(): List<Info> {
        val names = LinkedHashSet<String>()

        // 1) 扫描 /dev
        try {
            val dev = File("/dev")
            dev.list()?.forEach { n ->
                if (n.startsWith("ttyS") || n.startsWith("ttyHS") || n.startsWith("ttyMT") ||
                    n.startsWith("ttyMSM") || n.startsWith("ttyUSB") || n.startsWith("ttyACM") ||
                    n.startsWith("ttyXR") || n.startsWith("ttyVK")
                ) {
                    names.add("/dev/$n")
                }
            }
        } catch (e: Exception) {
            AppLog.w("扫描 /dev 失败: ${e.message}")
        }

        // 2) 固定列表兜底
        names.addAll(FIXED)

        return names.map { p ->
            val f = File(p)
            Info(p, f.exists(), f.canRead(), f.canWrite())
        }.sortedWith(compareByDescending<Info> { it.usable }.thenBy { it.path })
    }

    /** 默认候选顺序：可读写的排在前面，其余也放进去（便于报错时说明原因） */
    fun candidates(preferred: String? = null): List<String> {
        val list = ArrayList<String>()
        preferred?.let { if (it.isNotBlank()) list.add(it) }
        probe().sortedByDescending { it.usable }.forEach { if (!list.contains(it.path)) list.add(it.path) }
        return list
    }
}
