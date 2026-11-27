package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.lutcamera.lutcamerapro.network.nikon.ble.DoukBleKeepAliveService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.lutcamera.lutcamerapro.R
import com.lutcamera.lutcamerapro.log.DoukDiagnostics
import com.lutcamera.lutcamerapro.log.DoukLog
import com.lutcamera.lutcamerapro.log.DoukPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val APP_VERSION = com.lutcamera.lutcamerapro.BuildConfig.VERSION_NAME
private const val FEEDBACK_MAIL = "lianjingup@outlook.com"

/**
 * 把「支持作者」页的赞赏码原图写入系统相册。
 * 图片来自 drawable-nodpi 的完整原图（1280×1280），这里不做任何裁切或缩放。
 */
internal fun saveSupportQrToGallery(context: Context, bitmap: Bitmap): Boolean {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val name = "lianjing-support-$stamp.jpg"
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/连镜"
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    return try {
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val output = resolver.openOutputStream(uri)
        if (output == null) {
            resolver.delete(uri, null, null)
            return false
        }
        val written = output.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)
        }
        if (!written) {
            resolver.delete(uri, null, null)
            return false
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
        true
    } catch (_: Exception) {
        false
    }
}

/** 导入滤镜可归入的分类；内置滤镜固定「自带」。 */
private val FILTER_CATEGORIES = listOf("未分类", "人像", "风光", "胶片", "黑白", "自定义")

/**
 * 资源页的一组文件（LUT 或 滤镜）：每条带「通用 / 图片 / 视频」适用范围切换。
 * tick 只用来触发重组，分类与适用范围都落在 DoukPrefs。
 */
@Composable
private fun ResourceGroup(
    title: String,
    subtitle: String,
    files: List<File>,
    tick: Int,
    onChange: () -> Unit,
    onDelete: (File) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
        Text(subtitle, fontSize = 11.sp, color = DoukColors.textTertiary)
        Spacer(modifier = Modifier.height(8.dp))
        if (files.isEmpty()) {
            SettingCard {
                Text(
                    "这一类还没有文件。",
                    fontSize = 12.sp,
                    color = DoukColors.textTertiary,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            SettingCard {
            files.forEachIndexed { index, f ->
                if (index > 0) SettingDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.name, fontSize = 14.sp, color = DoukColors.textPrimary)
                            Text(
                                DoukPrefs.readableSize(f.length()),
                                fontSize = 11.sp,
                                color = DoukColors.textSecondary
                            )
                        }
                        TextButton(onClick = { onDelete(f) }) {
                            Text("删除", fontSize = 12.sp, color = DoukColors.textSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val use = DoukPrefs.resourceUse(f.name)
                        RESOURCE_USES.forEach { u ->
                            val on = use == u
                            Text(
                                when (u) {
                                    "通用" -> "图片 + 视频"
                                    "图片" -> "仅图片"
                                    else -> "仅视频"
                                },
                                fontSize = 11.sp,
                                color = if (on) Color(0xFFE08A2E) else DoukColors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(if (on) Color(0xFFFFEEDC) else DoukColors.surfaceMuted)
                                    .clickable {
                                        DoukPrefs.setResourceUse(f.name, u)
                                        onChange()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        }
        // tick 仅用于让 Compose 认识「已变更」，实际数据来自 DoukPrefs
        if (tick < 0) Spacer(modifier = Modifier.size(0.dp))
    }
}

/** 一个「标题 + 若干选项」的选择弹窗，设置页里十几种下拉都用它，避免重复写 AlertDialog */
private data class ValuePick(
    val title: String,
    val options: List<String>,
    val current: String,
    val onPick: (String) -> Unit
)

@Composable
fun DoukSettingsScreen(state: DoukState) {
    val context = LocalContext.current
    // 设置主页的滚动位置必须比二级页活得久：
    // 以前 rememberScrollState() 写在主页分支里，进二级页就出组合、返回后回到顶部。
    val settingsScroll = rememberScrollState()

    // ===== 设备 =====
    var autoConnect by remember { mutableStateOf(DoukPrefs.autoConnectKnown) }
    var known by remember { mutableStateOf(DoukPrefs.knownDevices()) }
    var showAddButton by remember { mutableStateOf(DoukPrefs.showAddDeviceButton) }
    // 蓝牙：连接过的设备 / 默认设备（不再提供「连上相机后自动连蓝牙」：尼康同一时间只允许一路连接）
    var bleKnown by remember { mutableStateOf(DoukPrefs.bleDevices()) }
    var bleDefault by remember { mutableStateOf(DoukPrefs.bleDefaultName) }
    var showBle by remember { mutableStateOf(false) }
    var showBleGpsCard by remember { mutableStateOf(DoukPrefs.showBleGpsCard) }
    var showBleRemoteCard by remember { mutableStateOf(DoukPrefs.showBleRemoteCard) }

    // ===== 下载 =====
    var autoDl by remember { mutableStateOf(DoukPrefs.autoDownload) }
    var dlSize by remember { mutableStateOf(DoukPrefs.downloadSize) }
    var dlFormat by remember { mutableStateOf(DoukPrefs.downloadFormat) }
    var dlDir by remember { mutableStateOf(DoukPrefs.downloadDir) }
    var lockedOnly by remember { mutableStateOf(DoukPrefs.lockedOnly) }
    var lkSize by remember { mutableStateOf(DoukPrefs.lockedSize) }
    var lkFormat by remember { mutableStateOf(DoukPrefs.lockedFormat) }
    var lkDir by remember { mutableStateOf(DoukPrefs.lockedDir) }
    var dlVideoSize by remember { mutableStateOf(DoukPrefs.downloadVideoSize) }
    var dlVideoFormat by remember { mutableStateOf(DoukPrefs.downloadVideoFormat) }
    var lkVideoSize by remember { mutableStateOf(DoukPrefs.lockedVideoSize) }
    var lkVideoFormat by remember { mutableStateOf(DoukPrefs.lockedVideoFormat) }
    var lkAuto by remember { mutableStateOf(DoukPrefs.lockedAutoImport) }
    var lkAsk by remember { mutableStateOf(DoukPrefs.lockedAskBeforeImport) }
    var lkNewOnly by remember { mutableStateOf(DoukPrefs.lockedNewOnly) }
    /** 下载二级页：null = 设置主页；"auto" = 自动下载；"locked" = 下载保护文件 */
    var downloadPage by remember { mutableStateOf<String?>(null) }
    var saveFormat by remember { mutableStateOf(DoukPrefs.saveFormat) }
    // 图库默认打开来源：相机内 / 应用内 / 手机内
    var galleryDefault by remember { mutableStateOf(DoukPrefs.galleryDefaultSource) }
    var galleryPreload by remember { mutableStateOf(DoukPrefs.galleryCameraPreload) }
    var galleryLoadAll by remember { mutableStateOf(DoukPrefs.galleryLoadAll) }
    // 参数卡片分组：卡片列表页 / 单卡编辑页共用的模式与当前卡 id
    var groupMode by remember { mutableStateOf(CaptureMode.STILL) }
    var editingGroupId by remember { mutableStateOf<String?>(null) }

    // ===== 通用 =====
    var language by remember { mutableStateOf(DoukPrefs.language) }
    var darkMode by remember { mutableStateOf(DoukPrefs.darkMode) }
    var themeColor by remember { mutableStateOf(DoukPrefs.themeColor) }
    var glass by remember { mutableStateOf(DoukPrefs.glassEffect) }
    var materialAlpha by remember { mutableFloatStateOf(DoukPrefs.materialAlpha) }
    var materialBlur by remember { mutableFloatStateOf(DoukPrefs.materialBlur) }
    var uiContrast by remember { mutableFloatStateOf(DoukPrefs.uiContrast) }
    var previewOnCapture by remember { mutableStateOf(DoukPrefs.capturePreview) }
    var photoPath by remember { mutableStateOf(DoukPrefs.cameraPhotoPath) }
    var lutCount by remember { mutableStateOf(0) }
    var lutBytes by remember { mutableStateOf(0L) }
    var filterFiles by remember { mutableStateOf(listOf<String>()) }
    var categoryTick by remember { mutableStateOf(0) }

    // 目录扫描是 IO：首帧不要在主线程数文件，先给 0 再异步补上
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val list = DoukPrefs.importedResources(context)
            val bytes = DoukPrefs.dirSize(DoukPrefs.lutDir(context))
            withContext(Dispatchers.Main) {
                lutCount = list.size
                lutBytes = bytes
                filterFiles = list.map { it.name }
            }
        }
    }

    // ===== 弹窗状态 =====
    var pick by remember { mutableStateOf<ValuePick?>(null) }
    var showKnown by remember { mutableStateOf(false) }
    /** 通用与控制面板的二级页：null = 设置主页；其余是各个独立全屏页 */
    var subPage by remember { mutableStateOf<String?>(null) }
    var showUpdate by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    /** 关于页里的二级内容（更新/日志/条款/捐赠）同样走整屏页，不再弹窗 */
    var aboutInfo by remember { mutableStateOf<String?>(null) }

    // 设备页相机实物照片：选一张存进应用私有目录，设备页与设置页共用
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val dst = File(context.filesDir, "camera-photo.jpg")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
            DoukPrefs.cameraPhotoPath = dst.absolutePath
            photoPath = dst.absolutePath
            state.photoVersion++
            Toast.makeText(context, "已更换设备页相机照片", Toast.LENGTH_SHORT).show()
        }
    }

    // LUT / 滤镜导入：选文件后拷进 filesDir/lut，图库与预览共用
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = displayName(context, uri) ?: "lut-${System.currentTimeMillis()}.cube"
            val dst = File(DoukPrefs.lutDir(context), name)
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }.onSuccess {
                lutCount = DoukPrefs.importedResources(context).size
                lutBytes = DoukPrefs.dirSize(DoukPrefs.lutDir(context))
                filterFiles = DoukPrefs.importedResources(context).map { it.name }
                Toast.makeText(context, "已导入 $name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 预设导入 / 导出 =====
    var showPresetExport by remember { mutableStateOf(false) }
    var presetSelection by remember { mutableStateOf(DoukPreset.allSelection()) }
    val scope = rememberCoroutineScope()

    val presetExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        DoukPreset.exportBundle(context, out, presetSelection).getOrThrow()
                    } ?: error("无法写入所选文件")
                }
            }
            Toast.makeText(
                context,
                result.fold(
                    { "预设包已导出（含 LUT/滤镜文件），可在另一台设备导入" },
                    { "导出预设失败：${it.message}" }
                ),
                Toast.LENGTH_LONG
            ).show()
            if (result.isSuccess) showPresetExport = false
        }
    }

    val presetImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        DoukPreset.import(context, input).getOrThrow()
                    } ?: error("无法读取所选文件")
                }
            }
            result.onSuccess { (count, names) ->
                Toast.makeText(
                    context,
                    "已导入 $count 组：${names.joinToString("、")}",
                    Toast.LENGTH_LONG
                ).show()
                // 设置页大量 remember 状态，重建一次 Activity 才能全部刷新
                context.findActivity()?.recreate()
            }.onFailure {
                Toast.makeText(context, "导入预设失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val diagnosticsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) { DoukDiagnostics.exportSafely(context, uri) }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    if (aboutInfo != null) {
        val back = { aboutInfo = null }
        when (aboutInfo) {
            "update" -> SettingsSubPage(title = "检查更新", onBack = back) {
                val context = LocalContext.current
                var result by remember { mutableStateOf<UpdateCenter.Result?>(null) }
                var checking by remember { mutableStateOf(false) }
                var downloading by remember { mutableStateOf(false) }
                var progress by remember { mutableIntStateOf(0) }
                // 下载细节：百分比 / 已下载 / 总量 / 速度，走 GitHub 时进度肉眼可见
                var progressDetail by remember { mutableStateOf("") }

                val runCheck: () -> Unit = {
                    if (!checking) {
                        scope.launch {
                            checking = true
                            result = UpdateCenter.check(APP_VERSION)
                            checking = false
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    checking = true
                    result = UpdateCenter.check(APP_VERSION)
                    checking = false
                }

                SettingCard {
                    SettingRow(
                        if (checking) "正在检查…" else "立即检查",
                        "每次打开 App 都会自动检查；也可以现在从 GitHub 读取最新 Release",
                        enabled = !checking
                    ) { runCheck() }
                    SettingDivider()
                    SettingRow(
                        "百度网盘分享链接",
                        "GitHub 连不上时从这里下载，提取码 lk01；新版本会放进同一文件夹",
                        value = "打开"
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCenter.BAIDU_MIRROR_URL))
                            )
                        }.onFailure {
                            Toast.makeText(context, "没有可打开浏览器的应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                val lines = when (val r = result) {
                    null -> listOf("当前版本：$APP_VERSION", "正在检查最新版本…")
                    is UpdateCenter.Result.Success -> {
                        val info = r.info
                        buildList {
                            add("当前版本：$APP_VERSION")
                            if (info.isNewer) {
                                add("发现新版本：${info.version}")
                                if (info.apkSize > 0) add("安装包：${"%.1f".format(info.apkSize / 1048576.0)} MB")
                                if (info.publishedAt.isNotBlank()) add("发布日期：${info.publishedAt}")
                                if (info.apkUrl.isNullOrBlank()) {
                                    add("GitHub Release 没有提供 APK 直链，无法应用内下载。")
                                }
                                add("")
                                if (info.notes.isNotBlank()) {
                                    add("更新说明：")
                                    addAll(info.notes.lines().take(8))
                                }
                            } else {
                                add("已是最新版本：$APP_VERSION")
                                add("远端版本：${info.version}")
                                if (info.publishedAt.isNotBlank()) add("发布日期：${info.publishedAt}")
                                add("")
                                add("当前已是最新版本，无需更新。")
                            }
                        }
                    }
                    is UpdateCenter.Result.NoRelease -> listOf(
                        "当前版本：$APP_VERSION",
                        "GitHub 上还没有发布版本。",
                        r.message
                    )
                    is UpdateCenter.Result.Failure -> listOf(
                        "当前版本：$APP_VERSION",
                        "检查失败：${r.message}",
                        "检查网络后，点「立即检查」重试。"
                    )
                }
                InfoLines(lines)

                val info = (result as? UpdateCenter.Result.Success)?.info
                if (info != null && info.isNewer && !info.apkUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .doukFlatSurface(RoundedCornerShape(18.dp))
                            .clickable(enabled = !downloading) {
                                scope.launch {
                                    downloading = true
                                    progress = 0
                                    progressDetail = "正在连接 GitHub…"
                                    val downloadResult = runCatching {
                                        UpdateCenter.download(context, info) { p ->
                                            progress = p.percent.coerceAtLeast(0)
                                            progressDetail = p.text()
                                        }
                                    }
                                    downloading = false
                                    progressDetail = ""
                                    downloadResult.onSuccess { file ->
                                        if (!UpdateCenter.install(context, file)) {
                                            Toast.makeText(
                                                context,
                                                "下载完成，请允许安装未知来源应用后重试",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }.onFailure {
                                        if (it is kotlinx.coroutines.CancellationException) {
                                            Toast.makeText(context, "已取消下载", Toast.LENGTH_SHORT).show()
                                        } else {
                                            UpdateCenter.showBaiduFallback(context, it.message ?: "网络中断")
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (downloading) {
                                progressDetail.ifBlank { "正在下载 ${progress}%" }
                            } else "下载并安装最新版本",
                            style = DoukType.body,
                            color = DoukColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = DoukColors.textTertiary
                        )
                    }
                }
            }
            "changelog" -> SettingsSubPage(title = "更新日志", onBack = back) {
                InfoLines(changelogFor(APP_VERSION))
            }
            "terms" -> SettingsSubPage(title = "使用条款", onBack = back) {
                InfoLines(TERMS_LINES)
            }
            "donate" -> SettingsSubPage(title = "支持作者", onBack = back) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val qr by produceState<Bitmap?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            BitmapFactory.decodeResource(context.resources, R.drawable.douk_support_qr)
                        }.getOrNull()
                    }
                }
                var saving by remember { mutableStateOf(false) }
                var lastSaveAt by remember { mutableStateOf(0L) }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "连镜 由一个人维护，免费、无广告、无内购。",
                        style = DoukType.body,
                        color = DoukColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    val qrBitmap = qr
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "铜锣烧的赞赏码",
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .aspectRatio(1f)
                                .background(Color.White)
                                .pointerInput(qrBitmap) {
                                    detectTapGestures(
                                        onLongPress = {
                                            val now = System.currentTimeMillis()
                                            if (!saving && now - lastSaveAt > 1200L) {
                                                saving = true
                                                lastSaveAt = now
                                                scope.launch {
                                                    val ok = withContext(Dispatchers.IO) {
                                                        saveSupportQrToGallery(context, qrBitmap)
                                                    }
                                                    saving = false
                                                    Toast.makeText(
                                                        context,
                                                        if (ok) "赞赏码原图已保存到相册" else "保存失败，请稍后再试",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    )
                                },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .aspectRatio(1f)
                                .background(Color(0xFFF3F4F6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("正在载入赞赏码…", style = DoukType.label, color = DoukColors.textTertiary)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "长按图片可保存原图到系统相册，不裁切",
                        style = DoukType.label,
                        color = DoukColors.textTertiary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "赞赏完全自愿，不影响任何功能；不打赏也能用全部功能。",
                        style = DoukType.label,
                        color = DoukColors.textTertiary
                    )
                }
            }
        }
    } else if (showPresetExport) {
        PresetExportPanel(
            selection = presetSelection,
            onSelectionChange = { presetSelection = it },
            onBack = { showPresetExport = false },
            onExport = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                presetExportLauncher.launch("lianjing-preset-$stamp.zip")
            }
        )
    } else if (showFeedback) {
        FeedbackPanel(
            onBack = { showFeedback = false },
            onSaveDiagnostics = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                diagnosticsExportLauncher.launch("lianjing-diagnostics-$stamp.zip")
            },
            onSendMail = { withLogs ->
                if (withLogs) {
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            DoukDiagnostics.email(
                                context,
                                FEEDBACK_MAIL,
                                "连镜 反馈（$APP_VERSION）",
                                feedbackBody()
                            ).fold(
                                { "已生成诊断包并拉起邮件客户端，请确认收件人后发送" },
                                { "发送失败：${it.message}" }
                            )
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                } else {
                    sendFeedback(context)
                }
            }
        )
    } else if (showAbout) {
        AboutPanel(
            onBack = { showAbout = false },
            onCheckUpdate = { aboutInfo = "update" },
            onChangelog = { aboutInfo = "changelog" },
            onFeedback = { showFeedback = true },
            onTerms = { aboutInfo = "terms" },
            onDonate = { aboutInfo = "donate" }
        )
    } else if (downloadPage == "auto") {
        DownloadPanel(
            title = "自动下载",
            enabledTitle = "自动下载图片与视频",
            enabledSubtitle = "连上相机后，把新拍的照片和视频自动回传到手机",
            enabled = autoDl,
            onEnabled = {
                autoDl = it
                DoukPrefs.autoDownload = it
                state.vm.setAutoTransfer(it && DoukPrefs.downloadFormat != "不下载")
            },
            options = listOf(
                DownloadOption(
                    "图片大小",
                    "原图更清晰，缩略图更快",
                    dlSize,
                    listOf("原图", "仅缩略图")
                ) { v -> dlSize = v; DoukPrefs.downloadSize = v },
                DownloadOption(
                    "图片格式",
                    "选「不下载」就只回传视频，不接收相机里的 JPG/RAW",
                    dlFormat,
                    listOf("JPG", "JPG + RAW", "全部格式", "不下载")
                ) { v ->
                    dlFormat = v
                    DoukPrefs.downloadFormat = v
                    state.vm.setAutoTransfer(autoDl && v != "不下载")
                },
                DownloadOption(
                    "视频大小",
                    "原视频画质最好，缩略图更快",
                    dlVideoSize,
                    listOf("原视频", "仅缩略图")
                ) { v -> dlVideoSize = v; DoukPrefs.downloadVideoSize = v },
                DownloadOption(
                    "视频格式",
                    "选「不下载」就只回传图片",
                    dlVideoFormat,
                    listOf("MOV", "MP4", "全部格式", "不下载")
                ) { v -> dlVideoFormat = v; DoukPrefs.downloadVideoFormat = v },
                DownloadOption(
                    "保存位置",
                    "App 相册或手机相册",
                    dlDir,
                    listOf("应用相册", "系统相册", "Download")
                ) { v -> dlDir = v; DoukPrefs.downloadDir = v }
            ),
            optionsEnabled = autoDl,
            onBack = { downloadPage = null }
        )
    } else if (downloadPage == "locked") {
        // 进这一页时按当前设置静默核一遍：状态行显示的是这次的真实结果，
        // 而不是上一轮留下的旧文案（旧文案会把「已导入过」误报成「格式不对」）。
        LaunchedEffect(Unit) {
            if (!(state.isConnected && DoukPrefs.lockedOnly && DoukPrefs.lockedAutoImport)) {
                return@LaunchedEffect
            }
            // 后台巡检可能刚好在跑，等它这一轮结束再扫，否则会直接 return 掉、状态行还是旧的
            repeat(4) {
                if (!state.vm.isImportingLocked) {
                    state.vm.importLockedPhotos(
                        onlyAfterConnect = DoukPrefs.lockedNewOnly,
                        quiet = true
                    )
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(1_500)
            }
        }
        DownloadPanel(
            title = "下载保护文件",
            enabledTitle = "只下载相机里锁定的文件",
            enabledSubtitle = "按相机的锁定标记挑选",
            enabled = lockedOnly,
            onEnabled = {
                lockedOnly = it
                DoukPrefs.lockedOnly = it
                // 只保留顶部一个总开关：它同时决定「连接后自动导入」是否工作，
                // 不再在下面重复放一个「连接相机后导入锁定照片」开关。
                lkAuto = it
                DoukPrefs.lockedAutoImport = it
                // 用户刚打开这个开关时，相机里已有的锁定文件就是他要的文件。
                // 「仅连接后新增」的分界只用于之后的自动巡检，这里先无条件导入全部，
                // 否则已连接状态下打开开关什么都不会发生。
                if (it) {
                    state.vm.importLockedPhotos(onlyAfterConnect = false)
                }
            },
            options = listOf(
                DownloadOption(
                    "图片大小",
                    "原图更清晰，缩略图更快",
                    lkSize,
                    listOf("原图", "仅缩略图")
                ) { v -> lkSize = v; DoukPrefs.lockedSize = v },
                DownloadOption(
                    "图片格式",
                    "不下载图片时只导入视频",
                    lkFormat,
                    listOf("JPG", "RAW", "JPG + RAW", "全部格式", "不下载")
                ) { v -> lkFormat = v; DoukPrefs.lockedFormat = v },
                DownloadOption(
                    "视频大小",
                    "原视频画质最好，缩略图更快",
                    lkVideoSize,
                    listOf("原视频", "仅缩略图")
                ) { v -> lkVideoSize = v; DoukPrefs.lockedVideoSize = v },
                DownloadOption(
                    "视频格式",
                    "不下载视频时只导入图片",
                    lkVideoFormat,
                    listOf("MOV", "MP4", "全部格式", "不下载")
                ) { v -> lkVideoFormat = v; DoukPrefs.lockedVideoFormat = v },
                DownloadOption(
                    "保存位置",
                    "App 相册或手机相册",
                    lkDir,
                    listOf("应用相册", "系统相册", "Download")
                ) { v -> lkDir = v; DoukPrefs.lockedDir = v }
            ),
            optionsEnabled = lockedOnly,
            footnote = if (lockedOnly) {
                "自动导入已开启：" +
                    (if (lkNewOnly) "只导入连接后新增的锁定文件" else "导入相机内全部锁定文件") +
                    (if (lkAsk) "，导入前先确认。" else "。")
            } else {
                "总开关关闭：不会自动导入。"
            },
            extraContent = {
                SettingCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (lockedOnly) 1f else 0.38f)
                            .padding(horizontal = DoukSpace.md, vertical = 12.dp)
                    ) {
                        Text(
                            "导入范围",
                            style = DoukType.body,
                            fontWeight = FontWeight.Medium,
                            color = DoukColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            if (lkNewOnly) "只导入连接后新增的文件"
                            else "导入相机内所有已锁定文件",
                            style = DoukType.caption,
                            color = DoukColors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(9.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "相机内全部" to false,
                                "仅连接后新增" to true
                            ).forEach { (label, onlyNew) ->
                                val active = lkNewOnly == onlyNew
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(DoukShapes.pill)
                                        .background(
                                            if (active) DoukColors.accentSoft
                                            else DoukColors.surfaceMuted
                                        )
                                        .clickable(enabled = lockedOnly) {
                                            lkNewOnly = onlyNew
                                            DoukPrefs.lockedNewOnly = onlyNew
                                        }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        style = DoukType.caption,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (active) DoukColors.accent else DoukColors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                    SettingDivider()
                    SettingSwitchRow(
                        "导入前先询问",
                        "导入前先确认数量",
                        lkAsk,
                        enabled = lockedOnly
                    ) {
                        lkAsk = it
                        DoukPrefs.lockedAskBeforeImport = it
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                SheetPrimaryButton(
                    "立即导入相机内的全部锁定文件",
                    enabled = lockedOnly
                ) {
                    state.vm.importLockedPhotos(onlyAfterConnect = false)
                }
                if (state.vm.lockedImportStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        state.vm.lockedImportStatus,
                        fontSize = 12.sp,
                        color = DoukColors.textSecondary,
                        modifier = Modifier.padding(horizontal = DoukSpace.xxs)
                    )
                }
            },
            onBack = { downloadPage = null }
        )
    } else if (pick != null) {
        val p = pick!!
        SettingsSubPage(title = p.title, onBack = { pick = null }) {
            SettingCard {
                p.options.forEachIndexed { index, option ->
                    if (index > 0) SettingDivider()
                    val active = option == p.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                p.onPick(option)
                                pick = null
                            }
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) DoukColors.accent else DoukColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = DoukColors.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    } else if (subPage != null) {
        val back: () -> Unit = { subPage = null }
        when (subPage) {
            // ===== 资源 · LUT 与滤镜 =====
            "editor" -> SettingsSubPage(title = "图像编辑", onBack = back) {
                var format by remember { mutableStateOf(DoukPrefs.editorFormat) }
                var quality by remember { mutableStateOf(DoukPrefs.editorQuality.toString()) }
                var preview by remember { mutableStateOf(DoukPrefs.editorPreview.toString()) }
                var grid by remember { mutableStateOf(DoukPrefs.editorGrid) }
                var compare by remember { mutableStateOf(DoukPrefs.editorCompare) }
                Text(
                    "这些是图库「编辑」页的默认行为。编辑本身的所有调节、曲线与滤镜都在编辑页里，保存时按这里选的格式和质量导出。",
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                SettingCard {
                    SettingRow("导出格式", "JPG 体积小；PNG 无损", format) {
                        pick = ValuePick("导出格式", listOf("JPG", "PNG"), format) { v ->
                            format = v; DoukPrefs.editorFormat = v
                        }
                    }
                    SettingDivider()
                    SettingRow("导出质量", "只对 JPG 生效", "$quality%") {
                        pick = ValuePick("导出质量", listOf("90", "95", "100"), quality) { v ->
                            quality = v; DoukPrefs.editorQuality = v.toIntOrNull() ?: 95
                        }
                    }
                    SettingDivider()
                    SettingRow("预览分辨率", "滑动时的计算长边；保存仍用原图", "${preview}px") {
                        pick = ValuePick("预览分辨率", listOf("720", "1080", "1440", "2048", "3072", "4096"), preview) { v ->
                            preview = v; DoukPrefs.editorPreview = v.toIntOrNull() ?: 3072
                        }
                    }
                    SettingDivider()
                    SettingSwitchRow(
                        title = "显示九宫格",
                        subtitle = "编辑画面上叠一层三分线，方便构图",
                        checked = grid,
                        onCheckedChange = { grid = it; DoukPrefs.editorGrid = it }
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        title = "按住对比原图",
                        subtitle = "长按画面时暂时显示未编辑的原图",
                        checked = compare,
                        onCheckedChange = { compare = it; DoukPrefs.editorCompare = it }
                    )
                    SettingDivider()
                    SettingRow(
                        "滤镜分类",
                        "给资源页导入的滤镜分组，编辑器按分类筛选",
                        "${filterFiles.size} 个"
                    ) { subPage = "filterCategory" }
                }
            }
            "filterCategory" -> SettingsSubPage(title = "滤镜分类", onBack = back) {
                Text(
                    "只对资源页导入的滤镜生效。分类会出现在编辑器滤镜页顶部的分类条里，内置的 18 个滤镜固定在「自带」。",
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 读取一次 tick，保证分类改动后这一页立即重绘
                val catTick = categoryTick
                if (catTick >= 0 && filterFiles.isEmpty()) {
                    SettingCard {
                        Text(
                            "还没有导入滤镜。先到「资源」页导入 .cube / .3dl / .look 或尼康 .NP3 / .NCP 文件。",
                            fontSize = 13.sp,
                            color = DoukColors.textTertiary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    filterFiles.forEach { name ->
                        SettingCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(name, fontSize = 14.sp, color = DoukColors.textPrimary)
                                Spacer(modifier = Modifier.height(10.dp))
                                FILTER_CATEGORIES.chunked(3).forEach { rowCats ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowCats.forEach { cat ->
                                            val on = DoukPrefs.filterCategory(name) == cat
                                            Text(
                                                cat,
                                                fontSize = 12.sp,
                                                color = if (on) Color(0xFFE08A2E) else DoukColors.textSecondary,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(percent = 50))
                                                    .background(
                                                        if (on) Color(0xFFFFEEDC)
                                                        else DoukColors.surfaceMuted
                                                    )
                                                    .clickable {
                                                        DoukPrefs.setFilterCategory(name, cat)
                                                        categoryTick += 1
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                                            )
                                        }
                                        repeat(3 - rowCats.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }
            "resources" -> SettingsSubPage(title = "资源", onBack = back) {
                // 不再内置 LUT：资源页只读取用户自己导入的文件。
                LaunchedEffect(Unit) {
                    lutCount = DoukPrefs.importedResources(context).size
                    lutBytes = DoukPrefs.dirSize(DoukPrefs.lutDir(context))
                    filterFiles = DoukPrefs.importedResources(context).map { it.name }
                    categoryTick += 1
                }
                val files = DoukPrefs.importedResources(context)
                val tick = categoryTick
                Text(
                    "两类文件分开管理：LUT（.cube / .3dl / .look）用于视频监看与灰片还原；滤镜（尼康 .NP3 / .NCP / .NP2）用于图片风格。" +
                        "每条再标适用范围，标了「视频」的会出现在监看 LUT 列表，标了「图片」的只出现在图片编辑器。",
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                SheetPrimaryButton("导入 LUT / 滤镜") { lutPicker.launch("*/*") }
                Spacer(modifier = Modifier.height(18.dp))
                if (files.isEmpty()) {
                    SettingCard {
                        Text(
                            "还没有导入任何 LUT 或滤镜。",
                            fontSize = 13.sp,
                            color = DoukColors.textTertiary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    val luts = files.filter { DoukPrefs.resourceKind(it.name) == "LUT" }
                    val looks = files.filter { DoukPrefs.resourceKind(it.name) == "滤镜" }
                    ResourceGroup(
                        title = "LUT 文件",
                        subtitle = "监看 / 视频还原用，.cube / .3dl / .look",
                        files = luts,
                        tick = tick,
                        onChange = { categoryTick += 1 },
                        onDelete = { f ->
                            f.delete()
                            lutCount = DoukPrefs.importedResources(context).size
                            lutBytes = DoukPrefs.dirSize(DoukPrefs.lutDir(context))
                            filterFiles = DoukPrefs.importedResources(context).map { it.name }
                        }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    ResourceGroup(
                        title = "滤镜格式",
                        subtitle = "图片风格用，尼康 Picture Control .NP3 / .NCP / .NP2",
                        files = looks,
                        tick = tick,
                        onChange = { categoryTick += 1 },
                        onDelete = { f ->
                            f.delete()
                            lutCount = DoukPrefs.importedResources(context).size
                            lutBytes = DoukPrefs.dirSize(DoukPrefs.lutDir(context))
                            filterFiles = DoukPrefs.importedResources(context).map { it.name }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(30.dp))
            }

            // ===== GPS 数据导入 =====
            "gps" -> GpsPanel(ble = state.ble, onBack = back)

            // ===== 储存 =====
            "storage" -> SettingsSubPage(title = "储存", onBack = back) {
                val photoBytes = DoukPrefs.dirSize(File(context.filesDir, "nikon-photo-inbox"))
                SettingCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        StorageLine("应用内图片", DoukPrefs.readableSize(photoBytes))
                        StorageLine("LUT 与滤镜", DoukPrefs.readableSize(lutBytes))
                        StorageLine("合计", DoukPrefs.readableSize(photoBytes + lutBytes))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                SheetPrimaryButton("导出预设") { showPresetExport = true }
                Spacer(modifier = Modifier.height(10.dp))
                SettingCard {
                    SettingRow(
                        "导入预设",
                        "从另一台设备导出的 .zip 预设包还原设置和 LUT；只覆盖文件里包含的分组",
                        "选择文件"
                    ) {
                        presetImportLauncher.launch(
                            arrayOf("application/zip", "application/json", "text/plain", "application/octet-stream")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "预设包会带上已导入的 LUT(.cube) 与滤镜(.xmp) 原文件，换一台手机导入即可一起还原；" +
                        "不包含照片和视频。导出时可以逐组勾选，也可以全选。",
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(30.dp))
            }

            // ===== 外观设置（悬浮 Douk 栏已按要求去掉） =====
            "appearance" -> SettingsSubPage(title = "外观设置", onBack = back) {
                SettingCard {
                    SettingSwitchRow("高级材质", "卡片磨砂玻璃 + 底栏真实模糊（可调）", glass) {
                        glass = it
                        DoukPrefs.glassEffect = it
                        DoukColors.applyGlass(it)
                    }
                    if (glass) {
                        SettingDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("模糊强度", fontSize = 14.sp, color = DoukColors.textPrimary)
                                Text(
                                    "${materialBlur.roundToInt()} dp",
                                    fontSize = 13.sp,
                                    color = DoukColors.textSecondary
                                )
                            }
                            Slider(
                                value = materialBlur,
                                onValueChange = {
                                    materialBlur = it
                                    DoukPrefs.materialBlur = it
                                    DoukColors.applyMaterial(materialAlpha, it)
                                },
                                valueRange = 0f..40f,
                                colors = SliderDefaults.colors(
                                    thumbColor = DoukColors.accent,
                                    activeTrackColor = DoukColors.accent
                                )
                            )
                            Text(
                                "卡片底固定为浅色 60% 不透明度，这里只调背后的模糊半径："
                                    + "数值越大，卡片后面的文字和图标糊得越彻底（默认 24dp）。",
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = DoukColors.textSecondary
                            )
                        }
                    }
                    SettingDivider()
                    SettingRow(
                        "深色模式",
                        "跟随系统、常亮或常暗",
                        darkMode
                    ) {
                        pick = ValuePick("深色模式", listOf("跟随系统", "浅色", "深色"), darkMode) { v ->
                            darkMode = v
                            DoukPrefs.darkMode = v
                            DoukColors.applyDark(
                                when (v) {
                                    "深色" -> true
                                    "浅色" -> false
                                    else -> android.content.res.Configuration.UI_MODE_NIGHT_YES ==
                                        (context.resources.configuration.uiMode and
                                            android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                SectionTitle("App 主题色")
                SettingCard {
                    DoukAccentPresets.forEachIndexed { index, name ->
                        if (index > 0) SettingDivider()
                        val active = name == themeColor
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    themeColor = name
                                    DoukPrefs.themeColor = name
                                    DoukColors.applyAccent(name)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(accentOf(name))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                name,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) DoukColors.accent else DoukColors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (active) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = DoukColors.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    SettingDivider()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        var customHue by remember { mutableIntStateOf(DoukPrefs.customAccentHue) }
                        val customActive = themeColor == "自定义"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    themeColor = "自定义"
                                    DoukPrefs.themeColor = "自定义"
                                    DoukColors.applyAccentHue(customHue)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(accentFromHue(customHue))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "自定义",
                                fontSize = 14.sp,
                                fontWeight = if (customActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (customActive) DoukColors.accent else DoukColors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${customHue}°", fontSize = 12.sp, color = DoukColors.textSecondary)
                            if (customActive) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = DoukColors.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (customActive) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
                                Slider(
                                    value = customHue.toFloat(),
                                    onValueChange = {
                                        customHue = it.roundToInt()
                                        DoukPrefs.customAccentHue = customHue
                                        DoukColors.applyAccentHue(customHue)
                                    },
                                    valueRange = 0f..360f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = accentFromHue(customHue),
                                        activeTrackColor = accentFromHue(customHue),
                                        inactiveTrackColor = accentFromHue(customHue).copy(alpha = 0.18f)
                                    )
                                )
                                Text(
                                    "拖动色相，全站按钮与选中态实时跟着变；饱和度与亮度已固定，避免文字看不清。",
                                    fontSize = 11.sp,
                                    color = DoukColors.textTertiary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }

            // ===== 语言 =====
            "language" -> SettingsSubPage(title = "语言 / Language", onBack = back) {
                SettingCard {
                    listOf("跟随系统", "中文", "English").forEachIndexed { index, name ->
                        if (index > 0) SettingDivider()
                        val active = name == language
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (language != name) {
                                        language = name
                                        DoukPrefs.language = name
                                        DoukPrefs.languageFollowSystem = name == "跟随系统"
                                        Toast.makeText(
                                            context,
                                            if (name == "跟随系统") "语言改为跟随系统，下次启动生效" else "语言改为 $name，下次启动生效",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                name,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) DoukColors.accent else DoukColors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (active) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = DoukColors.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "语言改动在下次启动应用时生效，界面里的相机参数名保持原厂写法。",
                    fontSize = 12.sp,
                    color = DoukColors.textTertiary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Language changes take effect the next time you open the app.",
                    fontSize = 12.sp,
                    color = DoukColors.textTertiary
                )
                Spacer(modifier = Modifier.height(30.dp))
            }

            // ===== 控制面板参数排序：拍摄 / 视频分开 =====
            "sort" -> SettingsSubPage(title = "参数排列顺序", onBack = back, scrollable = false) {
                var sortMode by remember { mutableStateOf(CaptureMode.STILL) }
                var draggingKey by remember { mutableStateOf<String?>(null) }
                var dragOffset by remember { mutableStateOf(0f) }
                val listState = rememberLazyListState()
                val density = LocalDensity.current
                val rowHeightPx = with(density) { 54.dp.toPx() }
                val order = state.paramOrderFor(sortMode)

                LaunchedEffect(sortMode) {
                    draggingKey = null
                    dragOffset = 0f
                    listState.scrollToItem(0)
                }

                Text(
                    "拍摄和视频各一套顺序，互不影响。长按左侧拖动排序；右侧开关关掉后，该功能不在控制页显示。卡片分组可单独改名、拆分功能。",
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortModePill("拍摄", sortMode == CaptureMode.STILL) {
                        sortMode = CaptureMode.STILL
                    }
                    SortModePill("视频", sortMode == CaptureMode.VIDEO) {
                        sortMode = CaptureMode.VIDEO
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SettingCard {
                    SettingRow(
                        "卡片分组",
                        if (sortMode == CaptureMode.VIDEO) {
                            state.paramGroupsFor(CaptureMode.VIDEO).joinToString(" / ") { it.name }
                        } else {
                            state.paramGroupsFor(CaptureMode.STILL).joinToString(" / ") { it.name }
                        },
                        null
                    ) {
                        groupMode = sortMode
                        subPage = "groups"
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(DoukShapes.cardSmall)
                        .doukFlatSurface(DoukShapes.cardSmall)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 130.dp)
                    ) {
                        itemsIndexed(
                            order,
                            key = { _, key -> key },
                            contentType = { _, _ -> "param-row" }
                        ) { index, key ->
                            val param = state.cameraParams.firstOrNull { it.key == key }
                            val label = param?.label ?: key
                            val hidden = state.isParamHidden(key)
                            val dragging = draggingKey == key
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                                    .background(if (dragging) DoukColors.surface else Color.Transparent)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 14.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 只有左侧把手和文字区域参与长按拖动，右侧开关仍可正常点按
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .pointerInput(sortMode) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        draggingKey = key
                                                        dragOffset = 0f
                                                    },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        dragOffset += amount.y
                                                        val from = order.indexOf(key)
                                                        val step = (dragOffset / rowHeightPx).roundToInt()
                                                        if (from >= 0 && step != 0) {
                                                            val to = (from + step).coerceIn(0, order.lastIndex)
                                                            if (to != from) {
                                                                val moved = order.removeAt(from)
                                                                order.add(to, moved)
                                                                dragOffset -= (to - from) * rowHeightPx
                                                                state.persistParamOrder(sortMode)
                                                            }
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggingKey = null
                                                        dragOffset = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggingKey = null
                                                        dragOffset = 0f
                                                    }
                                                )
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.DragHandle,
                                            contentDescription = "长按拖动排序",
                                            tint = DoukColors.textTertiary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            label,
                                            fontSize = 14.sp,
                                            color = if (hidden) DoukColors.textTertiary else DoukColors.textPrimary
                                        )
                                    }
                                    Text(
                                        if (hidden) "隐藏" else "显示",
                                        fontSize = 11.sp,
                                        color = if (hidden) DoukColors.textTertiary else DoukColors.textSecondary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Switch(
                                        checked = !hidden,
                                        onCheckedChange = { state.setParamVisible(key, it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = DoukColors.accent,
                                            uncheckedThumbColor = DoukColors.textTertiary,
                                            uncheckedTrackColor = DoukColors.surfaceMuted
                                        )
                                    )
                                }
                                if (index < order.lastIndex) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .padding(start = 44.dp)
                                            .height(1.dp)
                                            .background(DoukColors.divider)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ===== 参数卡片分组：卡片列表 =====
            "groups" -> SettingsSubPage(title = "卡片分组", onBack = back) {
                Text(
                    "控制页会按这里的卡片名称逐张显示。点一张卡可以改名，并把功能勾进这张卡；一个功能只会出现在一张卡里。",
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))
                val groups = state.paramGroupsFor(groupMode)
                groups.forEach { group ->
                    SettingCard {
                        SettingRow(
                            group.name,
                            "${group.keys.size} 项 · 点击编辑名称和功能",
                            null
                        ) {
                            editingGroupId = group.id
                            subPage = "groupedit"
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Spacer(modifier = Modifier.height(6.dp))
                SheetPrimaryButton("新建一张卡片") {
                    state.addParamGroup(groupMode)
                }
                Spacer(modifier = Modifier.height(30.dp))
            }

            // ===== 参数卡片分组：单卡改名 / 选功能 =====
            "groupedit" -> SettingsSubPage(title = "编辑卡片", onBack = { subPage = "groups" }) {
                val group = state.paramGroupsFor(groupMode)
                    .firstOrNull { it.id == editingGroupId }
                if (group == null) {
                    Text("这张卡片已不存在。", fontSize = 13.sp, color = DoukColors.textSecondary)
                } else {
                    var nameDraft by remember(group.id, group.name) { mutableStateOf(group.name) }
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        singleLine = true,
                        label = { Text("卡片名称") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = DoukShapes.cardSmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            onClick = {
                                state.renameParamGroup(groupMode, group.id, nameDraft)
                            },
                            enabled = nameDraft.isNotBlank() && nameDraft != group.name
                        ) {
                            Text("保存名称", fontSize = 13.sp)
                        }
                        if (state.paramGroupsFor(groupMode).size > 1) {
                            TextButton(
                                onClick = {
                                    state.removeParamGroup(groupMode, group.id)
                                    editingGroupId = null
                                    subPage = "groups"
                                }
                            ) {
                                Text("删除这张卡片", fontSize = 13.sp, color = DoukColors.danger)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "勾选放进「${group.name}」的功能；取消勾选会从这张卡移除。已被别的卡勾走的功能会显示为未选中。",
                        fontSize = 12.sp,
                        color = DoukColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingCard {
                        val params = state.paramOrderFor(groupMode).mapNotNull { k ->
                            state.cameraParams.firstOrNull { it.key == k }
                        }
                        params.forEachIndexed { index, param ->
                            if (index > 0) SettingDivider()
                            SettingSwitchRow(
                                param.label,
                                if (param.key in group.keys) "在这张卡里" else "不在本卡",
                                param.key in group.keys
                            ) { checked ->
                                state.setParamInGroup(groupMode, group.id, param.key, checked)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }

            // ===== 已连接过的设备（整屏页） =====
            "known" -> SettingsSubPage(title = "已连接过的设备", onBack = back) {
                known = DoukPrefs.knownDevices()
                if (known.isEmpty()) {
                    Text(
                        "还没有连接记录。连上一次相机后会自动记在这里。",
                        fontSize = 13.sp,
                        color = DoukColors.textSecondary
                    )
                }
                SettingCard {
                    known.forEachIndexed { index, device ->
                        if (index > 0) SettingDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.model,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = DoukColors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(device.ip, fontSize = 11.sp, color = DoukColors.textSecondary)
                            }
                            TextButton(onClick = {
                                state.connectMode = ConnectMode.WIFI_STA
                                state.connectDirect(context, device.ip)
                                subPage = null
                            }) { Text("连接", fontSize = 12.sp) }
                            TextButton(onClick = {
                                DoukPrefs.forgetDevice(device.ip)
                                known = DoukPrefs.knownDevices()
                            }) { Text("删除", fontSize = 12.sp, color = DoukColors.textSecondary) }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }

            // ===== 蓝牙 GPS（整屏页） =====
            "ble" -> SettingsSubPage(title = "蓝牙", onBack = back) {
                bleKnown = DoukPrefs.bleDevices()
                bleDefault = DoukPrefs.bleDefaultName
                SettingCard {
                    SettingSwitchRow(
                        "显示蓝牙 GPS 卡片",
                        "设备页蓝牙区显示 GPS 推送；关掉后仍可从其他入口推送",
                        showBleGpsCard
                    ) {
                        showBleGpsCard = it
                        DoukPrefs.showBleGpsCard = it
                        state.showBleGpsCard = it
                    }
                    SettingDivider()
                    SettingSwitchRow(
                        "显示蓝牙遥控卡片",
                        "设备页蓝牙区显示快门/录像遥控；关掉后只是不显示卡片，不影响已连接的遥控",
                        showBleRemoteCard
                    ) {
                        showBleRemoteCard = it
                        DoukPrefs.showBleRemoteCard = it
                        state.showBleRemoteCard = it
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    "尼康相机同一时间只允许一路连接：连上 Wi-Fi / USB 后蓝牙 GPS 要单独连，不会自动占用。"
                        + "两张卡片可以分别隐藏，隐藏不等于断开连接。",
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text("连接过的设备", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DoukColors.textPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                if (bleKnown.isEmpty()) {
                    SettingCard {
                        Text(
                            "还没有蓝牙连接记录。去设备页的「蓝牙 GPS 推送」卡片连一次，成功后会自动记在这里。",
                            fontSize = 12.sp,
                            color = DoukColors.textSecondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    SettingCard {
                        bleKnown.forEachIndexed { index, dev ->
                            if (index > 0) SettingDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        dev.name + if (dev.name == bleDefault) "（默认）" else "",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = DoukColors.textPrimary
                                    )
                                    Text(dev.address, fontSize = 11.sp, color = DoukColors.textSecondary)
                                }
                                TextButton(onClick = {
                                    bleDefault = dev.name
                                    DoukPrefs.bleDefaultName = dev.name
                                }) { Text(if (dev.name == bleDefault) "已默认" else "设为默认", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    state.ble.startScan(autoConnect = true)
                                    subPage = null
                                }) { Text("连接", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    DoukPrefs.forgetBleDevice(dev.name)
                                    bleKnown = DoukPrefs.bleDevices()
                                    bleDefault = DoukPrefs.bleDefaultName
                                }) { Text("删除", fontSize = 12.sp, color = DoukColors.textSecondary) }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "相机蓝牙地址每次开机都会变，所以按广播名认设备；点「连接」后 App 会自己扫到那台再握手。",
                    fontSize = 11.sp,
                    color = DoukColors.textTertiary
                )
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    } else {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DoukSpace.page, end = DoukSpace.sm, top = DoukSpace.sm, bottom = DoukSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "设置",
                style = DoukType.h1,
                color = DoukColors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAbout = true }) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = "关于连镜",
                    tint = DoukColors.textSecondary
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(settingsScroll)
                .padding(horizontal = 20.dp)
        ) {
            // ========== 设备 ==========
            SectionTitle("设备")
            SettingCard {
                SettingSwitchRow(
                    title = "自动连接已连接过的设备",
                    subtitle = "没有连接时，自动重连上次的相机",
                    checked = autoConnect,
                    onCheckedChange = {
                        autoConnect = it
                        DoukPrefs.autoConnectKnown = it
                    }
                )
                SettingDivider()
                SettingRow(
                    title = "已连接过的设备",
                    subtitle = if (known.isEmpty()) "还没有连接记录" else "共 ${known.size} 台，点开可连接或删除",
                    onClick = {
                        known = DoukPrefs.knownDevices()
                        subPage = "known"
                    }
                )
                SettingDivider()
                SettingSwitchRow(
                    title = "设备页显示「添加设备」按钮",
                    subtitle = "关闭后只留顶部 + 与搜索",
                    checked = showAddButton,
                    onCheckedChange = {
                        showAddButton = it
                        DoukPrefs.showAddDeviceButton = it
                        state.showAddDeviceButton = it
                    }
                )
                SettingDivider()
                SettingRow(
                    title = "蓝牙",
                    subtitle = buildString {
                        append(if (bleKnown.isEmpty()) "还没有蓝牙连接记录" else "连接过 ${bleKnown.size} 台")
                        if (bleDefault.isNotBlank()) append(" · 默认 $bleDefault")
                    },
                    onClick = {
                        bleKnown = DoukPrefs.bleDevices()
                        bleDefault = DoukPrefs.bleDefaultName
                        subPage = "ble"
                    }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            SectionTitle("下载")
            SettingCard {
                SettingRow(
                    title = "自动下载",
                    subtitle = "新拍的照片和视频自动回传手机",
                    value = if (autoDl) "已开启" else "已关闭"
                ) { downloadPage = "auto" }
                SettingDivider()
                SettingRow(
                    title = "下载保护文件",
                    subtitle = "导入相机里锁定的照片和视频",
                    value = if (lockedOnly) "已开启" else "已关闭"
                ) { downloadPage = "locked" }
                SettingDivider()
                SettingRow(
                    title = "从相机下载的格式",
                    subtitle = "图库保存时只下载选中格式",
                    value = saveFormat
                ) {
                    pick = ValuePick(
                        "从相机下载的格式",
                        listOf("JPG", "RAW", "JPG + RAW", "全部格式"),
                        DoukPrefs.saveFormat
                    ) { v -> saveFormat = v; DoukPrefs.saveFormat = v }
                }
            }

            // ========== 图库 ==========
            // 用户要求把「资源」「图像编辑」从通用移到这里，并增加图库默认打开来源
            Spacer(modifier = Modifier.height(18.dp))
            SectionTitle("图库")
            SettingCard {
                // 顺序：资源 → 相机预加载 → 图像编辑 → 默认打开 → 打开图库加载全部
                SettingRow(
                    "资源",
                    if (lutCount == 0) "LUT、滤镜与图片预设" else "已导入 $lutCount 个（${DoukPrefs.readableSize(lutBytes)}）",
                    null
                ) { subPage = "resources" }
                SettingDivider()
                SettingSwitchRow(
                    title = "相机内图片预加载",
                    subtitle = "后台预读最新照片，图库打开即看",
                    checked = galleryPreload,
                    onCheckedChange = {
                        galleryPreload = it
                        DoukPrefs.galleryCameraPreload = it
                    }
                )
                SettingDivider()
                SettingRow(
                    "图片默认导出格式",
                    "选了就导出不再问；不选每次导出时再挑",
                    DoukPrefs.defaultExportFormat.ifBlank { "每次选择" }
                ) {
                    pick = ValuePick(
                        "图片默认导出格式",
                        listOf("每次选择", "JPG", "PNG"),
                        DoukPrefs.defaultExportFormat.ifBlank { "每次选择" }
                    ) { v ->
                        DoukPrefs.defaultExportFormat = if (v == "每次选择") "" else v
                    }
                }
                SettingDivider()
                SettingRow(
                    "编辑默认格式",
                    "同一张有 JPG+RAW 时，编辑默认用哪种",
                    DoukPrefs.defaultEditFormat.ifBlank { "每次选择" }
                ) {
                    pick = ValuePick(
                        "编辑默认格式",
                        listOf("每次选择", "JPG", "HEIC", "RAW"),
                        DoukPrefs.defaultEditFormat.ifBlank { "每次选择" }
                    ) { v ->
                        DoukPrefs.defaultEditFormat = if (v == "每次选择") "" else v
                    }
                }
                SettingDivider()
                SettingRow("图像编辑", "导出格式、质量与预览", null) {
                    subPage = "editor"
                }
                SettingDivider()
                SettingRow(
                    "默认打开",
                    "进图库先显示哪一类",
                    galleryDefault
                ) {
                    pick = ValuePick(
                        "图库默认打开",
                        listOf("相机内", "应用内", "手机内"),
                        galleryDefault
                    ) { v ->
                        galleryDefault = v
                        DoukPrefs.galleryDefaultSource = v
                    }
                }
                SettingDivider()
                SettingSwitchRow(
                    title = "打开图库加载全部图片",
                    subtitle = "关闭时先显示最新一批，下滑再加载",
                    checked = galleryLoadAll,
                    onCheckedChange = {
                        galleryLoadAll = it
                        DoukPrefs.galleryLoadAll = it
                    }
                )
            }

            // ========== 通用 ==========
            // 顺序按验收意见重排：资源 → GPS → 储存 → 拍摄后快速预览 → 设备页更换图片 → 外观 → 语言
            Spacer(modifier = Modifier.height(18.dp))
            SectionTitle("通用")
            SettingCard {
                SettingRow("GPS 数据导入", gpsSummary(DoukPrefs.gpsAttachImport, DoukPrefs.gpsAttachBle), null) {
                    subPage = "gps"
                }
                SettingDivider()
                SettingSwitchRow(
                    title = "拍摄后快速预览",
                    subtitle = "拍完一张就把成片显示在应用里",
                    checked = previewOnCapture,
                    onCheckedChange = {
                        previewOnCapture = it
                        DoukPrefs.capturePreview = it
                    }
                )
                SettingDivider()
                SettingRow(
                    title = "设备页更换图片",
                    subtitle = if (photoPath.isBlank()) "从相册选一张做设备页图片" else "已设置，点这里更换",
                    onClick = { photoPicker.launch("image/*") }
                )
                SettingDivider()
                SettingRow("外观设置", "$themeColor · $darkMode", null) { subPage = "appearance" }
                SettingDivider()
                SettingRow(
                    title = "后台保活",
                    subtitle = "连接时保持后台运行",
                    value = if (DoukBleKeepAliveService.isBatteryOptimizationIgnored(context)) "无限制" else "去设置"
                ) {
                    DoukBleKeepAliveService.requestIgnoreBatteryOptimizations(context)
                }
                SettingDivider()
                SettingRow("储存", "查看占用，导入导出预设", null) {
                    subPage = "storage"
                }
                SettingDivider()
                SettingRow("语言 / Language", "中文 / English", language) { subPage = "language" }            }

            // ========== 控制面板 ==========
            Spacer(modifier = Modifier.height(18.dp))
            SectionTitle("控制面板")
            SettingCard {
                SettingRow(
                    title = "参数排列顺序",
                    subtitle = "长按拖动排序，可隐藏功能",
                    onClick = { subPage = "sort" }
                )
            }

            // ========== 关于：只留一个入口，点开才是里面的分类 ==========
            Spacer(modifier = Modifier.height(18.dp))
            SectionTitle("关于")
            SettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAbout = true }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            // adaptive-icon 是 XML，Compose 的 painterResource 读不了，这里用导出的位图版
                            painter = painterResource(id = R.drawable.douk_app_icon),
                            contentDescription = "连镜",
                            modifier = Modifier.size(52.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("连镜", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DoukColors.textPrimary)
                        Spacer(modifier = Modifier.height(3.dp))
                        Text("版本 $APP_VERSION", fontSize = 11.sp, color = DoukColors.textSecondary)
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = DoukColors.textTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 内容铺到屏幕最底部，滚动时会从透明胶囊下面穿过去；
            // 末尾留一块比胶囊更高的空白，滚到底时「关于」这张卡完整露在胶囊上方。
            Spacer(modifier = Modifier.height(124.dp))
        }
    }
}
}

private data class ChangelogEntry(val version: String, val lines: List<String>)

private val CHANGELOG_ENTRIES = listOf(
    ChangelogEntry(
        "1.2.0",
        listOf(
            "· 连接：mDNS 首个服务出现后 400ms 收尾即返回；入网判定同时挂 DHCP 回调，不再等满超时；入网后 1 秒确认网络仍在，被系统收回时直接判失败。",
            "· 连接：加入相机热点前先检测本机「个人热点」，在跑就提前拦截并提示去关，不再白等一轮超时。",
            "· 连接：失败原因分「热点被系统收回 / 本机热点占用网卡 / 不在相机网段 / 端口未开放 / 握手无应答 / VPN 接管」，各带下一步动作。",
            "· 配对：InitFail 1 时可在诊断面板「换新身份并重新配对」，让机身重新弹一次配对确认。",
            "· 口令：相机热点密码用 Keystore 加密保存；修复每次冷启动被清空导致「无密码」超时的回归。",
            "· 界面：设备页胶囊去掉外描边，添加设备卡片未选中不再叠第二条边。",
        )
    ),
    ChangelogEntry(
        "1.1.29",
        listOf(
            "· 修复：相机热点口令每次冷启动都被清空。1.1.26 起口令以 Keystore 密文存在 camera_wifi_password（带 enc1: 前缀），而初始化里仍无条件写空串，等于每次启动都把密文抹掉 —— 表现就是连过之后重启应用，再连相机热点变成「无密码」，等 22 秒超时。现在只清明文，密文保留。",
            "· 连接：没有带密码加入失败时直接说「这次没有带密码，要填相机屏幕上的 8 位密码」，不再混在「等待入网超时」里让人猜。",
        )
    ),
    ChangelogEntry(
        "1.1.28",
        listOf(
            "· 连接：加入相机热点前先查本机「个人热点」是否还在跑（热点网卡不在系统网络列表里，靠这个特征识别，结果写进日志）。还在跑就直接拦住并提示去关，不再等系统给一次 DHCP 再收回。",
            "· 连接：失败文案新增「本机热点占用网卡」这一种，PTP 与预检两条路径都会点名是热点没关，不再统一说「手机不在相机网段」。",
            "· 界面：设备页顶部「蓝牙 / Wi-Fi|USB」胶囊去掉外描边，只留一层填充；「添加设备」里的连接方式卡片未选中时不再叠第二条边，避免卡片套卡片。",
        )
    ),
    ChangelogEntry(
        "1.1.27",
        listOf(
            "· 连接：相机热点入网后会再等 1 秒确认网络还在。平板自己开着「个人热点」时，系统会先给一次 DHCP 再把相机热点收回，以前这会被误判成「手机不在相机网段」；现在直接提示「入网 N 秒后被系统断开，请先关闭个人热点」。",
            "· 连接：失败文案按原因分开——热点被系统收回、手机确实不在相机网段、端口没开放三种情况不再共用一句话。",
            "· 配对：接口回 InitFail 1（相机不认当前身份）时，新增「换新身份并重新配对」，生成新 GUID 让机身重新弹一次配对确认；有旧日志仍可走「恢复配对身份」。",
        )
    ),
    ChangelogEntry(
        "1.1.26",
        listOf(
            "· 安全：相机热点口令改回本机记忆，但用系统 Keystore 的 AES‑GCM 加密后落盘，密钥不可导出、不进备份；1.1.25 及以前留下的明文在首次读取时自动迁移成密文，不用重输。",
            "· 连接：mDNS 发现改成「首个服务出现后再等 400ms 收尾」就返回，不再白等满 1.2–1.6 秒窗口；多个服务并发解析，连接前探测整体快了 0.5–1.5 秒。",
            "· 连接：热点入网总预算从最坏 56 秒收到 22 秒（WPA2）+ 16 秒（WPA3 补试）；DHCP 比 onAvailable 晚回来时，链路属性一更新就继续，不再干等到超时。",
            "· 连接：PTP/IP 命令与事件通道开启 TCP keepalive，相机休眠或路由器 NAT 超时后会尽早暴露死连接，交给重连逻辑处理，减少「显示在线但发指令没回」。",
        )
    ),
    ChangelogEntry(
        "1.1.25",
        listOf(
            "· 隐私：GPS 设置新增「在线高程补海拔」开关，关闭后只用本机定位、气压计与本地缓存；权限说明与条款补充麦克风、在线高程的用途口径。",
            "· 安全：FTP 数据通道在接收前校验来源地址，不属于已授权客户端则拒绝；相机热点口令不再写入本机存储，只记住 SSID，旧版残留口令一并清除。",
            "· 稳定性：日志统一收口到运行日志文件，去掉堆栈直接打印；音频/视频输入缓冲区空值补安全回退；PTP/IP 单包分配上限收紧；相机热点 SSID 识别只认 NIKON 前缀。",
            "· 清理：移除废弃的旧 HDR 视图，去掉冗余 SDK 版本判断与陈旧构建注释。",
        )
    ),
    ChangelogEntry(
        "1.1.24",
        listOf(
            "· 撤销「Wi‑Fi 会话期间继续用蓝牙推 GPS」：相机进入「连接至智能设备」后，机身蓝牙不再作为可靠链路，连上 Wi‑Fi 就把蓝牙扫描停掉；GPS 推送只在设备页的「蓝牙」模式下进行。",
            "· 海拔补齐（保留）：本次 fix → 20 分钟内 GPS 海拔 → 气压计（用 GPS 海拔标定海平面气压）→ 在线高程（按经纬度查地面高程，1.5 公里内复用）→ 最近的 GPS fix，设备页显示这一包海拔来源。",
        )
    ),
    ChangelogEntry(
        "1.1.23",
        listOf(
            "· 添加设备：Wi‑Fi AP 直连下方新增「搜索相机设备」与「连接相机热点（备用）」两个入口，热点入口从设备页挪过来。",
            "· 设备页：蓝牙 / Wi‑Fi|USB 胶囊单独占一行，与相机照片块隔开 16dp；照片卡片与胶囊不再是一整块。",
            "· 连接：相机热点弹窗改为四角 24dp 圆角，不再像从底部切出来的一块。",
            "· 海拔：网络定位不带高度时，有气压计的机器用气压计补（用 GPS 海拔反推海平面气压标定），没气压计的机器按经纬度查一次在线高程，缓存 1.5 公里；设备页显示这一包海拔的来源（本次 / 缓存 / 气压计 / 在线高程 / GPS 最近）。",
            "· 稳定性：修掉 API 34 及以下清理 HDR 缓存时的 removeFirst() 崩溃隐患；Wi‑Fi 扫描和麦克风采集补上显式权限检查，lint 已清零。",
        )
    ),
    ChangelogEntry(
        "1.1.22",
        listOf(
            "· Live 图：播放层改为 TextureView + MediaPlayer，首帧上屏后才淡入，长按瞬间不再黑一下；不再「挖空」画面。",
            "· 设备页：蓝牙与 Wi‑Fi|USB 拆成两枚独立胶囊，中间留 10dp，不再粘在一起。",
            "· 连接：相机热点名称可直接从「当前 Wi‑Fi」或附近 Wi‑Fi 列表点选，减少手输；列表会标出相机热点并支持重新扫描。",
        )
    ),
    ChangelogEntry(
        "1.1.21",
        listOf(
            "· Live 图：长按改在原图位置上直接播放，不再另开全屏窗口，播完/轻点回到原图，画面不再黑一下再跳回来；播放期间横滑与缩放暂停响应。",
            "· 设备页：蓝牙 / Wi‑Fi|USB 胶囊挪到相机照片卡片顶部正中。",
            "· 连接：新增应用内直接加入相机热点（WifiNetworkSpecifier），记住 SSID/密码，断开连接时自动释放这条无外网网络。",
            "· 连接：端口探测并发加入当前网段低号地址、192.168.122/125 段与网关，并与 mDNS（_ptpip/_ptp）同时跑，机身公布的地址优先。",
            "· 连接：按机型代际调整探测窗口与握手前等待（一代 1.2s/900ms、二代 0.9s/600ms、三代 0.6s/350ms），失败提示按代际给排查步骤。",
            "· 蓝牙：配对身份按机身分别保存，一台手机连多台机身不再互相覆盖 device/nonce。",
        )
    ),
    ChangelogEntry(
        "1.1.20",
        listOf(
            "· 设备页：蓝牙 / Wi‑Fi|USB 的切换从「再点一次设备」的浮层挪到相机照片右上角的小胶囊，点一下即切，页面不再被浮层压住。",
            "· 连接：梳理 AP / STA / USB / 蓝牙四条链路的接入策略，保留自动探测与看门狗重连，去掉和设备切换无关的底部弹层。"
        )
    ),
    ChangelogEntry(
        "1.1.19",
        listOf(
            "· 编辑器：调色整条链路改到 GPU（曝光/亮度/对比/高光/阴影/色温色调/饱和度/自然饱和度/曲线/HSL/褪色/黑白，以及降噪/锐化/清晰度/颗粒），45MP 导出不再堆几百 MB 的 IntArray。",
            "· HDR：预览光晕（bloom）改在 GPU 上做阈值遮罩 + 可分离模糊，亮部外溢更平滑，拖动调节不再掉帧。",
            "· 监视：新增「峰值对焦」开关，用屏幕空间梯度在 GPU 上描品红边缘，与伪色互斥。",
            "· 图库：应用图库改成增量索引（记录 mtime/大小/宽高），再次进入只补新照片，不再全量重读每张图尺寸。",
            "· 连接：新增 Wi‑Fi 网络回调，掉线立刻提示并触发重连，不用等轮询超时。"
        )
    ),
    ChangelogEntry(
        "1.1.18",
        listOf(
            "· 连接：事件通道断开后会用原会话号自动重建，不再只能退回每 2 秒的轮询兜底，回传恢复即时通知。",
            "· 图库：缩略图缓存改为真 LRU（按最近查看淘汰），容量随设备内存自适应；每格独立刷新，滚动时不再整屏重组。",
            "· 定位：写入照片时补 GPSDateStamp/GPSTimeStamp/海拔（按 UTC），缺少时间戳的定位不再被看图软件判为无效；媒体库图片改为原地写，40MP 原图不再整包搬三遍；新增 HEIC/HEIF 写入。",
            "· 监视：直方图跨像素取样，计算量降到原来的 1/4；直方图数据按内容比较，不再每 150ms 强制刷新面板。",
            "· 取景：位图双缓冲复用，长时间监看的 GC 抖动和内存峰值明显下降。",
            "· 构建：Kotlin 2.1 + Compose Compiler 插件，compileSdk 升到 35，移除 metadata 版本跳过与编译器扩展的旧补丁。"
        )
    ),
    ChangelogEntry(
        "1.1.5",
        listOf(
            "· 更换 Release 签名证书：已安装旧版的用户需先卸载再安装 1.1.5，直接覆盖会提示应用未安装。",
            "· 「关于」新增开源许可页，列出 LibRaw、Compose、Coil、Telephoto、OkHttp 等组件与许可证原文链接。",
            "· 使用条款补充 LibRaw 源码获取地址。"
        )
    ),
    ChangelogEntry(
        "1.1.4",
        listOf(
            "· 优化尼康 Z6II / Z7II 等机型的相机热点（AP）连接成功率：首次握手失败会自动重建网络路由与 socket 再试一次，不必手动点重连。",
            "· 主机注册（配对）不再要求相机处于「仅公布少量命令」的受限态，只要机身公布 0x952B 就能发起；提交确认前会等满 8.5 秒，避免相机还在落库档案时回绝。",
            "· 相机端公布的 0x952B / 0x935A 配对流程与 0x9435/0x90C2 接管流程保持独立，AP 模式仍不碰这两个命令。"
        )
    ),
    ChangelogEntry(
        "1.1.3",
        listOf(
            "· 相机网络选择更严格：排除 VPN / 代理网络（要求 Wi‑Fi 且带 NOT_VPN），避免把包送进 tun 网卡后相机收不到。",
            "· VPN 存在时，进程默认网络在「建 socket → 握手」整段保持指向相机热点，握手结束立即恢复，不再只覆盖建链瞬间。",
            "· 相机热点（AP）模式探测次数由 4 次降到最多 3 次，探测后冷却由 400ms 延长到 1.2 秒，减少占用机身唯一的 PTP 客户端槽。",
            "· 连接失败日志补充出口地址、VPN 状态与绑定结果，便于判断是选路、端口还是握手层。"
        )
    ),
    ChangelogEntry(
        "1.1.2",
        listOf(
            "· 修复开着 VPN / 代理 / 双网加速时连相机热点失败：连接包会从虚拟网卡（如 172.17.x.x）发出，现强制把 PTP 连接钉在相机 Wi‑Fi 上。",
            "· 检测到 VPN 接管网络时提前提示，连接失败时直接显示真实出口地址。"
        )
    ),
    ChangelogEntry(
        "1.1.1",
        listOf(
            "· 优化尼康 Z6II / Z7II 等二代机型的相机热点（AP）连接成功率：自动识别相机热点网段，预检失败自动重试，探测后留出冷却再发起握手。",
            "· AP 模式不再扫描整个网段，避免相机热点被并发连接压断。",
            "· 连接后的参数刷新频率下调，减少相机 Wi‑Fi 指示灯闪动与整机电量消耗。",
            "· 修复局域网模式误标为「手机热点」的问题，设备页按真实拓扑显示「相机热点」或「局域网」。",
            "· 添加设备页右上角新增「连接教程」，连接步骤与失败排查集中到弹窗里。",
            "· 清理静态检查告警，日志统一走 Timber 桥接，行为不变。"
        )
    ),
    ChangelogEntry(
        "1.1.0",
        listOf(
            "· 新增持久化传输中心：回传、导入任务在切页与切后台后继续执行。",
            "· 图库支持 RAW + JPG 合并显示、Live 图长按播放与 40MP 图片预览。",
            "· 编辑器新增 HDR 直出与 LUT 滤镜链路，支持视频片段转 Live 图。",
            "· 蓝牙遥控、定位写入与保活重连完善。"
        )
    ),
    ChangelogEntry(
        "1.0.0",
        listOf(
            "· 连镜第一个正式版本。",
            "· 应用名、安装包与保存目录统一为「连镜」；旧版 Douk / LensLink 目录里的照片仍会在图库显示。",
            "· 预设格式升级为 lianjing-preset-v1，旧版 lenslink-preset-v1 预设仍可直接导入。",
            "· 看图页修复底部黑边、状态栏透底和 HDR 翻页闪帧；HDR 高光做了肩部压缩，减少过曝。",
            "· 内置 LUT 已全部移除，只使用用户自行导入的 LUT。",
            "· 增加 RAW 原始数据解码（LibRaw 0.23），支持尼康 Z50II 高效率★ NEF。",
            "· 补齐非官方声明与第三方组件说明。"
        )
    ),
    ChangelogEntry(
        "0.2.6",
        listOf(
            "· 移除内置的 5 个第三方 LUT，改为用户自行导入，应用不再随包分发任何 LUT。",
            "· 导出水印去掉 NIKON 字样，复古铭牌模板改用「连镜」。",
            "· 应用名统一为「连镜」，安装包改用拼音命名。",
            "· 补齐第三方组件声明，并在 README 与应用内增加非官方、无关联声明。"
        )
    ),
    ChangelogEntry(
        "0.1.0",
        listOf(
            "· 修复已知 bug。"
        )
    ),
    ChangelogEntry(
        "0.0.145",
        listOf(
            "· Douk 栏加一圈玻璃反光白边：左上角与右下角最亮（0.95 白），沿两条边往中间淡下去，不再是均匀卡片框。",
            "· 底栏顶部加一层极淡的白色吃光，浅色页面里也能看出玻璃的厚度。"
        )
    ),
    ChangelogEntry(
        "0.0.144",
        listOf(
            "· Douk 栏改回全透明：去掉 95% 白底和折光描边，不再看成压在页面上的一张卡片。",
            "· 外观设置里的「底栏模糊度」恢复真实模糊，0–20dp 拖动即时生效，滚到栏下面的照片会被高斯模糊。",
            "· 所有页面内容铺到屏幕最底部，底部收尾间距比胶囊更高，滚到最底时最后一块内容完整越过 Douk 栏。"
        )
    ),
    ChangelogEntry(
        "0.0.143",
        listOf(
            "· 进监视默认不接管相机：机身屏幕继续显示画面，不再弹「已连接至智能设备」；相机参数里新增「接管相机」按钮，需要时再切。",
            "· 取消 6 秒保活心跳，不再把相机屏幕反复点亮；相机按自己的节能设置休眠。",
            "· 点画面改为真正的「点哪对哪」：下发 ChangeAfArea 到对应传感器坐标并驱动一次对焦。",
            "· 工具箱划出后双击画面即可收起，点画面对焦不再有双击等待。",
            "· 内置 5 个尼康 N-Log 转 Rec709 LUT（含 RED 系列），首启写入资源页，监看/图库滤镜直接用。",
            "· 图片详情新增 EXIF 参数段：快门、光圈、ISO、焦距、曝光补偿、测光、白平衡、闪光灯、色彩空间等。",
            "· 滤镜缩略图改为每个滤镜单独渲染，条上直接显示套完滤镜的效果。"
        )
    ),
    ChangelogEntry(
        "0.0.142",
        listOf(
            "· 图库底部不再留出一大片空白，照片网格直接铺到悬浮底栏上方。",
            "· 相机上给照片加锁后，不打开设置页也会在几秒内自动下载（取景时不打断取景）。",
            "· 「相机内全部」导入前先比对手机上已有的文件，以前下过的锁定照片不会再重复下载。"
        )
    ),
    ChangelogEntry(
        "0.0.141",
        listOf(
            "· 保护文件自动导入默认改为「相机内全部」，相机里已经锁好的照片连接后就会补齐。",
            "· 已导入过的锁定照片跨会话自动跳过，不会每次连接重复下载。",
            "· 自动下载与保护文件都保存到应用图库目录，拍完即在图库可见。"
        )
    ),
    ChangelogEntry(
        "0.0.140",
        listOf(
            "· 修复自动下载：保存位置「应用相册」现在真正写进应用图库目录，拍完的照片立刻出现在图库。",
            "· 修复下载保护文件：连接后自动导入锁定照片，任何页面都会弹导入确认。",
            "· 自动导入改为持续巡检，机身里新加锁的照片会自动补导入。",
            "· 旧版下载到 LutCameraPro 目录的照片也会在图库显示。"
        )
    ),
    ChangelogEntry(
        "0.0.139",
        listOf(
            "· 应用内图库同一张 JPG/RAW 合并为一格，角标同时显示两种格式。",
            "· 修复多选照片删除失败的问题，配对文件会一起删除。",
            "· 导入 JPG 时写入手机当前定位；无缓存定位时会短时请求一次新鲜定位。",
            "· 控制页拍摄卡片下移，贴近底部导航栏上方。"
        )
    ),
    ChangelogEntry(
        "0.0.138",
        listOf(
            "· 监看右栏新增「相机控制」按钮，取景中也能调参数；画质胶囊直接显示当前屏幕画质。",
            "· 底部 Douk 栏不再压住图库、设置和二级页内容，最后一行都能完整看到。",
            "· 相机拨盘或机身菜单改参数后，App 每 2.5 秒同步一次快门、光圈、ISO、曝光补偿、测光、白平衡和对焦。",
            "· 更新日志只显示当前版本及之后的版本，不再堆历史旧版。",
            "· 精简设置页功能说明，保留一句能看懂的重点。"
        )
    ),
    ChangelogEntry(
        "0.0.128",
        listOf(
            "· 监看画面缩到左右栏之间，右栏功能一行一个，快门不被遮挡。",
            "· 自动下载重启后恢复回传；保护文件支持全部导入；图库支持双指缩放。",
            "· 设置精简，语言页补英文，移除自启动权限入口。"
        )
    )
)

private fun versionParts(version: String): List<Int> =
    version.split('.').map { it.toIntOrNull() ?: 0 }

private fun compareVersion(a: String, b: String): Int {
    val max = maxOf(versionParts(a).size, versionParts(b).size)
    val pa = versionParts(a) + List(max) { 0 }
    val pb = versionParts(b) + List(max) { 0 }
    for (i in 0 until max) {
        if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
    }
    return 0
}

/** 只显示当前安装版本及之后版本的更新日志，旧版本不再往前堆。 */
private fun changelogFor(current: String): List<String> {
    val visible = CHANGELOG_ENTRIES.filter { compareVersion(it.version, current) >= 0 }
    if (visible.isEmpty()) return listOf("当前版本 $current", "", "暂无更新日志。")
    return buildList {
        add("当前版本 $current")
        add("")
        visible.forEach { entry ->
            add("版本 ${entry.version}")
            addAll(entry.lines)
            add("")
        }
    }
}

/** 「开源许可」页正文：满足 CDDL / Apache-2.0 / BSD 的署名与源码可获取要求。完整清单见仓库 THIRD_PARTY_NOTICES.md */
private val OPEN_SOURCE_LINES = listOf(
    "连镜使用下列开源组件，各组件的版权归其作者所有，许可证原文随组件保留在仓库对应目录。本页只作署名与源码获取说明，不改变任何许可证条款。",
    "",
    "一、RAW 解码",
    "LibRaw 0.23.0 Snapshot202611：本项目选择 CDDL-1.0 许可，以源码形式随工程编译。",
    "源码获取：https://github.com/4647luo/lianjing/tree/main/app/src/main/cpp/libraw",
    "许可证原文：同目录下的 LICENSE.CDDL、LICENSE.LGPL 与 COPYRIGHT。你也可以按 LGPL-2.1 的条款使用该组件。",
    "LibRaw-CMake（把 LibRaw 接入 CMake 的构建包装脚本）：BSD-3-Clause，源码在 app/src/main/cpp/libraw-cmake。",
    "",
    "二、界面与图像",
    "Jetpack Compose（BOM 2024.12.01）、AndroidX Core KTX、Lifecycle、Activity Compose、Navigation Compose、ExifInterface、DocumentFile：Apache-2.0。",
    "Coil 2.6.0、Telephoto 0.13.0、Haze 1.2.0、Material Icons Extended：Apache-2.0，均以 Gradle 依赖引用，未修改其源码。",
    "",
    "三、网络与基础库",
    "OkHttp 4.12.0、Gson 2.10.1、Timber 5.0.1、xLog 1.11.0、ACRA 5.11.4：Apache-2.0。",
    "",
    "四、许可证原文",
    "Apache-2.0：https://www.apache.org/licenses/LICENSE-2.0",
    "BSD-3-Clause：见 app/src/main/cpp/libraw-cmake/LICENSE",
    "CDDL-1.0 与 LGPL-2.1：见 app/src/main/cpp/libraw/LICENSE.CDDL、LICENSE.LGPL",
    "",
    "五、商标",
    "Nikon、Nikon Z、NIKKOR 等名称与标识归尼康株式会社及其权利人所有，本项目仅在说明兼容的相机型号时提及，与尼康株式会社没有任何关联、隶属、授权、赞助或背书关系。",
    "",
    "完整组件清单与逐项许可判定见仓库根目录 THIRD_PARTY_NOTICES.md：",
    "https://github.com/4647luo/lianjing/blob/main/THIRD_PARTY_NOTICES.md",
    ""
)

private val TERMS_LINES = listOf(
    "一、关于本应用",
    "「连镜」是一款非官方的第三方应用，用于连接尼康相机，提供图片回传、参数控制、实时取景与定位写入等功能。本应用为个人开发项目，免费提供，不含任何付费功能或广告。安装并使用本应用，即表示你已阅读、理解并同意本条款的全部内容。如果你不同意其中任何一条，请停止使用并卸载本应用。",
    "",
    "二、相机连接与设备要求",
    "1. 本应用通过 PTP/IP（15740 端口）与相机通信，也可以通过 USB PTP 或蓝牙与相机交互。相机端必须处于「连接至智能设备」或「连接至计算机」等对应模式，连接才可能建立。",
    "2. 不同机型、不同固件支持的命令集与属性并不一致。部分机型在首次连接时需要应用控制授权，或只能进行有限的参数读取，这属于相机固件行为，不是应用故障。",
    "3. 同一时间通常只能有一个客户端连接相机。如果你的相机已被其他设备（如官方传输应用）占用，请先断开那台设备再尝试连接。",
    "4. 无线连接质量受相机热点、路由器、手机天线与现场电磁环境影响。连接中断、画面卡顿或传输变慢，不一定代表应用本身出错。",
    "",
    "三、数据与隐私",
    "1. 本应用不收集、不上传你的照片、相机序列号、位置信息或使用记录，也不包含统计埋点与第三方推广 SDK。",
    "2. 应用产生的所有数据（回传的图片、导入的 LUT、运行日志、设置项）都保存在本机应用私有目录中，卸载应用时会一并删除。",
    "3. 只有在你主动点「反馈与建议」时，应用才会调用系统邮件客户端，由你确认收件人与内容后自行发送。应用不会在后台自行发送任何信息。",
    "4. 定位权限仅用于把坐标写入照片或推送给相机。关闭相关开关后，应用不会再获取位置。",
    "5. 海拔补齐：当 GPS 与气压计都拿不到高度时，可按经纬度查询公开高程服务补海拔；该行为可在 GPS 设置里关闭，关闭后只用本机定位、气压计与本地缓存。",
    "",
    "四、照片与文件",
    "1. 回传到手机的图片、在应用内进行的浏览与删除操作，均直接作用于相机存储卡或手机本地文件。删除相机内文件属于不可恢复操作，请在操作前自行确认。",
    "2. 因相机断电、存储卡拔出、无线中断等原因造成的传输中断，可能出现不完整文件。本应用不保证任何一次传输都能完整成功。",
    "3. 建议在重要拍摄任务前先做一次小批量回传测试，确认流程与你的相机、存储卡配合正常。",
    "",
    "五、权限使用",
    "网络权限用于与相机建立连接；定位权限用于写入或推送 GPS；蓝牙权限仅用于向相机推送定位；通知权限用于在蓝牙推送期间保持前台服务；文件与照片权限用于读取相机回传图片、导入 LUT 与导出日志；麦克风权限仅在监看页开启「音频电平」时采样环境声做参考电平，不录音、不上传。以上权限只在对应功能开启时使用。",
    "",
    "六、使用风险与免责",
    "1. 本应用按「现状」提供，不对特定用途的适用性、无中断性或无错误作出明示或默示担保。",
    "2. 在拍摄、传输、参数调整过程中因设备故障、网络异常、操作失误或固件差异导致的数据丢失、拍摄延误或其他损失，作者不承担责任。",
    "3. 请勿在需要高度可靠性的场合（如商业交付、新闻现场）把本应用作为唯一的数据通路，建议同时保留相机存储卡内的原始文件。",
    "4. 因用户违反当地法律法规使用本应用而产生的后果，由用户自行承担。",
    "",
    "七、知识产权与商标",
    "应用代码按 Apache-2.0 许可开源；「连镜」名称与应用图标仅用于标识本项目，不得用于暗示官方认可或授权。RAW 解码使用 LibRaw 0.23.0 Snapshot202611，按 CDDL-1.0 许可随包编译，完整源码随工程提供（https://github.com/4647luo/lianjing/tree/main/app/src/main/cpp/libraw）。尼康、Nikon、NIKKOR 等商标归尼康株式会社或其权利人所有，本应用仅在说明兼容性时提及，与尼康株式会社没有任何隶属、授权、赞助或背书关系。界面中的相机照片由用户自行设置，不代表尼康官方素材。",
    "",
    "八、服务变更与终止",
    "作者可能在不另行通知的情况下调整功能、修复问题或停止维护本项目。由于本应用不依赖任何在线服务，你已安装的版本不会因服务下线而立即失效。",
    "",
    "九、适用法律",
    "本条款的解释与适用遵循中华人民共和国法律。若条款中的某一条被认定为无效，不影响其余条款的效力。",
    "",
    "十、条款更新与联系方式",
    "条款可能随功能调整而更新，更新后的内容会在应用内展示。如对本条款有疑问，可通过「反馈与建议」或发送邮件至 $FEEDBACK_MAIL 与作者联系。"
)

/** 关于类二级页共用的正文列表：统一放进一张高级材质卡，不再让文字直接铺在背景上 */
@Composable
private fun InfoLines(lines: List<String>) {
    SettingCard {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lines.forEach { line ->
                if (line.isBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                } else {
                    Text(line, fontSize = 13.sp, color = DoukColors.textSecondary, lineHeight = 20.sp)
                }
            }
        }
    }
}

private fun gpsSummary(import: Boolean, ble: Boolean): String {
    val on = buildList {
        if (import) add("导入时补充")
        if (ble) add("蓝牙推送")
    }
    return on.joinToString(" / ").ifBlank { "蓝牙推送" }
}

private fun accentOf(name: String): Color = when (name) {
    "青蓝" -> Color(0xFF2E8BF0)
    "松绿" -> Color(0xFF12A87B)
    "玫粉" -> Color(0xFFE8386D)
    "紫晶" -> Color(0xFF7A5AF0)
    "珊瑚红" -> Color(0xFFFF5A5F)
    "柠檬黄" -> Color(0xFFE6A400)
    "海盐蓝" -> Color(0xFF4C7DFF)
    "薄荷青" -> Color(0xFF12B5B0)
    "石墨灰" -> Color(0xFF5B6472)
    "自定义" -> accentFromHue(DoukPrefs.customAccentHue)
    else -> Color(0xFFFF6900)
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else null
    }

/** 反馈邮件正文：把需要用户填的内容留成空行，别让人对着空白邮件发呆。 */
private fun feedbackBody(): String = buildString {
    appendLine("版本：$APP_VERSION")
    appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    appendLine("系统：Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
    appendLine()
    appendLine("【问题描述】")
    appendLine("（简单说一下哪里不对，例如：连接相机后点快门没反应）")
    appendLine()
    appendLine("【复现步骤】")
    appendLine("1. ")
    appendLine("2. ")
    appendLine("3. ")
    appendLine()
    appendLine("【期望结果】")
    appendLine()
    appendLine("【补充说明】")
    appendLine("（相机型号、连接方式 Wi‑Fi/USB/蓝牙、是否必现，有截图可以一起附上）")
}

/** 反馈：拉邮件客户端，正文预填版本、机型和需要用户填写的模板 */
private fun sendFeedback(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_MAIL))
        putExtra(Intent.EXTRA_SUBJECT, "连镜 反馈（$APP_VERSION）")
        putExtra(Intent.EXTRA_TEXT, feedbackBody())
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "没有找到邮件客户端，可手动发到 $FEEDBACK_MAIL", Toast.LENGTH_LONG).show()
    }
}

/** 下载二级页里的一行「选项」：点开弹出单选，选完立刻落盘 */
/** GPS 二级选项：推送间隔 / 定位精度都用整屏选择页，不用弹窗 */
private data class GpsChoice(
    val title: String,
    val subtitle: String,
    val current: String,
    val options: List<String>,
    val onPick: (String) -> Unit
)

private fun accuracyName(mode: String): String = when (mode) {
    "high" -> "高精度"
    "low" -> "低功耗"
    else -> "均衡"
}

/** GPS 数据导入：三种附加方式 + 真实作用到蓝牙链路的推送间隔/定位精度/校时 */
@Composable
private fun GpsPanel(
    ble: com.lutcamera.lutcamerapro.network.nikon.ble.NikonBleGpsClient,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("douk_nikon_ble", Context.MODE_PRIVATE) }
    var importAttach by remember { mutableStateOf(DoukPrefs.gpsAttachImport) }
    var bleAttach by remember { mutableStateOf(DoukPrefs.gpsAttachBle) }
    var onlineElev by remember { mutableStateOf(DoukPrefs.onlineElevationEnabled) }
    val masterOn = true
    var interval by remember { mutableStateOf(ble.intervalMinutes.value) }
    var accuracy by remember { mutableStateOf(ble.locationAccuracy.value) }
    var syncTime by remember { mutableStateOf(ble.syncTime.value) }
    var pick by remember { mutableStateOf<GpsChoice?>(null) }

    val intervalLabel = if (interval >= 60) "1 小时" else "$interval 分钟"
    val choices = listOf(
        GpsChoice(
            "推送间隔",
            "连接相机期间，每隔多久向相机写一次当前位置",
            intervalLabel,
            listOf("1 分钟", "2 分钟", "3 分钟", "5 分钟", "10 分钟", "20 分钟", "30 分钟", "1 小时")
        ) { v ->
            interval = if (v == "1 小时") 60 else v.substringBefore(" 分钟").toIntOrNull() ?: 5
            ble.setPushInterval(interval)
        },
        GpsChoice(
            "定位精度",
            "高精度只用 GPS；均衡 GPS + 网络；低功耗只用网络",
            accuracyName(accuracy),
            listOf("高精度", "均衡", "低功耗")
        ) { v ->
            accuracy = when (v) {
                "高精度" -> "high"
                "低功耗" -> "low"
                else -> "balanced"
            }
            ble.setLocationAccuracy(accuracy)
        }
    )

    val current = pick
    if (current != null) {
        SettingsSubPage(title = current.title, onBack = { pick = null }) {
            SettingCard {
                current.options.forEachIndexed { index, item ->
                    if (index > 0) SettingDivider()
                    val active = item == current.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                current.onPick(item)
                                pick = null
                            }
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) DoukColors.accent else DoukColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = DoukColors.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                current.subtitle,
                fontSize = 12.sp,
                color = DoukColors.textTertiary,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(168.dp))
        }
        return
    }

    // 总开关与「蓝牙 GPS 附加」开关已按反馈移除：进到这个页面就是启用状态，
    // 蓝牙推送跟着蓝牙链路自动走，只保留「导入图片时附加」一个选择。
    LaunchedEffect(Unit) {
        DoukPrefs.gpsEnabled = true
        DoukPrefs.gpsAttachBle = true
        bleAttach = true
        ble.setGpsEnabled(true)
    }

    SettingsSubPage(title = "GPS 数据导入", onBack = onBack) {
        SettingCard {
            SettingSwitchRow(
                "导入图片时附加",
                "把相机照片导入应用时补上手机坐标",
                importAttach
            ) { importAttach = it; DoukPrefs.gpsAttachImport = it }
        }

        Spacer(modifier = Modifier.height(18.dp))
        SectionTitle("蓝牙推送")
        SettingCard {
            choices.forEachIndexed { index, choice ->
                if (index > 0) SettingDivider()
                SettingRow(choice.title, choice.subtitle, choice.current, enabled = masterOn) { pick = choice }
            }
            SettingDivider()
            SettingSwitchRow(
                "同时校准相机时间",
                "每次推送位置时把手机时间写入相机，避免 EXIF 时间偏差",
                syncTime,
                enabled = masterOn
            ) {
                syncTime = it
                ble.setSyncTime(it)
            }
            SettingDivider()
            SettingSwitchRow(
                "在线高程补海拔",
                "缺海拔时按经纬度查询公开高程服务；关闭后只用 GPS、气压计与本地缓存",
                onlineElev,
                enabled = masterOn
            ) {
                onlineElev = it
                DoukPrefs.onlineElevationEnabled = it
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        SectionTitle("蓝牙遥控怎么开")
        SettingCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    "1. 相机菜单 → 网络 →「连接至智能设备」→「配对」，让机身停在等待连接的界面。\n" +
                        "2. 支持 ML‑L7 的机型（如 Z50II / Z6III / Z8）也可从菜单进入「蓝牙遥控 / 遥控器配对」。\n" +
                        "3. 回到设备页 → 蓝牙 →「扫描蓝牙相机」，选中相机后点「连接遥控」。\n" +
                        "4. 配对成功后可按住快门、切换录像；蓝牙遥控与 GPS 推送共用链路，二者只能同时用一个。",
                    fontSize = 12.sp,
                    color = DoukColors.textSecondary,
                    lineHeight = 19.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        SectionTitle("最近一次推送")
        SettingCard {
            val lastAt = sp.getLong("last_push_at", 0L)
            val lastPlace = sp.getString("last_push_place", "") ?: ""
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    if (lastAt > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date(lastAt)) else "还没有成功推送",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = DoukColors.textPrimary
                )
                if (lastPlace.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(lastPlace, fontSize = 12.sp, color = DoukColors.textSecondary)
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    "以相机回执为准，只有相机确认收到位置才会更新这里",
                    fontSize = 11.sp,
                    color = DoukColors.textTertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "相机端需在菜单里开启「位置数据」并允许蓝牙写入；尼康没有开放 PTP/IP 的 GPS 写入口，所以位置只走蓝牙 GEO。高精度持续使用 GPS 更耗电；低功耗只用网络定位，室内也可用但精度较低。推送间隔在下一轮推送生效，定位精度在下次连接相机时生效。",
            fontSize = 12.sp,
            color = DoukColors.textTertiary,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(30.dp))
    }
}

private data class DownloadOption(
    val title: String,
    val subtitle: String,
    val value: String,
    val options: List<String>,
    val onPick: (String) -> Unit
)

/**
 * 设置页所有二级页共用的外壳：顶部返回 + 标题，内容整屏滚动。
 * 设置项点开都进这里，不再用弹窗压住原页面。
 */
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = DoukSpace.page, top = DoukSpace.sm, bottom = DoukSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = DoukColors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(title, style = DoukType.h1, color = DoukColors.textPrimary)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollable) Modifier.verticalScroll(rememberScrollState())
                    else Modifier
                )
                .padding(horizontal = DoukSpace.page)
        ) {
            Spacer(modifier = Modifier.height(DoukSpace.sm))
            content()
        }
    }
}

/** 参数排序页的「拍摄 / 视频」切换胶囊。 */
@Composable
private fun SortModePill(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (active) DoukColors.accent else DoukColors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) Color.White else DoukColors.textSecondary
        )
    }
}

/** 子页里的一级动作按钮：整行、胶囊圆角、主题色。 */
@Composable
private fun SheetPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled) DoukColors.accent else DoukColors.accent.copy(alpha = 0.22f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.55f)
        )
    }
}

/**
 * 「自动下载」与「下载保护文件」共用的一屏：顶部返回 + 开关 + 图片/视频的
 * 大小、格式、保存位置。两个入口各自 remember 自己的状态，互不影响。
 */
@Composable
private fun DownloadPanel(
    title: String,
    enabledTitle: String,
    enabledSubtitle: String,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    options: List<DownloadOption>,
    onBack: () -> Unit,
    footnote: String? = null,
    // 顶部总开关关闭时，下面所有回传规则一起置灰、点不动
    optionsEnabled: Boolean = true,
    extraContent: @Composable ColumnScope.() -> Unit = {}
) {
    BackHandler(onBack = onBack)
    var pick by remember { mutableStateOf<DownloadOption?>(null) }

    val current = pick
    if (current != null) {
        // 二级选择同样整屏进入，不再弹窗
        SettingsSubPage(title = current.title, onBack = { pick = null }) {
            SettingCard {
                current.options.forEachIndexed { index, item ->
                    if (index > 0) SettingDivider()
                    val active = item == current.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                current.onPick(item)
                                pick = null
                            }
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) DoukColors.accent else DoukColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = DoukColors.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = DoukSpace.page, top = DoukSpace.sm, bottom = DoukSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = DoukColors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(title, style = DoukType.h1, color = DoukColors.textPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DoukSpace.page)
        ) {
            SettingCard {
                SettingSwitchRow(
                    title = enabledTitle,
                    subtitle = enabledSubtitle,
                    checked = enabled,
                    onCheckedChange = onEnabled
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            SectionTitle("回传规则")
            SettingCard {
                options.forEachIndexed { index, option ->
                    if (index > 0) SettingDivider()
                    SettingRow(option.title, option.subtitle, option.value, enabled = optionsEnabled) {
                        if (optionsEnabled) pick = option
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            extraContent()

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                footnote ?: if (enabled) "已开启：符合上面规则的照片与视频会在连接相机后排队回传。" else
                    "当前是关闭状态：不会自动回传，只能在图库里手动保存单张。",
                style = DoukType.caption,
                color = DoukColors.textTertiary,
                modifier = Modifier.padding(horizontal = DoukSpace.xxs)
            )
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

/** 「导出预设」独立整屏：逐组勾选或全选，不弹窗 */
@Composable
private fun PresetExportPanel(
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit
) {
    BackHandler(onBack = onBack)
    val allSelected = selection.size == DoukPreset.groups.size
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = DoukSpace.page, top = DoukSpace.sm, bottom = DoukSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = DoukColors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("导出预设", style = DoukType.h1, color = DoukColors.textPrimary)
                Text(
                    "勾选要带到另一台设备的内容",
                    style = DoukType.caption,
                    color = DoukColors.textSecondary
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DoukSpace.page)
        ) {
            SettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectionChange(
                                if (allSelected) emptySet() else DoukPreset.allSelection()
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("全选", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textPrimary)
                        Text(
                            "设备、下载、通用、外观、图库、图像编辑、LUT 与滤镜",
                            fontSize = 11.sp,
                            color = DoukColors.textSecondary
                        )
                    }
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = DoukColors.accent,
                            uncheckedColor = DoukColors.textTertiary
                        )
                    )
                }
                DoukPreset.groups.forEach { group ->
                    SettingDivider()
                    val checked = group.key in selection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectionChange(
                                    if (checked) selection - group.key else selection + group.key
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(group.title, fontSize = 14.sp, color = DoukColors.textPrimary)
                            Text(group.subtitle, fontSize = 11.sp, color = DoukColors.textSecondary)
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = DoukColors.accent,
                                uncheckedColor = DoukColors.textTertiary
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "导出为 .zip 预设包，勾选「LUT 与滤镜」时会连 .cube / .xmp 原文件一起打包。导入时只覆盖所选分组，没选的组保持不变。",
                fontSize = 12.sp,
                color = DoukColors.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DoukSpace.page, vertical = DoukSpace.sm)
        ) {
            SheetPrimaryButton(
                if (selection.isEmpty()) "请至少选择一组" else "导出 ${selection.size} 组预设"
            ) {
                if (selection.isNotEmpty()) onExport()
            }
        }
    }
}

/** 反馈与建议：两个入口 —— 只保存诊断包，或打开邮件（诊断包可选，默认带上）。 */
@Composable
private fun FeedbackPanel(
    onBack: () -> Unit,
    onSaveDiagnostics: () -> Unit,
    onSendMail: (Boolean) -> Unit
) {
    BackHandler(onBack = onBack)
    var showMailDialog by remember { mutableStateOf(false) }
    var attachLogs by remember { mutableStateOf(true) }

    SettingsSubPage(title = "反馈与建议", onBack = onBack) {
        Text(
            "遇到闪退、连不上相机、按钮没反应，把诊断包一起发过来最快。诊断包只含设备信息、运行日志、相机协议交互和崩溃堆栈，不含照片、视频和 LUT 原文件。",
            fontSize = 12.sp,
            color = DoukColors.textSecondary,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingCard {
            SettingRow("保存诊断日志", "生成 ZIP 存到手机，之后自己决定怎么发") {
                onSaveDiagnostics()
            }
            SettingDivider()
            SettingRow(
                "发送邮箱",
                "收件人已填 $FEEDBACK_MAIL；正文带问题模板，诊断日志可勾选",
            ) { showMailDialog = true }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "邮件由你本机的邮件客户端发出，应用不会在后台自动发送任何内容。收件邮箱：$FEEDBACK_MAIL",
            fontSize = 12.sp,
            color = DoukColors.textTertiary,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(30.dp))
    }

    if (showMailDialog) {
        AlertDialog(
            onDismissRequest = { showMailDialog = false },
            title = { Text("发送反馈邮件", color = DoukColors.textPrimary) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { attachLogs = !attachLogs },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = attachLogs,
                            onCheckedChange = { attachLogs = it },
                            colors = CheckboxDefaults.colors(checkedColor = DoukColors.accent)
                        )
                        Text(
                            "附带诊断日志（推荐）",
                            fontSize = 14.sp,
                            color = DoukColors.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "诊断包含设备与系统信息、运行日志、相机协议交互记录、崩溃堆栈；不含照片、视频和 LUT 原文件。",
                        fontSize = 12.sp,
                        color = DoukColors.textTertiary,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMailDialog = false
                        onSendMail(attachLogs)
                    }
                ) { Text("发送邮箱", color = DoukColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showMailDialog = false }) {
                    Text("取消", color = DoukColors.textTertiary)
                }
            }
        )
    }
}

/** 「关于」单独一屏：从设置页点关于入口才进来，里面才是各个分类 */
@Composable
private fun AboutPanel(
    onBack: () -> Unit,
    onCheckUpdate: () -> Unit,
    onChangelog: () -> Unit,
    onFeedback: () -> Unit,
    onTerms: () -> Unit,
    onDonate: () -> Unit
) {
    BackHandler(onBack = onBack)
    // 权限说明已经从「通用」搬到关于里，仍然是一个独立全屏页
    var showPermission by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        SettingsSubPage(title = "开源许可", onBack = { showLicenses = false }) {
            InfoLines(OPEN_SOURCE_LINES)
        }
        return
    }
    if (showPermission) {
        SettingsSubPage(title = "权限说明", onBack = { showPermission = false }) {
            Text(
                "下面按功能列出每一项系统权限的用途。只有你开启对应功能时才会真正调用，全部记录只留在本机。",
                fontSize = 12.sp,
                color = DoukColors.textSecondary,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    PermissionLine("网络（Wi-Fi / 本地网络）", "与相机建立 PTP/IP 连接，走 15740 端口；也用于读取详情页的地图瓦片。")
                    PermissionLine("USB 设备", "以 PTP 方式读取相机机身照片与参数，只在你插线并点允许后生效。")
                    PermissionLine("位置", "拍摄时把手机 GPS 写进照片或推送给相机；蓝牙 GPS 推送同样依赖它。")
                    PermissionLine("附近的设备（蓝牙）", "只用于向相机推送定位，不承担图传与遥控。")
                    PermissionLine("通知", "连上相机或蓝牙推送期间保持前台服务，避免后台被系统冻结。")
                    PermissionLine("照片与文件", "读取相机回传的图片、导入 LUT 与导出日志。")
                    PermissionLine("手机相册", "仅在图库切到「手机」来源时读取缩略图，不上传、不修改。")
                    PermissionLine("麦克风", "仅在监看页开启「音频电平」时采样环境声做参考电平，不录音、不上传。")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "除可关闭的在线高程查询外，以上权限只在对应功能开启时使用，数据不上传到任何服务器。",
                fontSize = 12.sp,
                color = DoukColors.textTertiary
            )
            Spacer(modifier = Modifier.height(30.dp))
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = DoukSpace.page, top = DoukSpace.sm, bottom = DoukSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = DoukColors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                "关于",
                style = DoukType.h1,
                color = DoukColors.textPrimary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DoukSpace.page)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DoukShapes.card)
                    .doukFlatSurface(DoukShapes.card)
                    .padding(vertical = DoukSpace.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(DoukShapes.card),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.douk_app_icon),
                        contentDescription = "连镜",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.height(DoukSpace.sm))
                Text("连镜", style = DoukType.h2, color = DoukColors.textPrimary)
                Spacer(modifier = Modifier.height(3.dp))
                Text("版本 $APP_VERSION", style = DoukType.caption, color = DoukColors.textSecondary)
            }

            Spacer(modifier = Modifier.height(18.dp))
            SettingCard {
                SettingRow("检查更新", "从 GitHub 检查最新版本") { onCheckUpdate() }
                SettingDivider()
                SettingRow("更新日志", "只看当前版本及以后") { onChangelog() }
                SettingDivider()
                SettingRow("反馈与建议", "保存日志或发邮件给作者") { onFeedback() }
                SettingDivider()
                SettingRow("使用条款", "数据使用与免责说明") { onTerms() }
                SettingDivider()
                SettingRow("权限说明", "各功能需要的系统权限") { showPermission = true }
                SettingDivider()
                SettingRow("开源许可", "第三方组件、许可证与源码地址") { showLicenses = true }
                SettingDivider()
                SettingRow("支持作者", "扫码赞赏") { onDonate() }
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun InfoDialog(title: String, lines: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("知道了") } },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                lines.forEach { line ->
                    if (line.isBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    } else {
                        Text(
                            line,
                            fontSize = 13.sp,
                            color = DoukColors.textSecondary,
                            modifier = Modifier.padding(vertical = 3.dp),
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun StorageLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = DoukColors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = DoukColors.textSecondary)
    }
}

@Composable
private fun PermissionLine(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DoukColors.textPrimary)
        Spacer(modifier = Modifier.height(3.dp))
        Text(body, fontSize = 12.sp, color = DoukColors.textSecondary, lineHeight = 18.sp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = DoukType.section,
        color = DoukColors.textSecondary,
        modifier = Modifier.padding(bottom = DoukSpace.xs, start = DoukSpace.xxs)
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DoukShapes.cardSmall)
            .doukFlatSurface(DoukShapes.cardSmall)
    ) {
        content()
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DoukSpace.md, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = DoukType.body, fontWeight = FontWeight.Medium, color = DoukColors.textPrimary)
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, style = DoukType.caption, color = DoukColors.textSecondary)
            }
        }
        if (value != null) {
            Text(value, style = DoukType.caption, color = DoukColors.textTertiary, modifier = Modifier.padding(end = 6.dp))
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = DoukColors.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = DoukSpace.md, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = DoukType.body, fontWeight = FontWeight.Medium, color = DoukColors.textPrimary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, style = DoukType.caption, color = DoukColors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = DoukColors.accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = DoukColors.surfaceMuted
            )
        )
    }
}

@Composable
private fun SettingDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DoukSpace.md)
            .height(1.dp)
            .background(DoukColors.divider)
    )
}

/** Context 可能是 ContextThemeWrapper，逐层找到 Activity 才能 recreate。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}

