package com.lutcamera.lutcamerapro

import android.app.Application
import android.content.Context
import com.lutcamera.lutcamerapro.log.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.file.FilePrinter
import com.elvishew.xlog.printer.file.naming.DateFileNameGenerator
import com.elvishew.xlog.printer.file.backup.FileSizeBackupStrategy
import com.lutcamera.lutcamerapro.log.DoukLog
import com.lutcamera.lutcamerapro.ui.douk.DoukPrefs
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.StringFormat
import timber.log.Timber
import java.io.File

/**
 * 连镜的进程入口。
 *
 * 日志四层各司其职：
 * - Timber：应用代码里的统一门面，业务代码只写 Timber.i/d/w/e；
 * - DoukLog：结构化事件日志（时间 级别/标签: 事件 key=value），按会话落文件；
 * - xLog：底层文件日志，进程被杀、崩溃前的最后几行也能留下来；
 * - ACRA：崩溃堆栈、ANR、设备信息，单独落到 acra-reports。
 *
 * 所有目录都在应用私有空间，发出去之前由用户主动导出/分享。
 */
class DoukApplication : Application(), ImageLoaderFactory {

    /**
     * Coil 的进程级 ImageLoader，只服务图库缩略图和「细节查看」。
     *
     * 这里不用磁盘缓存：连镜的图本来就在本机文件系统里，把原图再抄一份进
     * cache 目录纯属重复占空间。内存缓存按堆的 15% 封顶，比图库那个手写
     * 48 张的 Map 更适合扛长列表来回滚。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            // 本地文件没有 HTTP 头，关掉缓存头校验省一次无意义的比较
            .respectCacheHeaders(false)
            // 缩略图不做淡入：图库滑动时淡入会闪一片格子
            .crossfade(false)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // ACRA 必须在进程最早阶段挂上，否则启动期崩溃收不到
        runCatching {
            val config = CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
                .withReportFormat(StringFormat.KEY_VALUE_LIST)
                .withReportContent(
                    ReportField.REPORT_ID,
                    ReportField.APP_VERSION_CODE,
                    ReportField.APP_VERSION_NAME,
                    ReportField.PACKAGE_NAME,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.PRODUCT,
                    ReportField.ANDROID_VERSION,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                    ReportField.CUSTOM_DATA
                )
                .withSendReportsInDevMode(true)
            ACRA.init(this, config)
        }.onFailure { Log.e("DoukApplication", "ACRA init failed", it) }
    }

    override fun onCreate() {
        super.onCreate()
        initFileLogger()
        DoukLog.install(this)
        DoukPrefs.init(this)
        com.lutcamera.lutcamerapro.network.FtpPushReceiver.init(this)
        com.lutcamera.lutcamerapro.ui.douk.TransferCenter.init(this)
        plantTimber()
        // HDR 缓存以前只写不清；启动时先按 512MB / 24h 配额收一次。
        runCatching { com.lutcamera.lutcamerapro.ui.douk.ImageDecode.trimCache(this) }
        prewarmLutLibrary()
        Timber.i("连镜 %s 启动", BuildConfig.VERSION_NAME)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 只清理可再生的 HDR 临时文件；正在看的文件由查看页自己持有，不会因为删 cache 变白。
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { com.lutcamera.lutcamerapro.ui.douk.ImageDecode.trimCache(this) }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { com.lutcamera.lutcamerapro.ui.douk.ImageDecode.trimCache(this) }
        // 系统真的没内存了，Coil 的缩略图缓存是可再生的，先丢它再丢 HDR 缓存
        runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
    }

    /**
     * 启动后空闲时把导入的 LUT 解析一遍。监视页/编辑器第一次打开时就不用再等
     * 几十个 .cube/.3dl/.np3 全量解析；真正打开时只做一次目录 stat，直接命中缓存。
     * 放到后台低优先级线程并延迟 2 秒，避免和首屏、相机连接抢 CPU。
     */
    private fun prewarmLutLibrary() {
        Thread {
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                Thread.sleep(2_000)
                val started = android.os.SystemClock.elapsedRealtime()
                val result = com.lutcamera.lutcamerapro.ui.douk.FilterLibrary.load(applicationContext)
                Log.i(
                    "DoukApplication",
                    "LUT prewarm: ${result.entries.size} entries in ${android.os.SystemClock.elapsedRealtime() - started}ms"
                )
            }.onFailure { Log.w("DoukApplication", "LUT prewarm failed", it) }
        }.start()
    }

    /** xLog 只落文件，不重复打 logcat；按天分文件，单文件 2MB，保留 7 天。 */
    private fun initFileLogger() {
        runCatching {
            val dir = File(filesDir, "xlog").apply { mkdirs() }
            // 自己清理 7 天前的文件，避免依赖不同 xLog 版本的 cleaner API
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
            val config = LogConfiguration.Builder()
                .logLevel(LogLevel.ALL)
                .tag("LianJing")
                .build()
            val printer = FilePrinter.Builder(dir.absolutePath)
                .fileNameGenerator(DateFileNameGenerator())
                .backupStrategy(FileSizeBackupStrategy(2L * 1024 * 1024))
                .build()
            XLog.init(config, printer)
        }.onFailure {
            Log.e("DoukApplication", "xLog init failed", it)
        }
    }

    /**
     * Timber 统一转给 DoukLog，同时放一份到 xLog 文件。
     * 业务代码不需要知道下面有几个日志实现。
     */
    private fun plantTimber() {
        if (Timber.treeCount > 0) return
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                val level = when (priority) {
                    android.util.Log.VERBOSE -> DoukLog.Level.VERBOSE
                    android.util.Log.DEBUG -> DoukLog.Level.DEBUG
                    android.util.Log.INFO -> DoukLog.Level.INFO
                    android.util.Log.WARN -> DoukLog.Level.WARN
                    else -> DoukLog.Level.ERROR
                }
                val body = if (t != null) {
                    message + "\n" + android.util.Log.getStackTraceString(t)
                } else {
                    message
                }
                DoukLog.write(level, tag ?: "App", body, emptyArray())
            }
        })
    }
}
