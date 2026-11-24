package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.lutcamera.lutcamerapro.log.DoukLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Nikon PTP/IP client. Camera operations stay serial; JPEG decoding runs independently. */
class NikonPtpClient {
    companion object {
        private const val TAG = "NikonPtpClient"
        const val DEFAULT_NIKON_AP_IP = "192.168.1.1"
        const val PTP_PORT = 15740
        private val OP_NIKON_DEVICE_READY = 0x90C8.toShort()
        val defaultInstance = NikonPtpClient()
    }
    enum class ConnectionMode { CAMERA_AP, PHONE_HOTSPOT, ROUTER }
    data class LiveMetrics(val receiveFps: Double = 0.0, val decodeFps: Double = 0.0,
        val jpegKB: Double = 0.0, val payloadMBps: Double = 0.0,
        val requestMs: Double = 0.0, val decodeMs: Double = 0.0,
        val width: Int = 0, val height: Int = 0, val skipped: Long = 0)
    data class PropDesc(val dataType: Int, val writable: Boolean, val currentRaw: Long,
        val enumValues: LongArray, val rangeMin: Long, val rangeMax: Long, val rangeStep: Long) {
        val isEnum get() = enumValues.isNotEmpty()
    }
    interface NikonEventListener {
        fun onConnected() {}
        fun onDisconnected(reason: String) {}
        fun onLog(msg: String) {}
        fun onPhotoReceived(fileName: String, jpegBytes: ByteArray) {}
        suspend fun onAutoJpegReceived(fileName: String, jpegBytes: ByteArray) { onPhotoReceived(fileName, jpegBytes) }
        /** 新拍的是 RAW（NEF/NRW/DNG）：不走 JPG 处理流水线，直接原样落盘。 */
        suspend fun onAutoRawReceived(fileName: String, rawBytes: ByteArray) {}
        fun onTransferStatus(message: String) {}
        fun onLiveViewFrame(bitmap: Bitmap) {}
    }
    private class Connection(val command: Socket, val events: Socket, val trace: NikonTrace?) {
        val label = UUID.randomUUID().toString().take(8)
        fun record(channel: String): (String, ByteArray) -> Unit = { direction, bytes -> trace?.packet("$label/$channel", direction, bytes) }
        fun send(socket: Socket, channel: String, bytes: ByteArray) {
            record(channel)("TX_ATTEMPT", bytes)
            socket.outputStream.write(bytes)
            record(channel)("TX_WRITTEN", bytes)
        }
        val photos = Channel<Int>(32)
        val pendingPhotos = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val deliveredPhotos = LinkedHashSet<Int>()
        var pollEvents = false
        val mutex = Mutex()
        var transaction = 1
        @Volatile var restricted = false
        fun close() { photos.close(); trace?.note("$label CLOSE sockets"); runCatching { command.close() }; runCatching { events.close() } }
    }
    private class CameraResponse(val code: Int) : IOException("相机返回 0x${code.toString(16)}")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var connection: Connection? = null
    private val lifecycle = Any()
    private var generation = 0L
    val sessionVersion: Long get() = synchronized(lifecycle) { generation }
    val canControlCamera: Boolean get() = connection?.let { !it.restricted } ?: false
    @Volatile var autoTransferJpeg = false
    /**
     * 空闲保活间隔。固定 15 秒，只发 0x1001 这类只读心跳，不写相机菜单。
     * 0.0.143 起取消「Wi‑Fi 下压到 6 秒」的老做法：那是把相机反复唤醒的元凶。
     */
    @Volatile var keepAliveIntervalMs: Long = 15_000L
    /**
     * 是否允许接管相机（0x9435/0x90C2）：false 时取景只发 0x9201，
     * 机身屏幕继续显示画面，也不出「已连接至智能设备」；由监看里的「接管相机」开关控制。
     */
    @Volatile var takeoverHostControl: Boolean = false
    /**
     * 传感器像素尺寸：0x9205 ChangeAfArea 要的是传感器坐标，不是取景 JPEG 坐标。
     * 连上后按机身型号查表，查不到就按 6000×4000 估算（D7200 级别，偏差可接受）。
     */
    @Volatile private var sensorWidthPx: Int = 6000
    @Volatile private var sensorHeightPx: Int = 4000

    /** 机身型号 → 传感器分辨率（用于 ChangeAfArea 坐标换算） */
    private fun sensorSizeForModel(model: String): Pair<Int, Int> {
        val m = model.uppercase().replace(" ", "")
        return when {
            m.contains("Z9") || m.contains("Z8") || m.contains("Z7") -> 8256 to 5504
            m.contains("Z50") || m.contains("Z30") || m.contains("ZFC") -> 5568 to 3712
            m.contains("Z6") -> 6048 to 4024
            m.contains("Z5") -> 6016 to 4016
            m.contains("Z") -> 6048 to 4024
            m.contains("D850") || m.contains("D500") || m.contains("D7500") -> 5568 to 3712
            m.contains("D7") || m.contains("D6") -> 6000 to 4000
            else -> 6000 to 4000
        }
    }
    private var watchJob: Job? = null
    private var liveViewJob: Job? = null
    private val liveControl = Mutex()
    private var activeListener: NikonEventListener? = null
    /** 读图库期间暂停 0x90C7 轮询，避免尼康把枚举事务取消（0x201F） */
    @Volatile private var storageBusy = false
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    /** 会话对象是否还挂着：UI 的 isConnected 可能因事件通道抖动变 false，这里才是真实存活。 */
    val hasConnection: Boolean get() = synchronized(lifecycle) { connection != null }
    private val _isLiveViewActive = MutableStateFlow(false)
    val isLiveViewActive = _isLiveViewActive.asStateFlow()
    /** 用户是否还想要取景；控制操作临时停帧后只在为 true 时自动恢复。 */
    @Volatile private var liveViewRequested = false
    /** 连续超时次数：单次读超时可能只是相机忙，连续 3 次才判定会话真的坏了。 */
    private var ioTimeoutStreak = 0
    private val _liveViewBitmap = MutableStateFlow<Bitmap?>(null)
    val liveViewBitmap = _liveViewBitmap.asStateFlow()
    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics = _metrics.asStateFlow()
    private val _connectionStage = MutableStateFlow("未连接")
    val connectionStage = _connectionStage.asStateFlow()
    private val _cameraLabel = MutableStateFlow("")
    val cameraLabel = _cameraLabel.asStateFlow()
    // 相机只给「待配对」最小能力集时置 true：这不是连接失败，而是还没完成机身配对
    private val _pairingRequired = MutableStateFlow(false)
    val pairingRequired = _pairingRequired.asStateFlow()
    /**
     * 相机处于「连接至计算机」模式：机身把控制权交给了电脑端（Camera Control Pro / NX Tether）。
     * 这种会话能读到图库，但控制类命令会被拒，而且电脑端会抢事件通道。
     * 与「待配对」区分开，否则会误导用户去点配对。
     */
    private val _hostBusy = MutableStateFlow(false)
    val hostBusy = _hostBusy.asStateFlow()
    @Volatile var lastResponseCode = 0
        private set
    /** 最近一次属性写入是否经过回读校验（true=机身当前值确实变了） */
    @Volatile var lastWriteVerified = false
        private set
    private var cameraIdentity = ""
    private var preferences: android.content.SharedPreferences? = null
    private var hostGuid = UUID.randomUUID()
    private var trace: NikonTrace? = null
    @Synchronized private fun recorder(context: Context): NikonTrace {
        return trace ?: NikonTrace(java.io.File(context.filesDir, "nikon-traces")).also { trace = it }
    }
    private fun environment(context: Context): String = buildString {
        appendLine("Android=${android.os.Build.VERSION.RELEASE} SDK=${android.os.Build.VERSION.SDK_INT} phone=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}")
        appendLine("App=${context.packageName} version=${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        appendLine("defaultNetwork=${cm.activeNetwork} processBoundNetwork=${cm.boundNetworkForProcess}")
        runCatching { cm.allNetworks.forEach { network ->
            appendLine("network=$network capabilities=${cm.getNetworkCapabilities(network)} links=${cm.getLinkProperties(network)}")
        } }.onFailure { appendLine("networkSnapshotError=$it") }
        runCatching { java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            appendLine("interface=${iface.name} up=${iface.isUp} addresses=${iface.interfaceAddresses.joinToString()}")
        } }.onFailure { appendLine("interfaceSnapshotError=$it") }
    }
    fun exportDiagnostics(context: Context, output: java.io.OutputStream) {
        val log = recorder(context)
        log.note("EXPORT stage=${_connectionStage.value} mode/session=$generation")
        log.export(output, environment(context))
    }
    fun recordDiagnostic(message: String) { trace?.note("APP $message") }

    fun restorePairingIdentity(context: Context, archive: InputStream): String {
        if (connection != null) throw IOException("请先断开相机连接")
        val recovered = NikonIdentityRecovery.fromArchive(archive)
        val saved = context.applicationContext.getSharedPreferences("nikon_transport", Context.MODE_PRIVATE)
        saved.edit().putString("hostGuid", recovered.toString()).apply()
        if (saved.getString("hostGuid", null) != recovered.toString()) throw IOException("配对身份未能保存")
        hostGuid = recovered
        recorder(context).note("PAIRING_IDENTITY_RESTORED fingerprint=${recovered.toString().take(8)}")
        return recovered.toString().take(8)
    }

    fun startNikonWiWatch(context: Context? = null, cameraIp: String = DEFAULT_NIKON_AP_IP,
        listener: NikonEventListener? = null, mode: ConnectionMode = ConnectionMode.CAMERA_AP) {
        stopNikonWatch()
        val token = synchronized(lifecycle) { activeListener = listener; generation }
        preferences = context?.applicationContext?.getSharedPreferences("nikon_transport", Context.MODE_PRIVATE)
        hostGuid = runCatching { UUID.fromString(preferences?.getString("hostGuid", null)) }.getOrElse {
            UUID.randomUUID().also { preferences?.edit()?.putString("hostGuid", it.toString())?.apply() }
        }
        _metrics.value = LiveMetrics()
        // 新一次连接先清掉上一轮的待配对 / 电脑占用标记
        _pairingRequired.value = false
        _hostBusy.value = false
        // 身份 GUID 决定相机「已配对设备」里记几条：重装/清数据会换新身份，旧记录会一直留在相机上
        DoukLog.i("NikonPtp", "IDENTITY", "hostGuid" to hostGuid.toString(), "mode" to mode, "target" to cameraIp)
        watchJob = scope.launch {
            var own: Connection? = null
            try {
                context?.let { recorder(it).note("BEGIN mode=$mode target=$cameraIp:$PTP_PORT generation=$token\n${environment(it)}") }
                DoukLog.i("NikonPtp", "CONNECT_BEGIN", "mode" to mode, "target" to "$cameraIp:$PTP_PORT")
                require(cameraIp.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) &&
                    cameraIp.split('.').all { it.toInt() in 0..255 }) { "请输入相机的 IPv4 地址" }
                val address = InetAddress.getByName(cameraIp)
                // 选路交给 CameraRouteResolver：现成网络优先，再 requestNetwork 兜底，
                // 最后把 source 地址钉到相机网段（鸿蒙上 per-network socket 不保证生效）。
                val route = context?.let { CameraRouteResolver.resolve(it, cameraIp) }
                    ?: CameraRouteResolver.Route(
                        network = null,
                        pinnedLocalIp = null,
                        source = CameraRouteResolver.Route.Source.DEFAULT,
                        validated = false,
                        localNets = CameraLanProbe.localNetworkSummary()
                    )
                if (mode == ConnectionMode.CAMERA_AP && route.validated) {
                    listener?.onLog("当前 Wi-Fi 能访问互联网，可能是家用路由器。相机热点模式请先连接机身显示的 Wi-Fi，再连接 192.168.1.1。")
                    trace?.note("AP_NETWORK_WARNING ${route.describe()} target=$cameraIp may be a router")
                }
                val commandSocket = route.network?.socketFactory?.createSocket() ?: Socket()
                val eventSocket = route.network?.socketFactory?.createSocket() ?: Socket()
                route.pinnedLocalIp?.let { ip ->
                    runCatching { commandSocket.bind(InetSocketAddress(ip, 0)) }
                    runCatching { eventSocket.bind(InetSocketAddress(ip, 0)) }
                }
                DoukLog.i("NikonPtp", "NET_ROUTE", "target" to cameraIp, "route" to route.describe())
                if (route.source == CameraRouteResolver.Route.Source.REQUESTED_NETWORK) {
                    DoukLog.i("NikonPtp", "NET_REQUESTED", "target" to cameraIp, "granted" to true)
                }
                val c = Connection(commandSocket, eventSocket, trace)
                own = c
                synchronized(lifecycle) {
                    if (generation != token) { c.close(); throw CancellationException() }
                    connection = c
                    _connectionStage.value = "正在连接 $cameraIp:$PTP_PORT"
                }
                listener?.onLog("连接方式 $mode，目标 $cameraIp；${if (route.bound) "使用 Wi-Fi 路由" else "使用系统本地路由"}")
                trace?.note("${c.label} ROUTE ${route.describe()} mode=$mode target=$address:$PTP_PORT")
                c.command.tcpNoDelay = true
                c.command.soTimeout = 12000
                c.command.connect(InetSocketAddress(address, PTP_PORT), 8000)
                trace?.note("${c.label} TCP_COMMAND_CONNECTED local=${c.command.localSocketAddress} remote=${c.command.remoteSocketAddress}")
                DoukLog.d("NikonPtp", "TCP_CONNECTED", "local" to c.command.localSocketAddress, "remote" to c.command.remoteSocketAddress)
                _connectionStage.value = "设备可达，正在握手"
                val name = "LutCameraPro\u0000".toByteArray(Charsets.UTF_16LE)
                val init = ByteBuffer.allocate(16 + name.size + 4).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(hostGuid.mostSignificantBits).putLong(hostGuid.leastSignificantBits)
                    .put(name).putInt(0x10000).array()
                c.send(c.command, "command", PtpIpWire.packet(1, init))
                val ack = PtpIpWire.read(c.command.inputStream, trace = c.record("command"))
                if (PtpIpWire.type(ack) == 5) {
                    val reason = if (ack.size >= 12) PtpIpWire.le(ack).getInt(8) else -1
                    // 相机端屏幕上那句「连接失败」就对应这里：机身主动回了 InitFail
                    DoukLog.e("NikonPtp", "HANDSHAKE_FAIL", "initFail" to reason, "target" to "$cameraIp:$PTP_PORT",
                        "hint" to if (reason == 1) "相机不认这个配对身份" else "相机主动断开")
                    throw IOException(if (reason == 1)
                        "相机拒绝此应用的配对身份 (InitFail 1)。若曾重装或清除应用数据，请从旧日志恢复身份，或在相机上新建配置并重新配对。"
                    else "相机拒绝握手 (InitFail $reason)。相机一次只允许一台设备连接：请在相机「已配对设备」里删掉除本机以外的记录（包括 SnapBridge 和旧版连镜记录），再重连。")
                }
                if (PtpIpWire.type(ack) != 2 || ack.size < 28) throw IOException("相机握手回复无效")
                val id = PtpIpWire.le(ack).getInt(8)
                cameraIdentity = ack.copyOfRange(12, 28).joinToString("") { "%02x".format(it) }
                DoukLog.i("NikonPtp", "HANDSHAKE_ACK", "identity" to cameraIdentity.take(12), "sessionId" to id)
                c.events.tcpNoDelay = true
                c.events.soTimeout = 5000
                c.events.connect(InetSocketAddress(address, PTP_PORT), 4000)
                trace?.note("${c.label} TCP_EVENT_CONNECTED local=${c.events.localSocketAddress} remote=${c.events.remoteSocketAddress}")
                c.send(c.events, "event", PtpIpWire.packet(3, ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN).putInt(id).array()))
                if (PtpIpWire.type(PtpIpWire.read(c.events.inputStream, trace = c.record("event"))) != 4)
                    throw IOException("事件通道握手失败")
                c.events.soTimeout = 0 // An idle event channel is normal; close() interrupts it.
                val opened = operation(0x1002, intArrayOf(1), expected = c)
                DoukLog.i("NikonPtp", "OPEN_SESSION", "code" to "0x%04X".format(opened.response.code))
                if (opened.response.code != 0x2001) throw CameraResponse(opened.response.code)
                val info = operation(0x1001, expected = c)
                DoukLog.i("NikonPtp", "DEVICE_INFO", "code" to "0x%04X".format(info.response.code))
                if (info.response.code != 0x2001) {
                    // 会话没真正成立：OpenSession 过了但机身信息拿不到，不能算「已连接」
                    throw IOException("相机未返回机身信息（0x1001 响应 0x${"%04X".format(info.response.code)}），会话未成立")
                }
                var device = PtpIpWire.deviceInfo(info.data)
                if (device.model.isBlank()) throw IOException("相机返回的机身信息为空，会话未成立")
                c.restricted = PtpIpWire.restrictedNikonProfile(device.operations)
                c.pollEvents = 0x90c7 in device.operations
                trace?.note("${c.label} CAPABILITIES restricted=${c.restricted} operations=${device.operations.joinToString { "0x" + it.toString(16) }} properties=${device.properties.joinToString { "0x" + it.toString(16) }}")
                _cameraLabel.value = "${device.model} · ${device.firmware}"
                sensorSizeForModel(device.model).let { (w, h) ->
                    sensorWidthPx = w
                    sensorHeightPx = h
                    DoukLog.i("NikonPtp", "SENSOR_SIZE", "model" to device.model, "size" to "${w}x$h")
                }
                DoukLog.i("NikonPtp", "CAPABILITIES", "model" to device.model, "firmware" to device.firmware,
                    "commands" to device.operations.size, "props" to device.properties.size, "restricted" to c.restricted)
                // 把机身公布的操作码整份落盘：排查图库/取景为什么被拒时按这个对表
                DoukLog.i("NikonPtp", "OPERATIONS",
                    "ops" to device.operations.joinToString(",") { "0x" + it.toString(16) })
                listener?.onLog("相机 ${device.model}，固件 ${device.firmware}，上报 ${device.operations.size} 个命令和 ${device.properties.size} 个属性")

                // 尼康 Z 系列机型连上后必须先发 ChangeApplicationMode(0x9435, p1=1)
                // 才会把相机从受限保护态切到"应用控制"模式。不发这条，相机只公布
                // 0x952b/0x935a 这类最小命令集，看起来像「待配对」，其实是没被授权。
                var unlockCode = -1
                if (c.restricted) {
                    val unlock = runCatching { operation(0x9435, intArrayOf(1), expected = c) }.getOrNull()
                    unlockCode = unlock?.response?.code ?: -1
                    DoukLog.i("NikonPtp", "APP_MODE", "op" to "0x9435", "code" to "0x%04X".format(unlockCode))
                    trace?.note("${c.label} APP_MODE code=0x${"%04X".format(unlockCode)}")
                    if (unlockCode == 0x2001) {
                        val info2 = runCatching { operation(0x1001, expected = c) }.getOrNull()
                        if (info2 != null && info2.response.code == 0x2001) {
                            val d2 = PtpIpWire.deviceInfo(info2.data)
                            DoukLog.i("NikonPtp", "CAPABILITIES_AFTER_MODE",
                                "commands" to d2.operations.size, "props" to d2.properties.size,
                                "restricted" to PtpIpWire.restrictedNikonProfile(d2.operations))
                            trace?.note("${c.label} CAPABILITIES_AFTER_MODE ops=${d2.operations.joinToString { "0x" + it.toString(16) }}")
                            if (d2.operations.size >= device.operations.size) {
                                device = d2
                                c.restricted = PtpIpWire.restrictedNikonProfile(d2.operations)
                                c.pollEvents = 0x90c7 in d2.operations
                            }
                        }
                    }
                }
                if (c.restricted) {
                    // 切换到应用控制模式被拒，且相机回的是「忙」类错误码：说明机身正在
                    // 「连接至计算机」模式，控制权已经在电脑端（Camera Control Pro / NX Tether）。
                    // 这不是待配对，让用户去点配对只会白折腾。
                    if (unlockCode == 0x2019 || unlockCode == 0xA004 || unlockCode == 0x200F || unlockCode == 0x2016) {
                        _hostBusy.value = true
                        DoukLog.w("NikonPtp", "HOST_BUSY", "model" to device.model,
                            "unlockCode" to "0x%04X".format(unlockCode),
                            "commands" to device.operations.size)
                        withContext(Dispatchers.Main) {
                            synchronized(lifecycle) {
                                if (generation != token) throw CancellationException()
                                _connectionStage.value = "相机被计算机占用：控制不可用"
                            }
                        }
                        listener?.onLog("相机在「连接至计算机」模式，控制权在电脑端，机身会拒绝连镜的操作。要控制相机请在相机上改成「连接至智能设备」。")
                        // 这种会话不取景、不列图库，但仍要喂白名单保活，
                        // 否则相机在等电脑时会把 Wi‑Fi 睡过去，用户看到的就是「时不时断开」。
                        launch { keepAliveLoop(c) }
                        return@launch
                    }
                    // 相机只给 0x952b(请求配对) / 0x935a(提交确认) 这一小组命令时，说明它在等配对，
                    // 不是连接失败。这里绝不能抛异常关会话，否则永远没机会把配对做下去。
                    _pairingRequired.value = true
                    DoukLog.i("NikonPtp", "PAIRING_REQUIRED", "model" to device.model,
                        "commands" to device.operations.size, "props" to device.properties.size, "unlockCode" to "0x%04X".format(unlockCode))
                    withContext(Dispatchers.Main) {
                        synchronized(lifecycle) {
                            if (generation != token) throw CancellationException()
                            _connectionStage.value = "相机待配对：请点「开始配对」，核对机身屏幕上的验证码"
                        }
                    }
                    listener?.onLog("相机处于待配对状态（仅 ${device.operations.size} 个命令）。点「开始配对」即可继续。")
                    // 配对窗口里也要保活，否则等用户去点机身验证码时连接先断了。
                    launch { keepAliveLoop(c) }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    synchronized(lifecycle) {
                        if (generation != token) throw CancellationException()
                        _isConnected.value = true
                        _connectionStage.value = "已连接 ${device.model}，准备取景"
                        listener?.onConnected()
                    }
                }
                launch { listenEvents(c, token) }
                launch { receiveNewPhotos(c, listener) }
                launch { pollPhotoEvents(c) }
                launch { keepAliveLoop(c) }
                // 取景只在用户打开全屏监视时才启动：常开会让相机一直处于「取景事务」中，
                // 读机身图库、写曝光参数都会被回 0x2019 / 0xA002，白耗电还抢不到属性。
            } catch (e: CancellationException) { own?.close(); throw e
            } catch (e: Exception) {
                trace?.note("CONNECT_ERROR session=${own?.label} stage=${_connectionStage.value} local=${own?.command?.localSocketAddress}\n${e.stackTraceToString()}")
                own?.close()
                withContext(Dispatchers.Main) {
                    synchronized(lifecycle) {
                        if (generation == token) {
                            val onCameraNet = context?.let { CameraLanProbe.localIpForSubnet(cameraIp) }
                            val friendly = when {
                                e is java.net.SocketTimeoutException ->
                                    "相机端口可达但 PTP/IP 握手无响应：请先在相机上断开 SnapBridge/旧设备，重新进入「连接至智能设备」后再试"
                                e is java.net.ConnectException && onCameraNet == null ->
                                    "手机不在相机网段（本机 ${CameraLanProbe.localNetworkSummary()}，相机 $cameraIp）。请确认已连上相机显示的 Wi-Fi 热点，并关闭 VPN / Wi-Fi 双网加速 / 移动数据后重试；若相机 Wi-Fi 已自动关闭，请在相机上重新进入「连接至智能设备 → Wi-Fi 连接」"
                                e is java.net.ConnectException ->
                                    "已在相机网段（本机 $onCameraNet），但 $cameraIp:$PTP_PORT 不通。相机可能在等待超时后自行关闭了 Wi-Fi，请在相机上重新进入 Wi-Fi 连接画面后再试"
                                else -> e.message ?: "连接失败"
                            }
                            DoukLog.e("NikonPtp", "CONNECT_FAIL", "stage" to _connectionStage.value,
                                "error" to (e.message ?: e::class.java.simpleName), "type" to e::class.java.simpleName,
                                "onCameraNet" to (onCameraNet ?: "-"),
                                "localNets" to CameraLanProbe.localNetworkSummary())
                            _connectionStage.value = "连接失败：$friendly"
                            _isConnected.value = false
                            connection = null
                            listener?.onDisconnected(friendly)
                        }
                    }
                }
            }
        }
    }

    fun stopNikonWatch() {
        trace?.note("STOP requested stage=${_connectionStage.value}")
        synchronized(lifecycle) {
            generation++
            _isConnected.value = false
            _isLiveViewActive.value = false
            _pairingRequired.value = false
            _hostBusy.value = false
            connection?.close() // Close synchronously so an old job cannot close a newer connection.
            connection = null
            watchJob?.cancel(); watchJob = null
            liveViewJob?.cancel(); liveViewJob = null
            _liveViewBitmap.value = null
            _metrics.value = LiveMetrics()
            _connectionStage.value = "未连接"
            _cameraLabel.value = ""
            storageCache = null
        }
    }

    private fun transportFailed(c: Connection, reason: String) {
        c.trace?.note("${c.label} TRANSPORT_FAILED $reason")
        ioTimeoutStreak = 0
        liveViewRequested = false
        synchronized(lifecycle) {
            c.close()
            if (connection !== c) return
            connection = null
            _isConnected.value = false
            _isLiveViewActive.value = false
            _liveViewBitmap.value = null
            _connectionStage.value = "连接中断：$reason"
            liveViewJob?.cancel()
            activeListener?.onDisconnected(reason)
        }
    }

    private suspend fun operation(code: Int, params: IntArray = intArrayOf(), data: ByteArray? = null,
        expected: Connection? = connection,
        onDataProgress: ((Long, Long) -> Unit)? = null): PtpIpWire.Result {
        val c = expected ?: throw IOException("尚未连接相机")
        return c.mutex.withLock {
            // Once sent, drain the entire transaction even if the caller is cancelled.
            withContext(Dispatchers.IO + NonCancellable) {
                if (connection !== c) throw IOException("连接已更换")
                if (c.restricted && code !in setOf(0x1001, 0x1002, 0x1003, 0x952b, 0x935a, 0x9435)) {
                    c.trace?.note("${c.label} OP_BLOCKED restricted profile opcode=0x${code.toString(16)} hostBusy=${_hostBusy.value}")
                    // 两种受限要分开说：电脑占用时让用户去点配对是错的归因。
                    throw IOException(
                        if (_hostBusy.value) "相机在「连接至计算机」模式，控制权在电脑端，这项操作被机身拒绝"
                        else "相机当前仅公布有限命令；已暂停控制操作，等待配对流程验证"
                    )
                }
                try {
                    val sampled = code != 0x9203 || c.transaction % 30 == 0
                    val startedAt = System.nanoTime()
                    if (sampled) c.trace?.note("${c.label} OP_BEGIN opcode=0x${code.toString(16)} transaction=${c.transaction} params=${params.joinToString()} outbound=${data?.size ?: 0}")
                    val timeout = when (code) { 0x9203 -> 4000L; 0x1009 -> 90000L; else -> 12000L }
                    val deadline = System.nanoTime() + timeout * 1_000_000
                    val result = PtpIpWire.transaction(c.command.inputStream, c.command.outputStream,
                        c.transaction++, code, params, data,
                        if (code == 0x9203) 16 * 1024 * 1024 else PtpIpWire.MAX_DATA, trace = { direction, bytes ->
                            if (sampled || direction.startsWith("RX_PARTIAL") || direction == "RX_INVALID_HEADER")
                                c.record("command")(direction, bytes)
                        }, onDataProgress = { copied, total -> onDataProgress?.invoke(copied, total) }) {
                        c.command.soTimeout = PtpIpWire.remainingMillis(deadline)
                    }
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                    if (code == 0x9203 && (elapsedMs >= 250 || result.response.code != 0x2001))
                        c.trace?.note("${c.label} FRAME_RESULT transaction=${result.response.transaction} code=0x${result.response.code.toString(16)} bytes=${result.data.size} elapsedMs=$elapsedMs")
                    // 成功一次就清掉超时计数，避免相机偶尔忙一下被当成掉线。
                    ioTimeoutStreak = 0
                    result
                } catch (e: IOException) {
                    c.trace?.note("${c.label} OP_ERROR opcode=0x${code.toString(16)}\n${e.stackTraceToString()}")
                    // 单次读超时（相机正在写卡/取景）不立刻拆会话；连续超时才按真实掉线处理。
                    // 受限会话（待配对 / 电脑占用）机身响应更慢，门限放宽到 5 次，
                    // 否则电脑端一抢总线就会表现为「时不时断开」。
                    val timedOut = e is SocketTimeoutException ||
                        (e.message?.contains("timed out", true) == true) ||
                        (e.message?.contains("超时") == true)
                    val streakLimit = if (c.restricted) 5 else 3
                    if (timedOut && ++ioTimeoutStreak < streakLimit) {
                        c.trace?.note("${c.label} OP_TIMEOUT_SOFT streak=$ioTimeoutStreak opcode=0x${code.toString(16)}")
                        throw IOException("相机响应超时（会话保留，可重试）：${e.message}")
                    }
                    transportFailed(c, e.message ?: "网络异常")
                    throw e
                }
            }
        }
    }

    private suspend fun sendOperation(
        opCode: Short,
        params: IntArray = intArrayOf(),
        onDataProgress: ((Long, Long) -> Unit)? = null
    ): ByteArray? {
        val result = operation(opCode.toInt() and 0xffff, params, onDataProgress = onDataProgress)
        if (result.response.code != 0x2001) throw CameraResponse(result.response.code)
        return result.data
    }

    /** 解析 PTP 的 UINT32 数组（第一项是元素个数） */
    private fun parseU32Array(packet: ByteArray?): List<Int> {
        if (packet == null || packet.size < 8) return emptyList()
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getInt(0)
        if (count <= 0 || count > 100000) return emptyList()
        val out = ArrayList<Int>(count)
        for (i in 0 until count) {
            if (4 + i * 4 + 4 > packet.size) break
            out.add(buffer.getInt(4 + i * 4))
        }
        return out
    }

    /**
     * 事件通道监听。只有「本条连接仍然是当前会话」时才允许上报断线：
     * 主动 stopNikonWatch / 换新会话都会先自增 generation 再 close 旧 socket，
     * 旧线程读到 EOF 时 generation 已变，不能再把这次主动关闭当成相机掉线。
     */
    private suspend fun listenEvents(c: Connection, token: Long) {
        try {
            while (connection === c && !c.events.isClosed) {
                val packet = PtpIpWire.read(c.events.inputStream, trace = c.record("event"))
                when (PtpIpWire.type(packet)) {
                    8 -> {
                        val event = PtpIpWire.response(packet, 8)
                        event.parameters.firstOrNull()?.let { photoEvent(c, event.code, it) }
                    }
                    13 -> { c.send(c.events, "event", PtpIpWire.packet(14)); c.events.outputStream.flush() }
                }
            }
        } catch (e: Exception) {
            c.trace?.note("${c.label} EVENT_ERROR\n${e.stackTraceToString()}")
            val stillCurrent = synchronized(lifecycle) {
                connection === c && generation == token
            }
            if (!stillCurrent) {
                c.trace?.note("${c.label} EVENT_CLOSED_SUPERSEDED token=$token generation=$generation")
                return
            }
            // 只有事件通道断了、命令通道还活着：不判整条连接死亡，避免界面反复横跳。
            // 真掉线时下一条命令会自己失败并走 transportFailed。
            val commandAlive = runCatching { probeCommandAlive(c) }.getOrDefault(false)
            if (commandAlive) {
                c.trace?.note("${c.label} EVENT_CHANNEL_LOST command=alive reason=${e.message}")
                DoukLog.w("NikonPtp", "EVENT_CHANNEL_LOST", "reason" to (e.message ?: ""))
                activeListener?.onLog("事件通道已断开，控制通道仍在线；拍摄回传改由轮询兜底")
            } else {
                transportFailed(c, "事件通道中断：${e.message}")
            }
        }
    }

    /** 事件通道断掉后，用一次最轻的 GetDeviceInfo 判断命令通道是否还活着。 */
    private suspend fun probeCommandAlive(c: Connection): Boolean = c.mutex.withLock {
        try {
            c.command.soTimeout = 2500
            val result = PtpIpWire.transaction(c.command.inputStream, c.command.outputStream,
                c.transaction++, 0x1001, intArrayOf(), null, PtpIpWire.MAX_DATA, trace = { _, _ -> })
            result.response.code == 0x2001
        } finally {
            c.command.soTimeout = 6000
        }
    }

    private fun photoEvent(c: Connection, code: Int, handle: Int) {
        if (code != 0x4002 && code != 0xc101) return
        c.trace?.note("${c.label} PHOTO_EVENT code=0x${code.toString(16)} handle=$handle enabled=$autoTransferJpeg")
        if (!autoTransferJpeg || connection !== c || handle == 0 || !c.pendingPhotos.add(handle)) return
        if (c.photos.trySend(handle).isFailure) {
            c.pendingPhotos.remove(handle)
            activeListener?.onTransferStatus("回传队列已满，请暂缓拍摄")
        }
    }

    private suspend fun pollPhotoEvents(c: Connection) {
        while (connection === c) {
            val enabled = autoTransferJpeg
            // 关掉自动回传后没必要每 2 秒发一次 0x90C7：相机 Wi‑Fi 和机身都会被无谓唤醒。
            // 取景时降到 3 秒（画面本身在保活），纯待机且不回传时 8 秒一次即可，
            // 重新打开自动回传最多 8 秒内接上，不会漏掉刚拍的照片。
            delay(
                when {
                    !enabled -> 8_000L
                    _isLiveViewActive.value -> 3_000L
                    else -> 2_000L
                }
            )
            // 读机身图库时先别抢命令通道：尼康在枚举对象期间收到 0x90C7 会直接取消事务（0x201F）
            if (storageBusy) continue
            if (c.pollEvents && !c.restricted) {
                try {
                    val result = operation(0x90c7, expected = c)
                    if (result.response.code == 0x2001) {
                        val events = NikonPhotoEvents.parse(result.data)
                        // Drain while disabled as well, so stale events do not accumulate.
                        if (enabled) events.forEach { photoEvent(c, it.first, it.second) }
                    } else if (result.response.code != 0x2019) {
                        c.pollEvents = false
                        activeListener?.onLog("新照片轮询不受支持：0x${result.response.code.toString(16)}，保留事件通道监听")
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    c.pollEvents = false
                    activeListener?.onTransferStatus("照片事件读取失败：${e.message}")
                }
            }
        }
    }

    /**
     * 空闲保活。
     *
     * 相机/路由器会把长时间没有命令的 TCP 连接悄悄回收；旧实现只在
     * `pollEvents=true` 时每 2 秒发一次 0x90C7，不支持该命令的机型事件通道
     * 会长时间空闲，随后第一次点击就报「连接已断开」。
     *
     * 这里在 `pollEvents=false` 时按 [keepAliveIntervalMs] 发最轻的 GetDeviceInfo(0x1001)：
     * 空闲时默认 60 秒，正在取景时整段跳过（画面数据本身就在保活）。
     * 它走同一把命令锁，不会和读图库/拍摄抢事务；失败就按真实掉线处理，
     * 交给自动连接逻辑重连，而不是让 UI 停在假连接状态。
     */
    private suspend fun keepAliveLoop(c: Connection) {
        var softFailures = 0
        while (connection === c && !c.command.isClosed) {
            // 取景流每帧都在传，本身就是保活；旧实现还会每 4 秒醒来发一次空操作，
            // 对相机和 Wi‑Fi 都是纯浪费。取景期间直接睡 30 秒，只负责观察会话是否还活着。
            if (_isLiveViewActive.value) {
                delay(30_000)
                continue
            }
            // 空闲时把间隔放到 60 秒以上，否则频繁 GetDeviceInfo 会让相机 Wi‑Fi 灯一直闪、白耗电。
            val interval = keepAliveIntervalMs.coerceIn(20_000L, 180_000L)
            delay(interval)
            if (connection !== c) return
            if (_isLiveViewActive.value) continue
            // 0x90C7 轮询本身就在保活；读图库/传图期间不要插命令，避免 0x201F。
            // 受限会话（待配对 / 电脑占用）也要保活：只发白名单里的 0x1001，
            // 否则相机在没人喂数据时会休眠关 Wi‑Fi，表现为「时不时断开」。
            if (c.pollEvents || storageBusy) continue
            val failLimit = if (c.restricted) 5 else 3
            try {
                val result = operation(0x1001, expected = c)
                val code = result.response.code
                if (code == 0x2019 || code == 0xA004) {
                    // DeviceBusy / 相机忙不是掉线，等下一轮即可。
                    c.trace?.note("${c.label} KEEPALIVE_BUSY code=0x${code.toString(16)}")
                    softFailures = 0
                    continue
                }
                if (code != 0x2001) {
                    softFailures++
                    c.trace?.note("${c.label} KEEPALIVE_SOFT_FAIL code=0x${code.toString(16)} streak=$softFailures")
                    if (softFailures >= failLimit) {
                        transportFailed(c, "保活失败：0x${code.toString(16)}")
                        return
                    }
                    delay(3000)
                    continue
                }
                softFailures = 0
                ioTimeoutStreak = 0
                c.trace?.note("${c.label} KEEPALIVE_OK")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connection !== c) return
                // operation() 已经负责连续超时的计数；这里只做一轮延迟重试，不重复拆会话。
                softFailures++
                c.trace?.note("${c.label} KEEPALIVE_EXCEPTION streak=$softFailures ${e.message}")
                if (softFailures >= failLimit) {
                    transportFailed(c, "保活超时：${e.message}")
                    return
                }
                delay(3000)
            }
        }
    }

    private suspend fun receiveNewPhotos(c: Connection, listener: NikonEventListener?) {
        for (handle in c.photos) {
            if (connection !== c) return
            var resume = false
            try {
                if (!autoTransferJpeg || handle in c.deliveredPhotos) continue
                listener?.onTransferStatus("发现新照片，正在读取 JPG…")
                resume = _isLiveViewActive.value
                if (resume) stopForStorage(c)
                var info = operation(0x1008, intArrayOf(handle), expected = c)
                // ObjectAdded may arrive while the JPEG is still being written to the card.
                repeat(3) {
                    if (info.response.code == 0x2019) {
                        delay(350)
                        info = operation(0x1008, intArrayOf(handle), expected = c)
                    }
                }
                var name = "Nikon_${System.currentTimeMillis()}_${handle.toUInt()}.jpg"
                if (info.response.code == 0x2001) {
                    val metadata = NikonPhotoEvents.objectName(info.data)
                    if (!metadata.isNullOrBlank()) name = metadata
                } else if (info.response.code != 0x200f) throw CameraResponse(info.response.code)
                // Some STA profiles deny metadata: try the specific newly announced object only.
                var photo = operation(0x1009, intArrayOf(handle), expected = c)
                repeat(2) {
                    if (photo.response.code == 0x2019) {
                        delay(350)
                        photo = operation(0x1009, intArrayOf(handle), expected = c)
                    }
                }
                if (photo.response.code == 0x200f) {
                    throw IOException("相机拒绝读取新照片；请检查机身“连接到计算机→选项→自动上传”")
                }
                if (photo.response.code != 0x2001) throw CameraResponse(photo.response.code)
                if (!autoTransferJpeg || connection !== c) continue
                val isJpeg = NikonPhotoEvents.isJpeg(photo.data)
                val isRaw = name.lowercase().endsWith(".nef") ||
                    name.lowercase().endsWith(".nrw") ||
                    name.lowercase().endsWith(".dng")
                when {
                    isJpeg -> {
                        listener?.onTransferStatus("JPG 已下载，正在保存…")
                        listener?.onAutoJpegReceived(name, photo.data)
                    }
                    isRaw -> {
                        listener?.onTransferStatus("RAW 已下载，正在保存…")
                        listener?.onAutoRawReceived(name, photo.data)
                    }
                    else -> {
                        // 视频体积可能是几个 GB，自动回传会直接 OOM；留给图库手动下载。
                        listener?.onTransferStatus("新文件不是 JPG/RAW（可能是视频），已跳过自动下载")
                        continue
                    }
                }
                c.deliveredPhotos.add(handle)
                if (c.deliveredPhotos.size > 256) c.deliveredPhotos.remove(c.deliveredPhotos.first())
                // Nikon SDRAM may reuse its handle for the next exposure.
                if ((handle ushr 16) == 0xffff) c.deliveredPhotos.remove(handle)
                c.trace?.note("${c.label} AUTO_FILE_ACCEPTED handle=$handle bytes=${photo.data.size}")
                listener?.onTransferStatus("已接收：$name；保存进度见成片面板")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                c.trace?.note("${c.label} AUTO_FILE_FAILED handle=$handle ${e.message}")
                listener?.onTransferStatus("自动回传失败：${e.message}")
                listener?.onLog("自动回传失败：${e.message}")
            } finally {
                c.pendingPhotos.remove(handle)
                if (resume && connection === c && _isConnected.value) startLiveView()
            }
        }
    }

    private suspend fun ready(c: Connection) {
        repeat(40) {
            val response = operation(OP_NIKON_DEVICE_READY.toInt() and 0xffff, expected = c).response.code
            if (response == 0x2001 || response == 0x2005) return
            if (response != 0x2019) throw CameraResponse(response)
            delay(25)
        }
        throw IOException("相机持续忙碌")
    }

    fun startLiveView() {
        val c = connection ?: return
        // 用户打开监视才置 true；控制操作临时停帧不会改变这个意图。
        liveViewRequested = true
        if (c.restricted) {
            activeListener?.onLog("当前受限会话不发送取景命令，请先完成配对验证")
            return
        }
        if (!_isConnected.value) return
        scope.launch {
            liveControl.withLock {
                if (connection !== c || liveViewJob?.isActive == true) return@withLock
                _isLiveViewActive.value = true
                liveViewJob = scope.launch { runLiveView(c) }
            }
        }
    }

    private suspend fun runLiveView(c: Connection) = coroutineScope {
        val frames = Channel<ByteArray>(Channel.CONFLATED)
        val received = AtomicLong(); val decoded = AtomicLong(); val bytes = AtomicLong()
        val requestNanos = AtomicLong(); val decodeNanos = AtomicLong()
        var width = 0; var height = 0
        val decoder = launch(Dispatchers.Default) {
            for (jpeg in frames) {
                val start = System.nanoTime()
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, LiveViewQuality.decodeOptions()) ?: continue
                decodeNanos.addAndGet(System.nanoTime() - start)
                decoded.incrementAndGet()
                width = bitmap.width; height = bitmap.height
                if (connection === c && _isLiveViewActive.value) _liveViewBitmap.value = bitmap
            }
        }
        val reporter = launch {
            var lastReceived = 0L; var lastDecoded = 0L; var lastBytes = 0L
            var lastRequest = 0L; var lastDecode = 0L; var lastTime = System.nanoTime()
            while (isActive) {
                delay(1000)
                val now = System.nanoTime(); val seconds = (now - lastTime) / 1e9
                val r = received.get(); val d = decoded.get(); val b = bytes.get()
                val rq = requestNanos.get(); val dq = decodeNanos.get()
                if (connection === c) _metrics.value = LiveMetrics(
                    (r-lastReceived)/seconds, (d-lastDecoded)/seconds,
                    (b-lastBytes).toDouble()/(r-lastReceived).coerceAtLeast(1)/1024,
                    (b-lastBytes)/seconds/1_000_000,
                    (rq-lastRequest).toDouble()/(r-lastReceived).coerceAtLeast(1)/1e6,
                    (dq-lastDecode).toDouble()/(d-lastDecoded).coerceAtLeast(1)/1e6,
                    width, height, (r-d-1).coerceAtLeast(0))
                if (connection === c) c.trace?.note("${c.label} LIVE_METRICS ${_metrics.value}")
                lastReceived=r; lastDecoded=d; lastBytes=b; lastRequest=rq; lastDecode=dq; lastTime=now
            }
        }
        var level = 0
        try {
            // 先决条件按「是否接管相机」分两条路：
            // - 接管：完整 0x9435(1)/0x90C2(1)/0xD1A6=0，机身进入遥控界面；
            // - 不接管（默认）：绝不动 0x9435/0x90C2，先直接 0x9201，取不到帧再逐级升级，
            //   这样相机屏幕仍然显示实时画面，也不会弹「已连接至智能设备」。
            level = prepareLiveView(c)
            ready(c)
            var retries = 0
            var reactivations = 0
            var badImages = 0
            // Z 系优先 0x9428（GetLiveViewImageEx），老机型或返回非 OK 时回落 0x9203
            var frameCommand = 0x9428
            while (isActive && connection === c && _isLiveViewActive.value) {
                val start = System.nanoTime()
                val result = operation(frameCommand, expected = c)
                when (result.response.code) {
                    0x2001 -> {
                        retries = 0
                        val raw = result.data
                        if (received.get() == 0L) {
                            Log.i(TAG, "LIVEVIEW_FIRST_FRAME cmd=0x${"%04X".format(frameCommand)} bytes=${raw.size}")
                            DoukLog.i("LiveView", "FIRST_FRAME", "cmd" to "0x%04X".format(frameCommand), "bytes" to raw.size)
                        }
                        var begin = -1
                        for (i in 0 until raw.size-1) {
                            if (raw[i] == 0xff.toByte() && raw[i+1] == 0xd8.toByte()) { begin=i; break }
                        }
                        var end = -1
                        // Search backward: EXIF thumbnails may contain an earlier EOI marker.
                        for (i in raw.size-2 downTo (begin+2).coerceAtLeast(0)) {
                            if (raw[i] == 0xff.toByte() && raw[i+1] == 0xd9.toByte()) { end=i+2; break }
                        }
                        if (begin < 0 || end <= begin) {
                            if (++badImages >= 10) throw IOException("相机连续返回无效取景图像")
                            delay(10); continue
                        }
                        badImages=0
                        val jpeg = raw.copyOfRange(begin, end)
                        requestNanos.addAndGet(System.nanoTime()-start)
                        received.incrementAndGet(); bytes.addAndGet(jpeg.size.toLong())
                        frames.trySend(jpeg)
                        _connectionStage.value = "实时取景中"
                        // 按画质档位限帧：以前是「相机吐多快就拉多快」，Wi‑Fi 射频、
                        // JPEG 解码和屏幕三边一起满负荷跑，机身和手机都热、电量掉得飞快。
                        // 现在补足到目标帧率的时间就睡，多余的帧不再请求。
                        val budgetNs = 1_000_000_000L / LiveViewQuality.targetFps().coerceAtLeast(1)
                        val spent = System.nanoTime() - start
                        if (spent < budgetNs) delay((budgetNs - spent) / 1_000_000L)
                        yield()
                    }
                    0x2019, 0xa004 -> {
                        if (++retries > 100) throw IOException("相机持续忙碌，取景已暂停")
                        delay(20)
                    }
                    0xa00b -> {
                        if (++reactivations > 3) throw IOException("相机无法保持取景状态")
                        if (frameCommand == 0x9203) {
                            frameCommand = 0x9428
                            continue
                        }
                        val restarted = operation(0x9201, expected = c).response.code
                        if (restarted != 0x2001 && restarted != 0x2019) throw CameraResponse(restarted)
                        ready(c)
                    }
                    else -> {
                        // 0x9428 不被这台机器支持时换成 0x9203 再试，两者都失败才算错误
                        if (received.get() == 0L) {
                            Log.w(TAG, "LIVEVIEW_FRAME_FAIL cmd=0x${"%04X".format(frameCommand)} -> 0x${"%04X".format(result.response.code)} bytes=${result.data.size}")
                            DoukLog.w("LiveView", "FRAME_FAIL", "cmd" to "0x%04X".format(frameCommand),
                                "code" to "0x%04X".format(result.response.code), "bytes" to result.data.size)
                        }
                        if (frameCommand == 0x9428 && result.response.code != 0x2001) {
                            frameCommand = 0x9203
                            continue
                        }
                        throw CameraResponse(result.response.code)
                    }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (connection === c) {
                _connectionStage.value = "已连接，取景失败：${e.message}"
                activeListener?.onLog(_connectionStage.value)
            }
        } finally {
            frames.close(); decoder.cancel(); reporter.cancel()
            if (connection === c) {
                _isLiveViewActive.value = false
                runCatching { endLiveViewSession(c, level) }
            }
        }
    }

    /**
     * 取景前的准备，返回真正用上的方案：
     * 0 = 纯取景（机身屏幕照常显示，默认）、1 = 只切相机模式、2 = 切应用模式、
     * 3 = 完整接管（用户主动点「接管相机」）。
     *
     * 尼康各机型对「不接管能不能取景」并不一致：Z50II 只发 0x9201 就能出帧，
     * 部分机型要求 0x90C2，还有些必须 0x9435。这里逐级试，取到第一帧就停，
     * 保证默认路径不会切到机身遥控界面。
     */
    private suspend fun prepareLiveView(c: Connection): Int {
        if (takeoverHostControl) {
            enterHostControl(c)
            val code = startLiveViewOp(c)
            DoukLog.i("LiveView", "PREP", "plan" to 3, "code" to "0x%04X".format(code))
            if (code != 0x2001 && code != 0x2019) throw CameraResponse(code)
            return 3
        }
        if (startAndProbe(c, 0)) return 0
        // 第一级失败：只把机身切到相机控制模式（不切应用模式），有些机型靠这一步放行取景
        runCatching {
            operation(0x90c2, intArrayOf(1), expected = c)
            operation(0x1016, intArrayOf(0xd1a6), byteArrayOf(0), expected = c)
        }
        if (startAndProbe(c, 1)) return 1
        // 第二级失败：再补应用模式，此时机身允许取景但 Display 可能已经让给 App
        runCatching { operation(0x9435, intArrayOf(1), expected = c) }
        if (startAndProbe(c, 2)) return 2
        throw IOException("相机拒绝在保留机身屏幕的情况下取景，请点「接管相机」")
    }

    /** 发 0x9201 并返回响应码（0x2001 = OK，0x2019 = DeviceBusy 也算接受）。 */
    private suspend fun startLiveViewOp(c: Connection): Int =
        runCatching { operation(0x9201, expected = c).response.code }.getOrDefault(-1)

    /** 起一次取景事务并抓一帧验证；失败就把事务停掉，留给下一级方案重试。 */
    private suspend fun startAndProbe(c: Connection, plan: Int): Boolean {
        val code = startLiveViewOp(c)
        DoukLog.i("LiveView", "PREP", "plan" to plan, "code" to "0x%04X".format(code))
        if (code != 0x2001 && code != 0x2019) return false
        runCatching { ready(c) }
        var probe = runCatching { operation(0x9428, expected = c) }.getOrNull()
        if (probe == null || probe.response.code != 0x2001 || probe.data.size <= 512) {
            // 老机型没有 0x9428（GetLiveViewImageEx），回落到 0x9203 再验证一次
            val alt = runCatching { operation(0x9203, expected = c) }.getOrNull()
            if (alt != null && alt.response.code == 0x2001 && alt.data.size > 512) probe = alt
        }
        val result = probe
        val ok = result != null && result.response.code == 0x2001 && result.data.size > 512
        DoukLog.i(
            "LiveView", "PROBE", "plan" to plan,
            "code" to "0x%04X".format(result?.response?.code ?: -1),
            "bytes" to (result?.data?.size ?: 0)
        )
        if (!ok) runCatching { operation(0x9202, expected = c) }
        return ok
    }

    /** 收尾：只停取景；接管过机身才把控制权/应用模式还回去。 */
    private suspend fun endLiveViewSession(c: Connection, level: Int) {
        runCatching { operation(0x9202, expected = c) }
        if (level >= 3 || takeoverHostControl) {
            leaveHostControl(c)
            return
        }
        if (level >= 2) runCatching { operation(0x9435, intArrayOf(0), expected = c) }
        if (level >= 1) {
            runCatching { operation(0x90c2, intArrayOf(0), expected = c) }
            runCatching { operation(0x1016, intArrayOf(0xd1a6), byteArrayOf(1), expected = c) }
        }
        c.trace?.note("${c.label} LIVEVIEW_END plan=$level")
    }

    /** 进入尼康主机控制：0x9435(1) → 0x90C2(1) → 0xD1A6=0（老机型不支持时静默跳过）。 */
    private suspend fun enterHostControl(c: Connection) {
        runCatching { operation(0x9435, intArrayOf(1), expected = c) }
        runCatching { operation(0x90c2, intArrayOf(1), expected = c) }
        runCatching {
            operation(0x1016, intArrayOf(0xd1a6), byteArrayOf(0), expected = c)
        }
        c.trace?.note("${c.label} HOST_CONTROL_ENTER")
    }

    /** 退出取景并把控制权还给机身：0x9202 → 0x9435(0) → 0x90C2(0) → 0xD1A6=1。 */
    private suspend fun leaveHostControl(c: Connection) {
        runCatching { operation(0x9202, expected = c) }
        runCatching { operation(0x9435, intArrayOf(0), expected = c) }
        runCatching { operation(0x90c2, intArrayOf(0), expected = c) }
        runCatching { operation(0x1016, intArrayOf(0xd1a6), byteArrayOf(1), expected = c) }
        c.trace?.note("${c.label} HOST_CONTROL_LEAVE")
    }

    private suspend fun pauseFrames() {
        liveControl.withLock {
            _isLiveViewActive.value = false
            liveViewJob?.cancelAndJoin()
            liveViewJob = null
        }
    }

    fun stopLiveView() {
        // 先落下「用户不想再取景」的意图，避免 withFramesPaused 的 finally 又把它拉起来。
        liveViewRequested = false
        _isLiveViewActive.value = false
        // 退出监视后把最后一帧也释放掉，45MP/长时间取景不会一直攥着几十 MB。
        _liveViewBitmap.value = null
        val c = connection ?: return
        scope.launch {
            liveControl.withLock {
                _isLiveViewActive.value = false
                liveViewJob?.cancelAndJoin(); liveViewJob = null
                if (connection === c) runCatching { operation(0x9202, expected = c) }
                // 关掉监视就把控制权还给机身，否则机身上的按键/拨盘一直是「被应用接管」的状态。
                // 没接管过就不去碰 0x9435/0x90C2，机身屏幕本来就还在显示。
                if (connection === c) endLiveViewSession(c, 0)
                if (connection === c) _connectionStage.value = "已连接，取景已暂停"
            }
        }
    }

    /**
     * 取景进行中的控制操作：先把 0x9202 停掉取景事务，做完再重新开始取景。
     * 尼康在取景事务里会直接拒写属性（回 0x2019），所以监视中也要能控机。
     */
    suspend fun <T> withFramesPaused(block: suspend () -> T): T {
        val c = connection ?: return block()
        val wasActive = _isLiveViewActive.value
        if (!wasActive) return block()
        pauseFrames()
        runCatching { operation(0x9202, expected = c) }
        // 停帧后机身要回到 ready 状态才肯写属性
        runCatching { ready(c) }
        return try {
            block()
        } finally {
            // 只有用户还想要取景才恢复；不能因为「刚才在取景」就把用户已经关掉的监视重新打开。
            if (_isConnected.value && connection === c && liveViewRequested && !_isLiveViewActive.value) {
                startLiveView()
            }
        }
    }

    /** 进入尼康主机控制（应用接管相机）：0x9435(1) → 0x90C2(1) → 0xD1A6=0 */
    suspend fun takeOverHostControl(): Boolean = withContext(Dispatchers.IO) {
        val c = connection ?: return@withContext false
        val code = runCatching { operation(0x9435, intArrayOf(1), expected = c).response.code }.getOrNull()
        DoukLog.i("NikonPtp", "TAKE_OVER", "mode" to "0x%04X".format(code ?: -1))
        enterHostControl(c)
        code == 0x2001 || code == 0x2005
    }

    /** 退出尼康主机控制，把控制权还给机身（机身按键、拨盘恢复正常） */
    suspend fun releaseHostControl(): Boolean = withContext(Dispatchers.IO) {
        val c = connection ?: return@withContext false
        leaveHostControl(c)
        DoukLog.i("NikonPtp", "HOST_CONTROL_RELEASE")
        true
    }

    /** 读字符串属性（PTP 字符串：1 字节字符数 + UTF-16LE），用于版权/作者回读 */
    suspend fun readPropAsString(propCode: Int): String? = withContext(Dispatchers.IO) {
        val c = connection ?: return@withContext null
        try {
            val data = operation(0x1015, intArrayOf(propCode), expected = c).data
            if (data.isEmpty()) return@withContext null
            val count = data[0].toInt().coerceAtLeast(0)
            val usable = minOf(count * 2, data.size - 1)
            if (usable <= 0) return@withContext null
            String(data, 1, usable, Charsets.UTF_16LE).trim()
        } catch (e: Exception) {
            DoukLog.w("Prop", "READ_STRING_FAIL", "prop" to "0x%04X".format(propCode), "error" to (e.message ?: "?"))
            null
        }
    }
    private fun control(code: Int, params: IntArray = intArrayOf()) {
        val c = connection ?: return
        scope.launch {
            try {
                // 取景进行中先停取景事务再发命令，否则尼康会直接回 0x2019（DeviceBusy）
                val paused = _isLiveViewActive.value
                if (paused) {
                    pauseFrames()
                    runCatching { operation(0x9202, expected = c) }
                    runCatching { ready(c) }
                }
                val result = operation(code, params, expected = c)
                if (result.response.code != 0x2001) throw CameraResponse(result.response.code)
                if (paused && _isConnected.value && connection === c) startLiveView()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                activeListener?.onLog("操作失败：${e.message}")
                if (_isConnected.value && connection === c) startLiveView()
            }
        }
    }
    /** 相机存储卡容量（PTP GetStorageIDs + GetStorageInfo 真实返回）。 */
    data class StorageCapacity(val freeBytes: Long, val totalBytes: Long, val freeImages: Int)

    suspend fun readStorageCapacity(): StorageCapacity? = withContext(Dispatchers.IO) {
        val c = connection ?: return@withContext null
        val parsed: StorageCapacity? = runCatching {
            val ids = operation(0x1004, expected = c).data
            if (ids.size < 8) return@runCatching null
            val idBuffer = ByteBuffer.wrap(ids).order(ByteOrder.LITTLE_ENDIAN)
            val count = idBuffer.int
            if (count <= 0) return@runCatching null
            val firstId = idBuffer.int
            val info = operation(0x1005, intArrayOf(firstId), expected = c).data
            if (info.size < 22) return@runCatching null
            val ib = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
            val total = ib.getLong(6)
            // PTP 1.1 在 e 偏移 14 放 FreeSpaceInBytes(UINT64)，PTP 1.0 该位置是 FreeSpaceInImages(UINT32)
            val free = ib.getLong(14)
            val freeImages = if (info.size >= 26) ib.getInt(22) else free.toInt()
            when {
                free > 0 -> StorageCapacity(free, if (total > 0) total else free, freeImages)
                freeImages > 0 -> StorageCapacity(0L, total, freeImages)
                else -> null
            }
        }.getOrNull()
        parsed
    }

    /** 读取机身照片的大缩略图（尼康厂商操作 0x90C4，约 1600×1200，比 0x100A 清晰）。 */
    suspend fun readBigThumbnail(handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val r = operation(0x90C4, intArrayOf(handle))
            if (r.response.code == 0x2001 && r.data.isNotEmpty()) r.data else null
        }.getOrNull()
    }

    /** 读取机身照片缩略图（GetThumb 0x100A，Wi‑Fi PTP/IP 通道）。 */
    suspend fun readThumbnail(handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val r = operation(0x100A, intArrayOf(handle))
            if (r.response.code == 0x2001) r.data else null
        }.getOrNull()
    }

    fun triggerCapture() = control(0x9207, intArrayOf(-1, 0))    fun triggerAf() = control(0x90c1)

    /**
     * 点哪对哪：0x9205 ChangeAfArea 的两个参数是**传感器像素坐标**（不是取景 JPEG 的
     * 640×480，也不是 0..1000），所以按机身型号的传感器分辨率换算，再发 0x90C1 驱动一次对焦。
     * 参考实测（PiCamControl / libgphoto2）：changeafarea 只写不回读，坐标必须是 sensor 像素。
     */
    fun setFocusPoint(normX: Float, normY: Float) {
        val c = connection ?: return
        val nx = normX.coerceIn(0f, 1f)
        val ny = normY.coerceIn(0f, 1f)
        val x = (nx * sensorWidthPx).toInt()
        val y = (ny * sensorHeightPx).toInt()
        scope.launch {
            try {
                var code = -1
                repeat(6) { attempt ->
                    val r = runCatching { operation(0x9205, intArrayOf(x, y), expected = c) }.getOrNull()
                    code = r?.response?.code ?: -1
                    // 取景事务抢着命令通道时相机回 DeviceBusy，等一拍再发，不算失败
                    if (code == 0x2019 || code == 0xa004) { delay(60); return@repeat }
                    return@repeat
                }
                DoukLog.i("Focus", "CHANGE_AF_AREA", "x" to x, "y" to y,
                    "sensor" to "${sensorWidthPx}x${sensorHeightPx}", "code" to "0x%04X".format(code))
                if (code == 0x2001) {
                    // 区域挪好后再驱动一次 AF，否则只挪框不对焦
                    val af = runCatching { operation(0x90c1, expected = c) }.getOrNull()
                    DoukLog.i("Focus", "AF_DRIVE", "code" to "0x%04X".format(af?.response?.code ?: -1))
                    if (af != null && af.response.code != 0x2001) {
                        activeListener?.onLog("对焦驱动失败：0x${"%04X".format(af.response.code)}")
                    }
                } else {
                    activeListener?.onLog("对焦点下发失败：0x${"%04X".format(code)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activeListener?.onLog("对焦点下发失败：${e.message}")
            }
        }
    }
    fun toggleMovieRecord(startRecord: Boolean) = control(if (startRecord) 0x920a else 0x920b)

    /**
     * 录像开/停，同步等机身真实响应再返回成功与否。
     * 以前是即发即忘，指令被机身拒绝时 UI 状态照样翻转，快门钮会卡在录制态的方块。
     */
    suspend fun toggleMovieRecordSync(startRecord: Boolean): Boolean = withContext(Dispatchers.IO) {
        val c = connection ?: return@withContext false
        val code = if (startRecord) 0x920a else 0x920b
        try {
            // 尼康的录像开关要在取景开着时发；无参和带参两种写法都试，机身回「忙」再停取景重试。
            val attempts = if (startRecord) listOf(intArrayOf(), intArrayOf(1))
                           else listOf(intArrayOf(), intArrayOf(0))
            for ((i, p) in attempts.withIndex()) {
                val r = runCatching { operation(code, p, expected = c) }.getOrNull()
                val rc = r?.response?.code
                DoukLog.d("Rec", if (i == 0) "MOVIE_REC_TRY" else "MOVIE_REC_TRY2",
                    "start" to startRecord.toString(), "params" to p.size.toString(), "code" to "0x%04X".format(rc ?: 0))
                if (rc == 0x2001) { lastResponseCode = 0x2001; return@withContext true }
                lastResponseCode = rc ?: 0
            }
            val firstCode = lastResponseCode
            val paused = _isLiveViewActive.value
            if (paused) {
                pauseFrames()
                runCatching { operation(0x9202, expected = c) }
                runCatching { ready(c) }
                val r = runCatching { operation(code, expected = c) }.getOrNull()
                val rc = r?.response?.code
                DoukLog.d("Rec", "MOVIE_REC_NOLV", "start" to startRecord.toString(), "code" to "0x%04X".format(rc ?: 0))
                lastResponseCode = rc ?: 0
                if (_isConnected.value && connection === c) runCatching { startLiveView() }
                if (rc == 0x2001) return@withContext true
            }
            DoukLog.w("Rec", "MOVIE_REC_REJECT", "start" to startRecord.toString(), "code" to "0x%04X".format(firstCode))
            false
        } catch (e: Exception) {
            DoukLog.w("Rec", "MOVIE_REC_FAIL", "start" to startRecord.toString(), "error" to (e.message ?: "?"))
            if (_isConnected.value && connection === c) runCatching { startLiveView() }
            false
        }
    }

    fun rememberOriginalCompression(value: Long, version: Long) = synchronized(lifecycle) {
        sessionFor(version)
        val key = "compression_$cameraIdentity"
        if (preferences?.contains(key) != true && preferences?.edit()?.putLong(key, value)?.commit() != true)
            throw IOException("无法保存原始压缩值，已取消修改")
    }
    fun originalCompression(): Long? {
        val key = "compression_$cameraIdentity"
        return if (preferences?.contains(key) == true) preferences?.getLong(key, 0) else null
    }
    fun clearOriginalCompression() { preferences?.edit()?.remove("compression_$cameraIdentity")?.apply() }

    private fun sessionFor(version: Long): Connection = synchronized(lifecycle) {
        if (version != generation) throw IOException("连接已更换，取消旧会话的压缩设置")
        connection ?: throw IOException("相机已断开")
    }
    private suspend fun stopForStorage(c: Connection) {
        pauseFrames()
        val stopped = operation(0x9202, expected = c).response.code
        c.trace?.note("${c.label} STORAGE_END_LIVE_VIEW response=0x${stopped.toString(16)}")
        DoukLog.i("Storage", "END_LIVE_VIEW", "code" to "0x%04X".format(stopped))
        // 0x201F=事务被取消、0xA00B=本来就没在取景：都说明相机当前没有取景会话，直接往下走
        if (stopped != 0x2001 && stopped != 0xa00b && stopped != 0x201f) throw CameraResponse(stopped)
        try {
            ready(c)
        } catch (e: CameraResponse) {
            DoukLog.w("Storage", "READY_AFTER_STOP_FAIL", "code" to "0x%04X".format(e.code))
            if (e.code != 0x201f) throw e
        }
        _connectionStage.value = "正在读取 SD 卡，取景暂时停止"
    }
    // Protocol sequence corroborated by open-nikonz/zf-capture-one-helper pairing fixtures.
    // Z30 compatibility remains experimental; user must compare the camera's displayed code.
    suspend fun beginPairing(version: Long): String {
        val c = sessionFor(version)
        if (!c.restricted) throw IOException("当前会话不是已识别的待配对能力状态")
        val result = operation(0x952b, expected = c)
        if (result.response.code != 0x2001) throw CameraResponse(result.response.code)
        return PtpIpWire.pairingCode(result.data)
    }
    suspend fun confirmPairing(version: Long) {
        val c = sessionFor(version)
        if (!c.restricted) throw IOException("配对会话状态已变化")
        val result = operation(0x935a, intArrayOf(0x2001), expected = c)
        if (result.response.code != 0x2001) throw CameraResponse(result.response.code)
        c.trace?.note("PAIR_SUBMIT_OK; waiting for user to finish wizard on camera; reconnect required")
    }

    // 用当前这条待配对会话直接请求 / 提交配对，不需要外部再传会话版本号
    suspend fun requestPairingNow(): String {
        val c = connection ?: throw IOException("会话已断开，请重新连接相机")
        if (!c.restricted) throw IOException("当前会话已是完整权限，不需要配对")
        val result = operation(0x952b, expected = c)
        if (result.response.code != 0x2001) throw CameraResponse(result.response.code)
        val code = PtpIpWire.pairingCode(result.data)
        DoukLog.i("NikonPtp", "PAIR_CODE", "code" to code)
        return code
    }

    suspend fun submitPairingNow(): Int {
        val c = connection ?: throw IOException("会话已断开，请重新连接相机")
        val result = operation(0x935a, intArrayOf(0x2001), expected = c)
        DoukLog.i("NikonPtp", "PAIR_SUBMIT", "code" to "0x%04X".format(result.response.code))
        return result.response.code
    }

    fun clearPairingRequired() { _pairingRequired.value = false }
    suspend fun readCompression(version: Long): Long {
        val r = operation(0x1015, intArrayOf(0xd1bc), expected = sessionFor(version))
        if (r.response.code != 0x2001) throw CameraResponse(r.response.code)
        if (r.data.size != 1) throw IOException("压缩属性不是预期的单字节值")
        return r.data[0].toLong() and 0xff
    }
    suspend fun writeCompression(version: Long, value: Long): Boolean {
        require(value in 0..255)
        val r = operation(0x1016, intArrayOf(0xd1bc), byteArrayOf(value.toByte()), sessionFor(version))
        lastResponseCode = r.response.code
        return r.response.code == 0x2001
    }

    // ========== 🎛️ 相机属性 读取/设置/描述 (PTP 标准操作码) ==========
    // PTP 属性码常量表 (定义为顶层常量，避免 companion object 冲突)
                private val PROP_SHUTTER_SPEED: Short = 0x500D.toShort()
    private val PROP_FNUMBER: Short = 0x5007.toShort()
    private val PROP_ISO: Short = 0x500F.toShort()
    private val PROP_EXPOSURE_PROGRAM: Short = 0x500E.toShort()
    private val PROP_WHITE_BALANCE: Short = 0x5005.toShort()
    // 尼康专用属性码
                private val PROP_NIKON_AF_MODE: Short = 0xD161.toShort()  // AF-S / AF-C / MF

    /**
     * 📖 从相机读取指定属性的当前值 (GetDevicePropValue, OpCode 0x1015)
     * @return 属性值的原始字节数组, 无则 null
     */
    suspend fun getDevicePropValue(propCode: Short): ByteArray? = withContext(Dispatchers.IO) {
        if (!_isConnected.value) return@withContext null
        try {
            val resp = sendOperation(0x1015.toShort(), intArrayOf(propCode.toInt() and 0xFFFF))
            if (resp != null && resp.isNotEmpty()) {
                return@withContext resp
            }
            return@withContext null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "读取属性失败: 0x${"%04X".format(propCode.toInt() and 0xFFFF)}", e)
            return@withContext null
        }
    }

    /**
     * ⚙️ 向相机设置指定属性值 (SetDevicePropValue, OpCode 0x1016)
     * @param propCode 属性码 (如 0x500D=快门 0x5007=光圈)
     * @param valueBytes 要设置的值的字节表示
     * @return true=设置成功
     */
    suspend fun setDevicePropValue(propCode: Short, valueBytes: ByteArray): Boolean {
        if (!_isConnected.value) return false
        val result = operation(0x1016, intArrayOf(propCode.toInt() and 0xffff), valueBytes)
        lastResponseCode = result.response.code
        return result.response.code == 0x2001
    }

    /**
     * 便捷方法: 设置 UINT32 类型的属性值     */
    suspend fun setDevicePropValueUint32(propCode: Short, value: Int): Boolean {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        return setDevicePropValue(propCode, buf.array())
    }

    /**
     * 便捷方法: 设置 UINT16 类型的属性值     */
    suspend fun setDevicePropValueUint16(propCode: Short, value: Short): Boolean {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(value)
        return setDevicePropValue(propCode, buf.array())
    }

    /**
     * 便捷方法: 设置 UINT8 类型的属性值     */
    suspend fun setDevicePropValueUint8(propCode: Short, value: Byte): Boolean {
        return setDevicePropValue(propCode, byteArrayOf(value))
    }

    /**
     * 📋 获取属性描述 (GetDevicePropDesc, OpCode 0x1014)
     * 返回包含当前值、默认值、可选范围的原始字节数组
     */
    suspend fun getDevicePropDesc(propCode: Short): ByteArray? = withContext(Dispatchers.IO) {
        if (!_isConnected.value) return@withContext null
        try {
            val resp = sendOperation(0x1014.toShort(), intArrayOf(propCode.toInt() and 0xFFFF))
            if (resp != null && resp.isNotEmpty()) {
                return@withContext resp
            }
            return@withContext null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.e(TAG, "获取属性描述失败: 0x${"%04X".format(propCode.toInt() and 0xFFFF)}", e)
            return@withContext null
        }
    }

    /** PTP 数据类型码与字节宽度 */
    private fun ptpTypeSize(dataType: Int): Int = when (dataType) {
        0x0001, 0x0002, 0xFFFF -> 1
        0x0003, 0x0004 -> 2
        0x0005, 0x0006 -> 4
        0x0007, 0x0008 -> 8
        else -> 4
    }

    private fun readLeRaw(bytes: ByteArray, offset: Int, size: Int): Long {
        if (offset < 0 || offset + size > bytes.size) return 0L
        var v = 0L
        for (i in 0 until size) {
            v = v or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    /**
     * 拉取并解析设备属性描述（含枚举可选值与当前值）。     * DevicePropertyDesc dataset 布局（小端、字节紧凑）：     * u16 code / u16 dataType / u8 getSet / factoryValue(size) / currentValue(size) / u8 formFlag
     * formFlag=2(枚举): u16 count + N×value(size)；formFlag=1(范围): min/max/step 各 size
     */
    suspend fun fetchPropDesc(propCode: Short): PropDesc? {
        val hex = "%04X".format(propCode.toInt() and 0xffff)
        val bytes = try {
            getDevicePropDesc(propCode)
        } catch (e: Exception) {
            Log.w(TAG, "PROP_DESC_FAIL 0x$hex 响应=0x${"%04X".format(lastResponseCode)} msg=${e.message}")
            DoukLog.w("Prop", "DESC_FAIL", "prop" to "0x$hex", "code" to "0x%04X".format(lastResponseCode), "msg" to e.message)
            return null
        }
        if (bytes == null) {
            Log.w(TAG, "PROP_DESC_NULL 0x$hex 响应=0x${"%04X".format(lastResponseCode)}")
            DoukLog.w("Prop", "DESC_NULL", "prop" to "0x$hex", "code" to "0x%04X".format(lastResponseCode))
            return null
        }
        return try {
            val d = PtpIpWire.numericProperty(bytes)
            if (d.code != (propCode.toInt() and 0xffff)) {
                Log.w(TAG, "PROP_DESC_MISMATCH 0x$hex 实际=0x${"%04X".format(d.code)} len=${bytes.size}")
                DoukLog.w("Prop", "DESC_MISMATCH", "prop" to "0x$hex", "actual" to "0x%04X".format(d.code), "len" to bytes.size)
                return null
            }
            Log.i(TAG, "PROP_DESC OK 0x$hex type=0x${d.type.toString(16)} cur=${d.current} values=${d.values.size} writable=${d.writable}")
            DoukLog.d("Prop", "DESC_OK", "prop" to "0x$hex", "cur" to d.current, "values" to d.values.size, "writable" to d.writable)
            PropDesc(d.type, d.writable, d.current, d.values, d.min, d.max, d.step)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "PROP_DESC_PARSE_FAIL 0x$hex len=${bytes.size} msg=${e.message}")
            DoukLog.w("Prop", "DESC_PARSE_FAIL", "prop" to "0x$hex", "len" to bytes.size,
                "msg" to e.message, "bytes" to bytes.joinToString("") { "%02X".format(it) })
            activeListener?.onLog("属性描述无效: ${e.message}")
            null
        }
    }

    /**
     * 0x5003 ImageSize 在 Z 系上是 PTP 字符串（如 "5568x3712"），不是数值数组。
     * 写入后按字符串回读校验，避免尼康回 OK 但机身没变。
     */
    suspend fun setImageSize(size: String): Boolean = withContext(Dispatchers.IO) {
        if (!_isConnected.value) return@withContext false
        lastWriteVerified = false
        val count = size.length
        val bytes = ByteArray(1 + count * 2 + 2)
        bytes[0] = count.toByte()
        for (i in 0 until count) {
            val c = size[i].code
            bytes[1 + i * 2] = (c and 0xFF).toByte()
            bytes[1 + i * 2 + 1] = ((c shr 8) and 0xFF).toByte()
        }
        withFramesPaused {
            var ok = false
            var verified = false
            for (attempt in 0 until 3) {
                try {
                    sendOperation(OP_NIKON_DEVICE_READY)
                    delay(if (attempt == 0) 60 else 120)
                    ok = setDevicePropValue(0x5003.toShort(), bytes)
                    if (ok) {
                        delay(if (attempt == 0) 140 else 220)
                        val readBack = runCatching { readPropAsString(0x5003) }.getOrNull()
                        verified = readBack == size
                        if (verified) break
                        DoukLog.w("Prop", "SET_IMAGE_SIZE_MISMATCH", "want" to size, "got" to (readBack ?: "null"))
                    } else if (lastResponseCode != 0x2019) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    break
                }
            }
            lastWriteVerified = verified
            DoukLog.i(
                "Prop", "SET_IMAGE_SIZE",
                "size" to size, "ok" to ok, "verified" to verified,
                "code" to "0x%04X".format(lastResponseCode)
            )
            ok && verified
        }
    }

    /**
     * 按相机枚举/范围的原生值下发属性（设置前先发尼康 DeviceReady 0x90C8）。
     * 写完后立刻用 GetDevicePropValue 回读：尼康在智能设备/取景受限状态下会先回 0x2001 OK、
     * 但实际不改机身值；只凭返回码判断会把「没生效」误报成「已写入」。这里只有回读一致才算成功。
     * @param dataType 由 fetchPropDesc 返回的 PTP 数据类型码
     */
    suspend fun setPropRawValue(propCode: Short, dataType: Int, rawValue: Long): Boolean = withContext(Dispatchers.IO) {
        if (!_isConnected.value) return@withContext false
        lastWriteVerified = false
        // 取景事务占着机身时写属性会被回 0x2019：先停取景，写完自动恢复监视
        withFramesPaused {
            val size = ptpTypeSize(dataType)
            val bytes = ByteArray(size)
            var v = rawValue
            for (i in 0 until size) { bytes[i] = (v and 0xFF).toByte(); v = v shr 8 }
            var ok = false
            var verified = false
            for (attempt in 0 until 3) {
                try {
                    sendOperation(OP_NIKON_DEVICE_READY)
                    delay(if (attempt == 0) 60 else 120)
                    ok = setDevicePropValue(propCode, bytes)
                    if (ok) {
                        // 关键：等机身把新值落到属性表后再回读，避免读到写前的缓存值
                        delay(if (attempt == 0) 140 else 220)
                        val readBack = runCatching { getDevicePropValue(propCode) }.getOrNull()
                        verified = readBack != null && readBack.size >= size &&
                            readBack.copyOf(size).contentEquals(bytes)
                        if (verified) break
                        DoukLog.w(
                            "Prop", "SET_READBACK_MISMATCH",
                            "prop" to "0x%04X".format(propCode.toInt() and 0xFFFF),
                            "want" to bytes.joinToString("") { "%02X".format(it) },
                            "got" to (readBack?.copyOf(size)?.joinToString("") { "%02X".format(it) } ?: "null"),
                            "attempt" to attempt
                        )
                    } else if (lastResponseCode != 0x2019) {
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "下发属性原始值失败: 0x${"%04X".format(propCode.toInt() and 0xFFFF)}", e)
                    break
                }
            }
            lastWriteVerified = verified
            DoukLog.i("Prop", "SET_RAW", "prop" to "0x%04X".format(propCode.toInt() and 0xFFFF),
                "raw" to rawValue, "ok" to ok, "verified" to verified,
                "code" to "0x%04X".format(lastResponseCode))
            ok && verified
        }
    }

    /** 读取属性当前原始值（按给定数据类型宽度解析） */
    suspend fun getPropRawValue(propCode: Short, dataType: Int): Long? {
        val bytes = getDevicePropValue(propCode) ?: return null
        val size = ptpTypeSize(dataType).coerceAtMost(bytes.size)
        if (size <= 0) return null
        return readLeRaw(bytes, 0, size)
    }

    /** 读取曝光模式码 (0x500E): 1=手动M, 2=程序自动P, 3=光圈优先A, 4=快门优先S；null=读取失败 */
    suspend fun getExposureProgram(): Int? {
        val bytes = getDevicePropValue(PROP_EXPOSURE_PROGRAM) ?: return null
        if (bytes.isEmpty()) return null
        return (bytes[0].toInt() and 0xFF)
    }

    /**
     * 🔄 连接成功后同步相机当前参数值 (快门/光圈/ISO)
     * @return Map<propCode, rawValue> 
     */
    suspend fun syncAllDeviceProps(): Map<Short, Int> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Short, Int>()
        val propsToSync = listOf(PROP_SHUTTER_SPEED, PROP_FNUMBER, PROP_ISO)
        for (prop in propsToSync) {
            try {
                val bytes = getDevicePropValue(prop)
                if (bytes != null && bytes.size >= 4) {
                    val value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
                    result[prop] = value
                    activeListener?.onLog("📖 读取灞炴€?0x${"%04X".format(prop.toInt() and 0xFFFF)} = $value")
                } else if (bytes != null && bytes.size >= 2) {
                    val value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt() and 0xFFFF
                    result[prop] = value
                    activeListener?.onLog("📖 读取灞炴€?0x${"%04X".format(prop.toInt() and 0xFFFF)} = $value")
                }
            } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
                Log.w(TAG, "同步属性: 0x${"%04X".format(prop.toInt() and 0xFFFF)} 失败: ${e.message}")
            }
        }
        return@withContext result
    }

    /**
     * 📸 [真实功能] 获取机身 SD 卡内的真实照片列表 (防爆内存分页拉取设计)
     */
    @Volatile var storageStatus: String = ""
        private set
    /** 本次窗口之后是否还有更早的照片：图库下拉续读靠它判断 */
    @Volatile var storageHasMore: Boolean = false
        private set
    /** 上一次枚举的完整结果：进图库复用，避免每次都把 400+ 个 0x1008 重读一遍（省电、省等待） */
    @Volatile private var storageCache: List<com.lutcamera.lutcamerapro.model.CameraStorageFile>? = null
    @Volatile private var storageCacheVersion: Long = -1L
    @Volatile private var storageCacheAt: Long = 0L
    fun invalidateStorageCache() { storageCache = null }
    suspend fun fetchCameraPhotos(startIdx: Int = 0, count: Int = 50, prepareTransfer: Boolean = false,
        force: Boolean = false, quiet: Boolean = false): List<com.lutcamera.lutcamerapro.model.CameraStorageFile> = withContext(Dispatchers.IO) {
        storageStatus = "正在读取相机文件列表"
        val resultList = mutableListOf<com.lutcamera.lutcamerapro.model.CameraStorageFile>()
        val storageConnection = connection ?: return@withContext resultList
        val socket = storageConnection.command
        if (socket.isClosed || !_isConnected.value) return@withContext resultList

        val cached = storageCache
        if (!force && cached != null &&
            storageCacheVersion == sessionVersion && storageCacheAt > 0L &&
            SystemClock.elapsedRealtime() - storageCacheAt < 15 * 60_000L) {
            storageStatus = "已复用本次会话的机身列表（${cached.size} 张）"
            storageHasMore = false
            DoukLog.i("Storage", "CACHE_HIT", "photos" to cached.size,
                "ageMs" to SystemClock.elapsedRealtime() - storageCacheAt)
            return@withContext cached.subList(minOf(startIdx, cached.size), minOf(startIdx + count, cached.size))
        }

        val wasLiveView = _isLiveViewActive.value
        DoukLog.i("Storage", "ENUM_BEGIN", "liveView" to wasLiveView, "prepareTransfer" to prepareTransfer,
            "start" to startIdx, "count" to count)
        try {
            storageBusy = true
            // Drain the current transaction before transferring files.
            if (wasLiveView) {
                stopForStorage(storageConnection)
            }
            if (prepareTransfer && _cameraLabel.value.startsWith("Z 30")) {
                activeListener?.onLog("Z 30 上无参数 AdvancedTransfer 已实测返回 0x2006；直接重读已标记照片")
            } else if (prepareTransfer) {
                storageStatus = "正在启动尼康传输流程"
                val transfer = operation(0x9010, expected = storageConnection)
                DoukLog.i("Storage", "ADVANCED_TRANSFER", "code" to "0x%04X".format(transfer.response.code))
                if (transfer.response.code == 0x2001) activeListener?.onLog("尼康 AdvancedTransfer 已返回成功，重新尝试读取文件信息")
                else if (transfer.response.code == 0x2006) activeListener?.onLog("本机身拒绝无参数 AdvancedTransfer（0x2006），继续检查文件列表")
                else if (transfer.response.code == 0x201f || transfer.response.code == 0x2005) activeListener?.onLog("AdvancedTransfer 不支持/被取消，继续读文件列表")
                else throw CameraResponse(transfer.response.code)
            }

            // 1/2. 枚举机身对象句柄：先取存储 ID，再按存储发 GetObjectHandles。
            //      直接拿 0xFFFFFFFF 当 storageID，尼康这边会判非法句柄，回 0xA002（私有码）或 0x201F（事务取消）。
            val storageIds = try {
                // 0x1004 的返回是「数量 + 各存储 ID」，首项是数量，必须丢掉，
                // 否则会把数量当成存储 ID 发 0x1007，机身边直接回 0xA002 / 0x201F
                // 事务被取消（0x201F）常常是上一条命令的数据没读干净，重发一次即可清掉
                var ids: List<Int> = emptyList()
                for (attempt in 0 until 3) {
                    try {
                        val raw = sendOperation(0x1004.toShort(), intArrayOf())
                        // parseU32Array 已经按「首项是元素个数」把个数剥掉了，这里不能再 drop(1)：
                        // 只有一个卡槽机身的返回就是「1 + 1 个 ID」，再 drop 会把唯一 ID 丢掉，
                        // 后续只能拿 -1 当 storageID，0x1007 必然回空句柄列表。
                        ids = parseU32Array(raw)
                        DoukLog.i("Storage", "STORAGE_IDS_RAW", "attempt" to attempt + 1,
                            "bytes" to (raw?.size ?: 0),
                            "hex" to (raw?.take(16)?.joinToString(" ") { "%02x".format(it) } ?: "-"),
                            "ids" to ids.joinToString(","))
                        break
                    } catch (e: CameraResponse) {
                        DoukLog.w("Storage", "STORAGE_IDS_FAIL", "attempt" to attempt + 1,
                            "code" to "0x%04X".format(e.code))
                        delay(400)
                    }
                }
                ids
            } catch (e: Exception) {
                DoukLog.w("Storage", "GET_STORAGE_IDS_FAIL", "error" to (e.message ?: e::class.java.simpleName))
                emptyList()
            }
            DoukLog.i("Storage", "STORAGE_IDS", "ids" to storageIds.joinToString(","))
            // 尼康无线会话里的 0x1007 一般只回「存储上的一个关联句柄」，
            // 真正的照片在它下面的文件夹（DCIM/100NCZ…）里，必须按关联句柄逐层展开。
            val storageList = storageIds.ifEmpty { listOf(-1) }
            var denied = 0
            var hasMore = false

            suspend fun listHandles(sid: Int, association: Int): List<Int> = try {
                val packet = sendOperation(0x1007.toShort(), intArrayOf(sid, 0, association))
                val handles = parseU32Array(packet)
                DoukLog.i("Storage", "OBJECT_HANDLES", "sid" to sid, "allHandle" to association,
                    "count" to handles.size, "bytes" to (packet?.size ?: 0))
                handles
            } catch (e: Exception) {
                val code = (e as? CameraResponse)?.code
                DoukLog.w("Storage", "OBJECT_HANDLES_FAIL", "sid" to sid, "allHandle" to association,
                    "code" to (code?.let { "0x%04X".format(it) } ?: e.message ?: "?"))
                if (code == 0x2019 || code == 0xA002 || code == 0x201F) {
                    runCatching { stopForStorage(storageConnection) }
                    delay(350)
                }
                emptyList()
            }

            // 先把机身里所有目录都走一遍（DCIM 下面常有 100NZ502 / 101NZ502 / … 好几个），
            // 以前只按「最新一屏」停，永远只读到最新的那个文件夹。
            val rootHandles = ArrayList<Int>()
            for (sid in storageList) {
                for (parent in intArrayOf(-1, 0)) {
                    val top = listHandles(sid, parent)
                    if (top.isNotEmpty()) {
                        top.forEach { rootHandles.add(it) }
                        break
                    }
                }
            }

            val dirs = ArrayList<Pair<Int, String>>()
            val rootFiles = ArrayList<Int>()
            val visited = HashSet<Int>()

            suspend fun infoOf(handle: Int): ByteArray? = try {
                sendOperation(0x1008.toShort(), intArrayOf(handle))
            } catch (e: CameraResponse) {
                if (e.code == 0x200f) denied++
                DoukLog.w("Storage", "OBJECT_INFO_FAIL", "handle" to handle,
                    "code" to "0x%04X".format(e.code))
                null
            }

            suspend fun walk(handles: List<Int>, fallbackName: String?, depth: Int) {
                for (h in handles) {
                    if (!visited.add(h)) continue
                    val packet = infoOf(h) ?: continue
                    if (packet.size < 53) continue
                    val fmt = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getShort(4)
                    if (fmt == 0x3001.toShort()) {
                        val dirName = runCatching {
                            val strLen = packet[52].toInt() and 0xFF
                            if (strLen > 0 && 53 + strLen * 2 <= packet.size)
                                String(packet, 53, (strLen - 1) * 2, Charsets.UTF_16LE).trim()
                            else ""
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackName ?: "机身存储"
                        DoukLog.i("Storage", "FOLDER_OPEN", "handle" to h, "depth" to depth, "dir" to dirName)
                        dirs.add(h to dirName)
                        if (depth < 6) {
                            val children = ArrayList<Int>()
                            for (sid in storageList) children.addAll(listHandles(sid, h))
                            walk(children, dirName, depth + 1)
                        }
                    } else {
                        rootFiles.add(h)
                    }
                }
            }
            walk(rootHandles, null, 0)

            // 每个目录再单独取一次子句柄（这一步很便宜，0x1007 只回句柄列表），
            // 这样不同文件夹里的照片都会进同一个候选池。
            val candidates = ArrayList<Pair<Int, String>>()
            dirs.forEach { (handle, name) ->
                for (sid in storageList) {
                    listHandles(sid, handle).forEach { child -> candidates.add(child to name) }
                }
            }
            rootFiles.forEach { candidates.add(it to "机身存储") }

            val folderOf = HashMap<Int, String>()
            candidates.forEach { (h, name) -> if (folderOf[h] == null) folderOf[h] = name }
            // 机身的 ObjectHandle 是递增分配的，倒序就是最新在前；跨文件夹统一按句柄排序
            val ordered = candidates.map { it.first }.distinct().sortedDescending()
            val total = ordered.size
            val validStart = minOf(startIdx, total)
            val endIdx = minOf(validStart + count, total)
            hasMore = endIdx < total

            ordered.subList(validStart, endIdx).forEach { handle ->
                val infoPacket = infoOf(handle) ?: return@forEach
                if (infoPacket.size < 53) return@forEach
                val infoBuf = ByteBuffer.wrap(infoPacket).order(ByteOrder.LITTLE_ENDIAN)
                val compressedSize = infoBuf.getInt(8)
                val format = infoBuf.getShort(4)
                if (format == 0x3001.toShort()) return@forEach
                val protection = infoBuf.getShort(6).toInt() and 0xFFFF
                val isRaw = format == 0xB101.toShort() || format == 0xB103.toShort()
                var fileName = "DSC_${handle}.JPG"
                var captureMillis: Long? = null
                runCatching {
                    val strLen = infoPacket[52].toInt() and 0xFF
                    if (strLen > 0 && 53 + strLen * 2 <= infoPacket.size) {
                        fileName = String(infoPacket, 53, (strLen - 1) * 2, Charsets.UTF_16LE)
                        captureMillis = parsePtpStringTime(infoPacket, 53 + strLen * 2)
                    }
                }.onFailure { if (isRaw) fileName = "DSC_${handle}.RAW" }
                if (captureMillis == null) {
                    DoukLog.w("Storage", "CAPTURE_DATE_MISS", "handle" to handle, "file" to fileName,
                        "len" to infoPacket.size)
                }
                resultList.add(
                    com.lutcamera.lutcamerapro.model.CameraStorageFile(
                        handle = handle,
                        fileName = fileName,
                        sizeBytes = compressedSize.toLong().coerceAtLeast(1024L),
                        dateModified = captureMillis ?: System.currentTimeMillis(),
                        isRaw = isRaw,
                        folder = folderOf[handle],
                        isLocked = protection != 0
                    )
                )
            }
            storageHasMore = hasMore

            DoukLog.i("Storage", "ENUM_DONE", "folders" to dirs.size,
                "candidates" to total, "photos" to resultList.size,
                "denied" to denied, "hasMore" to hasMore)
            if (resultList.isNotEmpty()) {
                activeListener?.onLog(
                    "📂 已读到机身 ${dirs.size} 个目录 / 最新 ${total} 个文件，返回区间 [$validStart - $endIdx]" +
                        if (hasMore) "，更早的照片下拉再读" else ""
                )
            }

        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            storageStatus = "读取失败：${e.message}"
            activeListener?.onLog("读取照片列表失败: ${e.message}")
        } finally {
            storageBusy = false
            if (wasLiveView && _isConnected.value && connection === storageConnection) startLiveView()
        }
        if (resultList.isNotEmpty()) {
            storageStatus = "已读取 ${resultList.size} 个文件" + if (storageHasMore) "，下拉继续加载更早的照片" else ""
        } else if (storageStatus == "正在读取相机文件列表" || storageStatus == "正在启动尼康传输流程") {
            storageStatus = "相机未返回可显示的文件"
        }
        return@withContext resultList
    }

    /**
     * 📸 [真实功能] 从机身拉取全尺寸原图字节流 (GetObject)
     */
    suspend fun downloadCameraPhoto(
        handle: Int,
        onProgress: ((copied: Long, total: Long) -> Unit)? = null
    ): ByteArray? = withContext(Dispatchers.IO) {
        val storageConnection = connection ?: return@withContext null
        val socket = storageConnection.command
        if (socket.isClosed || !_isConnected.value) return@withContext null

        val wasLiveView = _isLiveViewActive.value
        try {
            if (wasLiveView) {
                stopForStorage(storageConnection)
            }

            activeListener?.onLog("📥 正在通过 PTP 高速通道拉取原图 (Handle: $handle)...")

            // PTP 每个数据包通常 1MB 以上；按 180ms / 256KB 节流，避免传输时
            // 每包都去改 Compose 状态，同时保证界面上的速度是实时更新的。
            var lastCopied = -1L
            var lastAt = 0L
            val progress: ((Long, Long) -> Unit)? = onProgress?.let { sink ->
                { copied, total ->
                    val now = System.nanoTime()
                    if (copied != lastCopied &&
                        (lastAt == 0L || now - lastAt >= 180_000_000L || copied - lastCopied >= 256L * 1024L ||
                            (total > 0L && copied >= total))
                    ) {
                        lastCopied = copied
                        lastAt = now
                        sink(copied, total)
                    }
                }
            }

            // 1. 发送 GetObject (0x1009)
                val photoBytes = sendOperation(0x1009.toShort(), intArrayOf(handle), progress)



            
            return@withContext photoBytes
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            activeListener?.onLog("下载照片失败: ${e.message}")
            return@withContext null
        } finally {
            if (wasLiveView && _isConnected.value && connection === storageConnection) startLiveView()
        }
    }
}



/** PTP 字符串（1 字节长度 + UTF‑16LE 字符，含结尾 0）→ 毫秒；解不出返回 null。 */
private fun parsePtpStringTime(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset >= bytes.size) return null
    val len = bytes[offset].toInt() and 0xFF
    if (len <= 0 || offset + 1 + len * 2 > bytes.size) return null
    val raw = String(bytes, offset + 1, (len - 1) * 2, Charsets.UTF_16LE).trim()
    if (raw.isEmpty()) return null
    val fmt = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getDefault()
    return runCatching { fmt.parse(raw)?.time }.getOrNull()
}