package com.lutcamera.lutcamerapro.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.lutcamera.lutcamerapro.BuildConfig
import org.acra.file.ReportLocator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包：把用户真正需要发给开发者的东西打成一个 ZIP。
 *
 * 包含：README、设备信息、结构化日志、xLog 文件日志、ACRA 崩溃报告、当前设置快照。
 * 不包含：相机照片、LUT 原文件、账号 token。
 */
object DoukDiagnostics {

    private const val MAX_FILES_PER_DIR = 40

    /** 生成到 cache/diagnostics，返回文件；调用方负责在 IO 线程调用。 */
    fun build(context: Context): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        // 只保留最近 3 个诊断包，避免缓存无限增长
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "LensLink-diagnostics-$stamp.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            addText(zip, "README.txt", readme(context, stamp))
            addText(zip, "device-info.txt", deviceInfo(context))
            addText(zip, "settings/preset.json", settingsJson(context))
            addDir(zip, File(context.filesDir, "douk-logs"), "logs")
            addDir(zip, File(context.filesDir, "xlog"), "xlog")
            // ACRA 5.x 的存放目录由 ReportLocator 决定，不再猜目录名
            runCatching {
                val locator = ReportLocator(context)
                addDir(zip, locator.unapprovedFolder, "acra/unapproved")
                addDir(zip, locator.approvedFolder, "acra/approved")
            }
        }
        return file
    }

    /** 导出到用户通过 SAF 选定的位置。 */
    fun exportTo(context: Context, uri: Uri): Result<File> = runCatching {
        val file = build(context)
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: context.contentResolver.openOutputStream(uri)
            ?: error("无法写入所选文件")
        out.use { target ->
            file.inputStream().use { it.copyTo(target) }
        }
        DoukLog.i("Diagnostics", "EXPORT", "bytes" to file.length())
        file
    }

    /**
     * 生成诊断包并以附件形式拉起邮件客户端。
     * 用 ACTION_SEND 而不是 ACTION_SENDTO：mailto: 不支持 EXTRA_STREAM，附件会丢。
     */
    fun email(
        context: Context,
        to: String,
        subject: String,
        body: String
    ): Result<File> = runCatching {
        val file = build(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("LensLink diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "发送连镜反馈邮件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        file
    }

    /** 直接拉起系统分享面板，用户选微信/邮件即可把 ZIP 发给开发者。 */
    fun share(context: Context): Result<File> = runCatching {
        val file = build(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "连镜诊断日志 ${BuildConfig.VERSION_NAME} / ${Build.MODEL}"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                "请把附件一起发送。日志不含照片和 LUT 原文件，含设备信息、连接记录、相机协议交互和崩溃堆栈。"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "发送连镜诊断日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        file
    }

    private fun readme(context: Context, stamp: String): String = buildString {
        appendLine("连镜 LensLink 诊断包")
        appendLine("生成时间：$stamp")
        appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
        appendLine("目录说明：")
        appendLine("- logs/      结构化运行日志，每次启动一份，记录连接/传输/图库/蓝牙 GPS 关键事件")
        appendLine("- xlog/      底层文件日志，按天分文件，用于进程异常退出前的最后现场")
        appendLine("- acra/      ACRA 崩溃报告（堆栈、机型、系统、logcat 尾部）")
        appendLine("- settings/  当前设置快照（预设 JSON，不含照片和 LUT 文件）")
        appendLine()
        appendLine("隐私说明：")
        appendLine("包内不包含相机照片、视频、LUT 原文件、账号密码。")
        appendLine("连接日志会包含相机 IP、蓝牙设备名、相机返回码，用于定位连接问题。")
        appendLine("如果介意，可在发送前解压删除 xlog/ 或 acra/。")
    }

    private fun deviceInfo(context: Context): String = buildString {
        appendLine("应用包名：${context.packageName}")
        appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("时区：${TimeZone.getDefault().id}")
        appendLine("语言：${Locale.getDefault().toLanguageTag()}")
        appendLine("厂商：${Build.MANUFACTURER}")
        appendLine("机型：${Build.MODEL}")
        appendLine("产品：${Build.PRODUCT}")
        appendLine("Android：${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("屏幕：${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels} @ ${context.resources.displayMetrics.densityDpi}dpi")
        appendLine("日志目录：${File(context.filesDir, "douk-logs").absolutePath}")
        appendLine("xLog 目录：${File(context.filesDir, "xlog").absolutePath}")
        appendLine("ACRA 目录：${runCatching { ReportLocator(context).unapprovedFolder.absolutePath }.getOrElse { "未初始化" }}")
    }

    private fun settingsJson(context: Context): String {
        val out = ByteArrayOutputStream()
        DoukPreset.export(context, out, DoukPreset.allSelection())
        return out.toString(Charsets.UTF_8.name())
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addDir(zip: ZipOutputStream, dir: File, prefix: String) {
        if (!dir.isDirectory) return
        dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_FILES_PER_DIR)
            ?.forEach { file ->
                val entryName = "$prefix/${file.name}"
                runCatching {
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }.onFailure { runCatching { zip.closeEntry() } }
            }
    }

    /** 给 UI 用的安全分享入口：IO 失败时返回异常，由调用方 Toast。 */
    fun shareSafely(context: Context): String = runCatching {
        share(context)
        "诊断日志已生成，正在打开分享面板"
    }.getOrElse { "生成诊断日志失败：${it.message}" }

    fun exportSafely(context: Context, uri: Uri): String = runCatching {
        exportTo(context, uri).getOrThrow()
        "诊断日志已导出"
    }.getOrElse { "导出诊断日志失败：${it.message}" }

    /** 兼容 API：把 ZIP 写到任意流，便于测试。 */
    fun writeTo(context: Context, out: OutputStream) {
        build(context).inputStream().use { it.copyTo(out) }
    }
}
