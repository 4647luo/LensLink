package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.util.Log
import com.lutcamera.lutcamerapro.gpu.ImageAutoProcessor
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import com.lutcamera.lutcamerapro.util.BoundedIo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 照片回传通道：手搓微型 FTP 服务器。
 *
 * 运行约束：
 * - 仅绑定当前 Wi‑Fi 本机地址；相机通过手机热点 / 直连 Wi-Fi 接入，免密登录；
 * - 上传先落到 cache/ftp-incoming 临时文件，不再把 200MB 读进 ByteArray；
 * - 单文件 200MB、并发 2 个、临时目录最多 500MB / 32 个文件。
 */
object FtpPushReceiver {
    private const val TAG = "FtpPushReceiver"
    private const val MAX_UPLOAD_BYTES = BoundedIo.MAX_FTP_BYTES
    private const val MAX_INCOMING_TOTAL_BYTES = 500L * 1024 * 1024
    private const val MAX_INCOMING_FILES = 32
    private const val MAX_EVENTS_PER_CLIENT = 512

    private var cmdServer: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val dataSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val dataServers = Collections.newSetFromMap(ConcurrentHashMap<ServerSocket, Boolean>())
    private val uploadSlots = Semaphore(2, true)

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _receivedCount = MutableStateFlow(0)
    val receivedCount = _receivedCount.asStateFlow()

    /**
     * 收到一张已经落盘的相机 JPEG。回调方拥有这个临时文件的所有权：
     * 必须把它移进 PhotoInbox 或处理完删除，不能直接丢掉引用。
     */
    var onPhotoFileReceived: ((fileName: String, file: File) -> Unit)? = null

    // 自动化流水线配置（只有在没有 onPhotoFileReceived 时才会直接处理）
    var autoApplyLut: Boolean = true
    var autoApplyBorder: Boolean = false
    var currentLut: LutData? = null
    var intensity: Float = 1.0f
    var saveMode: SaveMode = SaveMode.DUAL
    var borderState: BorderState = BorderState()

    private var appContext: Context? = null
    private var onProcessedCallback: ((String) -> Unit)? = null

    /** 在 Application 启动时调用，先记下 applicationContext 并清理临时目录。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 启动 FTP 监听（默认端口 2121，避开 Android 低端口权限问题）。
     */
    fun startReceiver(port: Int = 2121) {
        if (_isRunning.value) return
        appContext?.let { trimIncoming(it) }
        _isRunning.value = true

        serverJob = scope.launch {
            try {
                val localIp = runCatching { NetworkUtils.getLocalIpv4Address() }.getOrNull()
                    ?.takeIf { it.isNotBlank() && it != "0.0.0.0" && it != "127.0.0.1" }
                cmdServer = ServerSocket().apply {
                    reuseAddress = true
                    if (localIp != null) {
                        bind(InetSocketAddress(localIp, port))
                    } else {
                        // 拿不到本机 Wi‑Fi IP 时仍要能用，只是控制连接会落在默认网卡。
                        Log.w(TAG, "未取到本机 Wi‑Fi IP，控制连接回退到默认网卡")
                        bind(InetSocketAddress(port))
                    }
                }

                while (isActive && _isRunning.value) {
                    val client = cmdServer?.accept() ?: break
                    clientSockets.add(client)
                    launch {
                        try {
                            handleCameraClient(client)
                        } finally {
                            clientSockets.remove(client)
                            runCatching { client.close() }
                        }
                    }
                }
            } catch (e: Exception) {
                if (_isRunning.value) Log.e(TAG, "FTP 监听服务异常", e)
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * 开启自动化处理管道。
     */
    fun startListening(
        context: Context,
        currentLut: LutData?,
        intensity: Float,
        autoApplyLut: Boolean,
        onPhotoProcessed: (String) -> Unit,
        port: Int = 2121
    ) {
        appContext = context.applicationContext
        this.currentLut = currentLut
        this.intensity = intensity
        this.autoApplyLut = autoApplyLut
        this.onProcessedCallback = onPhotoProcessed
        startReceiver(port)
    }

    fun stopListening() {
        stopReceiver()
    }

    fun stopReceiver() {
        _isRunning.value = false
        serverJob?.cancel()
        serverJob = null
        runCatching { cmdServer?.close() }
        cmdServer = null
        dataServers.toList().forEach { runCatching { it.close() } }
        dataServers.clear()
        dataSockets.toList().forEach { runCatching { it.close() } }
        dataSockets.clear()
        clientSockets.toList().forEach { runCatching { it.close() } }
        clientSockets.clear()
        Log.d(TAG, "FtpPushReceiver stopped")
    }

    private suspend fun handleCameraClient(client: Socket) = withContext(Dispatchers.IO) {
        val context = appContext
        try {
            client.soTimeout = 30_000
            val reader = client.inputStream.bufferedReader()
            val writer = client.outputStream.bufferedWriter()
            var dataServer: ServerSocket? = null
            var authenticated = false
            var events = 0

            fun reply(code: String) {
                runCatching {
                    writer.write("$code\r\n")
                    writer.flush()
                }.onFailure { Log.w(TAG, "发送响应失败: $code") }
            }

            fun requireAuth(): Boolean {
                if (authenticated) return true
                reply("530 Please login with USER and PASS")
                return false
            }

            reply("220 Welcome to LianJing FTP Server")

            while (isActive && _isRunning.value && !client.isClosed && events < MAX_EVENTS_PER_CLIENT) {
                val line = reader.readLine() ?: break
                if (line.length > 512) {
                    reply("500 Command line too long")
                    break
                }
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                events++

                val parts = trimmed.split(" ", limit = 2)
                val cmd = parts[0].uppercase()
                val arg = if (parts.size > 1) parts[1] else ""

                when (cmd) {
                    "USER" -> reply("331 Password required")
                    "PASS" -> {
                        // 局域网 / 手机热点直连场景：免密放行，不再设置 FTP 密码。
                        authenticated = true
                        reply("230 User logged in successfully")
                    }
                    "SYST" -> if (requireAuth()) reply("215 UNIX Type: L8")
                    "PWD" -> if (requireAuth()) reply("257 \"/\" is current directory")
                    "TYPE" -> if (requireAuth()) reply("200 Type set to I (Binary)")
                    "PORT" -> if (requireAuth()) reply("200 PORT command successful")
                    "PASV" -> {
                        if (!requireAuth()) continue
                        dataServer?.let { old -> dataServers.remove(old); runCatching { old.close() } }
                        val localIp = client.localAddress?.hostAddress
                            ?.takeIf { it != "0.0.0.0" && it != "127.0.0.1" }
                            ?: NetworkUtils.getLocalIpv4Address()
                        val server = runCatching {
                            ServerSocket(0, 1, InetAddress.getByName(localIp))
                        }.getOrElse {
                            Log.w(TAG, "PASV 绑定 $localIp 失败，回退到默认网卡: ${it.message}")
                            ServerSocket(0)
                        }.apply { reuseAddress = true }
                        dataServer = server
                        dataServers.add(server)
                        val localPort = server.localPort
                        val formattedIp = localIp.replace('.', ',')
                        val p1 = localPort / 256
                        val p2 = localPort % 256
                        reply("227 Entering Passive Mode ($formattedIp,$p1,$p2)")
                    }
                    "STOR" -> {
                        if (!requireAuth()) continue
                        if (!uploadSlots.tryAcquire()) {
                            reply("450 Too many concurrent uploads")
                            continue
                        }
                        try {
                            val rawName = if (arg.isNotBlank()) {
                                arg.substringAfterLast('/').substringAfterLast('\\')
                            } else "IMG_${System.currentTimeMillis()}.JPG"
                            val filename = rawName.take(120).ifBlank {
                                "IMG_${System.currentTimeMillis()}.JPG"
                            }
                            if (!filename.endsWith(".jpg", true) && !filename.endsWith(".jpeg", true)) {
                                reply("550 Only JPEG uploads are accepted")
                                continue
                            }
                            reply("150 Opening BINARY mode data connection for $filename")

                            val server = dataServer
                            if (server == null) {
                                reply("425 Use PASV first")
                                continue
                            }
                            val incoming = context?.let {
                                File(it.cacheDir, "ftp-incoming").apply { mkdirs() }
                            }
                            if (incoming == null) {
                                reply("451 Storage unavailable")
                                continue
                            }
                            trimIncoming(context)
                            val temp = File(incoming, "ftp-${System.nanoTime()}.part")
                            var copied = 0L
                            var dataSocket: Socket? = null
                            try {
                                server.soTimeout = 15_000
                                dataSocket = server.accept()
                                dataSockets.add(dataSocket)
                                dataSocket.soTimeout = 20_000
                                copied = temp.outputStream().use { output ->
                                    BoundedIo.copyWithLimit(dataSocket.inputStream, output, MAX_UPLOAD_BYTES)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "接收图片数据流超时或异常", e)
                                temp.delete()
                                reply("426 Transfer aborted")
                                continue
                            } finally {
                                dataSocket?.let { dataSockets.remove(it); runCatching { it.close() } }
                                dataServers.remove(server)
                                runCatching { server.close() }
                                dataServer = null
                            }

                            if (copied <= 0L) {
                                temp.delete()
                                reply("550 Empty upload")
                                continue
                            }
                            reply("226 Transfer complete")
                            _receivedCount.value += 1
                            Log.d(TAG, "📸 成功接收相机照片: $filename, 大小: ${copied / 1024} KB")

                            val callback = onPhotoFileReceived
                            if (callback != null) {
                                try {
                                    withContext(Dispatchers.Main) { callback(filename, temp) }
                                } catch (e: Exception) {
                                    Log.e(TAG, "照片移交失败", e)
                                    temp.delete()
                                }
                            } else if (context != null) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        temp.inputStream().use { stream ->
                                            val ok = ImageAutoProcessor.processIncomingCameraPhoto(
                                                context = context,
                                                imageStream = stream,
                                                currentLut = currentLut,
                                                intensity = intensity,
                                                autoApplyLut = autoApplyLut,
                                                autoApplyBorder = autoApplyBorder,
                                                saveMode = saveMode,
                                                borderState = borderState,
                                                sourceFileName = filename
                                            )
                                            if (ok) withContext(Dispatchers.Main) {
                                                onProcessedCallback?.invoke(filename)
                                            }
                                        }
                                    } finally {
                                        temp.delete()
                                    }
                                }
                            } else {
                                temp.delete()
                            }
                        } finally {
                            uploadSlots.release()
                        }
                    }
                    "QUIT" -> {
                        reply("221 Service closing control connection")
                        break
                    }
                    "NOOP" -> reply("200 OK")
                    "FEAT" -> if (requireAuth()) {
                        writer.write("211-Features:\r\n PASV\r\n SIZE\r\n UTF8\r\n211 End\r\n")
                        writer.flush()
                    }
                    else -> reply("200 Command recognized, ignored")
                }
            }
            dataServer?.let { dataServers.remove(it); runCatching { it.close() } }
            client.close()
        } catch (e: Exception) {
            Log.w(TAG, "相机客户端通信断开: ${e.message}")
        }
    }

    /**
     * 临时目录配额：先删 1 小时前的残留，再按最旧优先删到 500MB / 32 个以内。
     * 只处理 ftp-*.part，不碰 PhotoInbox 或导出目录。
     */
    private fun trimIncoming(context: Context?) {
        val dir = context?.let { File(it.cacheDir, "ftp-incoming") } ?: return
        val files = dir.listFiles()?.filter { it.isFile && it.name.startsWith("ftp-") } ?: return
        val cutoff = System.currentTimeMillis() - 60L * 60 * 1000
        files.filter { it.lastModified() < cutoff }.forEach { runCatching { it.delete() } }
        var remaining = files.filter { it.exists() }.sortedBy { it.lastModified() }.toMutableList()
        var total = remaining.sumOf { it.length() }
        while (remaining.size > MAX_INCOMING_FILES || total > MAX_INCOMING_TOTAL_BYTES) {
            val oldest = remaining.removeFirstOrNull() ?: break
            total -= oldest.length()
            runCatching { oldest.delete() }
        }
    }
}
