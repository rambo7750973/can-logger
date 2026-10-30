package com.philips.canlogger.service

/**
 * 服务与 UI 之间共享的运行状态（UI 每 500ms 轮询，不用广播/事件，简单可靠）。
 */
object LoggerState {

    @Volatile var running: Boolean = false
    @Volatile var portName: String = "-"
    @Volatile var baud: Int = 0
    @Volatile var frames: Long = 0
    @Volatile var dropped: Long = 0
    @Volatile var bytes: Long = 0
    @Volatile var startedAt: Long = 0
    @Volatile var reconnects: Int = 0
    @Volatile var logPath: String = ""
    @Volatile var lastError: String = ""

    private const val MAX_PREVIEW = 200

    private val recent = ArrayDeque<String>(MAX_PREVIEW + 8)
    private val lock = Any()

    fun reset(port: String, baud: Int, logPath: String) {
        running = true
        portName = port
        this.baud = baud
        frames = 0
        dropped = 0
        bytes = 0
        reconnects = 0
        startedAt = System.currentTimeMillis()
        this.logPath = logPath
        lastError = ""
        clearRecent()
    }

    fun push(line: String) {
        synchronized(lock) {
            recent.addLast(line)
            while (recent.size > MAX_PREVIEW) recent.removeFirst()
        }
    }

    fun recentLines(): List<String> = synchronized(lock) { recent.toList() }

    fun clearRecent() {
        synchronized(lock) { recent.clear() }
    }

    fun sizeText(): String {
        val b = bytes
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
            b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
            else -> String.format("%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
        }
    }
}
