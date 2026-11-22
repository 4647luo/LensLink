package com.lutcamera.lutcamerapro

import android.app.Application
import android.content.Context
import android.util.Log
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
class DoukApplication : Application() {

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
        plantTimber()
        Timber.i("连镜 %s 启动", BuildConfig.VERSION_NAME)
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
                    Log.VERBOSE -> DoukLog.Level.VERBOSE
                    Log.DEBUG -> DoukLog.Level.DEBUG
                    Log.INFO -> DoukLog.Level.INFO
                    Log.WARN -> DoukLog.Level.WARN
                    else -> DoukLog.Level.ERROR
                }
                val body = if (t != null) {
                    message + "\n" + Log.getStackTraceString(t)
                } else {
                    message
                }
                DoukLog.write(level, tag ?: "App", body, emptyArray())
            }
        })
    }
}
