package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lutcamera.lutcamerapro.BuildConfig
import com.lutcamera.lutcamerapro.util.BoundedIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * 统一更新入口。
 *
 * 只从 GitHub 官方 Releases 检查版本；百度网盘只保留一个分享链接，
 * 由设置页直接打开，不参与版本检测，也不读取任何网盘清单。
 */
object UpdateCenter {
    /** 百度网盘备用下载：永久有效的文件夹分享，后续新版本放进同一文件夹即可。 */
    const val BAIDU_MIRROR_URL =
        "https://pan.baidu.com/share/init?surl=BIIX6VP7PJxVjlgmYiCwzw&pwd=lk01"

    data class Info(
        val version: String,
        val notes: String,
        val pageUrl: String?,
        val apkUrl: String?,
        val apkSize: Long,
        val apkSha256: String?,
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
                        version = i.tag,
                        notes = i.notes,
                        pageUrl = i.pageUrl,
                        apkUrl = i.apkUrl,
                        apkSize = i.apkSize,
                        apkSha256 = i.apkSha256,
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
     * 每次启动 App 都检查一次 GitHub Release；失败静默，只有发现新版本才弹窗。
     * 返回 true 表示本次确实弹出了更新对话框，调用方可以据此决定是否再弹别的提示。
     */
    suspend fun checkOnLaunch(activity: Activity): Boolean {
        val info = (check(BuildConfig.VERSION_NAME) as? Result.Success)?.info ?: return false
        if (!info.isNewer || info.apkUrl.isNullOrBlank()) return false
        // 「该版本不提示」只压制同一个版本；出现更新的版本仍会提示。
        if (info.version == DoukPrefs.ignoredUpdateVersion) return false
        withContext(Dispatchers.Main) {
            showLaunchDialog(activity, info)
        }
        return true
    }

    /**
     * 启动更新弹窗：圆角卡片，而不是系统 AlertDialog 的三段式。
     * 卡片 = 版本徽标 + 标题 + 当前→新版本 + 可滚动的更新说明 + 底部三个按钮。
     * 说明照原文全部显示，太长就在卡片里滑动，不再截前几行。
     */
    private fun showLaunchDialog(activity: Activity, info: Info) {
        if (activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        dialog.setContentView(buildUpdateCard(activity, info, dialog))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            val screen = activity.resources.displayMetrics
            val width = minOf((screen.widthPixels * 0.88f).toInt(), (560 * screen.density).toInt())
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
    }

    private fun buildUpdateCard(activity: Activity, info: Info, dialog: Dialog): View {
        val night = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun dpf(v: Int) = v * density

        val accent = runCatching { DoukColors.accentState.value.toArgb() }.getOrDefault(0xFFFF6900.toInt())
        val cardColor = if (night) 0xFF1F1F21.toInt() else 0xFFFFFFFF.toInt()
        val titleColor = if (night) 0xFFF4F5F7.toInt() else 0xFF17181A.toInt()
        val bodyColor = if (night) 0xFFB6BABF.toInt() else 0xFF5A5F66.toInt()
        val hintColor = 0xFF8C9299.toInt()
        val dividerColor = if (night) 0xFF2E3034.toInt() else 0xFFEDEEF0.toInt()
        val strokeColor = if (night) 0xFF3A3D41.toInt() else 0xFFD9DCE0.toInt()

        fun pill(label: String, fill: Int?, stroke: Int?, labelColor: Int, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                text = label
                textSize = 13.5f
                gravity = Gravity.CENTER
                setTextColor(labelColor)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dpf(12)
                    setColor(fill ?: Color.TRANSPARENT)
                    if (stroke != null) setStroke(dp(1), stroke)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpf(20)
                setColor(cardColor)
            }
        }

        // 头部：主色徽标 + 品牌小字
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }
        val badgeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        badgeRow.addView(TextView(activity).apply {
            text = "版本更新"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dpf(6)
                setColor(accent)
            }
        })
        badgeRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        badgeRow.addView(TextView(activity).apply {
            text = "连镜"
            textSize = 11f
            setTextColor(hintColor)
        })
        head.addView(badgeRow)
        head.addView(TextView(activity).apply {
            text = "发现新版本 ${info.version}"
            textSize = 20f
            setTextColor(titleColor)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
        })
        head.addView(TextView(activity).apply {
            text = "当前版本 ${BuildConfig.VERSION_NAME}　→　${info.version}"
            textSize = 12.5f
            setTextColor(hintColor)
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(head)

        // 通栏细分隔线
        root.addView(View(activity).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        // 更新说明：内容不足时按内容走，超出才限高并出现滚动条
        val body = TextView(activity).apply {
            text = info.notes.trim().ifBlank { "本版本没有提供更新说明。" }
            textSize = 13f
            setLineSpacing(dpf(5), 1.25f)
            setTextColor(bodyColor)
            setTextIsSelectable(true)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val cardWidth = minOf((metrics.widthPixels * 0.88f).toInt(), dp(560))
        body.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth - dp(40), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bodyHeight = minOf(body.measuredHeight, (metrics.heightPixels * 0.40f).toInt())
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bodyHeight))

        // 底部按钮：左「该版本不提示」，右「稍后 / 下载」
        root.addView(View(activity).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(16))
        }
        footer.addView(TextView(activity).apply {
            text = "该版本不提示"
            textSize = 12.5f
            setTextColor(hintColor)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isClickable = true
            setOnClickListener {
                DoukPrefs.ignoredUpdateVersion = info.version
                dialog.dismiss()
            }
        })
        footer.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(pill("稍后", null, strokeColor, titleColor) { dialog.dismiss() })
        footer.addView(pill("下载", accent, null, Color.WHITE) {
            dialog.dismiss()
            downloadAndInstall(activity, info)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
        root.addView(footer)

        return root
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

    /** 体积文案固定用 Locale.US 格式化，避免阿拉伯语等区域把小数点变成「٫」。 */
    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun downloadAndInstall(activity: Activity, info: Info) {
        if (info.apkUrl.isNullOrBlank()) {
            Toast.makeText(activity, "更新清单没有提供 APK 下载地址", Toast.LENGTH_LONG).show()
            return
        }
        val progress = ProgressDialog(activity).apply {
            setTitle("正在下载更新")
            // 首包回来之前不假装知道进度：GitHub 连不上时显示转圈而不是永远 0%。
            setMessage("连接 GitHub…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            isIndeterminate = true
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
                        if (p.percent >= 0) {
                            progress.isIndeterminate = false
                            progress.progress = p.percent
                        } else {
                            progress.isIndeterminate = true
                        }
                    }
                }
            }
            runCatching { progress.dismiss() }
            result.onSuccess { file ->
                if (!install(activity, file)) {
                    Toast.makeText(activity, "下载完成，请允许安装未知来源应用后重试", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) {
                    Toast.makeText(activity, "已取消下载", Toast.LENGTH_SHORT).show()
                } else {
                    showBaiduFallback(activity, it.message ?: "网络中断")
                }
            }
        }
    }

    /** 应用内下载失败时，给用户一个明确可用的备用路径，而不是只丢一句 Toast。 */
    fun showBaiduFallback(context: Context, reason: String) {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return
        AlertDialog.Builder(context)
            .setTitle("应用内下载失败")
            .setMessage("$reason\n\n可以改用百度网盘下载最新版本，提取码 lk01。")
            .setPositiveButton("打开百度网盘") { _, _ ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BAIDU_MIRROR_URL)))
                }.onFailure {
                    Toast.makeText(context, "没有可打开浏览器的应用", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        require(info.apkSize <= BoundedIo.MAX_UPDATE_APK_BYTES) { "更新包超过 128MB 上限" }
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
            require(total <= BoundedIo.MAX_UPDATE_APK_BYTES) { "更新包超过 128MB 上限" }
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
                        if (copied > BoundedIo.MAX_UPDATE_APK_BYTES) {
                            error("更新包超过 128MB 上限")
                        }
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
            if (target.length() > BoundedIo.MAX_UPDATE_APK_BYTES) {
                target.delete()
                error("更新包超过 128MB 上限")
            }
            if (total > 0 && target.length() < total) error("下载不完整（${sizeText(target.length())}/${
                sizeText(total)}）")
            // 装之前核对包名、versionCode、签名证书、以及可选的 GitHub digest。
            val archiveInfo = context.packageManager.getPackageArchiveInfo(
                target.absolutePath,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SIGNING_CERTIFICATES
            )
            if (archiveInfo == null || archiveInfo.packageName != context.packageName) {
                target.delete()
                error("安装包校验失败：不是连镜的更新包")
            }
            if (archiveInfo.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
                target.delete()
                error("安装包校验失败：versionCode 没有高于当前版本")
            }
            val installedSigner = signerSha256(context, null)
            val archiveSigner = signerSha256(context, target.absolutePath)
            if (installedSigner == null || archiveSigner == null || installedSigner != archiveSigner) {
                target.delete()
                error("安装包校验失败：签名证书与当前安装包不一致")
            }
            info.apkSha256?.let { expected ->
                val actual = sha256(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    target.delete()
                    error("安装包校验失败：SHA-256 不一致")
                }
            }
            target
        }
    }

    /** 读取已安装包或 APK 文件的第一个签名证书 SHA-256；用于拒绝同包名不同签名的更新包。 */
    private fun signerSha256(context: Context, archivePath: String?): String? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = if (archivePath != null) {
            context.packageManager.getPackageArchiveInfo(archivePath, flags)
        } else {
            runCatching { context.packageManager.getPackageInfo(context.packageName, flags) }.getOrNull()
        } ?: return null
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
            Toast.makeText(context, "请先允许「连镜」安装未知来源应用", Toast.LENGTH_LONG).show()
            openUnknownSourcesSettings(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
