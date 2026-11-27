package com.lutcamera.lutcamerapro.network

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.lutcamera.lutcamerapro.log.DoukLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * USB Bulk 传输层（内核 usbfs 直连的收口处）。
 *
 * 为什么单独抽出来：
 * 尼康 PTP 在 bulk 端点上有一类经典故障——相机中途取消、超时或换了模式之后，
 * 端点会停在 HALT 状态，之后所有 bulk 读写都返回 -1，只能拔线重插才能恢复。
 * Android 的 UsbDeviceConnection 没有 clearHalt()，必须自己发 USB 控制请求
 * CLEAR_FEATURE(ENDPOINT_HALT, ep)。这里把它做成失败后的自动恢复：
 * 清 halt → 重试一次 → 只统计连续失败，不再让单次 -1 拖死整条会话。
 *
 * 所有 PTP 报文仍然走裸 USB（不经过 MTP / 厂商驱动），只是把传输细节收口。
 */
internal object UsbBulk {

    /** 命令容器：相机应答很快，3 秒足够，超时说明相机不在 PTP 模式 */
    const val CMD_TIMEOUT = 3_000

    /** LiveView 取帧：相机没出新帧时宁可快点返回让上层重试 */
    const val FRAME_TIMEOUT = 2_000

    /** 文件/属性数据：大 JPEG、RAW 预览可能几 MB，给足时间 */
    const val DATA_TIMEOUT = 8_000

    /** 事件端点：没事件就立刻回来，不占线程 */
    const val EVENT_TIMEOUT = 1_000

    private const val ESC_REQ_CLEAR_FEATURE: Int = 0x01
    private const val ESC_FEATURE_ENDPOINT_HALT: Int = 0x00
    // bmRequestType = 0x02：方向 OUT(0x00) | 标准请求(0x00) | 接收者 Endpoint(0x02)
    // Android 的 UsbConstants 没有 recipient 常量，这里直接按 USB 2.0 规范取值
    private const val CLEAR_FEATURE_TYPE: Int = 0x02

    private val failures = AtomicInteger(0)

    /** 连续失败次数：上层拿它判断「是不是该重连了」 */
    val consecutiveFailures: Int get() = failures.get()

    fun resetFailures() {
        failures.set(0)
    }

    fun bulkIn(conn: UsbDeviceConnection, ep: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int =
        transfer(conn, ep, buffer, 0, length, timeout, retryOnFailure = timeout < DATA_TIMEOUT)

    fun bulkOut(conn: UsbDeviceConnection, ep: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int =
        transfer(conn, ep, buffer, 0, length, timeout, retryOnFailure = true)

    private fun transfer(
        conn: UsbDeviceConnection,
        ep: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeout: Int,
        retryOnFailure: Boolean
    ): Int {
        val first = runCatching { conn.bulkTransfer(ep, buffer, offset, length, timeout) }.getOrDefault(-1)
        if (first >= 0) {
            failures.set(0)
            return first
        }
        // 端点被 HALT 住时，不先清 halt 光重试也没有意义
        val cleared = clearHalt(conn, ep)
        if (!retryOnFailure) {
            failures.incrementAndGet()
            DoukLog.i(
                "USB", "BULK_FAIL",
                "ep" to ep.address.toString(),
                "dir" to if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                "clearHalt" to cleared,
                "fails" to failures.get()
            )
            return -1
        }
        val second = runCatching { conn.bulkTransfer(ep, buffer, offset, length, timeout) }.getOrDefault(-1)
        if (second >= 0) {
            failures.set(0)
            DoukLog.i("USB", "BULK_RECOVER", "ep" to ep.address.toString(), "clearHalt" to cleared)
            return second
        }
        failures.incrementAndGet()
        DoukLog.i(
            "USB", "BULK_FAIL",
            "ep" to ep.address.toString(),
            "dir" to if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out",
            "clearHalt" to cleared,
            "fails" to failures.get()
        )
        return -1
    }

    /** 标准控制请求清端点 halt：bmRequestType=0x02, bRequest=0x01, wValue=0, wIndex=端点地址 */
    fun clearHalt(conn: UsbDeviceConnection, ep: UsbEndpoint): Boolean = runCatching {
        conn.controlTransfer(
            CLEAR_FEATURE_TYPE,
            ESC_REQ_CLEAR_FEATURE,
            ESC_FEATURE_ENDPOINT_HALT,
            ep.address,
            null,
            0,
            500
        ) >= 0
    }.getOrDefault(false)

    /** 会话结束/重连前统一清一次，避免上一次的 halt 状态带进新会话 */
    fun resetEndpoints(conn: UsbDeviceConnection, endpoints: List<UsbEndpoint>) {
        endpoints.forEach { runCatching { clearHalt(conn, it) } }
        resetFailures()
    }
}
