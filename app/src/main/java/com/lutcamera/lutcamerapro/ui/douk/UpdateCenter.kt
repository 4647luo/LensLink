package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lutcamera.lutcamerapro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 统一更新入口。
 *
 * 只从 GitHub 官方 Releases 检查版本；百度网盘只保留一个分享链接，
 * 由设置页直接打开，不参与版本检测，也不读取任何网盘清单。
 */
object UpdateCenter {
    const val SOURCE_GITHUB = "GitHub 官方"

    /** 百度网盘备用下载：永久有效的文件夹分享，后续新版本放进同一文件夹即可。 */
    const val BAIDU_MIRROR_URL =
        "https://pan.baidu.com/share/init?surl=BIIX6VP7PJxVjlgmYiCwzw&pwd=lk01"

    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class Info(
        val source: String,
        val version: String,
        val notes: String,
        val pageUrl: String?,
        val apkUrl: String?,
        val apkSize: Long,
        val publishedAt: String,
        val isNewer: Boolean
    )

    sealed interface Result {
        data class Success(val info: Info) : Result
        data class NoRelease(val message: String) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        when (val r = GitHubUpdateChecker.check(currentVersion)) {
            is GitHubUpdateChecker.Result.Success -> {
                val i = r.info
                Result.Success(
                    Info(
                        source = SOURCE_GITHUB,
                        version = i.tag,
                        notes = i.notes,
                        pageUrl = i.pageUrl,
                        apkUrl = i.apkUrl,
                        apkSize = i.apkSize,
                        publishedAt = i.publishedAt,
                        isNewer = i.isNewer
                    )
                )
            }
            is GitHubUpdateChecker.Result.NoRelease -> Result.NoRelease(r.repoUrl)
            is GitHubUpdateChecker.Result.Failure -> Result.Failure(r.message)
        }
    }

    /**
     * 启动时检查一次。24 小时内不重复请求，失败静默，只有发现新版本才弹窗。
     */
    suspend fun checkOnLaunch(activity: Activity) {
        if (!DoukPrefs.checkUpdateOnLaunch) return
        val now = System.currentTimeMillis()
        val last = DoukPrefs.lastAutoUpdateCheckAt
        if (last > 0 && now - last < CHECK_INTERVAL_MS) return
        DoukPrefs.lastAutoUpdateCheckAt = now

        val info = (check(BuildConfig.VERSION_NAME) as? Result.Success)?.info ?: return
        if (!info.isNewer || info.apkUrl.isNullOrBlank()) return
        withContext(Dispatchers.Main) {
            showLaunchDialog(activity, info)
        }
    }

    private fun showLaunchDialog(activity: Activity, info: Info) {
        if (activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return
        // 更新说明照原文全部显示，不再只留前 6 行；太长就放进 ScrollView，能一路滑到结尾。
        val notes = info.notes.trim()
        val density = activity.resources.displayMetrics.density
        val pad = (22 * density).toInt()
        val body = TextView(activity).apply {
            text = buildString {
                append("当前版本：").append(BuildConfig.VERSION_NAME).append("\n")
                append("更新源：").append(info.source)
                if (notes.isNotBlank()) {
                    append("\n\n")
                    append(notes)
                }
            }
            textSize = 13.5f
            setLineSpacing(0f, 1.28f)
            setTextColor(0xFF333333.toInt())
            setTextIsSelectable(true)
            setPadding(pad, pad / 3, pad, pad / 3)
        }
        val screen = activity.resources.displayMetrics
        val textWidth = screen.widthPixels - pad * 2
        body.measure(
            View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // 内容不足屏幕四成时按内容高度走，超出才限高并出现滚动条
        val maxH = (screen.heightPixels * 0.42f).toInt()
        val viewH = minOf(body.measuredHeight + pad / 3 * 2, maxH)
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val holder = FrameLayout(activity).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, viewH))
        }
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${info.version}")
            .setView(holder)
            .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(activity, info) }
            .setNegativeButton("以后再说", null)
            .show()
    }

    /** 下载进度：百分比 + 已下字节 + 总字节 + 速度，三者一起给 UI，进度条才不会「一直零」。 */
    data class Progress(
        val percent: Int,
        val copiedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) {
        /** 「46% · 23.4/51.1 MB · 1.2 MB/s」；总量未知时只报已下载 */
        fun text(): String {
            val done = sizeText(copiedBytes)
            val total = if (totalBytes > 0) sizeText(totalBytes) else null
            val speed = if (speedBytesPerSec > 0) "${sizeText(speedBytesPerSec)}/s" else null
            val head = if (total != null) "$percent% · $done/$total" else "已下载 $done"
            return listOfNotNull(head, speed).joinToString(" · ")
        }
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format("%.0f KB", bytes / 1024.0)
    }

    private fun downloadAndInstall(activity: Activity, info: Info) {
        if (info.apkUrl.isNullOrBlank()) {
            Toast.makeText(activity, "更新清单没有提供 APK 下载地址", Toast.LENGTH_LONG).show()
            return
        }
        val total0 = if (info.apkSize > 0) info.apkSize else 0L
        val progress = ProgressDialog(activity).apply {
            setTitle("正在下载更新")
            setMessage(if (total0 > 0) "0% · 0 B/${sizeText(total0)}" else "连接中…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        val owner = activity as? LifecycleOwner
        if (owner == null) {
            Toast.makeText(activity, "当前页面无法下载更新", Toast.LENGTH_LONG).show()
            return
        }
        var job: Job? = null
        progress.setButton(DialogInterface.BUTTON_NEGATIVE, "取消") { _, _ ->
            runCatching { job?.cancel() }
            runCatching { progress.dismiss() }
        }
        progress.show()
        job = owner.lifecycleScope.launch {
            val result = runCatching {
                download(activity, info) { p ->
                    if (!activity.isFinishing) {
                        progress.setMessage(p.text())
                        if (p.percent >= 0) progress.progress = p.percent
                    }
                }
            }
            runCatching { progress.dismiss() }
            result.onSuccess { file ->
                if (!install(activity, file)) {
                    Toast.makeText(activity, "下载完成，请允许安装未知来源应用后重试", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                val msg = if (it is kotlinx.coroutines.CancellationException) "已取消下载" else "下载失败：${it.message}"
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * GitHub 用的是专用客户端：连接/读超时按大文件放宽，不挂轻量请求那套 2.5 秒超时，
     * 也不会因为日志拦截器把每个字节块都记一遍而拖慢下载。
     */
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 下载 APK 到 cache/updates；进度回调在主线程。
     * 总量优先取 Content-Length，拿不到就用 Release 清单里的 apkSize，
     * 这样 GitHub 走 chunked 传输时进度条照样会动。
     */
    suspend fun download(
        context: Context,
        info: Info,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val apkUrl = info.apkUrl?.trim().orEmpty()
        // 只接受 https：以前允许 http，局域网里被劫持就能塞一个别的安装包进来。
        require(apkUrl.startsWith("https://")) { "APK 地址必须是 https 直链" }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val safeVersion = info.version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
            .ifBlank { "latest" }
        val target = File(dir, "lianjing-$safeVersion.apk")

        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "LianJing-Android/${Build.VERSION.SDK_INT}")
            .header("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
            // 不要 gzip：APK 本来就压过，解压反而多一道，还会隐藏 Content-Length
            .header("Accept-Encoding", "identity")
            .get()
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载返回 HTTP ${response.code}")
            val body = response.body ?: error("下载返回空响应")
            val total = sequenceOf(response.header("Content-Length")?.toLongOrNull(), body.contentLength(), info.apkSize)
                .filterNotNull().firstOrNull { it > 0 } ?: 0L
            var copied = 0L
            var lastPercent = -2
            var lastEmitMs = 0L
            val startedAt = System.currentTimeMillis()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        // 取消按钮 / 页面销毁时，下一块数据之前就退出
                        ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        val percent = if (total > 0) ((copied * 100) / total).toInt().coerceIn(0, 100) else -1
                        val elapsed = ((now - startedAt) / 1000L).coerceAtLeast(1L)
                        val speed = copied / elapsed
                        // 进度变了才回调，或者每 400ms 刷一次速度，避免刷 UI 掉帧
                        if (percent != lastPercent || now - lastEmitMs > 400L) {
                            lastPercent = percent
                            lastEmitMs = now
                            withContext(Dispatchers.Main) {
                                onProgress(Progress(percent, copied, total, speed))
                            }
                        }
                    }
                }
            }
            ensureActive()
            if (target.length() <= 0L) error("下载到的文件为空")
            if (total > 0 && target.length() < total) error("下载不完整（${sizeText(target.length())}/${
                sizeText(total)}）")
            // 装之前先核对包名：下载源被中间人替换成别的 App 时，系统安装器是照装的。
            val archiveInfo = context.packageManager.getPackageArchiveInfo(
                target.absolutePath,
                android.content.pm.PackageManager.GET_ACTIVITIES
            )
            if (archiveInfo == null || archiveInfo.packageName != context.packageName) {
                target.delete()
                error("安装包校验失败：不是连镜的更新包")
            }
            target
        }
    }

    /** 交给系统安装器；未授权「安装未知应用」时先跳到对应设置页。 */
    fun install(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) {
            Toast.makeText(context, "安装包不存在或为空", Toast.LENGTH_LONG).show()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(context, "请先允许 LensLink 安装未知来源应用", Toast.LENGTH_LONG).show()
            openUnknownSourcesSettings(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        return runCatching { context.startActivity(intent); true }.getOrElse {
            Toast.makeText(context, "无法打开系统安装器：${it.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "请到系统设置里允许安装未知来源应用", Toast.LENGTH_LONG).show()
        }
    }
}
