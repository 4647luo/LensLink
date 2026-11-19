package com.lutcamera.lutcamerapro.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.lutcamera.lutcamerapro.gpu.ImageAutoProcessor
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 🚀 极速照片回传通道：手搓微型 FTP 服务器
 * 配合尼康相机的“FTP 上传”功能，实现按下快门，照片瞬间推送到手机
 * 支持 AP 直连模式与 STA 局域网模式，支持自动解析 PASV IP 与多图片并发接收 */
object FtpPushReceiver {
    private const val TAG = "FtpPushReceiver"
    /** 单张照片/视频上限：防止匿名 FTP 被用来塞超大文件把内存打爆。 */
    private const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024
    private var cmdServer: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // 停止服务时主动关掉已接入的控制/数据 socket，而不是只取消协程等对端超时。
    private val clientSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val dataSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val dataServers = Collections.newSetFromMap(ConcurrentHashMap<ServerSocket, Boolean>())

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _receivedCount = MutableStateFlow(0)
    val receivedCount = _receivedCount.asStateFlow()

    // 回传照片的原始数据流全局回调 (fileName, jpegBytes)
                var onPhotoReceived: ((fileName: String, jpegBytes: ByteArray) -> Unit)? = null

    // 自动化流水线配置
                var autoApplyLut: Boolean = true
    var autoApplyBorder: Boolean = false
    var currentLut: LutData? = null
    var intensity: Float = 1.0f
    var saveMode: SaveMode = SaveMode.DUAL
    var borderState: BorderState = BorderState()
    private var appContext: Context? = null
    private var onProcessedCallback: ((String) -> Unit)? = null

    /**
     * 启动 FTP 监听 (默认端口 2121，避开 Android 低端口权限问题)
     */
    fun startReceiver(port: Int = 2121) {
        if (_isRunning.value) return
        _isRunning.value = true

        serverJob = scope.launch {
            try {
                // 尽量只监听当前 Wi‑Fi/热点这块网卡，不要把所有网卡都暴露成匿名 FTP。
                val localIp = runCatching { NetworkUtils.getLocalIpv4Address() }.getOrNull()
                    ?.takeIf { it.isNotBlank() && it != "0.0.0.0" && it != "127.0.0.1" }
                cmdServer = ServerSocket().apply {
                    reuseAddress = true
                    try {
                        if (localIp != null) bind(InetSocketAddress(localIp, port))
                        else bind(InetSocketAddress(port))
                    } catch (e: Exception) {
                        Log.w(TAG, "绑定 $localIp:$port 失败，回退到默认网卡: ${e.message}")
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
                if (_isRunning.value) {
                    Log.e(TAG, "FTP 监听服务异常", e)
                }
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * 开启自动化处理管道
     */
    fun startListening(
        context: Context,
        currentLut: LutData?,
        intensity: Float,
        autoApplyLut: Boolean,
        onPhotoProcessed: (String) -> Unit,
        port: Int = 2121
    ) {
        this.appContext = context.applicationContext
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
        try {
            cmdServer?.close()
        } catch (e: Exception) {}
        cmdServer = null
        // 不主动断开已经接入的客户端，取消协程后它们会一直挂到 readLine/readBytes 超时。
        dataServers.toList().forEach { runCatching { it.close() } }
        dataServers.clear()
        dataSockets.toList().forEach { runCatching { it.close() } }
        dataSockets.clear()
        clientSockets.toList().forEach { runCatching { it.close() } }
        clientSockets.clear()
        Log.d(TAG, "FtpPushReceiver stopped")
    }

    private suspend fun handleCameraClient(client: Socket) = withContext(Dispatchers.IO) {
        var currentFileName = "CAMERA_${System.currentTimeMillis()}.JPG"
        try {
            // 控制连接 30 秒没有新命令就释放，避免异常断开的相机 socket 长期占着线程。
            client.soTimeout = 30_000
            val reader = client.inputStream.bufferedReader()
            val writer = client.outputStream.bufferedWriter()
            var dataServer: ServerSocket? = null

            fun reply(code: String) {
                try {
                    writer.write("$code\r\n")
                    writer.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "发送响应失败: $code")
                }
            }

            // 1. 尼康连入，发送欢迎语
                reply("220 Welcome to LutCameraPro Pro FTP Server")

            while (isActive && _isRunning.value && !client.isClosed) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val parts = trimmed.split(" ", limit = 2)
                val cmd = parts[0].uppercase()
                val arg = if (parts.size > 1) parts[1] else ""

                when (cmd) {
                    "USER" -> reply("331 Anonymous access allowed")
                    "PASS" -> reply("230 User logged in successfully")
                    "SYST" -> reply("215 UNIX Type: L8")
                    "PWD" -> reply("257 \"/\" is current directory")
                    "TYPE" -> reply("200 Type set to I (Binary)")
                    "PORT" -> reply("200 PORT command successful")
                    "PASV" -> {
                        // 被动模式：开辟随机端口接收图片流
                dataServer?.let { old -> dataServers.remove(old); runCatching { old.close() } }
                        dataServer = ServerSocket(0).apply { reuseAddress = true }
                        dataServers.add(dataServer!!)
                        val server = dataServer!!
                        val localPort = server.localPort

                        // 智能获取对外通信的真实 IP
                val localIp = client.localAddress?.hostAddress?.let { ip ->
                            if (ip == "0.0.0.0" || ip == "127.0.0.1") NetworkUtils.getLocalIpv4Address() else ip
                        } ?: NetworkUtils.getLocalIpv4Address()

                        val formattedIp = localIp.replace('.', ',')
                        val p1 = localPort / 256
                        val p2 = localPort % 256
                        reply("227 Entering Passive Mode ($formattedIp,$p1,$p2)")
                    }
                    "STOR" -> {
                        // 🌟 核心：相机开始推送照片
                val rawName = if (arg.isNotBlank()) arg.substringAfterLast('/').substringAfterLast('\\') else "IMG_${System.currentTimeMillis()}.JPG"
                        val filename = rawName.take(120).ifBlank { "IMG_${System.currentTimeMillis()}.JPG" }
                        currentFileName = filename
                        reply("150 Opening BINARY mode data connection for $filename")

                        val server = dataServer
                        var imageBytes: ByteArray? = null
                        if (server != null) {
                            var dataSocket: Socket? = null
                            try {
                                server.soTimeout = 15000
                                dataSocket = server.accept()
                                dataSockets.add(dataSocket)
                                dataSocket.soTimeout = 20000
                                imageBytes = readLimited(dataSocket.inputStream, MAX_UPLOAD_BYTES)
                            } catch (e: Exception) {
                                Log.e(TAG, "接收图片数据流超时或异常", e)
                            } finally {
                                dataSocket?.let { dataSockets.remove(it); runCatching { it.close() } }
                                dataServers.remove(server)
                                server.close()
                                dataServer = null
                            }
                        }

                        reply("226 Transfer complete")

                        // 收到照片数据
                        if (imageBytes != null && imageBytes.isNotEmpty()) {
                            _receivedCount.value += 1
                            Log.d(TAG, "📸 成功接收相机照片: $currentFileName, 大小: ${imageBytes.size / 1024} KB")

                            // 1. 回调给业务监听
                            withContext(Dispatchers.Main) {
                                onPhotoReceived?.invoke(currentFileName, imageBytes)
                            }

                            // 2. 如果开启了自动化流水线，执行自动渲染与保存
                            val ctx = appContext
                            if (ctx != null) {
                                scope.launch(Dispatchers.IO) {
                                    ByteArrayInputStream(imageBytes).use { stream ->
                                        val ok = ImageAutoProcessor.processIncomingCameraPhoto(
                                            context = ctx,
                                            imageStream = stream,
                                            currentLut = currentLut,
                                            intensity = intensity,
                                            autoApplyLut = autoApplyLut,
                                            autoApplyBorder = autoApplyBorder,
                                            saveMode = saveMode,
                                            borderState = borderState
                                        )
                                        if (ok) {
                                            withContext(Dispatchers.Main) {
                                                onProcessedCallback?.invoke(currentFileName)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "QUIT" -> {
                        reply("221 Service closing control connection")
                        break
                    }
                    "NOOP" -> reply("200 OK")
                    "FEAT" -> {
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

    /** 有上限地读取数据流：超过上限直接中断，绝不把整个 socket 无限制读进 ByteArray。 */
    private fun readLimited(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("FTP 文件超过上限 ${maxBytes / 1024 / 1024}MB")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

