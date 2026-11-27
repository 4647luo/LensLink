package com.lutcamera.lutcamerapro.log

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import com.lutcamera.lutcamerapro.BuildConfig
import com.lutcamera.lutcamerapro.log.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elvishew.xlog.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue

/**
 * Douk 运行日志。
 *
 * 目的不是「在 App 里看」，而是**留成文件**：软件发出去以后，回收这个文件就能定位问题。
 *
 * 三条落盘路径：
 * 1. 每次启动新建一份会话日志 `filesDir/douk-logs/session-<yyyyMMdd-HHmmss>.log`，逐行写；
 * 2. 连接失败时**自动**把完整日志导出到公共目录 `Download/连镜/`，用户用文件管理器就能取；
 * 3. 设置页仍可手动导出一份。
 *
 * 行格式对齐 GitHub 常见日志库（KLog / Timber）：
 * `11-15 22:19:30.252 W/Scan: NOT_FOUND mode=WIFI_AP nets=wlan1 192.168.125.x`
 */
object DoukLog {

    private const val MAX_MEMORY = 800
    private const val MAX_FILE_BYTES = 3L * 1024 * 1024
    private const val KEEP_SESSIONS = 12
    private const val AUTO_EXPORT_INTERVAL_MS = 60_000L
    private const val VERSION_THROTTLE_MS = 120L
    const val LOGGER_TAG = "DoukLog"

    enum class Level(val flag: Char) {
        VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E')
    }

    private val buffer = ArrayList<String>(MAX_MEMORY)
    private val lock = Any()
    // SimpleDateFormat 不是线程安全的；日志会从网络、IO、主线程同时写入，
    // 用 ThreadLocal 保证每条线程各自一份格式化器，避免时间戳串位。
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
    private val stampFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    private val sessionFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // 所有写盘都走这条队列，由单独的守护线程落盘；主线程和网络回调不再等磁盘。
    private val fileQueue = LinkedBlockingQueue<String>()
    @Volatile private var writerThread: Thread? = null
    private var versionScheduled = false

    @Volatile private var appContext: Context? = null
    @Volatile private var sessionFile: File? = null
    @Volatile private var lastAutoExportAt = 0L
    @Volatile private var lastAutoExportReason: String? = null

    /** 任何一条新日志都会 +1，Compose 侧读它触发刷新。 */
    var version by mutableStateOf(0)
        private set

    /** 最近一次导出的相对路径，界面可以提示用户去哪儿找。 */
    @Volatile var lastExportPath: String? = null
        private set

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        synchronized(lock) {
            if (sessionFile != null) return
            // 这里只拼路径，不建目录、不 trim、不写文件；真正磁盘操作都在 writerThread。
            val file = File(logDir(app), "session-${sessionFormat.get().format(Date())}.log")
            sessionFile = file
            // 先把「文件头 + 安装前缓冲」排好，再启动写线程，保证文件头是第一行。
            fileQueue.clear()
            fileQueue.offer(
                buildString {
                    appendLine("连镜会话日志")
                    appendLine("开始时间: ${stampFormat.get().format(Date())}")
                    appendLine("应用: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                    appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.PRODUCT}")
                    appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) / ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("屏幕: ${app.resources.displayMetrics.widthPixels}x${app.resources.displayMetrics.heightPixels} @ ${app.resources.displayMetrics.densityDpi}dpi")
                    appendLine("时区: ${TimeZone.getDefault().id} 语言: ${Locale.getDefault().toLanguageTag()}")
                    appendLine("进程: pid=${android.os.Process.myPid()} 启动=${android.os.SystemClock.elapsedRealtime() / 1000}s")
                    appendLine("内存: ${memoryLine()}")
                    appendLine("存储: ${storageLine(app)}")
                    appendLine("网络: ${networkLine(app)}")
                    appendLine("电池: ${batteryLine(app)}")
                    appendLine("格式: 时间 级别/标签: 事件 key=value")
                    appendLine("========================================")
                }
            )
            buffer.forEach { fileQueue.offer(it) }
            startWriter()
        }
    }

    fun v(tag: String, event: String, vararg fields: Pair<String, Any?>) = write(Level.VERBOSE, tag, event, fields)
    fun d(tag: String, event: String, vararg fields: Pair<String, Any?>) = write(Level.DEBUG, tag, event, fields)
    fun i(tag: String, event: String, vararg fields: Pair<String, Any?>) = write(Level.INFO, tag, event, fields)
    fun w(tag: String, event: String, vararg fields: Pair<String, Any?>) = write(Level.WARN, tag, event, fields)
    fun e(tag: String, event: String, vararg fields: Pair<String, Any?>) = write(Level.ERROR, tag, event, fields)

    fun write(level: Level, tag: String, event: String, fields: Array<out Pair<String, Any?>>) {
        val line = buildString {
            append(timeFormat.get().format(Date()))
            append(' ').append(level.flag).append('/').append(tag).append(": ")
            append(event)
            for ((key, value) in fields) {
                if (value == null) continue
                append(' ').append(key).append('=')
                append(value.toString().replace('\n', ' '))
            }
        }
        runCatching { android.util.Log.println(level.ordinal + 2, LOGGER_TAG, line) }
        // xLog 只落文件：给崩溃前一帧留一份独立副本
        runCatching { XLog.d(line) }
        synchronized(lock) {
            buffer.add(line)
            while (buffer.size > MAX_MEMORY) buffer.removeAt(0)
            // offer 不阻塞，主线程只做一次入队；磁盘由 writerThread 处理。
            fileQueue.offer(line)
        }
        scheduleVersion()
    }

    /** 分隔一段流程，长日志里靠它快速定位阶段。 */
    fun section(title: String) = write(Level.INFO, "Session", "---- $title ----", emptyArray())

    /** 记录异常：类名 + message + 前 8 帧，够看清是哪个调用链炸的。 */
    fun eThrow(tag: String, event: String, tr: Throwable, vararg fields: Pair<String, Any?>) {
        val frames = tr.stackTrace.take(8).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        write(
            Level.ERROR,
            tag,
            event,
            arrayOf(
                *fields,
                "err" to "${tr.javaClass.simpleName}: ${tr.message}",
                "stack" to frames
            )
        )
    }

    /** 计时起点，配合 [end] 记录一段操作耗时。 */
    fun begin(): Long = android.os.SystemClock.elapsedRealtime()

    fun end(tag: String, event: String, startedAt: Long, vararg fields: Pair<String, Any?>) {
        write(
            Level.INFO,
            tag,
            event,
            arrayOf(*fields, "ms" to (android.os.SystemClock.elapsedRealtime() - startedAt))
        )
    }

    /**
     * 未捕获异常的兜底：队列可能来不及落盘，这里同步写一份再交回系统默认处理。
     * ACRA 负责堆栈和 logcat，这里只保证会话日志里也有完整一段。
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val line = buildString {
                    append(timeFormat.get().format(Date()))
                    append(" E/Crash: UNCAUGHT thread=").append(thread.name)
                    append(' ').append(error.javaClass.name).append(": ").append(error.message)
                    append('\n').append(android.util.Log.getStackTraceString(error))
                }
                writeSync(line)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 同步写入一行，只给崩溃路径用，正常日志仍走队列。 */
    private fun writeSync(line: String) {
        val file = sessionFile ?: return
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            file.appendText(line + "\n")
        }
        runCatching { XLog.d(line) }
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)

    private fun memoryLine(): String {
        val rt = Runtime.getRuntime()
        return "heap ${mb(rt.totalMemory() - rt.freeMemory())}/${mb(rt.maxMemory())}MB used"
    }

    private fun storageLine(context: Context): String =
        "free ${mb(runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L))}MB"

    private fun networkLine(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }.getOrDefault("unknown")

    private fun batteryLine(context: Context): String = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING
        "$pct% ${if (charging) "charging" else "battery"}"
    }.getOrDefault("unknown")

    private fun startWriter() {
        if (writerThread != null) return
        val thread = Thread({
            // 目录创建、历史裁剪都放在后台线程，避免拖慢启动。
            runCatching { appContext?.let { trim(logDir(it)) } }
            while (true) {
                val line = try {
                    fileQueue.take()
                } catch (_: InterruptedException) {
                    continue
                }
                runCatching { writeLineToFile(line) }
            }
        }, "DoukLogWriter").apply {
            isDaemon = true
            start()
        }
        writerThread = thread
    }

    private fun writeLineToFile(line: String) {
        var file = sessionFile ?: return
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (file.length() > MAX_FILE_BYTES) {
            // 单次会话异常长：换一个新文件继续写，保留原来的
            file = File(parent, "session-${sessionFormat.get().format(Date())}-b.log")
            sessionFile = file
        }
        file.appendText(line + "\n")
    }

    /**
     * 日志量大时每条都 +1 会让 Compose 每个网络回调都重组一次。
     * 这里把刷新合并到最多 8 次/秒，日志页仍然能实时看到。
     */
    private fun scheduleVersion() {
        synchronized(lock) {
            if (versionScheduled) return
            versionScheduled = true
        }
        mainHandler.postDelayed({
            synchronized(lock) { versionScheduled = false }
            version++
        }, VERSION_THROTTLE_MS)
    }

    /** 当前内存里的日志快照（旧 → 新）。 */
    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        mainHandler.post { version++ }
    }

    /** 本次会话的日志文件（用于「日志放哪儿了」这类提示）。 */
    fun currentFile(): File? = sessionFile

    /**
     * 连接失败时自动把日志导出到 `Download/连镜/`。
     * 60 秒内只导一次，避免连续失败刷出一堆文件。返回 null 表示刚导过、本次跳过。
     */
    fun autoExport(context: Context, reason: String): String? {
        val now = System.currentTimeMillis()
        // 同一原因 60 秒内只导一次；换了失败原因（比如从预检失败变成握手失败）立刻再导一份
        if (reason == lastAutoExportReason && now - lastAutoExportAt < AUTO_EXPORT_INTERVAL_MS) return null
        lastAutoExportAt = now
        lastAutoExportReason = reason
        write(Level.INFO, "DoukLog", "AUTO_EXPORT", arrayOf<Pair<String, Any?>>("reason" to reason))
        return export(context)
    }

    /**
     * 导出完整日志（当前会话 + 历史会话）到 `Download/连镜`，返回可读的结果文案。
     * 首部带设备与运行时信息，方便直接贴到 issue。
     *
     * 注意：这个方法会读文件、写 MediaStore，属于阻塞 IO，必须在 IO 线程调用。
     */
    fun export(context: Context): String {
        val body = synchronized(lock) { buffer.toList() }
        val history = runCatching {
            logDir(context).listFiles()
                ?.filter { it.name.endsWith(".log") }
                ?.sortedBy { it.name }
                ?.joinToString("\n") { file -> runCatching { file.readText() }.getOrDefault("") }
                ?: ""
        }.getOrDefault("")

        val header = buildString {
            appendLine("连镜运行日志")
            appendLine("导出时间: ${stampFormat.get().format(Date())}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("应用: ${context.packageName}")
            appendLine("格式: 时间 级别/标签: 事件 key=value")
            appendLine("===== 本次会话 =====")
        }
        val content = header + body.joinToString("\n") + "\n===== 历史会话文件 =====\n" + history

        return runCatching {
            val name = "lianjing-log-${sessionFormat.get().format(Date())}.log"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/连镜")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "日志导出失败：无法创建文件"
            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(content.toByteArray()) }
                ?: return "日志导出失败：无法写入"
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            lastExportPath = "Download/连镜/$name"
            "已导出到 Download/连镜/$name（${body.size} 条）"
        }.getOrElse { "日志导出失败：${it.message}" }
    }

    private fun logDir(context: Context): File = File(context.filesDir, "douk-logs")

    private fun trim(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.name.startsWith("session-") }?.sortedBy { it.lastModified() }
                ?: return
            if (files.size >= KEEP_SESSIONS) {
                files.take(files.size - KEEP_SESSIONS + 1).forEach { it.delete() }
            }
        }
    }
}
