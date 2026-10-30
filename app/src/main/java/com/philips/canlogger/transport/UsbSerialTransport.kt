package com.philips.canlogger.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.philips.canlogger.util.AppLog

/**
 * 备选方案：USB-OTG 转串口（CH340 / CP2102 / PL2303 / FTDI / CDC-ACM）。
 *
 * 板载串口权限打不开时，插一个 USB 转串口小板，把协议盒的 TX/RX 接到小板上即可，
 * 完全不需要 root。
 */
class UsbSerialTransport(
    private val ctx: Context,
    private val baud: Int
) : CanTransport {

    private var port: UsbSerialPort? = null
    private var conn: UsbDeviceConnection? = null

    override var name: String = "USB"
        private set

    override val isOpen: Boolean
        get() = port != null

    override fun open() {
        val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            ?: throw TransportException("未发现 USB 串口设备（请检查 OTG 供电、CH340/CP2102 驱动）")

        val device = driver.device
        if (!manager.hasPermission(device)) {
            throw TransportException("USB 设备未授权，请在界面上点「USB 授权」后重试")
        }

        val c = manager.openDevice(device)
            ?: throw TransportException("打开 USB 设备失败（可能被占用）")

        val p = driver.ports.firstOrNull()
            ?: throw TransportException("USB 设备没有可用串口")

        p.open(c)
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        p.setDTR(true)
        p.setRTS(true)

        port = p
        conn = c
        name = "USB ${device.productName ?: device.deviceName}"
        AppLog.i("USB 串口已打开: $name @$baud")
    }

    override fun read(buf: ByteArray): Int {
        val p = port ?: return -1
        return try {
            p.read(buf, READ_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLog.w("USB 读异常: ${e.message}")
            -1
        }
    }

    override fun write(bytes: ByteArray): Boolean {
        val p = port ?: return false
        return try {
            p.write(bytes, WRITE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            AppLog.w("USB 写失败: ${e.message}")
            false
        }
    }

    override fun close() {
        try {
            port?.close()
        } catch (_: Exception) {
        }
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        port = null
        conn = null
    }

    companion object {
        private const val READ_TIMEOUT_MS = 500
        private const val WRITE_TIMEOUT_MS = 500
        private const val ACTION_USB_PERM = "com.philips.canlogger.USB_PERMISSION"

        /** 找一个可用的 USB 串口设备（供 UI 申请权限用） */
        fun findDriver(ctx: Context): UsbSerialDriver? {
            val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            return UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        }

        fun hasPermission(ctx: Context, device: UsbDevice): Boolean {
            val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            return manager.hasPermission(device)
        }

        /** 申请 USB 权限；结果通过 [onResult] 回调（主线程） */
        fun requestPermission(ctx: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
            val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_USB_PERM).setPackage(ctx.packageName), flags
            )
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_USB_PERM) {
                        try {
                            c?.unregisterReceiver(this)
                        } catch (_: Exception) {
                        }
                        onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, IntentFilter(ACTION_USB_PERM), Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, IntentFilter(ACTION_USB_PERM))
            }
            manager.requestPermission(device, pi)
        }
    }
}
