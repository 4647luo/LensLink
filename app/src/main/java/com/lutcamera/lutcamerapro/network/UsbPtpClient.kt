package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.*
import com.lutcamera.lutcamerapro.log.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 🚀 原生 USB PTP 驱动引擎 (USB 线控 + 超低延迟实时图传 LiveView 30fps)
 * 直接通过 USB Bulk 端点与尼康相机交互，彻底解决 MtpDevice 无法取景与线控快门的缺陷 */
class UsbPtpClient {

    companion object {
        private const val TAG = "UsbPtpClient"

        // PTP 容器类型 (USB)
                private const val USB_CONTAINER_COMMAND: Short = 1
        private const val USB_CONTAINER_DATA: Short = 2
        private const val USB_CONTAINER_RESPONSE: Short = 3
        private const val USB_CONTAINER_EVENT: Short = 4

        // 标准与尼康 PTP 操作码
                private const val OP_OPEN_SESSION: Short = 0x1002.toShort()
        private const val OP_CLOSE_SESSION: Short = 0x1003.toShort()
        private const val OP_NIKON_DEVICE_READY: Short = 0x90C0.toShort()
        private const val OP_NIKON_AF_CAPTURE: Short = 0x9207.toShort()
        private const val OP_NIKON_START_LIVE_VIEW: Short = 0x9201.toShort()
        private const val OP_NIKON_END_LIVE_VIEW: Short = 0x9202.toShort()
        private const val OP_NIKON_GET_LIVE_VIEW: Short = 0x9203.toShort()

        val defaultInstance = UsbPtpClient()
    }

    interface UsbEventListener {
        fun onConnected(cameraName: String) {}
        fun onDisconnected(reason: String) {}
        fun onLiveViewFrame(bitmap: Bitmap) {}
        fun onPhotoCaptured(handle: Int) {}
        fun onLog(msg: String) {}
    }

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var interruptEndpoint: UsbEndpoint? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var liveViewJob: Job? = null
    private var interruptJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isLiveViewActive = MutableStateFlow(false)
    val isLiveViewActive = _isLiveViewActive.asStateFlow()

    private val _liveViewBitmap = MutableStateFlow<Bitmap?>(null)
    val liveViewBitmap = _liveViewBitmap.asStateFlow()

    private var transactionId = 1
    private var listener: UsbEventListener? = null
    private val bufferLock = Any()

    /** 当前取景取帧操作码：Z 系 0x9428，不支持时自动降级 0x9203。 */
    private var frameCommand = 0x9428

    // 自动恢复用：记住这台设备与上下文，bulk 通道挂掉后不用用户拔线重插
    private var lastDevice: UsbDevice? = null
    private var appContext: Context? = null
    @Volatile
    private var recovering = false
    private var recoverAttempts = 0
    private val maxRecoverAttempts = 3

    /**
     * 连接 USB 相机并初始化 PTP 会话     */
    fun connectDevice(
        context: Context,
        device: UsbDevice,
        eventListener: UsbEventListener? = null
    ): Boolean {
        this.listener = eventListener
        // 记住设备和上下文：bulk 通道挂掉时可以自己重开会话，不必等用户拔线
        appContext = context.applicationContext
        lastDevice = device
        disconnect()

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            eventListener?.onLog("USB permission not granted")
            return false
        }

        // 查找支持 PTP (Still Image / Vendor Spec) 的接口与端点
                var matchedInterface: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        var intEp: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val clazz = iface.interfaceClass
            if (clazz == UsbConstants.USB_CLASS_STILL_IMAGE || clazz == UsbConstants.USB_CLASS_VENDOR_SPEC || clazz == 0) {
                var foundIn = false
                var foundOut = false

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            inEp = ep
                            foundIn = true
                        } else {
                            outEp = ep
                            foundOut = true
                        }
                    } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                        intEp = ep
                    }
                }

                if (foundIn && foundOut) {
                    matchedInterface = iface
                    break
                }
            }
        }

        if (matchedInterface == null || inEp == null || outEp == null) {
            eventListener?.onLog("⚠️ 未找到相机的 PTP USB 通讯端点")
            return false
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            eventListener?.onLog("Cannot establish USB connection")
            return false
        }

        if (!conn.claimInterface(matchedInterface, true)) {
            conn.close()
            eventListener?.onLog("⚠️ 抢占 USB 接口失败 (可能被系统独占)")
            return false
        }

        // 新会话开始前先清掉三个端点上可能残留的 halt 状态，否则第一包就会读失败
        UsbBulk.resetEndpoints(conn, listOfNotNull(inEp, outEp, intEp))

        usbConnection = conn
        usbInterface = matchedInterface
        bulkInEndpoint = inEp
        bulkOutEndpoint = outEp
        interruptEndpoint = intEp

        // 开启 PTP 会话
                scope.launch(Dispatchers.IO) {
            try {
                sendPtpCommand(OP_OPEN_SESSION, intArrayOf(1))
                val resp = readPtpResponse()
                Log.d(TAG, "USB OpenSession success: ${resp.size} bytes")

                _isConnected.value = true
                recoverAttempts = 0
                val cameraName = device.productName ?: "USB 尼康相机"
                withContext(Dispatchers.Main) {
                    listener?.onConnected(cameraName)
                    listener?.onLog("🟢 USB 线控通道就绪: $cameraName")
                }

                // 启动中断事件监听
                startInterruptWatch()

                // 自动开启 LiveView
                startLiveView()
            } catch (e: Exception) {
                Log.e(TAG, "USB init PTP failed", e)
                withContext(Dispatchers.Main) {
                    listener?.onDisconnected("PTP 初始化失败: ${e.message}")
                }
                disconnect()
            }
        }

        return true
    }

    fun disconnect() {
        _isConnected.value = false
        stopLiveView()

        interruptJob?.cancel()
        interruptJob = null

        scope.launch(Dispatchers.IO) {
            synchronized(bufferLock) {
                try {
                    // 释放接口前清 halt：这颗接口重新 claim 之后端点可能仍停在 halt，
                    // 不清的话下一次连接第一包就超时（表现是"必须重新插拔"）。
                    listOfNotNull(bulkInEndpoint, bulkOutEndpoint, interruptEndpoint).forEach { ep ->
                        runCatching { usbConnection?.let { UsbBulk.clearHalt(it, ep) } }
                    }
                    usbInterface?.let { usbConnection?.releaseInterface(it) }
                    usbConnection?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "USB cleanup error", e)
                }
                UsbBulk.resetFailures()
                usbConnection = null
                usbInterface = null
                bulkInEndpoint = null
                bulkOutEndpoint = null
                interruptEndpoint = null
            }
        }
    }

    /**
     * bulk 通道连续失败（端点被 halt、相机切了模式、线材接触不良）时自动重开会话。
     * 以前这类故障只能拔线重插；现在在会话层自愈，最多重试 [maxRecoverAttempts] 次。
     */
    private fun scheduleRecover() {
        if (recovering) return
        val device = lastDevice
        val ctx = appContext
        if (device == null || ctx == null) return
        if (recoverAttempts >= maxRecoverAttempts) {
            _isConnected.value = false
            listener?.onDisconnected("USB 通道连续失败，已停止自动恢复（请重新插拔相机）")
            return
        }
        recovering = true
        recoverAttempts++
        _isConnected.value = false
        _isLiveViewActive.value = false
        liveViewJob?.cancel()
        liveViewJob = null
        interruptJob?.cancel()
        interruptJob = null
        scope.launch(Dispatchers.IO) {
            try {
                listener?.onLog("♻️ USB 通道异常，正在自动恢复（第 $recoverAttempts 次）")
                // 必须放掉旧句柄：同一个接口被两个 connection 抢会直接 open 失败
                synchronized(bufferLock) {
                    runCatching { usbInterface?.let { usbConnection?.releaseInterface(it) } }
                    runCatching { usbConnection?.close() }
                    usbConnection = null
                    usbInterface = null
                    bulkInEndpoint = null
                    bulkOutEndpoint = null
                    interruptEndpoint = null
                }
                UsbBulk.resetFailures()
                delay(1500)
                val started = connectDevice(ctx, device, listener)
                withContext(Dispatchers.Main) {
                    if (!started) listener?.onDisconnected("USB 通道恢复失败：相机没有响应")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    listener?.onDisconnected("USB 通道恢复异常：${e.message}")
                }
            } finally {
                recovering = false
            }
        }
    }

    /**
     * 开启 USB 超低延迟实时图传 LiveView
     */
    fun startLiveView() {
        if (!_isConnected.value || _isLiveViewActive.value) return
        _isLiveViewActive.value = true

        liveViewJob = scope.launch(Dispatchers.IO) {
            try {
                enterHostControl()
                sendPtpCommand(OP_NIKON_START_LIVE_VIEW)
                val resp = readPtpResponse()
                // 轮询 DeviceReady（0x90C8），最多 2s，让相机真正进入取景态
                val deadline = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < deadline) {
                    sendPtpCommand(0x90c8.toShort())
                    val readyResp = readPtpResponse()
                    if (readyResp.size >= 12) {
                        val code = ByteBuffer.wrap(readyResp).order(ByteOrder.LITTLE_ENDIAN).getShort(6).toInt() and 0xFFFF
                        if (code == 0x2001) break
                    }
                    delay(80)
                }

                withContext(Dispatchers.Main) {
                    listener?.onLog("🚀 USB LiveView 实时图传启动 (0x9428 优先)")
                }

                while (isActive && _isLiveViewActive.value && _isConnected.value) {
                    val frameBytes = fetchUsbLiveViewFrame()
                    if (frameBytes != null && frameBytes.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(
                            frameBytes, 0, frameBytes.size, LiveViewQuality.decodeOptions()
                        )
                        if (bmp != null) {
                            _liveViewBitmap.value = bmp
                            withContext(Dispatchers.Main) {
                                listener?.onLiveViewFrame(bmp)
                            }
                        }
                    }
                    delay(30) // ~30 fps 极速回传
                    // 取景是这条通道最频繁的读写：连续失败就自愈，不要死循环刷超时
                    if (UsbBulk.consecutiveFailures >= 5) {
                        withContext(Dispatchers.Main) {
                            listener?.onLog("⚠️ USB bulk 连续失败，准备恢复通道")
                        }
                        scheduleRecover()
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "USB LiveView 轮询异常: ${e.message}")
            } finally {
                _isLiveViewActive.value = false
            }
        }
    }

    fun stopLiveView() {
        _isLiveViewActive.value = false
        liveViewJob?.cancel()
        liveViewJob = null

        scope.launch(Dispatchers.IO) {
            try {
                sendPtpCommand(OP_NIKON_END_LIVE_VIEW)
                readPtpResponse()
                // 把控制权交还机身
                sendPtpCommand(0x90c2.toShort(), intArrayOf(0))
                readPtpResponse()
            } catch (e: Exception) {
                Log.w(TAG, "USB exit liveview fallback", e)
            }
        }
    }

    /** 进入尼康主机控制：0x9435(1) → 0x90C2(1) → 属性 0xD1A6=0，不支持的老机型静默跳过。 */
    private fun enterHostControl() {
        runCatching {
            sendPtpCommand(0x9435.toShort(), intArrayOf(1))
            readPtpResponse()
        }
        runCatching {
            sendPtpCommand(0x90c2.toShort(), intArrayOf(1))
            readPtpResponse()
        }
        runCatching { setDevicePropValueUint8(0xd1a6.toShort(), 0.toByte()) }
    }

    /**
     * USB 线控快门拍摄     */
    fun triggerCapture() {
        if (!_isConnected.value) return
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { listener?.onLog("📸 USB 线控触发快门...") }
                sendPtpCommand(OP_NIKON_DEVICE_READY)
                readPtpResponse()
                delay(80)
                sendPtpCommand(OP_NIKON_AF_CAPTURE, intArrayOf(-1, 0))
                readPtpResponse()
            } catch (e: Exception) {
                Log.e(TAG, "USB trigger capture failed", e)
                withContext(Dispatchers.Main) { listener?.onLog("📸 USB 快门触发失败: ${e.message}") }
            }
        }
    }

    /**
     * USB 触发 AF 对焦     */
    fun triggerAf() {
        if (!_isConnected.value) return
        scope.launch(Dispatchers.IO) {
            try {
                sendPtpCommand(OP_NIKON_DEVICE_READY)
                readPtpResponse()
                withContext(Dispatchers.Main) { listener?.onLog("🎯 USB 触发自动对焦 (AF)") }
            } catch (e: Exception) {
                Log.e(TAG, "USB AF 对焦异常", e)
            }
        }
    }

    /**
     * USB 屏幕触控点对焦：0x9205 ChangeAfArea 收的是传感器像素坐标（不是 0..1000），
     * 挪完 AF 区域再发 0x90C1 驱动一次对焦。0x9206 是「取消对焦」，不能用。
     */
    fun setFocusPoint(normX: Float, normY: Float) {
        if (!_isConnected.value) return
        scope.launch(Dispatchers.IO) {
            try {
                val x = (normX.coerceIn(0f, 1f) * 6000).toInt()
                val y = (normY.coerceIn(0f, 1f) * 4000).toInt()
                withContext(Dispatchers.Main) { listener?.onLog("🎯 USB 屏幕触控对焦 X=${(normX * 100).toInt()}% Y=${(normY * 100).toInt()}%") }
                sendPtpCommand(0x9205.toShort(), intArrayOf(x, y))
                val afArea = readPtpResponse()
                val code = if (afArea.size >= 8) {
                    (afArea[6].toInt() and 0xFF) or ((afArea[7].toInt() and 0xFF) shl 8)
                } else -1
                Log.i(TAG, "USB CHANGE_AF_AREA x=$x y=$y resp=0x${"%04X".format(code)}")
                if (code == 0x2001) {
                    sendPtpCommand(0x90c1.toShort())
                    readPtpResponse()
                }
                sendPtpCommand(OP_NIKON_DEVICE_READY)
                readPtpResponse()
            } catch (e: Exception) {
                Log.e(TAG, "USB touch AF error", e)
            }
        }
    }

    /**
     * USB 切换视频录制；onResult 回传相机是否真的接受了这条命令。
     * 以前调用方一发送就把按钮置成录制态，机身拒绝时看起来就是「点了没反应」。
     */
    fun toggleMovieRecord(startRecord: Boolean, onResult: (Boolean) -> Unit = {}) {
        if (!_isConnected.value) {
            onResult(false)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (startRecord) {
                    withContext(Dispatchers.Main) { listener?.onLog("🔴 USB 启动视频录制...") }
                    sendPtpCommand(0x920A.toShort())
                } else {
                    withContext(Dispatchers.Main) { listener?.onLog("USB stop movie") }
                    sendPtpCommand(0x920B.toShort())
                }
                val response = readPtpResponse()
                val code = if (response.size >= 8) {
                    ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).getShort(6).toInt() and 0xFFFF
                } else {
                    0x2000
                }
                val ok = code == 0x2001
                withContext(Dispatchers.Main) {
                    listener?.onLog(
                        if (ok) "USB 视频录制命令已接受" else "USB 视频录制命令被拒绝（0x%04X）".format(code)
                    )
                    onResult(ok)
                }
            } catch (e: Exception) {
                Log.e(TAG, "USB movie recording error", e)
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    private fun startInterruptWatch() {
        val intEp = interruptEndpoint ?: return
        val conn = usbConnection ?: return

        interruptJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(64)
            while (isActive && _isConnected.value) {
                val len = UsbBulk.bulkIn(conn, intEp, buffer, buffer.size, UsbBulk.EVENT_TIMEOUT)
                if (len >= 12) {
                    val eventBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                    val eventCode = eventBuffer.getShort(6)
                    if (eventCode == 0x4002.toShort()) {
                        // ObjectAdded
                        val handle = if (len >= 16) eventBuffer.getInt(12) else 0
                        withContext(Dispatchers.Main) {
                            listener?.onLog("📸 USB 捕获实体快门动作！生成新文件 Handle: $handle")
                            listener?.onPhotoCaptured(handle)
                        }
                    }
                }
            }
        }
    }

    private fun fetchUsbLiveViewFrame(): ByteArray? {
        synchronized(bufferLock) {
            try {
                sendPtpCommand(frameCommand.toShort())
                val rawData = readPtpDataStream() ?: return null
                val type = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).getShort(4).toInt()
                if (type != USB_CONTAINER_DATA.toInt()) {
                    // 这台机器不支持 0x9428（Z50II 之外的老机型）→ 换成 0x9203
                    if (frameCommand == 0x9428) frameCommand = 0x9203
                    return null
                }
                val jpegStart = findJpegMarker(rawData, 0xFF.toByte(), 0xD8.toByte())
                if (jpegStart >= 0) {
                    val jpegEnd = findJpegMarker(rawData, 0xFF.toByte(), 0xD9.toByte(), jpegStart + 2)
                    if (jpegEnd > jpegStart) {
                        val length = (jpegEnd + 2) - jpegStart
                        val jpegBytes = ByteArray(length)
                        System.arraycopy(rawData, jpegStart, jpegBytes, 0, length)
                        return jpegBytes
                    }
                }
            } catch (e: Exception) {
                // 读取超时或单帧跳过
                }
            return null
        }
    }

    private fun sendPtpCommand(opCode: Short, params: IntArray = IntArray(0)) {
        val conn = usbConnection ?: return
        val outEp = bulkOutEndpoint ?: return

        val transId = transactionId++
        val length = 12 + params.size * 4
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length)
        buffer.putShort(USB_CONTAINER_COMMAND)
        buffer.putShort(opCode)
        buffer.putInt(transId)
        for (p in params) buffer.putInt(p)

        UsbBulk.bulkOut(conn, outEp, buffer.array(), length, UsbBulk.CMD_TIMEOUT)
    }

    private fun readPtpResponse(): ByteArray {
        val conn = usbConnection ?: return ByteArray(0)
        val inEp = bulkInEndpoint ?: return ByteArray(0)
        val buffer = ByteArray(512)
        val len = UsbBulk.bulkIn(conn, inEp, buffer, buffer.size, UsbBulk.CMD_TIMEOUT)
        if (len <= 0) {
            // 连续读空说明通道已经不健康（halt 或掉线），交给恢复逻辑，别让上层一直空转
            if (UsbBulk.consecutiveFailures >= 4) scheduleRecover()
            return ByteArray(0)
        }
        return buffer.copyOf(len)
    }

    private fun readPtpDataStream(): ByteArray? {
        val conn = usbConnection ?: return null
        val inEp = bulkInEndpoint ?: return null

        val headerBuffer = ByteArray(512)
        val firstLen = UsbBulk.bulkIn(conn, inEp, headerBuffer, headerBuffer.size, UsbBulk.FRAME_TIMEOUT)
        if (firstLen < 12) return null

        val byteBuffer = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN)
        val totalLength = byteBuffer.getInt(0)
        val containerType = byteBuffer.getShort(4)

        if (containerType == USB_CONTAINER_RESPONSE) {
            val fullData = ByteArray(totalLength)
            System.arraycopy(headerBuffer, 0, fullData, 0, firstLen)
            return fullData
        } else if (containerType != USB_CONTAINER_DATA) {
            return null
        }

        if (totalLength < 12 || totalLength > 5 * 1024 * 1024) return null
        val fullData = ByteArray(totalLength)
        System.arraycopy(headerBuffer, 0, fullData, 0, firstLen)

        var readBytes = firstLen
        val chunk = ByteArray(16 * 1024)
        while (readBytes < totalLength) {
            val want = (totalLength - readBytes).coerceAtMost(chunk.size)
            val read = UsbBulk.bulkIn(conn, inEp, chunk, want, UsbBulk.DATA_TIMEOUT)
            if (read <= 0) break
            System.arraycopy(chunk, 0, fullData, readBytes, read)
            readBytes += read
        }

        if (containerType != USB_CONTAINER_RESPONSE) {
            // 读取随后的 Response 容器
            val respBuffer = ByteArray(64)
            UsbBulk.bulkIn(conn, inEp, respBuffer, respBuffer.size, UsbBulk.CMD_TIMEOUT)
        }

        return fullData
    }

    private fun sendPtpData(opCode: Short, params: IntArray, data: ByteArray) {
        val conn = usbConnection ?: return
        val outEp = bulkOutEndpoint ?: return
        val transId = transactionId++

        // 1. 发送 Command 容器
                val cmdLength = 12 + params.size * 4
        val cmdBuffer = ByteBuffer.allocate(cmdLength).order(ByteOrder.LITTLE_ENDIAN)
        cmdBuffer.putInt(cmdLength)
        cmdBuffer.putShort(USB_CONTAINER_COMMAND)
        cmdBuffer.putShort(opCode)
        cmdBuffer.putInt(transId)
        for (p in params) cmdBuffer.putInt(p)
        UsbBulk.bulkOut(conn, outEp, cmdBuffer.array(), cmdLength, UsbBulk.CMD_TIMEOUT)

        // 2. 发送 Data 容器
                val dataLength = 12 + data.size
        val dataBuffer = ByteBuffer.allocate(dataLength).order(ByteOrder.LITTLE_ENDIAN)
        dataBuffer.putInt(dataLength)
        dataBuffer.putShort(USB_CONTAINER_DATA)
        dataBuffer.putShort(opCode)
        dataBuffer.putInt(transId)
        dataBuffer.put(data)
        UsbBulk.bulkOut(conn, outEp, dataBuffer.array(), dataLength, UsbBulk.DATA_TIMEOUT)
    }

    // ========== 🎛️ 相机属性 读取/设置/描述 (PTP 标准操作码) ==========
                private val PROP_SHUTTER_SPEED: Short = 0x500D.toShort()
    private val PROP_FNUMBER: Short = 0x5007.toShort()
    private val PROP_ISO: Short = 0x500F.toShort()
    private val PROP_NIKON_AF_MODE: Short = 0xD161.toShort()

    fun getDevicePropValue(propCode: Short): ByteArray? {
        synchronized(bufferLock) {
            try {
                sendPtpCommand(0x1015.toShort(), intArrayOf(propCode.toInt() and 0xFFFF))
                val dataPacket = readPtpDataStream()
                if (dataPacket != null && dataPacket.size >= 12) {
                    val buf = ByteBuffer.wrap(dataPacket).order(ByteOrder.LITTLE_ENDIAN)
                    val containerType = buf.getShort(4)
                    if (containerType == USB_CONTAINER_DATA && dataPacket.size > 12) {
                        val length = dataPacket.size - 12
                        val result = ByteArray(length)
                        System.arraycopy(dataPacket, 12, result, 0, length)
                        return result
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "USB Read Property Failed", e)
            }
            return null
        }
    }

    fun setDevicePropValue(propCode: Short, valueBytes: ByteArray): Boolean {
        synchronized(bufferLock) {
            try {
                sendPtpData(0x1016.toShort(), intArrayOf(propCode.toInt() and 0xFFFF), valueBytes)
                val resp = readPtpResponse()
                if (resp.size >= 12) {
                    val buf = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
                    val respCode = buf.getShort(6).toInt() and 0xFFFF
                    return respCode == 0x2001 // OK
                }
            } catch (e: Exception) {
                Log.e(TAG, "USB Set Property Failed", e)
            }
            return false
        }
    }

    fun setDevicePropValueUint32(propCode: Short, value: Int): Boolean {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        return setDevicePropValue(propCode, buf.array())
    }

    fun setDevicePropValueUint16(propCode: Short, value: Short): Boolean {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(value)
        return setDevicePropValue(propCode, buf.array())
    }

    fun setDevicePropValueUint8(propCode: Short, value: Byte): Boolean {
        return setDevicePropValue(propCode, byteArrayOf(value))
    }

    fun syncAllDeviceProps(): Map<Short, Int> {
        val result = mutableMapOf<Short, Int>()
        val propsToSync = listOf(PROP_SHUTTER_SPEED, PROP_FNUMBER, PROP_ISO)
        for (prop in propsToSync) {
            try {
                val bytes = getDevicePropValue(prop)
                if (bytes != null && bytes.size >= 4) {
                    val value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
                    result[prop] = value
                } else if (bytes != null && bytes.size >= 2) {
                    val value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt() and 0xFFFF
                    result[prop] = value
                }
            } catch (e: Exception) {
                Log.w(TAG, "USB prop read skip", e)
            }
        }
        return result
    }

    // ========== 📦 机身状态与图库（USB 侧真实读取） ==========

    private val LE: ByteOrder = ByteOrder.LITTLE_ENDIAN

    /**
     * 发一条 PTP 命令并取回 Data 容器载荷（去掉 12 字节容器头）。
     * 返回 null 表示相机回了 Response/Error 而不是数据。
     */
    private fun requestData(opCode: Short, params: IntArray): ByteArray? {
        synchronized(bufferLock) {
            return try {
                sendPtpCommand(opCode, params)
                val packet = readPtpDataStream() ?: return null
                if (packet.size <= 12) return null
                val type = ByteBuffer.wrap(packet).order(LE).getShort(4).toInt()
                if (type != USB_CONTAINER_DATA.toInt()) return null
                packet.copyOfRange(12, packet.size)
            } catch (e: Exception) {
                Log.e(TAG, "USB requestData 0x${"%04X".format(opCode.toInt() and 0xFFFF)} 失败", e)
                null
            }
        }
    }

    /** 电量百分比（属性 0x5001 BatteryLevel，单字节）。 */
    fun readBatteryLevel(): Int? {
        val raw = getDevicePropValue(0x5001.toShort()) ?: return null
        if (raw.isEmpty()) return null
        return raw[0].toInt() and 0xFF
    }

    /** ISO 感光度（属性 0x500F，尼康多数机型返回 UINT32，少数 UINT16）。 */
    fun readIso(): Int? {
        val raw = getDevicePropValue(0x500F.toShort()) ?: return null
        if (raw.isEmpty()) return null
        return when {
            raw.size >= 4 -> {
                val value = ByteBuffer.wrap(raw).order(LE).getInt(0).toLong() and 0xFFFFFFFFL
                if (value in 1L..1000000L) value.toInt() else null
            }
            raw.size >= 2 -> ByteBuffer.wrap(raw).order(LE).getShort(0).toInt() and 0xFFFF
            else -> raw[0].toInt() and 0xFF
        }
    }

    /** 存储卡容量：GetStorageIDs(0x1004) + GetStorageInfo(0x1005)。 */
    data class UsbStorage(val freeBytes: Long, val totalBytes: Long, val freeImages: Int)

    fun readStorage(): UsbStorage? {
        val ids = requestData(0x1004.toShort(), intArrayOf()) ?: return null
        if (ids.size < 8) return null
        val count = ByteBuffer.wrap(ids).order(LE).getInt(0)
        if (count <= 0) return null
        val firstId = ByteBuffer.wrap(ids).order(LE).getInt(4)
        val info = requestData(0x1005.toShort(), intArrayOf(firstId)) ?: return null
        if (info.size < 22) return null
        val buffer = ByteBuffer.wrap(info).order(LE)
        val maxCapability = buffer.getLong(6)
        val freeBytes = buffer.getLong(14)
        val freeImages = if (info.size >= 26) buffer.getInt(22) else 0
        return when {
            freeBytes > 0 -> UsbStorage(freeBytes, if (maxCapability > 0) maxCapability else freeBytes, freeImages)
            freeImages > 0 -> UsbStorage(0L, maxCapability, freeImages)
            else -> null
        }
    }

    /** 机身照片条目（GetObjectHandles 0x1007 + GetObjectInfo 0x1008）。 */
    data class UsbObject(
        val handle: Int,
        val fileName: String,
        val sizeBytes: Long,
        val dateModified: String,
        val isRaw: Boolean,
        val formatCode: Int
    )

    fun listObjects(limit: Int = 200): List<UsbObject> {
        val ids = requestData(0x1004.toShort(), intArrayOf()) ?: return emptyList()
        if (ids.size < 8) return emptyList()
        val count = ByteBuffer.wrap(ids).order(LE).getInt(0)
        if (count <= 0) return emptyList()
        val storageId = ByteBuffer.wrap(ids).order(LE).getInt(4)

        val handleData = requestData(0x1007.toShort(), intArrayOf(storageId, 0, -1)) ?: return emptyList()
        if (handleData.size < 8) return emptyList()
        val handleCount = ByteBuffer.wrap(handleData).order(LE).getInt(0)
        if (handleCount <= 0) return emptyList()

        val result = mutableListOf<UsbObject>()
        val handles = ByteBuffer.wrap(handleData).order(LE)
        for (i in 0 until handleCount.coerceAtMost(limit)) {
            val handle = handles.getInt(4 + i * 4)
            val info = requestData(0x1008.toShort(), intArrayOf(handle)) ?: continue
            if (info.size < 52) continue
            val buffer = ByteBuffer.wrap(info).order(LE)
            val formatCode = buffer.getShort(4).toInt() and 0xFFFF
            val compressedSize = buffer.getInt(8).toLong() and 0xFFFFFFFFL
            val (name, _) = parsePtpString(info, 52)
            val (date, _) = parsePtpString(info, 52 + ptpStringLength(info, 52))
            val (modified, _) = parsePtpString(info, 52 + ptpStringLength(info, 52) + ptpStringLength(info, 52 + ptpStringLength(info, 52)))
            val upper = name.uppercase()
            val isRaw = upper.endsWith(".NEF") || upper.endsWith(".NRW") || upper.endsWith(".RAW")
            if (name.isBlank()) continue
            result += UsbObject(
                handle = handle,
                fileName = name,
                sizeBytes = compressedSize,
                dateModified = modified.ifBlank { date },
                isRaw = isRaw,
                formatCode = formatCode
            )
        }
        return result
    }

    /**
     * 用 GetPartialObject(0x101B) 分块把整张照片写到本地文件。
     * 单次 1 MiB，绕开 readPtpDataStream 的 5 MiB 单包上限，24MP 的 JPG 也能取。
     */
    fun copyObjectToFile(handle: Int, target: java.io.File, expectedSize: Long): Boolean {
        val chunkSize = 1024 * 1024
        var offset = 0
        try {
            target.parentFile?.mkdirs()
            java.io.FileOutputStream(target).use { out ->
                while (true) {
                    if (expectedSize > 0 && offset >= expectedSize) break
                    val chunk = requestData(0x101B.toShort(), intArrayOf(handle, offset, chunkSize)) ?: return false
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    offset += chunk.size
                    if (chunk.size < chunkSize) break
                    if (offset > 512 * 1024 * 1024) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB 分块下载失败 handle=$handle", e)
            return false
        }
        return target.length() > 0
    }

    /** 大缩略图（尼康厂商操作 0x90C4，约 1600×1200）。 */
    fun getBigThumbnail(handle: Int): ByteArray? = requestData(0x90C4.toShort(), intArrayOf(handle))

    /** 读取某张照片的缩略图（GetThumb 0x100A），返回 JPEG 字节。 */    fun getThumbnail(handle: Int): ByteArray? {
        val data = requestData(0x100A.toShort(), intArrayOf(handle)) ?: return null
        if (data.isEmpty()) return null
        // 有些机型会把 JPEG 头前再包一层，这里兜底找一下 SOI
        val soi = findJpegMarker(data, 0xFF.toByte(), 0xD8.toByte())
        return if (soi > 0) data.copyOfRange(soi, data.size) else data
    }

    /** PTP 字符串：1 字节长度（UTF-16 字符数）+ UTF-16LE 内容。 */
    private fun parsePtpString(buffer: ByteArray, offset: Int): Pair<String, Int> {
        if (offset >= buffer.size) return "" to 0
        val chars = buffer[offset].toInt() and 0xFF
        val next = offset + 1 + chars * 2
        if (chars == 0 || next > buffer.size) return "" to 0
        val text = buildString {
            for (i in 0 until chars) {
                val lo = buffer[offset + 1 + i * 2].toInt() and 0xFF
                val hi = buffer[offset + 2 + i * 2].toInt() and 0xFF
                val code = (hi shl 8) or lo
                if (code in 0x20..0x7E || code > 0x7E) append(code.toChar())
            }
        }
        return text to next
    }

    private fun ptpStringLength(buffer: ByteArray, offset: Int): Int {
        if (offset >= buffer.size) return 0
        val chars = buffer[offset].toInt() and 0xFF
        return 1 + chars * 2
    }

    private fun findJpegMarker(bytes: ByteArray, b1: Byte, b2: Byte, offset: Int = 0): Int {
        for (i in offset until bytes.size - 1) {
            if (bytes[i] == b1 && bytes[i + 1] == b2) return i
        }
        return -1
    }
}



