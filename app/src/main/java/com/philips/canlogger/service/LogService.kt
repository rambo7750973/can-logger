package com.philips.canlogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.philips.canlogger.MainActivity
import com.philips.canlogger.R
import com.philips.canlogger.model.CanFrame
import com.philips.canlogger.model.RawRecord
import com.philips.canlogger.model.TimeText
import com.philips.canlogger.recorder.LogFormatter
import com.philips.canlogger.recorder.LogRecorder
import com.philips.canlogger.recorder.LogRecovery
import com.philips.canlogger.recorder.StorageGuard
import com.philips.canlogger.transport.CanTransport
import com.philips.canlogger.transport.FrameParser
import com.philips.canlogger.transport.SerialPortTransport
import com.philips.canlogger.transport.SerialPorts
import com.philips.canlogger.transport.UsbSerialTransport
import com.philips.canlogger.util.AppLog
import com.philips.canlogger.util.IdFilter
import com.philips.canlogger.util.Prefs
import java.io.File

/**
 * 采集前台服务：串口读取线程 + 落盘线程，熄屏/后台继续工作。
 */
class LogService : Service() {

    companion object {
        const val ACTION_START = "com.philips.canlogger.action.START"
        const val ACTION_STOP = "com.philips.canlogger.action.STOP"

        private const val CHANNEL_ID = "can_record"
        private const val NOTIF_ID = 1001
        private const val MAX_BACKOFF = 10000L
        private const val SPACE_CHECK_INTERVAL_MS = 5000L

        @Volatile
        var isRunning: Boolean = false
    }

    private var reader: Thread? = null
    private var transport: CanTransport? = null
    private var recorder: LogRecorder? = null

    private val parser = FrameParser()
    private var sink: FrameParser.Sink? = null
    private var filter: IdFilter = IdFilter.ALL

    private var wakeLock: PowerManager.WakeLock? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    private var lastPreviewAt = 0L
    private var lastNotifAt = 0L
    private var lastSpaceCheckAt = 0L

    private val notifTick = object : Runnable {
        override fun run() {
            if (!running) return
            updateNotification()
            uiHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording("user_stop")
                stopSelf()
            }

            else -> startRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecording("service_destroy")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 启停

    private fun startRecording() {
        if (running) {
            // 重复点击「开始」时也必须重新进入前台，否则系统会判定超时崩溃
            startForegroundSafe(buildNotification(currentStatusText()))
            AppLog.i("已在录制中，忽略重复启动")
            return
        }

        // 必须尽快进入前台，否则系统会在 5s 后杀进程
        startForegroundSafe(buildNotification("正在准备..."))

        val prefs = Prefs.of(this)
        val dir = StorageGuard.resolveBaseDir(this)
        prefs.lastDir = dir.absolutePath

        val problem = StorageGuard.checkBeforeStart(dir)
        if (problem != null) {
            failAndStop(problem)
            return
        }

        LogRecovery.recover(dir)

        filter = IdFilter.parse(prefs.filterIds)

        val fmt = LogFormatter(prefs.csv)
        val rec = LogRecorder(
            baseDir = dir,
            formatter = fmt,
            portDesc = if (prefs.transport == Prefs.TRANSPORT_USB) "USB" else prefs.port,
            baud = prefs.baud,
            maxFileBytes = prefs.maxFileMb.toLong() * 1024 * 1024,
            maxTotalBytes = prefs.maxTotalMb.toLong() * 1024 * 1024
        )

        val file = try {
            rec.start()
        } catch (e: Exception) {
            failAndStop("创建日志文件失败：${e.message}")
            return
        }

        recorder = rec
        LoggerState.reset(if (prefs.transport == Prefs.TRANSPORT_USB) "USB" else prefs.port, prefs.baud, file.absolutePath)
        LoggerState.running = true
        running = true
        isRunning = true

        sink = RecordSink()

        acquireWakeLock()

        updateNotification()
        uiHandler.removeCallbacks(notifTick)
        uiHandler.postDelayed(notifTick, 2000)

        reader = Thread({ readLoop(prefs) }, "can-reader").also {
            it.isDaemon = true
            it.start()
        }

        AppLog.i("录制已启动: dir=${dir.absolutePath} transport=${prefs.transport} port=${prefs.port} baud=${prefs.baud} filter=${filter.describe()}")
    }

    private fun stopRecording(reason: String) {
        if (!running && recorder == null) return

        running = false
        isRunning = false
        uiHandler.removeCallbacks(notifTick)

        reader?.interrupt()
        try {
            reader?.join(2500)
        } catch (_: InterruptedException) {
        }
        reader = null

        // 把解析器里残留的半行刷出去（此时 recorder 还活着）
        try {
            val s = sink
            if (s != null) {
                parser.flush(SystemClock.elapsedRealtime(), System.currentTimeMillis(), s)
            }
        } catch (e: Exception) {
            AppLog.w("flush 解析器失败: ${e.message}")
        }

        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null

        try {
            recorder?.stop(reason)
        } catch (e: Exception) {
            AppLog.e("停止录制异常", e)
        }
        recorder = null

        releaseWakeLock()
        LoggerState.running = false

        stopForegroundSafe()
        AppLog.i("录制已停止: $reason")
    }

    private fun failAndStop(message: String) {
        AppLog.e("启动失败: $message")
        LoggerState.lastError = message
        LoggerState.running = false
        isRunning = false
        updateNotification(message)
        // 保留 3 秒让用户看到错误提示
        uiHandler.postDelayed({
            stopForegroundSafe()
            stopSelf()
        }, 3000)
    }

    // ------------------------------------------------------------- 读取主循环

    private fun readLoop(prefs: Prefs) {
        val buf = ByteArray(4096)
        var backoff = 1000L

        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSpaceCheckAt >= SPACE_CHECK_INTERVAL_MS) {
                lastSpaceCheckAt = now
                val dir = prefs.lastDir?.let { File(it) }
                val problem = dir?.let { StorageGuard.checkDuringRecording(it) }
                if (problem != null) {
                    LoggerState.lastError = problem
                    LoggerState.push("# $problem")
                    uiHandler.post {
                        if (running) stopRecording("storage_low")
                    }
                    break
                }
            }

            val current = transport
            if (current == null || !current.isOpen) {
                val nt = connect(prefs)
                if (nt == null) {
                    sleepQuiet(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
                    continue
                }
                transport = nt
                backoff = 1000L
            }

            val conn = transport
            if (conn == null) continue

            val n = try {
                conn.read(buf)
            } catch (e: Exception) {
                AppLog.w("读取异常: ${e.message}")
                -1
            }

            if (n > 0) {
                val nowUp = SystemClock.elapsedRealtime()
                val nowWall = System.currentTimeMillis()
                val s = sink
                if (s != null) {
                    try {
                        parser.feed(buf, n, nowUp, nowWall, s)
                    } catch (e: Exception) {
                        AppLog.e("解析异常", e)
                    }
                }
                LoggerState.bytes = recorder?.stats?.bytes ?: LoggerState.bytes
                backoff = 1000L

                val now = SystemClock.elapsedRealtime()
                if (now - lastNotifAt > 3000) {
                    lastNotifAt = now
                    uiHandler.post { updateNotification() }
                }
            } else if (n < 0) {
                AppLog.w("通道断开，准备重连")
                LoggerState.push("# 通道断开，重连中...")
                try {
                    conn.close()
                } catch (_: Exception) {
                }
                transport = null
                recorder?.noteReconnect()
                LoggerState.reconnects++
                sleepQuiet(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
            }
            // n == 0：读超时，正常，继续
        }
    }

    private fun connect(prefs: Prefs): CanTransport? {
        return try {
            val nt = createTransport(prefs)
            nt.open()
            LoggerState.portName = nt.name
            LoggerState.lastError = ""
            LoggerState.push("# 已连接 ${nt.name} @${prefs.baud}")
            AppLog.i("已连接 ${nt.name}")
            nt
        } catch (e: Exception) {
            LoggerState.lastError = e.message ?: "连接失败"
            LoggerState.push("# 连接失败: ${e.message}")
            AppLog.w("连接失败: ${e.message}")
            null
        }
    }

    private fun createTransport(prefs: Prefs): CanTransport {
        return if (prefs.transport == Prefs.TRANSPORT_USB) {
            UsbSerialTransport(this, prefs.baud)
        } else {
            SerialPortTransport(SerialPorts.candidates(prefs.port), prefs.baud)
        }
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    // ------------------------------------------------------------------ 落盘

    private inner class RecordSink : FrameParser.Sink {

        override fun onFrame(frame: CanFrame) {
            preview(formatPreview(frame))
            if (!filter.accept(frame.id)) return
            val ok = recorder?.submit(frame) ?: false
            if (ok) {
                LoggerState.frames++
            } else {
                LoggerState.dropped++
            }
        }

        override fun onRaw(record: RawRecord) {
            val ok = recorder?.submitRaw(record) ?: false
            if (ok) {
                preview(formatRawPreview(record))
            } else {
                LoggerState.dropped++
            }
        }
    }

    private fun formatPreview(f: CanFrame): String {
        val data = f.dataHex()
        return if (data.isEmpty()) "${f.idText} ${f.dlc}" else "${f.idText} ${f.dlc} $data"
    }

    private fun formatRawPreview(r: RawRecord): String = "${r.note} ${r.hex()}"

    /** 预览最多 10 行/秒，避免高帧率时把 UI 拖死 */
    private fun preview(line: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewAt < 100) return
        lastPreviewAt = now
        LoggerState.push(line)
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = getString(R.string.notif_channel_desc)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlag()
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LogService::class.java).setAction(ACTION_STOP),
            pendingFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_can)
            .setContentTitle(if (LoggerState.running) getString(R.string.notif_title) else "CAN 采集")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.btn_stop), stopIntent)
            .build()
    }

    private fun updateNotification(error: String? = null) {
        if (!running && error == null) return
        val msg = error ?: currentStatusText()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(msg))
        } catch (e: Exception) {
            AppLog.w("更新通知失败: ${e.message}")
        }
    }

    private fun currentStatusText(): String = buildString {
        append(LoggerState.portName).append(" @").append(LoggerState.baud)
        append(" | ").append(LoggerState.frames).append(" 帧")
        append(" | ").append(LoggerState.sizeText())
        val dur = if (LoggerState.startedAt > 0) System.currentTimeMillis() - LoggerState.startedAt else 0
        append(" | ").append(TimeText.duration(dur))
        if (LoggerState.reconnects > 0) append(" | 重连").append(LoggerState.reconnects)
    }

    private fun startForegroundSafe(notif: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            AppLog.e("startForeground 失败", e)
        }
    }

    private fun stopForegroundSafe() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    private fun pendingFlag(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    // ------------------------------------------------------------------ 电源

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CanLogger:record").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            AppLog.w("获取 WakeLock 失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    /** 供 UI 判断最近日志位置 */
    fun currentLog(): File? = recorder?.logFile
}
