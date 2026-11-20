package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lutcamera.lutcamerapro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
        val notes = info.notes.lines().filter { it.isNotBlank() }.take(6).joinToString("\n")
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${info.version}")
            .setMessage(
                buildString {
                    append("当前版本：").append(BuildConfig.VERSION_NAME).append("\n")
                    append("更新源：").append(info.source)
                    if (notes.isNotBlank()) {
                        append("\n\n")
                        append(notes)
                    }
                }
            )
            .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(activity, info) }
            .setNegativeButton("以后再说", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, info: Info) {
        if (info.apkUrl.isNullOrBlank()) {
            Toast.makeText(activity, "更新清单没有提供 APK 下载地址", Toast.LENGTH_LONG).show()
            return
        }
        val progress = ProgressDialog(activity).apply {
            setTitle("正在下载更新")
            setMessage("0%")
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
        val owner = activity as? LifecycleOwner
        if (owner == null) {
            progress.dismiss()
            Toast.makeText(activity, "当前页面无法下载更新", Toast.LENGTH_LONG).show()
            return
        }
        owner.lifecycleScope.launch {
            val result = runCatching {
                download(activity, info) { percent ->
                    if (!activity.isFinishing) progress.setMessage("$percent%")
                }
            }
            runCatching { progress.dismiss() }
            result.onSuccess { file ->
                if (!install(activity, file)) {
                    Toast.makeText(activity, "下载完成，请允许安装未知来源应用后重试", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                Toast.makeText(activity, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 下载 APK 到 cache/updates；进度回调在主线程。 */
    suspend fun download(context: Context, info: Info, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val apkUrl = info.apkUrl?.trim().orEmpty()
            require(apkUrl.startsWith("http://") || apkUrl.startsWith("https://")) {
                "APK 地址必须是 http/https 直链"
            }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            val safeVersion = info.version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
                .ifBlank { "latest" }
            val target = File(dir, "LensLink-$safeVersion.apk")

            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LensLink-Android/${Build.VERSION.SDK_INT}")
                setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) error("下载返回 HTTP $code")
                val total = conn.contentLengthLong
                var copied = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
                if (target.length() <= 0L) error("下载到的文件为空")
                if (total > 0 && target.length() < total) error("下载不完整")
                target
            } finally {
                conn.disconnect()
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
