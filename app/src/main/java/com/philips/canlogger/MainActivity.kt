package com.philips.canlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.philips.canlogger.export.Exporter
import com.philips.canlogger.model.TimeText
import com.philips.canlogger.recorder.StorageGuard
import com.philips.canlogger.service.LogService
import com.philips.canlogger.service.LoggerState
import com.philips.canlogger.transport.SerialPorts
import com.philips.canlogger.transport.UsbSerialTransport
import com.philips.canlogger.util.AppLog
import com.philips.canlogger.util.Prefs
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var spTransport: Spinner
    private lateinit var spPort: Spinner
    private lateinit var spBaud: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var etFilter: EditText
    private lateinit var cbAuto: CheckBox
    private lateinit var cbCsv: CheckBox
    private lateinit var cbClear: CheckBox

    private val transportNames = arrayOf("板载串口", "USB 转串口")
    private val baudList = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
    private val portPaths = ArrayList<String>()

    private var suppress = false

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs.of(this)
        bindViews()
        setupTransportSpinner()
        setupBaudSpinner()
        setupPortSpinner()
        setupChecks()
        setupButtons()

        requestBasicPermissions()
        ui.post(tick)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        prefs.filterIds = etFilter.text.toString()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 初始化

    private fun bindViews() {
        spTransport = findViewById(R.id.spTransport)
        spPort = findViewById(R.id.spPort)
        spBaud = findViewById(R.id.spBaud)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        etFilter = findViewById(R.id.etFilter)
        cbAuto = findViewById(R.id.cbAuto)
        cbCsv = findViewById(R.id.cbCsv)
        cbClear = findViewById(R.id.cbClear)
    }

    private fun setupTransportSpinner() {
        suppress = true
        spTransport.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transportNames)
        spTransport.setSelection(if (prefs.transport == Prefs.TRANSPORT_USB) 1 else 0)
        spTransport.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (suppress) return
                prefs.transport = if (position == 1) Prefs.TRANSPORT_USB else Prefs.TRANSPORT_SERIAL
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        suppress = false
    }

    private fun setupBaudSpinner() {
        suppress = true
        spBaud.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, baudList.map { it.toString() }
        )
        spBaud.setSelection(baudList.indexOf(prefs.baud).coerceAtLeast(0))
        spBaud.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (suppress) return
                prefs.baud = baudList[position]
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        suppress = false
    }

    private fun setupPortSpinner() {
        refreshPorts()
        spPort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (suppress) return
                if (position in portPaths.indices) prefs.port = portPaths[position]
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun refreshPorts() {
        suppress = true
        val infos = SerialPorts.probe()
        portPaths.clear()
        portPaths.addAll(infos.map { it.path })

        val labels = infos.map { it.label() }
        spPort.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val idx = portPaths.indexOf(prefs.port)
        if (idx >= 0) {
            spPort.setSelection(idx)
        } else if (portPaths.isNotEmpty()) {
            prefs.port = portPaths[0]
        }
        suppress = false

        val usable = infos.count { it.usable }
        AppLog.i("串口探测: ${infos.size} 个节点, 可读写 $usable 个")
    }

    private fun setupChecks() {
        cbAuto.isChecked = prefs.autoStart
        cbCsv.isChecked = prefs.csv
        etFilter.setText(prefs.filterIds)

        cbAuto.setOnCheckedChangeListener { _, v -> prefs.autoStart = v }
        cbCsv.setOnCheckedChangeListener { _, v -> prefs.csv = v }
        cbClear.setOnCheckedChangeListener { _, v ->
            if (v) {
                LoggerState.clearRecent()
                tvLog.text = ""
                cbClear.isChecked = false
            }
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnStart).setOnClickListener { startRecording() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopRecording() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.btnUsb).setOnClickListener { copyToUsb() }
        findViewById<Button>(R.id.btnPerm).setOnClickListener { openAllFilesSettings() }
    }

    // ---------------------------------------------------------------- 操作

    private fun startRecording() {
        if (LoggerState.running) {
            toast("已经在录制中")
            return
        }

        // 保存当前 UI 选择
        if (spPort.selectedItemPosition in portPaths.indices) {
            prefs.port = portPaths[spPort.selectedItemPosition]
        }
        prefs.baud = baudList[spBaud.selectedItemPosition.coerceIn(0, baudList.size - 1)]
        prefs.transport =
            if (spTransport.selectedItemPosition == 1) Prefs.TRANSPORT_USB else Prefs.TRANSPORT_SERIAL
        prefs.filterIds = etFilter.text.toString()

        if (prefs.transport == Prefs.TRANSPORT_USB) {
            startUsbThenService()
        } else {
            if (Build.VERSION.SDK_INT >= 30 && !StorageGuard.canWriteAllFiles()) {
                toast("未授予「全盘权限」，日志将保存到 App 私有目录（仍可导出）")
            }
            launchService()
        }
    }

    private fun startUsbThenService() {
        val driver = UsbSerialTransport.findDriver(this)
        if (driver == null) {
            toast("未发现 USB 串口设备（检查 OTG 供电/芯片）")
            return
        }
        if (UsbSerialTransport.hasPermission(this, driver.device)) {
            launchService()
            return
        }
        toast("请在弹出的系统对话框中允许 USB 访问")
        UsbSerialTransport.requestPermission(this, driver.device) { granted ->
            runOnUiThread {
                if (granted) launchService() else toast("USB 未授权，无法录制")
            }
        }
    }

    private fun launchService() {
        try {
            val i = Intent(this, LogService::class.java).setAction(LogService.ACTION_START)
            ContextCompat.startForegroundService(this, i)
            toast("已开始录制")
        } catch (e: Exception) {
            AppLog.e("启动服务失败", e)
            toast("启动失败：${e.message}")
        }
    }

    private fun stopRecording() {
        if (!LoggerState.running) {
            toast("当前没有在录制")
            return
        }
        try {
            val i = Intent(this, LogService::class.java).setAction(LogService.ACTION_STOP)
            startService(i)
            toast("正在停止并保存...")
        } catch (e: Exception) {
            AppLog.e("停止服务失败", e)
        }
    }

    private fun currentLogOrNewest(): File? {
        val p = LoggerState.logPath
        if (p.isNotEmpty()) {
            val f = File(p)
            if (f.exists()) return f
        }
        val dir = prefs.lastDir?.let { File(it) } ?: StorageGuard.resolveBaseDir(this)
        return StorageGuard.newestLog(File(dir, TimeText.day(System.currentTimeMillis())))
            ?: StorageGuard.newestLog(dir)
    }

    private fun exportLog() {
        val f = currentLogOrNewest()
        if (f == null) {
            toast("还没有日志文件")
            return
        }
        Exporter.share(this, f)
    }

    private fun copyToUsb() {
        val f = currentLogOrNewest()
        if (f == null) {
            toast("还没有日志文件")
            return
        }
        if (LoggerState.running) toast("录制中复制，只会复制已落盘部分")
        val dst = Exporter.copyToUsb(this, f)
        if (dst != null) {
            toast("已复制到：${dst.absolutePath}")
        } else {
            toast("未找到可写的 U 盘，或用系统分享导出")
        }
    }

    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("当前系统无需该权限")
            return
        }
        if (Environment.isExternalStorageManager()) {
            toast("已拥有全盘权限")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                toast("系统不支持该设置页")
            }
        }
    }

    // ---------------------------------------------------------------- 状态渲染

    private fun render() {
        val sb = StringBuilder()

        if (LoggerState.running) {
            val dur = System.currentTimeMillis() - LoggerState.startedAt
            sb.append("● 录制中  ").append(TimeText.duration(dur))
        } else {
            sb.append("○ 未录制")
        }

        sb.append('\n')
        sb.append(LoggerState.portName).append(" @").append(LoggerState.baud)
        sb.append("  |  ").append(String.format(Locale.US, "%,d", LoggerState.frames)).append(" 帧")
        sb.append("  |  ").append(LoggerState.sizeText())
        if (LoggerState.dropped > 0) sb.append("  |  丢弃 ").append(LoggerState.dropped)
        if (LoggerState.reconnects > 0) sb.append("  |  重连 ").append(LoggerState.reconnects)

        if (LoggerState.logPath.isNotEmpty()) {
            sb.append('\n').append("文件: ").append(LoggerState.logPath)
        }
        if (LoggerState.lastError.isNotEmpty()) {
            sb.append('\n').append("错误: ").append(LoggerState.lastError)
        }

        tvStatus.text = sb.toString()
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (LoggerState.lastError.isNotEmpty()) R.color.err
                else if (LoggerState.running) R.color.ok
                else R.color.fg_dim
            )
        )

        val lines = LoggerState.recentLines()
        if (lines.isNotEmpty()) {
            val text = lines.joinToString("\n")
            if (tvLog.text?.toString() != text) {
                tvLog.text = text
                svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // ---------------------------------------------------------------- 权限

    private fun requestBasicPermissions() {
        val list = ArrayList<String>()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT <= 29) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else if (Build.VERSION.SDK_INT <= 32) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (list.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, list.toTypedArray(), 100)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
