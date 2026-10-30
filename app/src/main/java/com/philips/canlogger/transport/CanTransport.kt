package com.philips.canlogger.transport

/**
 * CAN 通道抽象：板载串口 / USB 转串口 / （将来）MCU 透传 都实现这一层，
 * 上层 LogService 不关心数据从哪来。
 */
interface CanTransport {

    /** 展示用名称，如 "/dev/ttyS1" */
    val name: String

    val isOpen: Boolean

    /**
     * 打开通道。失败抛 [TransportException]，message 必须能直接展示给用户
     * （例如「/dev/ttyS1: 权限不足(r=false,w=false)」）。
     */
    fun open()

    /**
     * 阻塞读。
     * @return >0 读到的字节数；0 读超时（无数据）；-1 通道异常，需要重连
     */
    fun read(buf: ByteArray): Int

    fun write(bytes: ByteArray): Boolean

    fun close()
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
