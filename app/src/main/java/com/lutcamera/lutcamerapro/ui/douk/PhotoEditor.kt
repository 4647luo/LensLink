package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness3
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.CropLandscape
import androidx.compose.material.icons.rounded.CropPortrait
import androidx.compose.material.icons.rounded.CropRotate
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Details
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.pow

/**
 * 一张照片的编辑参数。所有值都是「相对原图」的增量，撤销就是回到上一个快照。
 *
 * 强度统一用 -100..100 的整数滑块，避免用户在浮点上纠结；
 * 90 度旋转与翻转是几何量，单独记。
 */
internal data class EditState(
    val quarterTurns: Int = 0,
    val fineRotate: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val cropRatio: String = "原始",
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlight: Float = 0f,
    val shadow: Float = 0f,
    val saturation: Float = 0f,
    val vibrancy: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val sharpen: Float = 0f,
    val clarity: Float = 0f,
    val grain: Float = 0f,
    val denoise: Float = 0f,
    /** HSL：色相 -180..180，饱和度 / 明度 -100..100；hslRange 是 6 个目标色相之一 */
    val hslHue: Float = 0f,
    val hslSat: Float = 0f,
    val hslLight: Float = 0f,
    val hslRange: Int = 0,
    /** 曲线五个固定横坐标控制点（0、0.25、0.5、0.75、1）的纵坐标，0..1 */
    val curve: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    val curveR: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    val curveG: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    val curveB: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    /** 滤镜强度：0 = 原图，100 = 全量 */
    val filterAmount: Float = 100f,
    /** 自由裁剪框：归一化 0..1，cropFree 为 false 时用 cropRatio 居中裁切 */
    val cropFree: Boolean = false,
    val cropL: Float = 0f,
    val cropT: Float = 0f,
    val cropR: Float = 1f,
    val cropB: Float = 1f,
    val filter: String = "原图",
    /** HDR 高光扩展强度：0 = 关闭增益，100 = 按原片 / 反推算法全量 */
    val hdrStrength: Float = 100f,
    /** HDR 高光层次：0 = 中高光一起抬，100 = 只抬最亮的高光 */
    val hdrHighlight: Float = 60f,
    /** HDR 暗部细节：0 = 不改，100 = 暗部最多抬 22% */
    val hdrShadow: Float = 0f,
    /** HDR 色彩浓度：50 = 原色，100 = 更浓，0 = 更淡 */
    val hdrColor: Float = 50f,
    /** HDR 色温：-100 = 冷，0 = 中性，100 = 暖 */
    val hdrWarmth: Float = 0f
) {
    /** 有没有做过任何改动，决定「还原」按钮要不要亮 */
    val isPristine: Boolean
        get() = this == EditState()
}

/** 调节项：名字 + 取值 + 写回；icon 用于功能卡，曲线 / HSL 是进入二级面板的入口。 */
private data class AdjustItem(
    val label: String,
    val icon: ImageVector,
    val get: (EditState) -> Float,
    val set: (EditState, Float) -> EditState
)

private val ADJUSTS = listOf(
    AdjustItem("曝光", Icons.Rounded.WbSunny, { it.exposure }) { s, v -> s.copy(exposure = v) },
    AdjustItem("亮度", Icons.Rounded.Brightness6, { it.brightness }) { s, v -> s.copy(brightness = v) },
    AdjustItem("对比度", Icons.Rounded.Contrast, { it.contrast }) { s, v -> s.copy(contrast = v) },
    AdjustItem("高光", Icons.Rounded.Highlight, { it.highlight }) { s, v -> s.copy(highlight = v) },
    AdjustItem("阴影", Icons.Rounded.Brightness3, { it.shadow }) { s, v -> s.copy(shadow = v) },
    AdjustItem("饱和度", Icons.Rounded.Palette, { it.saturation }) { s, v -> s.copy(saturation = v) },
    AdjustItem("自然饱和度", Icons.Rounded.ColorLens, { it.vibrancy }) { s, v -> s.copy(vibrancy = v) },
    AdjustItem("色温", Icons.Rounded.Thermostat, { it.temperature }) { s, v -> s.copy(temperature = v) },
    AdjustItem("色调", Icons.Rounded.Tune, { it.tint }) { s, v -> s.copy(tint = v) },
    AdjustItem("锐化", Icons.Rounded.AutoFixHigh, { it.sharpen }) { s, v -> s.copy(sharpen = v) },
    AdjustItem("清晰度", Icons.Rounded.Details, { it.clarity }) { s, v -> s.copy(clarity = v) },
    AdjustItem("颗粒", Icons.Rounded.Grain, { it.grain }) { s, v -> s.copy(grain = v) },
    AdjustItem("降噪", Icons.Rounded.BlurOn, { it.denoise }) { s, v -> s.copy(denoise = v) },
    AdjustItem("HSL", Icons.Rounded.Gradient, { 0f }, { s, _ -> s }),
    AdjustItem("曲线", Icons.Rounded.Timeline, { 0f }, { s, _ -> s })
)

/** 调节的三个二级分组；曲线和 HSL 放在色彩组里，点开是整块二级面板。 */
private val ADJUST_GROUPS: List<Pair<String, List<String>>> = listOf(
    "光效" to listOf("曝光", "亮度", "对比度", "高光", "阴影"),
    "色彩" to listOf("饱和度", "自然饱和度", "色温", "色调", "HSL", "曲线"),
    "细节" to listOf("锐化", "清晰度", "颗粒", "降噪")
)

/** HSL 六种目标色相（度）与对应圆点颜色。 */
private val HSL_RANGES = listOf(
    0f to Color(0xFFD64545),
    30f to Color(0xFFE2864A),
    60f to Color(0xFFE9D35A),
    120f to Color(0xFF6FD45A),
    205f to Color(0xFF5B87E0),
    280f to Color(0xFF9A5BD6)
)

/** 滤镜预设：一组真正的图像参数，强度 0..100 按比例混回原图。 */
private data class FilterLook(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val mono: Float = 0f
)

private val FILTERS = listOf(
    "原图", "鲜明", "柔和", "胶片", "复古", "黑白", "高反差黑白", "冷调", "暖阳",
    "青橙", "森系", "日落", "夜景", "通透", "低饱和", "奶油", "港风", "赛博"
)

private val FILTER_LOOKS: Map<String, FilterLook> = mapOf(
    "鲜明" to FilterLook(contrast = 16f, saturation = 36f),
    "柔和" to FilterLook(contrast = -14f, saturation = -6f, fade = 10f),
    "胶片" to FilterLook(contrast = 10f, saturation = -8f, temperature = 8f, fade = 16f),
    "复古" to FilterLook(contrast = 8f, saturation = -20f, temperature = 18f, tint = 6f, fade = 20f),
    "黑白" to FilterLook(mono = 100f),
    "高反差黑白" to FilterLook(mono = 100f, contrast = 38f),
    "冷调" to FilterLook(temperature = -24f),
    "暖阳" to FilterLook(exposure = 5f, temperature = 24f, saturation = 10f),
    "青橙" to FilterLook(contrast = 18f, saturation = 12f, temperature = 14f, tint = -10f),
    "森系" to FilterLook(contrast = -6f, saturation = -4f, temperature = -8f, tint = 8f),
    "日落" to FilterLook(exposure = 4f, temperature = 30f, saturation = 16f),
    "夜景" to FilterLook(exposure = -12f, contrast = 22f, temperature = -16f),
    "通透" to FilterLook(exposure = 4f, contrast = 18f, saturation = 8f),
    "低饱和" to FilterLook(saturation = -36f),
    "奶油" to FilterLook(exposure = 8f, contrast = -16f, temperature = 6f, fade = 22f),
    "港风" to FilterLook(contrast = 14f, saturation = -10f, temperature = 10f, tint = 12f),
    "赛博" to FilterLook(contrast = 26f, saturation = 26f, temperature = -26f, tint = 16f)
)

private val CROP_RATIOS = listOf("原始", "1:1", "3:4", "4:3", "9:16", "16:9")

/**
 * 新编辑器跟随 App 的浅色主题：暖白画布 + 白色控制台，
 * 主色直接取全局 accent，设置页换色时编辑器同步变化。
 */
private object EditorPalette {
    val canvas = Color(0xFFEFEAE2)
    val panel = Color(0xFFFFFDF9)
    val panelRaised = Color(0xFFF3EEE5)
    val panelSoft = Color(0xFFF8F4ED)
    val line = Color(0xFFE0D7CA)
    val lineSoft = Color(0xFFEDE6DC)
    val text = Color(0xFF24211C)
    val muted = Color(0xFF706A60)
    val dim = Color(0xFFA39B8E)
    val accent: Color get() = DoukColors.accent
    val accentSoft: Color get() = DoukColors.accent.copy(alpha = 0.14f)
    val accentText = Color(0xFFFFFBF6)
}

private data class EditorTab(val name: String, val icon: ImageVector)

private val EDITOR_TABS = listOf(
    EditorTab("裁剪", Icons.Rounded.Crop),
    EditorTab("调节", Icons.Rounded.Tune),
    EditorTab("滤镜", Icons.Rounded.FilterVintage),
    EditorTab("HDR", Icons.Rounded.HdrOn)
)

/** 编辑器 HDR 预览层：GainMap 位图 + 它对应的 ISO 21496-1 参数。 */
private data class HdrPreviewLayer(
    val gain: Bitmap,
    val ratioMax: Float,
    val ratioMin: Float = 1f,
    val gamma: Float = 1f,
    val epsSdr: Float = 1f / 64f,
    val epsHdr: Float = 1f / 64f,
    val displayRatioFull: Float = ratioMax,
    val minDisplayRatio: Float = 1f,
    val fromSource: Boolean = false,
    val fromRaw: Boolean = false
)

/** HDR 专属调色：只作用于 HDR 预览和 HDR 导出的底图，不污染普通 SDR 输出。 */
private data class HdrGrade(
    val shadow: Float = 0f,
    val color: Float = 50f,
    val warmth: Float = 0f
) {
    val isNeutral: Boolean
        get() = shadow <= 0.5f && kotlin.math.abs(color - 50f) <= 0.5f &&
            kotlin.math.abs(warmth) <= 0.5f

    companion object {
        fun from(s: EditState) = HdrGrade(
            shadow = s.hdrShadow.coerceIn(0f, 100f),
            color = s.hdrColor.coerceIn(0f, 100f),
            warmth = s.hdrWarmth.coerceIn(-100f, 100f)
        )
    }
}

private val ADJUST_MAX = 100f
/**
 * 拖动滑杆时的快速预览长边。
 *
 * 512px 逐像素算色 + 曲线 / LUT，在手机上单帧常常超过 20ms；手指快速拖动时
 * 旧的一帧还没算完，新的一帧已经在排队，画面就会慢半拍。降到 420px 后像素量
 * 仍只有 512px 的 67%，既比原来的 320px 清楚，又能配合「只保留最新一帧」队列跟手。
 * 松手后仍按设置里的 previewSide 重算高清图。
 */
private const val FAST_PREVIEW_SIDE = 420

/** 编辑解码/导出长边上限：9000 能原尺寸容纳 Z8/Z9/D850 的 8256×5504（约 45MP），不再缩到 7300。 */
private const val MAX_EXPORT_SIDE = 9000
/** 预览源图上限保持 7300，避免一打开编辑器就多占 40MB；导出会重新按原图解码。 */
private const val MAX_EDIT_DECODE_SIDE = 7300

/**
 * RAW 编辑预览的长边：2800 对手机屏幕已经足够，且能触发 LibRaw half_size。
 * Z50II 的 HE★ 全尺寸解压约 40 秒，半尺寸可显著缩短打开等待；导出仍按
 * [MAX_EXPORT_SIDE] 重新解全尺寸，预览降采样不会影响最终成片。
 */
private const val RAW_PREVIEW_SIDE = 2800

/** 一次预览渲染请求；seq 用来丢弃已经过期的结果，避免画面倒退。 */
private data class RenderRequest(
    val seq: Int,
    val base: Bitmap,
    val state: EditState,
    val quick: Boolean,
    val activeDetail: String,
    val filterRuntime: FilterRuntime
)

/**
 * 图片编辑器。真按参数算像素，不做假按钮：
 * 预览默认在长边 3072 的副本上算（滑动用 320 快速图，松手再精算），
 * 保存时对原图（长边最多 7300）重算一遍再落盘。
 */
internal object EditorChrome {
    var open by mutableStateOf(false)
}

@Composable
internal fun PhotoEditorScreen(
    item: GalleryItem,
    source: ImageBitmap?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    initialHdrPreview: Boolean = false
) {
    DisposableEffect(Unit) {
        EditorChrome.open = true
        onDispose { EditorChrome.open = false }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(EditState()) }
    var undoStack by remember { mutableStateOf(listOf(EditState())) }
    var undoIndex by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf("裁剪") }
    var activeAdjust by remember { mutableStateOf(ADJUSTS.first()) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var comparePressed by remember { mutableStateOf(false) }
    var filterThumbs by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var filterRuntime by remember { mutableStateOf(FilterRuntime()) }
    var filterEntries by remember {
        mutableStateOf(FILTERS.map { FilterEntry(it, it, "自带", kind = "滤镜", use = "图片") })
    }
    var filterCategory by remember { mutableStateOf("自带") }
    var filterLibraryVersion by remember { mutableIntStateOf(0) }
    var previewEpoch by remember { mutableStateOf(0) }
    var highQualityPreview by remember { mutableStateOf(true) }
    var fastBase by remember { mutableStateOf<Bitmap?>(null) }
    var hdBase by remember { mutableStateOf<Bitmap?>(null) }
    // 手机竖屏把底部工具区收紧；高度固定后，切「裁剪/调节/滤镜」预览区不会再跳上跳下。
    val compactTools = LocalConfiguration.current.screenHeightDp < 700
    // 手机竖屏要同时放下滑杆和调节分组按钮；工具区太矮会把下面一排按钮挤出屏幕。
    val toolsHeight = if (compactTools) 324.dp else 356.dp
    var hdrPreview by remember(item.stableKey) { mutableStateOf(initialHdrPreview) }
    var hdrPreviewLayer by remember(item.stableKey) { mutableStateOf<HdrPreviewLayer?>(null) }
    var hdrPreviewBitmap by remember(item.stableKey) { mutableStateOf<Bitmap?>(null) }
    // 窗口整体下移导航栏高度后，状态栏已经在窗口外面，标题栏不再重复加
    // statusBarsPadding，否则顶部会空出 60px。
    var windowLiftPx by remember(item.stableKey) { mutableIntStateOf(0) }

    // ---- 编辑源格式：同一张照片可能同时有 JPG / HEIC / RAW，先选再编辑 ----
    val availableFormats = remember(item.stableKey) {
        val seen = LinkedHashSet<String>()
        item.formats.forEach { f ->
            val u = f.uppercase()
            if (u.isNotBlank()) seen.add(if (u == "JPG") "JPG" else u)
        }
        if (seen.isEmpty()) {
            item.fileName.substringAfterLast('.', "JPG").uppercase().let { seen.add(it) }
        }
        seen.toList()
    }
    val rawExts = setOf("NEF", "DNG", "NRW", "CR2", "CR3", "ARW", "RAF", "ORF", "RW2")
    var srcFormat by remember(item.stableKey) {
        val preferred = DoukPrefs.defaultEditFormat.takeIf { it.isNotBlank() }
            ?.let { want ->
                availableFormats.firstOrNull { it.equals(want, ignoreCase = true) }
                    ?: if (want == "RAW") availableFormats.firstOrNull { it in rawExts } else null
            }
        mutableStateOf(
            preferred
                // 没设默认时：RAW 在编辑里只能拿内嵌预览，色彩不如 JPG，先给非 RAW
                ?: availableFormats.firstOrNull { it !in rawExts }
                ?: availableFormats.first()
        )
    }
    var editSource by remember(item.stableKey) { mutableStateOf(source) }
    // 同一张同时有 JPG 和 RAW、且设置里没指定默认格式时，进编辑先问一句用哪种
    var showSourceSheet by remember(item.stableKey) {
        mutableStateOf(availableFormats.size > 1 && DoukPrefs.defaultEditFormat.isBlank())
    }

    // 滤镜页里直接导入 LUT：选中的 .cube/.3dl/.look 拷进资源目录，
    // 并在本次导入时强制标成「通用」，避免文件名带 Log 时被默认判成仅视频。
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = editorDisplayName(context, uri)
            ?: "lut-${System.currentTimeMillis()}.cube"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("cube", "3dl", "look", "np3", "ncp", "np2")) {
            message = "LUT 导入失败：只支持 .cube/.3dl/.look/.np3/.ncp/.np2"
            return@rememberLauncherForActivityResult
        }
        val dst = File(DoukPrefs.lutDir(context), name)
        runCatching {
            val declared = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()
            if (declared != null && declared > com.lutcamera.lutcamerapro.util.BoundedIo.MAX_LUT_BYTES) {
                error("LUT 超过 32MB 上限")
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output ->
                    com.lutcamera.lutcamerapro.util.BoundedIo.copyWithLimit(
                        input, output, com.lutcamera.lutcamerapro.util.BoundedIo.MAX_LUT_BYTES
                    )
                }
            } ?: error("无法读取这个文件")
        }.onSuccess {
            DoukPrefs.setResourceUse(name, "通用")
            filterLibraryVersion++
            filterCategory = "LUT"
            message = "已导入 LUT：$name"
        }.onFailure {
            runCatching { if (dst.exists()) dst.delete() }
            message = "LUT 导入失败：${it.message ?: "无法读取文件"}"
        }
    }

    // ---- 导出设置：格式 / 质量 / HDR ----
    var showExportSheet by remember { mutableStateOf(false) }
    // 设置里给了默认导出格式就不必每次问；没给才在面板里选
    val fixedExportFormat = DoukPrefs.defaultExportFormat.takeIf { it.isNotBlank() }
    var exportFormat by remember(item.stableKey) {
        mutableStateOf(fixedExportFormat ?: DoukPrefs.editorFormat)
    }
    var exportQuality by remember { mutableIntStateOf(DoukPrefs.editorQuality) }
    // RAW 编辑出来的结果默认就带 HDR 效果（用户要求 RAW 导出直接是 HDR）
    var exportHdr by remember(item.stableKey) { mutableStateOf(srcFormat in rawExts) }
    val showGrid = remember { DoukPrefs.editorGrid }
    val canCompare = remember { DoukPrefs.editorCompare }
    val previewSide = remember { DoukPrefs.editorPreview }

    // 画一笔进一次历史：点按类改动直接 push 快照，滑杆类拖动时只改当前值，松手才记一步
    fun push(next: EditState) {
        highQualityPreview = true
        state = next
        if (undoStack.size > undoIndex && undoStack[undoIndex] == next) return
        val kept = undoStack.take(undoIndex + 1)
        undoStack = (kept + next).takeLast(40)
        undoIndex = undoStack.lastIndex
    }

    fun live(next: EditState) {
        highQualityPreview = false
        state = next
    }

    fun commit() {
        highQualityPreview = true
        if (undoStack.size > undoIndex && undoStack[undoIndex] == state) return
        val kept = undoStack.take(undoIndex + 1)
        undoStack = (kept + state).takeLast(40)
        undoIndex = undoStack.lastIndex
        previewEpoch++
    }

    fun undo() {
        if (undoIndex > 0) {
            undoIndex -= 1
            highQualityPreview = true
            state = undoStack[undoIndex]
        }
    }

    fun redo() {
        if (undoIndex < undoStack.lastIndex) {
            undoIndex += 1
            highQualityPreview = true
            state = undoStack[undoIndex]
        }
    }

    // 切换编辑源格式：从同一张照片的 JPG / HEIC / RAW 里换一张底座
    LaunchedEffect(item.stableKey, srcFormat) {
        val file = withContext(Dispatchers.IO) { resolveSourceFile(item, srcFormat) }
        if (file == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            if (ImageDecode.isRawFile(file.name)) {
                // RAW 编辑预览走 LibRaw 真解码：手机屏幕用 2800 长边足够，
                // 触发 half_size 后 Z50II HE★ 预览约 3 秒；真正保存时再按
                // MAX_EXPORT_SIDE 重新解全尺寸。
                RawDecode.decode(file, RAW_PREVIEW_SIDE, preview = true)?.asImageBitmap()
                    ?: ImageDecode.extractEmbeddedPreview(file, MAX_EDIT_DECODE_SIDE)
            } else {
                ImageDecode.decodeFile(file, MAX_EDIT_DECODE_SIDE)
            }
        } ?: return@LaunchedEffect
        if (loaded.asAndroidBitmap() !== editSource?.asAndroidBitmap()) editSource = loaded
        // 切到 RAW 源时默认打开 HDR 导出：RAW 的高光细节本身就是为了 Ultra HDR 用的。
        if (srcFormat in rawExts && android.os.Build.VERSION.SDK_INT >= 34) exportHdr = true
    }

    // 预览底座只缩一次：快速档与高清档各缓存一张，拖动时不再从原图重复缩放
    LaunchedEffect(item.stableKey, editSource, previewSide) {
        val bmp = editSource?.asAndroidBitmap()
        if (bmp != null) {
            val pair = withContext(Dispatchers.IO) {
                scaleToMax(bmp, FAST_PREVIEW_SIDE) to scaleToMax(bmp, previewSide)
            }
            fastBase = pair.first
            hdBase = pair.second
        }
    }

    // 滤镜：读取资源页导入的 LUT / 尼康预设，生成缩略图
    LaunchedEffect(item.stableKey, editSource, filterLibraryVersion) {
        val bmp = editSource?.asAndroidBitmap()
        val library = withContext(Dispatchers.IO) { FilterLibrary.load(context) }
        val runtime = library.runtime
        val entries = FILTERS.map {
            FilterEntry(it, it, "自带", kind = "滤镜", use = "图片")
        } + library.entries.filter { it.forPhoto }
        filterRuntime = runtime
        filterEntries = entries
        filterThumbs = if (bmp == null) emptyMap() else withContext(Dispatchers.IO) {
            val small = scaleToMax(bmp, 160)
            // 这里必须用本次加载出来的 runtime/entries，不能回头读刚赋值的
            // Compose 状态：否则导入滤镜的缩略图会拿到空 runtime，缩略图缺失。
            entries.associate { entry ->
                entry.id to runCatching {
                    // render() 是原地改像素（putPixels 返回 this），每个滤镜必须拿独立副本，
                    // 否则 18 个缩略图全指向同一张 bitmap，看起来就都跟原图一样。
                    val copy = small.copy(Bitmap.Config.ARGB_8888, true) ?: return@runCatching null
                    renderPreview(copy, EditState(filter = entry.id, filterAmount = 100f), 128, runtime)
                        .asImageBitmap()
                }.getOrNull()
            }.filterValues { it != null }.mapValues { it.value!! }
        }
    }

    // 预览：拖动时用 560px 底座快算、跳过细节滤镜；松手立刻换高清底座补一张
    val renderState = if (state.cropFree && tab == "裁剪") {
        state.copy(
            cropFree = false,
            cropRatio = "原始",
            cropL = 0f,
            cropT = 0f,
            cropR = 1f,
            cropB = 1f
        )
    } else state
    // 预览渲染队列：容量 1 且 CONFLATED，只保留最新的一个参数组合。
    // 以前每个滑杆位移都起一个 render，旧任务没法中断，快速拖动时会排队；
    // 这里由一个常驻 worker 顺序消费，新请求直接挤掉还没开始跑的旧请求。
    val renderQueue = remember(item.stableKey) { Channel<RenderRequest>(Channel.CONFLATED) }
    val renderSeq = remember(item.stableKey) { AtomicInteger(0) }

    LaunchedEffect(item.stableKey) {
        for (request in renderQueue) {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val copy = request.base.copy(Bitmap.Config.ARGB_8888, true)
                        ?: return@runCatching null
                    render(
                        copy,
                        request.state,
                        request.filterRuntime,
                        quick = request.quick,
                        activeDetail = request.activeDetail
                    ).asImageBitmap()
                }.getOrNull()
            }
            // 手指已经移到新位置时，这一帧就是过期帧：丢掉，等队里最新的那帧
            if (request.seq == renderSeq.get()) {
                if (result != null) preview = result
                working = false
            }
        }
    }

    // 编辑中的 HDR 预览：底图仍是当前调好的 SDR 预览。没有裁剪 / 旋转 / 翻转时，
    // 优先复用原片 GainMap（相机真实的高光层次）；改了几何才退回按亮度生成。
    // 拖动时延迟 220ms 再算，避免每帧都解码 / 生成。
    LaunchedEffect(
        preview,
        hdrPreview,
        state.hdrStrength,
        state.hdrHighlight,
        state.hdrShadow,
        state.hdrColor,
        state.hdrWarmth,
        state.cropFree,
        state.cropRatio,
        state.quarterTurns,
        state.fineRotate,
        state.flipHorizontal,
        state.flipVertical
    ) {
        if (!hdrPreview || preview == null) {
            hdrPreviewLayer = null
            hdrPreviewBitmap = null
            return@LaunchedEffect
        }
        val strength = (state.hdrStrength / 100f).coerceIn(0f, 1f)
        if (strength <= 0.02f) {
            hdrPreviewLayer = null
            hdrPreviewBitmap = null
            return@LaunchedEffect
        }
        delay(220)
        val bmp = preview?.asAndroidBitmap() ?: return@LaunchedEffect
        if (bmp.isRecycled) return@LaunchedEffect
        val grade = HdrGrade.from(state)
        val maxBoost = 1f + 2.4f * strength
        val highlightStart = (0.36f + 0.50f * (state.hdrHighlight / 100f)).coerceIn(0.30f, 0.90f)
        val layer = withContext(Dispatchers.Default) {
            val sourceFile = resolveSourceFile(item, srcFormat)
            // RAW：直接读传感器 CFA 原始数据生成 GainMap，不再用内嵌 JPEG 反推。
            val rawLayer = if (
                sourceFile != null &&
                ImageDecode.isRawFile(sourceFile.name) &&
                geometryNeutral(state)
            ) {
                RawDecode.hdrGain(sourceFile, 1024, maxBoost, highlightStart)?.let { raw ->
                    HdrPreviewLayer(
                        gain = raw.gain,
                        ratioMax = raw.ratioMax,
                        ratioMin = 1f,
                        gamma = 1f,
                        epsSdr = 0.01f,
                        epsHdr = 0.01f,
                        displayRatioFull = raw.ratioMax,
                        minDisplayRatio = 1f,
                        fromSource = true,
                        fromRaw = true
                    )
                }
            } else null
            if (rawLayer != null) {
                rawLayer
            } else {
                val sourcePair = if (
                    sourceFile != null &&
                    canReuseOriginalGain(state) &&
                    !ImageDecode.isRawFile(sourceFile.name)
                ) {
                    runCatching { ImageDecode.decodeHdrPair(sourceFile, 1024) }.getOrNull()
                } else null
                if (sourcePair != null && sourcePair.gain.width > 0 && sourcePair.gain.height > 0) {
                    // 预览只用 GainMap，底图只是 BitmapFactory 解码时的副产物。
                    runCatching { sourcePair.base.recycle() }
                    val orientation = GalleryFileOps.exifOrientationOf(sourceFile!!)
                    val gain = if (!sourcePair.orientationApplied &&
                        orientation != android.media.ExifInterface.ORIENTATION_NORMAL
                    ) {
                        GalleryFileOps.applyExifOrientation(sourcePair.gain, orientation).also {
                            if (it !== sourcePair.gain) runCatching { sourcePair.gain.recycle() }
                        }
                    } else {
                        sourcePair.gain
                    }
                    // 高光层次对原片 GainMap 走 gamma：只改变增益曲线，不改底图颜色。
                    val gammaAdjust = 0.60f + 0.90f * (state.hdrHighlight / 100f)
                    HdrPreviewLayer(
                        gain = gain,
                        ratioMax = 1f + (sourcePair.ratioMax - 1f) * strength,
                        ratioMin = sourcePair.ratioMin,
                        gamma = sourcePair.gamma * gammaAdjust,
                        epsSdr = sourcePair.epsSdr,
                        epsHdr = sourcePair.epsHdr,
                        displayRatioFull = 1f + (sourcePair.displayRatioFull - 1f) * strength,
                        minDisplayRatio = sourcePair.minDisplayRatio,
                        fromSource = true
                    )
                } else {
                    // RAW GainMap 生成失败时才退回内嵌预览；普通图按当前底图亮度反推。
                    val isRaw = sourceFile != null && ImageDecode.isRawFile(sourceFile.name)
                    val originalPreview = if (isRaw) {
                        runCatching {
                            ImageDecode.extractEmbeddedPreview(sourceFile!!, 1024)?.asAndroidBitmap()
                        }.getOrNull()
                    } else null
                    val gainSource = originalPreview ?: bmp
                    val synthetic = if (maxBoost <= 1.02f) {
                        null
                    } else {
                        buildHdrPreviewGain(gainSource, maxBoost, highlightStart)
                    }
                    if (originalPreview != null && originalPreview !== bmp) {
                        runCatching { originalPreview.recycle() }
                    }
                    synthetic?.let { HdrPreviewLayer(it, maxBoost) }
                }
            }
        }
        hdrPreviewLayer = layer
        val composed = if (layer != null) {
            withContext(Dispatchers.Default) { buildHdrPreviewComposite(bmp, layer, grade) }
        } else null
        hdrPreviewBitmap = composed
        com.lutcamera.lutcamerapro.log.DoukLog.i(
            "PhotoEditor", "HDR_PREVIEW",
            "from" to when {
                layer?.fromRaw == true -> "raw"
                layer?.fromSource == true -> "source"
                else -> "synthetic"
            },
            "gain" to (layer?.gain?.let { "${it.width}x${it.height}" } ?: "none"),
            "composed" to (composed?.let { "${it.width}x${it.height}" } ?: "none")
        )
    }

    LaunchedEffect(
        renderState,
        item.stableKey,
        filterRuntime,
        previewEpoch,
        highQualityPreview,
        activeAdjust.label,
        fastBase,
        hdBase
    ) {
        val base = if (highQualityPreview) hdBase else fastBase
        if (base != null) {
            working = true
            renderQueue.trySend(
                RenderRequest(
                    seq = renderSeq.incrementAndGet(),
                    base = base,
                    state = renderState,
                    quick = !highQualityPreview,
                    activeDetail = activeAdjust.label,
                    filterRuntime = filterRuntime
                )
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // 编辑器是浅色独立窗口，系统状态栏/导航栏图标必须强制深色；
        // 否则 App 处于深色主题时，这里会白图标压白底，看起来“上下不一致”。
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, dialogView) }
            val oldLightStatus = controller?.isAppearanceLightStatusBars
            val oldLightNav = controller?.isAppearanceLightNavigationBars
            val oldColorMode = window?.colorMode
            val gestureNavigation = runCatching {
                android.provider.Settings.Secure.getInt(
                    dialogView.context.contentResolver, "navigation_mode", 0
                ) == 2
            }.getOrDefault(false)
            val activity = dialogView.context.findActivity()
            val activityWindow = activity?.window
            val activityController = activityWindow?.let {
                WindowCompat.getInsetsController(it, it.decorView)
            }
            val oldActivityNav = activityWindow?.navigationBarColor
            val oldActivityLightNav = activityController?.isAppearanceLightNavigationBars
            window?.let { w ->
                // 对话框默认不是 edge-to-edge，状态栏/挖孔区域会露出底层 Activity 的
                // 浅灰底。把窗口本身铺到系统栏后面，再用浅色窗口背景兜底。
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0f)
                w.decorView.fitsSystemWindows = false
                w.decorView.setPadding(0, 0, 0, 0)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val attrs = w.attributes
                    attrs.fitInsetsTypes = 0
                    w.attributes = attrs
                }
                val real = android.util.DisplayMetrics()
                w.context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                    ?.getRealMetrics(real)
                val fullW = if (real.widthPixels > 0) real.widthPixels else w.attributes.width
                val fullH = if (real.heightPixels > 0) real.heightPixels else w.attributes.height
                w.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
                w.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                w.decorView.post {
                    val attrs = w.attributes
                    // 系统会把 floating Dialog 的高度压到 2780（屏幕高减导航栏）。
                    // 不跟它硬顶：把窗口整体下移导航栏那 100px，高度仍 2780，
                    // 底边就落到 2880，面板直接铺到屏幕底；顶部 100px 由
                    // Activity 的暖白 chrome 补上，和编辑器标题栏同色。
                    val lift = (fullH - 2780).coerceIn(0, 160)
                    windowLiftPx = lift
                    attrs.width = fullW
                    attrs.height = fullH - lift
                    attrs.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    attrs.y = lift
                    w.attributes = attrs
                    w.setLayout(fullW, fullH - lift)
                    val lp = w.decorView.layoutParams
                    if (lp != null) {
                        lp.width = fullW
                        lp.height = fullH - lift
                        w.decorView.layoutParams = lp
                    }
                    w.decorView.requestLayout()
                }
                w.setBackgroundDrawable(null)
                w.decorView.background = null
                w.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                w.decorView.elevation = 0f
                w.decorView.outlineProvider = null
                w.decorView.clipToOutline = false
                w.setElevation(0f)
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                val panelColor = android.graphics.Color.rgb(255, 253, 249)
                w.navigationBarColor = panelColor
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    w.navigationBarDividerColor = panelColor
                }
                w.isStatusBarContrastEnforced = false
                w.isNavigationBarContrastEnforced = false
            }
            controller?.isAppearanceLightStatusBars = true
            controller?.isAppearanceLightNavigationBars = !gestureNavigation
            activityController?.isAppearanceLightNavigationBars = !gestureNavigation
            if (gestureNavigation) {
                val hideBars = {
                    controller?.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                    controller?.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    activityController?.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                    activityController?.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    @Suppress("DEPRECATION")
                    window?.decorView?.systemUiVisibility =
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
                hideBars()
                dialogView.post { hideBars() }
                dialogView.postDelayed(hideBars, 300)
                dialogView.postDelayed(hideBars, 1200)
            }
            // 不管手势还是三键，Activity 窗口才是导航栏真正归属的窗口；
            // Dialog 的 navigationBarColor 改不动它时，Activity 这一层也刷暖白。
            activityWindow?.let { aw ->
                aw.navigationBarColor = android.graphics.Color.rgb(255, 253, 249)
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    aw.navigationBarDividerColor = android.graphics.Color.rgb(255, 253, 249)
                }
                aw.isNavigationBarContrastEnforced = false
            }
            activityController?.isAppearanceLightNavigationBars = !gestureNavigation
            onDispose {
                oldLightStatus?.let { controller?.isAppearanceLightStatusBars = it }
                oldLightNav?.let { controller?.isAppearanceLightNavigationBars = it }
                oldColorMode?.let { mode -> window?.colorMode = mode }
                oldActivityNav?.let { activityWindow?.navigationBarColor = it }
                oldActivityLightNav?.let { activityController?.isAppearanceLightNavigationBars = it }
            }
        }
        // 编辑器 HDR 预览改成系统标准 Gainmap 渲染：窗口必须是 HDR，
        // 否则 ImageView/Canvas 只会画 SDR 底图，看起来完全不亮。
        LaunchedEffect(hdrPreview) {
            val parent = dialogView.parent
            val provider = parent as? DialogWindowProvider
            val window = provider?.window
            com.lutcamera.lutcamerapro.log.DoukLog.i(
                "PhotoEditor", "HDR_WINDOW parent=${parent?.javaClass?.name} " +
                    "provider=${provider != null} window=${window != null} mode=${window?.colorMode}"
            )
            if (android.os.Build.VERSION.SDK_INT >= 34 && window != null) {
                window.colorMode = if (hdrPreview) {
                    android.content.pm.ActivityInfo.COLOR_MODE_HDR
                } else {
                    android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(EditorPalette.canvas)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶部：退出 / 撤销 / 重做 / 格式 / 保存 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (windowLiftPx > 0) Modifier
                            else Modifier.statusBarsPadding()
                        )
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StudioRoundButton(Icons.Rounded.Close, "退出") { onDismiss() }
                    StudioRoundButton(
                        Icons.Rounded.Undo,
                        "撤销",
                        enabled = undoIndex > 0
                    ) { undo() }
                    StudioRoundButton(
                        Icons.Rounded.Redo,
                        "重做",
                        enabled = undoIndex < undoStack.lastIndex
                    ) { redo() }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "编辑",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EditorPalette.text
                            )
                            if (hdrPreview) {
                                Text(
                                    "HDR",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EditorPalette.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .border(
                                            0.8.dp,
                                            EditorPalette.accent.copy(alpha = 0.55f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            "${srcFormat} · ${item.fileName}",
                            fontSize = 9.sp,
                            color = EditorPalette.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (availableFormats.size > 1) {
                        Text(
                            srcFormat,
                            fontSize = 10.sp,
                            color = EditorPalette.text,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(EditorPalette.panelRaised)
                                .border(
                                    0.8.dp,
                                    EditorPalette.line,
                                    RoundedCornerShape(percent = 50)
                                )
                                .clickable { showSourceSheet = true }
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                    StudioSaveButton(
                        saving = saving,
                        enabled = !saving && editSource != null
                    ) { showExportSheet = true }
                }

                // ---- 画面：只留一块黑底画布，控制全部收进底部 ----
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val shown = preview
                    if (shown != null) {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val aspect = shown.width.toFloat() / shown.height.toFloat()
                            val boxAspect = maxWidth.value / maxHeight.value
                            val drawW = if (boxAspect > aspect) maxHeight * aspect else maxWidth
                            val drawH = drawW / aspect
                            Box(
                                modifier = Modifier
                                    .width(drawW)
                                    .height(drawH)
                                    .then(
                                        if (canCompare && editSource != null && !state.cropFree)
                                            Modifier.pointerInput(item.stableKey) {
                                                detectTapGestures(onPress = {
                                                    comparePressed = true
                                                    tryAwaitRelease()
                                                    comparePressed = false
                                                })
                                            } else Modifier
                                    )
                            ) {
                                val hdrBmp = hdrPreviewBitmap
                                val display = if (hdrPreview && hdrBmp != null) {
                                    hdrBmp.asImageBitmap()
                                } else shown
                                Image(
                                    bitmap = if (comparePressed) (editSource ?: shown) else display,
                                    contentDescription = "编辑预览",
                                    contentScale = ContentScale.Fit,
                                    alpha = 1f,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (showGrid && !state.cropFree) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val c = Color.White.copy(alpha = 0.35f)
                                        for (i in 1..2) {
                                            drawLine(
                                                c,
                                                Offset(size.width * i / 3f, 0f),
                                                Offset(size.width * i / 3f, size.height),
                                                strokeWidth = 1f
                                            )
                                            drawLine(
                                                c,
                                                Offset(0f, size.height * i / 3f),
                                                Offset(size.width, size.height * i / 3f),
                                                strokeWidth = 1f
                                            )
                                        }
                                    }
                                }
                                if (state.cropFree && tab == "裁剪") {
                                    CropOverlay(state) { push(it) }
                                }
                                if (comparePressed) {
                                    Text(
                                        "原图",
                                        fontSize = 10.sp,
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(top = 10.dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(Color.Black.copy(alpha = 0.62f))
                                            .border(
                                                0.7.dp,
                                                Color.White.copy(alpha = 0.20f),
                                                RoundedCornerShape(percent = 50)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = EditorPalette.accent,
                                strokeWidth = 2.dp
                            )
                            Text(
                                if (working) "正在生成预览…" else "这张图暂时读不出来",
                                fontSize = 12.sp,
                                color = EditorPalette.muted
                            )
                        }
                    }
                    if (working && preview != null) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 4.dp)
                                .size(14.dp),
                            color = EditorPalette.accent,
                            strokeWidth = 1.5.dp
                        )
                    }
                    if (hdrPreview && tab != "HDR") {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 4.dp, bottom = 2.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .border(
                                    0.7.dp,
                                    EditorPalette.accent.copy(alpha = 0.45f),
                                    RoundedCornerShape(percent = 50)
                                )
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(EditorPalette.accent)
                            )
                            Text("HDR", fontSize = 8.sp, color = Color.White)
                        }
                    }
                }

                // ---- 底部控制台：一条工具栏 + 一块可滚动参数页 ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(toolsHeight)
                        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                        .background(EditorPalette.panel)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 7.dp, bottom = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(38.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(EditorPalette.line)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when (tab) {
                                "HDR" -> "HDR 高光"
                                "滤镜" -> "滤镜与 LUT"
                                "调节" -> "光效 / 色彩 / 细节"
                                else -> "画幅与旋转"
                            },
                            fontSize = 10.sp,
                            color = EditorPalette.muted,
                            letterSpacing = 0.4.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (working && highQualityPreview) {
                            Text("渲染中", fontSize = 9.sp, color = EditorPalette.dim)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (tab == "HDR") {
                            Text(
                                "恢复 100%",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EditorPalette.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .clickable {
                                        push(state.copy(
                                            hdrStrength = 100f,
                                            hdrHighlight = 60f,
                                            hdrShadow = 0f,
                                            hdrColor = 50f,
                                            hdrWarmth = 0f
                                        ))
                                        hdrPreview = true
                                    }
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(EditorPalette.panelRaised)
                                    .clickable(enabled = !state.isPristine) {
                                        push(EditState())
                                        hdrPreview = false
                                    }
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Restore,
                                    contentDescription = "还原",
                                    tint = if (!state.isPristine) EditorPalette.text else EditorPalette.dim,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    "还原",
                                    fontSize = 9.sp,
                                    color = if (!state.isPristine) EditorPalette.text else EditorPalette.dim
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 84.dp)
                    ) {
                        when (tab) {
                            "裁剪" -> StudioCropPanel(
                                state = state,
                                onState = { push(it) },
                                onLive = { live(it) },
                                onCommit = { commit() }
                            )

                            "调节" -> {
                                StudioAdjustGroups(activeAdjust) { item ->
                                    activeAdjust = item
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(top = 8.dp, bottom = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    val group = ADJUST_GROUPS.firstOrNull {
                                        it.second.contains(activeAdjust.label)
                                    }?.second ?: ADJUSTS.map { it.label }
                                    group.mapNotNull { label ->
                                        ADJUSTS.firstOrNull { it.label == label }
                                    }.forEach { item ->
                                        StudioAdjustChip(
                                            item = item,
                                            state = state,
                                            active = item.label == activeAdjust.label
                                        ) { activeAdjust = it }
                                    }
                                }
                                when (activeAdjust.label) {
                                    "曲线" -> CurvePanel(
                                        state = state,
                                        onLive = { live(it) },
                                        onCommit = { commit() },
                                        onCollapse = {
                                            activeAdjust = ADJUSTS.first { it.label == "饱和度" }
                                        }
                                    )

                                    "HSL" -> HslPanel(
                                        state = state,
                                        onLive = { live(it) },
                                        onCommit = { commit() },
                                        onCollapse = {
                                            activeAdjust = ADJUSTS.first { it.label == "饱和度" }
                                        }
                                    )

                                    else -> StudioValueSlider(
                                        label = activeAdjust.label,
                                        value = activeAdjust.get(state),
                                        range = -ADJUST_MAX..ADJUST_MAX,
                                        valueText = { formatAdjust(it) },
                                        onLive = { v ->
                                            live(activeAdjust.set(state, v))
                                        },
                                        onCommit = { commit() }
                                    )
                                }
                            }

                            "滤镜" -> {
                                val categories = (listOf("全部", "自带", "LUT") +
                                    filterEntries.map { it.category }.filter { it != "自带" })
                                    .distinct()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(top = 2.dp, bottom = 5.dp),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    categories.forEach { name ->
                                        StudioFilterCategoryChip(
                                            name = name,
                                            active = name == filterCategory
                                        ) { filterCategory = name }
                                    }
                                }
                                val shownEntries = when (filterCategory) {
                                    "全部" -> filterEntries
                                    "LUT" -> filterEntries.filter { it.kind == "LUT" }
                                    else -> filterEntries.filter { it.category == filterCategory }
                                }.filter { it.forPhoto }
                                StudioFilterRail(
                                    entries = shownEntries,
                                    current = state.filter,
                                    thumbs = filterThumbs,
                                    onPick = { entry -> push(state.copy(filter = entry.id)) },
                                    onImport = { lutPicker.launch("*/*") }
                                )
                                StudioValueSlider(
                                    label = "滤镜强度",
                                    value = state.filterAmount,
                                    range = 0f..100f,
                                    valueText = { "${it.roundToInt()}%" },
                                    onLive = { live(state.copy(filterAmount = it)) },
                                    onCommit = { commit() }
                                )
                            }

                            else -> StudioHdrPanel(
                                state = state,
                                previewOn = hdrPreview,
                                exportOn = exportHdr,
                                sdkReady = android.os.Build.VERSION.SDK_INT >= 34,
                                sourceLabel = if (srcFormat in rawExts) {
                                    "RAW 传感器直出 GainMap"
                                } else {
                                    "优先复用原片 GainMap"
                                },
                                onPreview = {
                                    hdrPreview = it
                                    exportHdr = it
                                },
                                onExport = { exportHdr = it },
                                onStateLive = { live(it) },
                                onStateCommit = { commit() }
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(EditorPalette.lineSoft)
                    )
                    // 主功能固定在控制台最下方，参数区在上面展开
                    StudioToolBar(tab) { name ->
                        tab = name
                        if (name == "HDR") {
                            hdrPreview = true
                            if (android.os.Build.VERSION.SDK_INT >= 34) exportHdr = true
                        }
                        if (name == "调节" && ADJUSTS.none { it.label == activeAdjust.label }) {
                            activeAdjust = ADJUSTS.first()
                        }
                        if (name == "滤镜") {
                            filterCategory = filterEntries.firstOrNull { it.id == state.filter }?.category
                                ?: filterCategory
                        }
                    }
                }
            }

            message?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = EditorPalette.text,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = toolsHeight + 14.dp, start = 24.dp, end = 24.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EditorPalette.panelRaised.copy(alpha = 0.96f))
                        .border(0.8.dp, EditorPalette.line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (showSourceSheet) {
                AlertDialog(
                    onDismissRequest = { showSourceSheet = false },
                    containerColor = EditorPalette.panel,
                    title = {
                        Text(
                            "用哪种格式编辑",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = EditorPalette.text
                        )
                    },
                    text = {
                        Column {
                            availableFormats.forEach { f ->
                                Text(
                                    f,
                                    fontSize = 13.sp,
                                    color = if (f == srcFormat) EditorPalette.accent else EditorPalette.muted,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            srcFormat = f
                                            showSourceSheet = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showSourceSheet = false }) {
                            Text("取消", color = EditorPalette.muted)
                        }
                    }
                )
            }

            if (showExportSheet) {
                ExportPanel(
                    format = exportFormat,
                    fixedFormat = fixedExportFormat != null,
                    quality = exportQuality,
                    hdr = exportHdr,
                    hdrStrength = state.hdrStrength,
                    srcIsRaw = ImageDecode.isRawFile(
                        resolveSourceFile(item, srcFormat)?.name ?: item.fileName
                    ),
                    onFormat = { exportFormat = it },
                    onQuality = { exportQuality = it },
                    onHdr = { exportHdr = it },
                    onDismiss = { showExportSheet = false },
                    onConfirm = {
                        showExportSheet = false
                        saving = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                saveEdited(
                                    context, item, state, filterRuntime,
                                    srcFormat = srcFormat,
                                    format = exportFormat,
                                    quality = exportQuality,
                                    hdr = exportHdr,
                                    hdrStrength = state.hdrStrength / 100f
                                )
                            }
                            saving = false
                            result.onSuccess { name -> onSaved(name) }
                                .onFailure { message = it.message ?: "保存失败" }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun StudioRoundButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(EditorPalette.panelRaised)
            .border(0.8.dp, EditorPalette.line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) EditorPalette.text else EditorPalette.dim.copy(alpha = 0.65f),            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun StudioSaveButton(
    saving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled) EditorPalette.accent else EditorPalette.line)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (saving) "保存中…" else "保存",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) EditorPalette.accentText else EditorPalette.muted
        )
    }
}

@Composable
private fun StudioToolBar(current: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        EDITOR_TABS.forEach { tool ->
            val active = current == tool.name
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (active) EditorPalette.accentSoft else Color.Transparent)
                    .clickable { onPick(tool.name) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    tool.icon,
                    contentDescription = tool.name,
                    tint = if (active) EditorPalette.accent else EditorPalette.muted,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    tool.name,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) EditorPalette.text else EditorPalette.muted
                )
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (active) EditorPalette.accent else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun StudioSectionLabel(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        color = EditorPalette.dim,
        letterSpacing = 0.3.sp,
        modifier = Modifier.padding(top = 5.dp, bottom = 5.dp)
    )
}

@Composable
private fun StudioCropPanel(
    state: EditState,
    onState: (EditState) -> Unit,
    onLive: (EditState) -> Unit,
    onCommit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StudioSectionLabel("画幅比例")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            StudioRatioChip(
                label = "原始",
                icon = Icons.Rounded.Crop,
                active = !state.cropFree && state.cropRatio == "原始"
            ) { onState(state.copy(cropRatio = "原始", cropFree = false)) }
            StudioRatioChip(
                label = "自由",
                icon = Icons.Rounded.CropFree,
                active = state.cropFree
            ) {
                onState(
                    if (state.cropFree) {
                        state.copy(cropFree = false, cropRatio = "原始")
                    } else {
                        state.copy(
                            cropFree = true,
                            cropRatio = "原始",
                            cropL = 0f,
                            cropT = 0f,
                            cropR = 1f,
                            cropB = 1f
                        )
                    }
                )
            }
            CROP_RATIOS.filter { it != "原始" }.forEach { ratio ->
                StudioRatioChip(
                    label = ratio,
                    icon = ratioIcon(ratio),
                    active = !state.cropFree && state.cropRatio == ratio
                ) { onState(state.copy(cropRatio = ratio, cropFree = false)) }
            }
        }
        if (state.cropFree) {
            Text(
                "拖动画面四角决定保留范围",
                fontSize = 9.sp,
                color = EditorPalette.accent,
                modifier = Modifier.padding(top = 5.dp)
            )
        }

        StudioSectionLabel("旋转与翻转")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            StudioActionPill("右转 90°", Icons.Rounded.CropRotate) {
                onState(state.copy(quarterTurns = (state.quarterTurns + 1) % 4))
            }
            StudioActionPill("左转 90°", Icons.Rounded.CropRotate) {
                onState(state.copy(quarterTurns = (state.quarterTurns + 3) % 4))
            }
            StudioActionPill(
                if (state.flipHorizontal) "左右已翻" else "左右翻转",
                Icons.Rounded.Flip
            ) { onState(state.copy(flipHorizontal = !state.flipHorizontal)) }
            StudioActionPill(
                if (state.flipVertical) "上下已翻" else "上下翻转",
                Icons.Rounded.Flip
            ) { onState(state.copy(flipVertical = !state.flipVertical)) }
        }

        StudioValueSlider(
            label = "微调角度",
            value = state.fineRotate,
            range = -45f..45f,
            valueText = { "${it.roundToInt()}°" },
            onLive = { onLive(state.copy(fineRotate = it)) },
            onCommit = onCommit
        )
    }
}

@Composable
private fun StudioRatioChip(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(62.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) EditorPalette.accentSoft else EditorPalette.panelRaised)
            .border(
                if (active) 1.dp else 0.7.dp,
                if (active) EditorPalette.accent else EditorPalette.line,
                RoundedCornerShape(11.dp)
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) EditorPalette.accent else EditorPalette.muted,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) EditorPalette.text else EditorPalette.muted
        )
    }
}

@Composable
private fun StudioActionPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(EditorPalette.panelRaised)
            .border(0.7.dp, EditorPalette.line, RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = EditorPalette.muted,
            modifier = Modifier.size(13.dp)
        )
        Text(label, fontSize = 9.5.sp, color = EditorPalette.text)
    }
}

@Composable
private fun StudioAdjustGroups(active: AdjustItem, onPick: (AdjustItem) -> Unit) {
    val currentGroup = ADJUST_GROUPS.firstOrNull { it.second.contains(active.label) }?.first
        ?: ADJUST_GROUPS.first().first
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(EditorPalette.panelRaised)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        ADJUST_GROUPS.forEach { (name, labels) ->
            val on = name == currentGroup
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) EditorPalette.panelSoft else Color.Transparent)
                    .clickable {
                        ADJUSTS.firstOrNull { it.label == labels.first() }?.let(onPick)
                    }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name,
                    fontSize = 10.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) EditorPalette.text else EditorPalette.muted
                )
            }
        }
    }
}

@Composable
private fun StudioAdjustChip(
    item: AdjustItem,
    state: EditState,
    active: Boolean,
    onPick: (AdjustItem) -> Unit
) {
    val changed = abs(item.get(state)) > 0.5f
    Column(
        modifier = Modifier
            .width(62.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) EditorPalette.accentSoft else EditorPalette.panelRaised)
            .border(
                if (active) 1.dp else 0.7.dp,
                if (active) EditorPalette.accent else EditorPalette.line,
                RoundedCornerShape(12.dp)
            )
            .clickable { onPick(item) }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            item.icon,
            contentDescription = item.label,
            tint = if (active) EditorPalette.accent else EditorPalette.muted,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            item.label,
            fontSize = 8.5.sp,
            maxLines = 1,
            color = if (active) EditorPalette.text else EditorPalette.muted
        )
        if (changed) {
            Text(
                formatAdjust(item.get(state)),
                fontSize = 7.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = EditorPalette.accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StudioValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    enabled: Boolean = true,
    onLive: (Float) -> Unit,
    onCommit: () -> Unit
) {
    val latestLive = rememberUpdatedState(onLive)
    val density = LocalDensity.current
    val thumbRadius = with(density) { 12.dp.toPx() }
    val padPx = thumbRadius
    val trackHeight = with(density) { 5.dp.toPx() }
    val fraction = ((value - range.start) / (range.endInclusive - range.start))
        .coerceIn(0f, 1f)
    val alpha = if (enabled) 1f else 0.38f

    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 10.sp, color = EditorPalette.muted.copy(alpha = alpha))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                valueText(value),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = EditorPalette.accent.copy(alpha = alpha)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .then(
                    if (enabled) Modifier.pointerInput(label) {
                        awaitEachGesture {
                            fun apply(x: Float) {
                                val f = ((x - padPx) / (size.width - padPx * 2f)).coerceIn(0f, 1f)
                                latestLive.value(range.start + (range.endInclusive - range.start) * f)
                            }
                            val down = awaitFirstDown()
                            apply(down.position.x)
                            down.consume()
                            var active = true
                            while (active) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    active = false
                                } else if (change.pressed) {
                                    apply(change.position.x)
                                    change.consume()
                                } else {
                                    active = false
                                    onCommit()
                                }
                            }
                        }
                    } else Modifier
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val left = padPx
                val right = size.width - padPx
                val cx = left + fraction * (right - left)
                val zeroF = ((0f - range.start) / (range.endInclusive - range.start))
                    .coerceIn(0f, 1f)
                val zeroX = left + zeroF * (right - left)
                drawRoundRect(
                    color = EditorPalette.panelRaised.copy(alpha = alpha),
                    topLeft = Offset(left, cy - trackHeight / 2f),
                    size = Size((right - left).coerceAtLeast(1f), trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
                )
                val fillL = minOf(cx, zeroX)
                val fillR = maxOf(cx, zeroX)
                if (fillR - fillL > 0.5f) {
                    drawRoundRect(
                        color = EditorPalette.accent.copy(alpha = alpha),
                        topLeft = Offset(fillL, cy - trackHeight / 2f),
                        size = Size(fillR - fillL, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
                    )
                }
                if (range.start < 0f && range.endInclusive > 0f) {
                    drawCircle(
                        color = EditorPalette.muted.copy(alpha = alpha * 0.7f),
                        radius = with(density) { 1.4.dp.toPx() },
                        center = Offset(zeroX, cy)
                    )
                }
                drawCircle(
                    color = Color.White.copy(alpha = alpha),
                    radius = thumbRadius,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = EditorPalette.accent.copy(alpha = alpha),
                    radius = thumbRadius,
                    center = Offset(cx, cy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        with(density) { 2.dp.toPx() }
                    )
                )
            }
        }
    }
}

@Composable
private fun StudioFilterCategoryChip(name: String, active: Boolean, onClick: () -> Unit) {
    Text(
        name,
        fontSize = 9.5.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) EditorPalette.text else EditorPalette.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (active) EditorPalette.accentSoft else EditorPalette.panelRaised)
            .border(
                if (active) 0.9.dp else 0.6.dp,
                if (active) EditorPalette.accent.copy(alpha = 0.65f) else EditorPalette.line,
                RoundedCornerShape(percent = 50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun StudioFilterRail(
    entries: List<FilterEntry>,
    current: String,
    thumbs: Map<String, ImageBitmap>,
    onPick: (FilterEntry) -> Unit,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        entries.forEach { entry ->
            val active = entry.id == current
            Box(
                modifier = Modifier
                    .width(82.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(EditorPalette.panelRaised)
                    .border(
                        if (active) 1.2.dp else 0.7.dp,
                        if (active) EditorPalette.accent else EditorPalette.line,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onPick(entry) }
            ) {
                thumbs[entry.id]?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = entry.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color(0xDD000000)
                            )
                        )
                )
                if (entry.kind == "LUT") {
                    Text(
                        "LUT",
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        color = EditorPalette.accentText,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(EditorPalette.accent)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Text(
                    entry.label
                        .replace(" N-Log 转 Rec709", "")
                        .replace("N-Log 转 Rec709", ""),
                    fontSize = 9.sp,
                    lineHeight = 10.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 5.dp, end = 5.dp, bottom = 5.dp)
                )
                if (active) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(EditorPalette.accent)
                    )
                }
            }
        }
        // 直接在滤镜页导入 LUT：不需要退出去设置里找资源页
        Box(
            modifier = Modifier
                .width(82.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(EditorPalette.panelRaised.copy(alpha = 0.55f))
                .border(0.8.dp, EditorPalette.line, RoundedCornerShape(14.dp))
                .clickable(onClick = onImport),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(EditorPalette.accentSoft)
                        .border(0.8.dp, EditorPalette.accent.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "导入 LUT",
                        tint = EditorPalette.accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text("导入 LUT", fontSize = 9.5.sp, color = EditorPalette.text)
                Text(".cube / .3dl / .look", fontSize = 7.5.sp, color = EditorPalette.dim)
            }
        }
    }
}

@Composable
private fun StudioHdrPanel(
    state: EditState,
    previewOn: Boolean,
    exportOn: Boolean,
    sdkReady: Boolean,
    sourceLabel: String,
    onPreview: (Boolean) -> Unit,
    onExport: (Boolean) -> Unit,
    onStateLive: (EditState) -> Unit,
    onStateCommit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        0f to EditorPalette.accent.copy(alpha = 0.12f),
                        0.55f to EditorPalette.panelRaised,
                        1f to EditorPalette.panelRaised
                    )
                )
                .border(
                    0.9.dp,
                    if (previewOn) EditorPalette.accent.copy(alpha = 0.55f) else EditorPalette.line,
                    RoundedCornerShape(18.dp)
                )
                .padding(11.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(EditorPalette.accentSoft)
                            .border(
                                0.8.dp,
                                EditorPalette.accent.copy(alpha = 0.45f),
                                RoundedCornerShape(9.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.HdrOn,
                            contentDescription = "HDR",
                            tint = EditorPalette.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "SDR → HDR 高光扩展",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = EditorPalette.text
                        )
                        Text(
                            if (previewOn) "预览已开启 · GainMap / Ultra HDR 参与扩展"
                            else "SDR 底图 + GainMap 高光 · Ultra HDR 导出",
                            fontSize = 9.sp,
                            color = if (previewOn) EditorPalette.accent else EditorPalette.dim,
                            maxLines = 1
                        )
                    }
                    StudioSwitch(previewOn, sdkReady) { onPreview(it) }
                }
            }
        }

        StudioValueSlider(
            label = "高光强度",
            value = state.hdrStrength,
            range = 0f..100f,
            valueText = { "${it.roundToInt()}%" },
            enabled = previewOn && sdkReady,
            onLive = { onStateLive(state.copy(hdrStrength = it)) },
            onCommit = onStateCommit
        )
        StudioValueSlider(
            label = "高光层次",
            value = state.hdrHighlight,
            range = 0f..100f,
            valueText = { if (it <= 35f) "宽" else if (it >= 75f) "窄" else "${it.roundToInt()}%" },
            enabled = previewOn && sdkReady,
            onLive = { onStateLive(state.copy(hdrHighlight = it)) },
            onCommit = onStateCommit
        )
        StudioValueSlider(
            label = "暗部细节",
            value = state.hdrShadow,
            range = 0f..100f,
            valueText = { "${it.roundToInt()}%" },
            enabled = previewOn && sdkReady,
            onLive = { onStateLive(state.copy(hdrShadow = it)) },
            onCommit = onStateCommit
        )
        StudioValueSlider(
            label = "色彩浓度",
            value = state.hdrColor,
            range = 0f..100f,
            valueText = { "${it.roundToInt()}%" },
            enabled = previewOn && sdkReady,
            onLive = { onStateLive(state.copy(hdrColor = it)) },
            onCommit = onStateCommit
        )
        StudioValueSlider(
            label = "色温",
            value = state.hdrWarmth,
            range = -100f..100f,
            valueText = {
                when {
                    it > 0.5f -> "+${it.roundToInt()}"
                    it < -0.5f -> "${it.roundToInt()}"
                    else -> "中性"
                }
            },
            enabled = previewOn && sdkReady,
            onLive = { onStateLive(state.copy(hdrWarmth = it)) },
            onCommit = onStateCommit
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("保存 HDR 文件", fontSize = 11.sp, color = EditorPalette.text)
                Text(
                    if (sdkReady) {
                        "JPG 写入 Ultra HDR（GainMap）· $sourceLabel"
                    } else {
                        "当前系统低于 Android 14，只能保存普通 SDR 图片"
                    },
                    fontSize = 8.5.sp,
                    color = EditorPalette.dim
                )
            }
            StudioSwitch(exportOn, sdkReady) { onExport(it) }
        }
    }
}

@Composable
private fun StudioSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = EditorPalette.accent,
            uncheckedThumbColor = EditorPalette.muted,
            uncheckedTrackColor = EditorPalette.panelSoft
        )
    )
}

@Composable
private fun ExportPanel(
    format: String,
    fixedFormat: Boolean,
    quality: Int,
    hdr: Boolean,
    hdrStrength: Float,
    srcIsRaw: Boolean,
    onFormat: (String) -> Unit,
    onQuality: (Int) -> Unit,
    onHdr: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(EditorPalette.panel)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(EditorPalette.line)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "导出",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = EditorPalette.text
                    )
                    Text(
                        "保存到 Pictures/连镜",
                        fontSize = 9.sp,
                        color = EditorPalette.dim
                    )
                }
                StudioRoundButton(Icons.Rounded.Close, "关闭导出") { onDismiss() }
            }
            Spacer(modifier = Modifier.height(12.dp))
            StudioSectionLabel("格式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fixedFormat) {
                    Text(
                        "$format（已在设置中固定）",
                        fontSize = 10.sp,
                        color = EditorPalette.muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(EditorPalette.panelRaised)
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                } else {
                    listOf("JPG", "PNG").forEach { f ->
                        val active = f == format
                        Text(
                            f,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) EditorPalette.text else EditorPalette.muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(if (active) EditorPalette.accentSoft else EditorPalette.panelRaised)
                                .border(
                                    if (active) 0.9.dp else 0.6.dp,
                                    if (active) EditorPalette.accent else EditorPalette.line,
                                    RoundedCornerShape(percent = 50)
                                )
                                .clickable { onFormat(f) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("JPG 质量", fontSize = 10.sp, color = EditorPalette.muted)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "$quality",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EditorPalette.accent
                )
            }
            androidx.compose.material3.Slider(
                value = quality.toFloat(),
                onValueChange = { onQuality(it.toInt()) },
                valueRange = 60f..100f,
                steps = 7,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = EditorPalette.accent,
                    activeTrackColor = EditorPalette.accent,
                    inactiveTrackColor = EditorPalette.line
                )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(EditorPalette.panelRaised)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("HDR 效果", fontSize = 11.sp, color = EditorPalette.text)
                    Text(
                        if (hdr) {
                            "Ultra HDR · 强度 ${hdrStrength.roundToInt()}% · " +
                                if (srcIsRaw) "RAW 传感器直出" else "优先复用原片 GainMap"
                        } else {
                            "关闭后导出普通 SDR 图片"
                        },
                        fontSize = 8.5.sp,
                        color = EditorPalette.dim
                    )
                }
                StudioSwitch(hdr, android.os.Build.VERSION.SDK_INT >= 34) { onHdr(it) }
            }
            Text(
                "Ultra HDR 只在 JPG 里生效；PNG / WebP 会输出高光滚降后的普通图片。",
                fontSize = 8.sp,
                color = EditorPalette.dim,
                modifier = Modifier.padding(top = 7.dp, bottom = 10.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(EditorPalette.accent)
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "保存到相册",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EditorPalette.accentText
                )
            }
        }
    }
}

/** 从 Compose 的 ContextThemeWrapper 里找到真正的 Activity，用来补系统栏颜色。 */
private fun Context.findActivity(): android.app.Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return current as? android.app.Activity
}

private fun editorDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

/** 保存前的导出设置：格式 / 质量 / HDR 效果。 */
@Composable
private fun ExportSheet(
    format: String,
    fixedFormat: Boolean = false,
    quality: Int,
    hdr: Boolean,
    srcIsRaw: Boolean,
    onFormat: (String) -> Unit,
    onQuality: (Int) -> Unit,
    onHdr: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("导出设置", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("格式", fontSize = 12.sp, color = Color(0xFF888888))
                if (fixedFormat) {
                    // 图库设置里已指定默认导出格式：直接按它走，不用每次再选
                    Text(
                        "$format（已在「设置 → 图库」固定）",
                        fontSize = 13.sp,
                        color = Color(0xFF333333),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    listOf("JPG", "PNG").forEach { f ->
                        val active = f == format
                        Text(
                            f,
                            fontSize = 12.sp,
                            color = if (active) Color.White else Color(0xFF333333),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (active) DoukColors.accent else Color(0xFFEFEFEF))
                                .clickable { onFormat(f) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text("质量 $quality", fontSize = 12.sp, color = Color(0xFF888888))
                androidx.compose.material3.Slider(
                    value = quality.toFloat(),
                    onValueChange = { onQuality(it.toInt()) },
                    valueRange = 60f..100f,
                    steps = 7,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = DoukColors.accent,
                        activeTrackColor = DoukColors.accent
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHdr(!hdr) }
                        .padding(vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("HDR 效果", fontSize = 13.sp, color = Color(0xFF333333))
                        Text(
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                if (srcIsRaw) {
                                    "RAW 没有现成 HDR 层，按亮度直方图反推增益图（最高 2.8x），" +
                                        "导出 Ultra HDR JPEG，图库/相册按真 HDR 显示"
                                } else {
                                    "JPG 本身没有 HDR 数据层，勾选后按画面亮度反推一张增益图（最高 2.8x），" +
                                        "导出 Ultra HDR JPEG，HDR 屏上亮部会顶出 SDR 白"
                                }
                            } else {
                                "当前系统低于 Android 14，只能做高光滚降的普通 JPG"
                            },
                            fontSize = 11.sp,
                            color = Color(0xFF9A9A9A)
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = hdr,
                        onCheckedChange = { onHdr(it) }
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("保存", color = DoukColors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = Color(0xFF777777)) }
        }
    )
}

/** 按格式在「主文件 / 配对文件」里找对应源文件。 */
private fun resolveSourceFile(item: GalleryItem, format: String): File? {
    val main = GalleryFileOps.locateAppFile(item.fileName)
    if (main != null && main.extension.equals(format, ignoreCase = true)) return main
    item.extraFiles.forEach { name ->
        val f = GalleryFileOps.locateAppFile(name)
        if (f != null && f.extension.equals(format, ignoreCase = true)) return f
    }
    return main
}

/** 滑杆那一行：基础页是旋转角度，调节页是当前选中的调节项。 */
@Composable
private fun SliderRow(
    state: EditState,
    tab: String,
    activeAdjust: AdjustItem,
    onLive: (EditState) -> Unit,
    onCommit: () -> Unit
) {
    val isBase = tab == "裁剪"
    val label = if (isBase) "旋转" else activeAdjust.label
    val range = when {
        isBase -> -45f..45f
        label == "滤镜强度" -> 0f..100f
        label == "磨皮" || label == "美白" || label == "红润" || label == "瘦脸" -> 0f..100f
        label == "HSL 色相" -> -180f..180f
        else -> -ADJUST_MAX..ADJUST_MAX
    }

    val sliderItem = if (isBase) {
        AdjustItem("旋转", Icons.Rounded.CropRotate, { it.fineRotate }) { s, v -> s.copy(fineRotate = v) }
    } else {
        activeAdjust
    }

    AdjustSlider(
        item = sliderItem,
        state = state,
        onLive = onLive,
        onCommit = onCommit,
        rangeOverride = range,
        compact = isBase,
        formatValue = { v ->
            when {
                isBase -> "${v.roundToInt()}°"
                label == "滤镜强度" -> v.roundToInt().toString()
                else -> formatAdjust(v)
            }
        }
    )
}

/** 底部主功能栏：图标 + 文字，固定在编辑面板最下方，像专业修图 App。 */
@Composable
private fun MainTabBar(current: String, onPick: (String) -> Unit) {
    val tabs = listOf(
        "裁剪" to Icons.Rounded.Crop,
        "调节" to Icons.Rounded.Tune,
        "滤镜" to Icons.Rounded.FilterVintage
    )
    // 手机版：底部工具页支持左右滑切换，功能放不下也不用把预览挤没。
    val drag = remember { androidx.compose.runtime.mutableStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .doukFlatSurface(RoundedCornerShape(15.dp))
            .pointerInput(tabs) {
                detectHorizontalDragGestures(
                    onDragStart = { drag.value = 0f },
                    onDragEnd = {
                        val index = tabs.indexOfFirst { it.first == current }
                        if (drag.value < -48f && index < tabs.lastIndex) {
                            onPick(tabs[index + 1].first)
                        } else if (drag.value > 48f && index > 0) {
                            onPick(tabs[index - 1].first)
                        }
                        drag.value = 0f
                    },
                    onDragCancel = { drag.value = 0f }
                ) { change, amount ->
                    change.consume()
                    drag.value += amount
                }
            }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        tabs.forEach { (name, icon) ->
            val active = current == name
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(name) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = name,
                    tint = if (active) Color(0xFFE08A2E) else Color(0xFF8A8A8A),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    name,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color(0xFFE08A2E) else Color(0xFF8A8A8A)
                )
            }
        }
    }
}

/** 调节主滑杆：灰色胶囊轨道 + 白芯橙环圆球，拖动时球上方实时显示数值。 */
@Composable
private fun AdjustSlider(
    item: AdjustItem,
    state: EditState,
    onLive: (EditState) -> Unit,
    onCommit: () -> Unit,
    rangeOverride: ClosedFloatingPointRange<Float>? = null,
    compact: Boolean = false,
    formatValue: ((Float) -> String)? = null
) {
    val value = item.get(state)
    val range = rangeOverride ?: when (item.label) {
        "曝光", "亮度", "对比度", "高光", "阴影" -> -100f..100f
        else -> -ADJUST_MAX..ADJUST_MAX
    }
    val shown = formatValue?.invoke(value) ?: formatAdjust(value)
    val latestState = rememberUpdatedState(state)
    val latestLive = rememberUpdatedState(onLive)
    val density = LocalDensity.current
    val thumbRadius = with(density) { 12.dp.toPx() }
    val padPx = thumbRadius + with(density) { 2.dp.toPx() }
    val trackHeight = with(density) { 17.dp.toPx() }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.84f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 2.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.label, fontSize = 12.sp, color = Color(0xFF888888))
                Spacer(modifier = Modifier.weight(1f))
                Text(shown, fontSize = 12.sp, color = Color(0xFFE08A2E), fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 52.dp else 66.dp)
                    .pointerInput(item.label) {
                        awaitEachGesture {
                            fun apply(x: Float) {
                                val fraction = ((x - padPx) / (size.width - padPx * 2f)).coerceIn(0f, 1f)
                                val v = range.start + (range.endInclusive - range.start) * fraction
                                latestLive.value(item.set(latestState.value, v))
                            }
                            val down = awaitFirstDown()
                            apply(down.position.x)
                            down.consume()
                            var active = true
                            while (active) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    active = false
                                } else if (change.pressed) {
                                    apply(change.position.x)
                                    change.consume()
                                } else {
                                    active = false
                                    onCommit()
                                }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cy = size.height - trackHeight / 2f - with(density) { 2.dp.toPx() }
                    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    val cx = padPx + fraction * (size.width - padPx * 2f)
                    drawRoundRect(
                        color = Color(0xFFE7E7E7),
                        topLeft = Offset(0f, cy - trackHeight / 2f),
                        size = Size(size.width, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
                    )
                    drawCircle(Color.White, radius = thumbRadius, center = Offset(cx, cy))
                    drawCircle(
                        color = Color(0xFFE08A2E),
                        radius = thumbRadius,
                        center = Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(with(density) { 3.dp.toPx() })
                    )
                }
            }
            Spacer(modifier = Modifier.height(if (compact) 4.dp else 10.dp))
        }
    }
}

private fun formatAdjust(v: Float): String {
    val rounded = v.roundToInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
}

/** 调节分组文字 + 带图标的功能卡；选中卡用橙框，改过值时在图标位显示数值。 */
@Composable
private fun AdjustGroups(active: AdjustItem, state: EditState, onPick: (AdjustItem) -> Unit) {
    val group = ADJUST_GROUPS.firstOrNull { it.second.contains(active.label) }?.first ?: "光效"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ADJUST_GROUPS.forEach { (name, items) ->
            Text(
                name,
                fontSize = 12.sp,
                fontWeight = if (name == group) FontWeight.SemiBold else FontWeight.Normal,
                color = if (name == group) Color(0xFF222222) else Color(0xFF999999),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable {
                        ADJUSTS.firstOrNull { it.label == items.first() }?.let(onPick)
                    }
                    .padding(vertical = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
    val items = ADJUST_GROUPS.first { it.first == group }.second
        .mapNotNull { label -> ADJUSTS.firstOrNull { it.label == label } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        items.forEach { item ->
            val on = item.label == active.label
            Column(
                modifier = Modifier
                    .width(50.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color(0xFFFBF0E3) else Color(0xFFEDEDED))
                    .then(
                        if (on) Modifier.border(1.5.dp, Color(0xFFE08A2E), RoundedCornerShape(10.dp))
                        else Modifier
                    )
                    .clickable { onPick(item) }
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    tint = if (on) Color(0xFF222222) else Color(0xFF4A4A4A),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    item.label,
                    fontSize = 9.sp,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (on) Color(0xFF222222) else Color(0xFF666666),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


/** 色彩组里的曲线面板：主/R/G/B 四条曲线，白线白点，底部通道圆点。 */
@Composable
private fun CurvePanel(
    state: EditState,
    onLive: (EditState) -> Unit,
    onCommit: () -> Unit,
    onCollapse: () -> Unit
) {
    var channel by remember { mutableStateOf(0) }
    val points = when (channel) {
        1 -> state.curveR
        2 -> state.curveG
        3 -> state.curveB
        else -> state.curve
    }
    val latestState = rememberUpdatedState(state)
    val latestLive = rememberUpdatedState(onLive)
    val latestPoints = rememberUpdatedState(points)
    val update: (List<Float>) -> Unit = { pts ->
        val s = latestState.value
        latestLive.value(
            when (channel) {
                1 -> s.copy(curveR = pts)
                2 -> s.copy(curveG = pts)
                3 -> s.copy(curveB = pts)
                else -> s.copy(curve = pts)
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(192.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .pointerInput(channel) {
                    var dragging = -1
                    fun updateAt(pos: Offset) {
                        val list = latestPoints.value.toMutableList()
                        if (dragging in list.indices) {
                            val y = (1f - (pos.y / size.height.toFloat())).coerceIn(0f, 1f)
                            val minY = if (dragging == 0) 0f else list[dragging - 1]
                            val maxY = if (dragging == list.lastIndex) 1f else list[dragging + 1]
                            list[dragging] = y.coerceIn(minY, maxY)
                            update(list)
                        }
                    }
                    detectDragGestures(
                        onDragStart = { pos ->
                            val t = (pos.x / size.width.toFloat()).coerceIn(0f, 1f)
                            dragging = (t * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                            updateAt(pos)
                        },
                        onDragEnd = { dragging = -1; onCommit() },
                        onDragCancel = { dragging = -1; onCommit() }
                    ) { change, _ -> updateAt(change.position) }
                }
        ) {
            val w = size.width
            val h = size.height
            val grid = EditorPalette.lineSoft
            val gridW = with(density) { 1.dp.toPx() }
            val lineW = with(density) { 1.6.dp.toPx() }
            val shadowW = with(density) { 3.2.dp.toPx() }
            val handleR = with(density) { 7.dp.toPx() }
            for (i in 0..4) {
                drawLine(grid, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), strokeWidth = gridW)
                drawLine(grid, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = gridW)
            }
            var prev: Offset? = null
            for (i in 0..72) {
                val t = i / 72f
                val pt = Offset(t * w, (1f - curveValue(points, t)) * h)
                prev?.let {
                    drawLine(Color.Black.copy(alpha = 0.55f), it, pt, strokeWidth = shadowW)
                    drawLine(EditorPalette.text, it, pt, strokeWidth = lineW)
                }
                prev = pt
            }
            points.forEachIndexed { index, v ->
                val p = Offset(w * index / (points.size - 1f), (1f - v) * h)
                drawCircle(EditorPalette.muted, radius = handleR + 2f, center = p)
                drawCircle(EditorPalette.text, radius = handleR, center = p)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(
                Color.White,
                Color(0xFFE05555),
                Color(0xFF57C95A),
                Color(0xFF5B8FEA)
            ).forEachIndexed { index, color ->
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .then(
                            if (index == channel) Modifier.border(2.dp, EditorPalette.accent, CircleShape)
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        )
                        .background(color)
                        .clickable { channel = index }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "重置曲线",
                tint = EditorPalette.muted,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(EditorPalette.panelRaised)
                    .clickable {
                        update(listOf(0f, 0.25f, 0.5f, 0.75f, 1f))
                        onCommit()
                    }
                    .padding(4.dp)
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(EditorPalette.panelRaised)
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = "收起曲线",
                    tint = EditorPalette.text,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 色彩组里的 HSL 面板：色相 / 饱和度 / 亮度三条渐变滑杆 + 六个色相圆点。 */
@Composable
private fun HslPanel(
    state: EditState,
    onLive: (EditState) -> Unit,
    onCommit: () -> Unit,
    onCollapse: () -> Unit
) {
    val baseHue = HSL_RANGES[state.hslRange.coerceIn(0, HSL_RANGES.lastIndex)].first
    val hueColors = (-60..60 step 20).map { d ->
        Color.hsv(((baseHue + d + 360f) % 360f), 0.85f, 0.92f)
    }
    val satColors = listOf(
        Color.hsv(baseHue, 0.08f, 0.78f),
        Color.hsv(baseHue, 0.35f, 0.85f),
        Color.hsv(baseHue, 0.95f, 0.9f)
    )
    val lightColors = listOf(
        Color.hsv(baseHue, 0.9f, 0.28f),
        Color.hsv(baseHue, 0.9f, 0.72f),
        Color.White
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        GradientSlider("色相", state.hslHue, -180f..180f, hueColors,
            onLive = { onLive(state.copy(hslHue = it)) },
            onCommit = onCommit
        )
        GradientSlider("饱和度", state.hslSat, -100f..100f, satColors,
            onLive = { onLive(state.copy(hslSat = it)) },
            onCommit = onCommit
        )
        GradientSlider("亮度", state.hslLight, -100f..100f, lightColors,
            onLive = { onLive(state.copy(hslLight = it)) },
            onCommit = onCommit
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            HSL_RANGES.forEachIndexed { index, (_, color) ->
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .then(
                            if (index == state.hslRange) Modifier.border(2.dp, EditorPalette.accent, CircleShape)
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        )
                        .background(color)
                        .clickable {
                            onLive(state.copy(hslRange = index, hslSat = 0f, hslHue = 0f, hslLight = 0f))
                            onCommit()
                        }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(EditorPalette.panelRaised)
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = "收起 HSL",
                    tint = EditorPalette.text,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: List<Color>,
    onLive: (Float) -> Unit,
    onCommit: () -> Unit
) {
    var touching by remember { mutableStateOf(false) }
    val latestLive = rememberUpdatedState(onLive)
    val density = LocalDensity.current
    val thumbRadius = with(density) { 11.dp.toPx() }
    val padPx = thumbRadius + with(density) { 2.dp.toPx() }
    val trackHeight = with(density) { 23.dp.toPx() }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .padding(top = 6.dp, bottom = 4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp, color = EditorPalette.muted)
                Spacer(modifier = Modifier.weight(1f))
                Text(formatAdjust(value), fontSize = 12.sp, color = EditorPalette.text)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                .pointerInput(label) {
                    awaitEachGesture {
                        fun apply(x: Float) {
                            val fraction = ((x - padPx) / (size.width - padPx * 2f)).coerceIn(0f, 1f)
                            latestLive.value(range.start + (range.endInclusive - range.start) * fraction)
                        }
                        val down = awaitFirstDown()
                        touching = true
                        apply(down.position.x)
                        down.consume()
                        var active = true
                        while (active) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) active = false
                            else if (change.pressed) {
                                apply(change.position.x)
                                change.consume()
                            } else {
                                active = false
                                touching = false
                                onCommit()
                            }
                        }
                        touching = false
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cy = size.height - trackHeight / 2f - with(density) { 2.dp.toPx() }
                val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val cx = padPx + fraction * (size.width - padPx * 2f)
                drawRoundRect(
                    brush = Brush.horizontalGradient(colors),
                    topLeft = Offset(0f, cy - trackHeight / 2f),
                    size = Size(size.width, trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
                )
                drawCircle(Color.White, radius = if (touching) thumbRadius + 2f else thumbRadius, center = Offset(cx, cy))
            }
        }
    }
    }
}

@Composable
private fun CurveEditor(points: List<Float>, onChange: (List<Float>) -> Unit) {
    var dragIndex by remember { mutableStateOf(-1) }
    val latestPoints = rememberUpdatedState(points)
    val latestChange = rememberUpdatedState(onChange)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
            .padding(top = 6.dp)
            .doukFlatSurface(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                fun pts(): List<Float> = latestPoints.value
                detectDragGestures(
                    onDragStart = { pos ->
                        val list = pts()
                        val t = (pos.x / size.width.toFloat()).coerceIn(0f, 1f)
                        dragIndex = (t * (list.size - 1)).roundToInt().coerceIn(0, list.lastIndex)
                        val y = 1f - (pos.y / size.height.toFloat()).coerceIn(0f, 1f)
                        latestChange.value(list.toMutableList().also { it[dragIndex] = y })
                    },
                    onDragEnd = { dragIndex = -1 },
                    onDragCancel = { dragIndex = -1 }
                ) { _, drag ->
                    val list = pts()
                    if (dragIndex in list.indices) {
                        val y = (1f - (drag.y / size.height.toFloat())).coerceIn(0f, 1f)
                        val next = list.toMutableList()
                        // 拖动时保持左右控制点不越界，曲线始终单增
                        val minY = if (dragIndex == 0) 0f else next[dragIndex - 1]
                        val maxY = if (dragIndex == list.lastIndex) 1f else next[dragIndex + 1]
                        next[dragIndex] = y.coerceIn(minY, maxY)
                        latestChange.value(next.toList())
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val w = size.width
        val h = size.height
        val grid = Color(0xFFDDDDDD)
        for (i in 0..4) {
            drawLine(grid, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), strokeWidth = 0.7f)
            drawLine(grid, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = 0.7f)
        }
        drawLine(Color(0xFFBBBBBB), Offset(0f, h), Offset(w, 0f), strokeWidth = 0.8f)
        var prev: Offset? = null
        for (i in 0..64) {
            val t = i / 64f
            val pt = Offset(t * w, (1f - curveValue(points, t)) * h)
            prev?.let { drawLine(Color(0xFFE08A2E), it, pt, strokeWidth = 2.4f) }
            prev = pt
        }
        points.forEachIndexed { index, v ->
            val p = Offset(w * index / (points.size - 1f), (1f - v) * h)
            drawCircle(Color.White, radius = 7f, center = p)
            drawCircle(Color(0xFFE08A2E), radius = 4.2f, center = p)
        }
    }
}

@Composable
private fun CurvePresetRow(current: List<Float>, onPick: (List<Float>) -> Unit) {
    val presets = listOf(
        "线性" to listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
        "S 曲线" to listOf(0f, 0.18f, 0.5f, 0.82f, 1f),
        "提亮" to listOf(0f, 0.32f, 0.58f, 0.80f, 1f),
        "压暗" to listOf(0f, 0.16f, 0.42f, 0.68f, 1f),
        "褪色" to listOf(0.12f, 0.30f, 0.52f, 0.72f, 0.92f)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { (name, pts) ->
            val active = pts.zip(current).all { (a, b) -> abs(a - b) < 0.01f }
            Text(
                name,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) Color(0xFFE08A2E) else Color(0xFF666666),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (active) Color(0xFFFFEEDC) else Color(0xFFE9E9E9))
                    .clickable { onPick(pts) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SmallText(text: String) {
    Text(text, fontSize = 11.sp, color = Color(0xFF999999))
}

/** 自由裁剪：四角拖动，框外压暗；和 ImageToolbox 的裁剪手感一致。 */
@Composable
private fun CropOverlay(state: EditState, onChange: (EditState) -> Unit) {
    var corner by remember { mutableStateOf(-1) }
    val latest = rememberUpdatedState(state)
    val latestChange = rememberUpdatedState(onChange)
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    val shadowWidth = with(LocalDensity.current) { 3.5.dp.toPx() }
    val arm = with(LocalDensity.current) { 18.dp.toPx() }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val s = latest.value
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val pts = listOf(
                            Offset(s.cropL * w, s.cropT * h),
                            Offset(s.cropR * w, s.cropT * h),
                            Offset(s.cropL * w, s.cropB * h),
                            Offset(s.cropR * w, s.cropB * h)
                        )
                        corner = pts.indices.minByOrNull { i ->
                            val dx = pts[i].x - pos.x
                            val dy = pts[i].y - pos.y
                            dx * dx + dy * dy
                        } ?: -1
                    },
                    onDragEnd = { corner = -1 },
                    onDragCancel = { corner = -1 }
                ) { change, _ ->
                    val s = latest.value
                    val nx = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                    val minGap = 0.08f
                    val next = when (corner) {
                        0 -> s.copy(cropL = nx.coerceAtMost(s.cropR - minGap), cropT = ny.coerceAtMost(s.cropB - minGap))
                        1 -> s.copy(cropR = nx.coerceAtLeast(s.cropL + minGap), cropT = ny.coerceAtMost(s.cropB - minGap))
                        2 -> s.copy(cropL = nx.coerceAtMost(s.cropR - minGap), cropB = ny.coerceAtLeast(s.cropT + minGap))
                        3 -> s.copy(cropR = nx.coerceAtLeast(s.cropL + minGap), cropB = ny.coerceAtLeast(s.cropT + minGap))
                        else -> s
                    }
                    if (next != s) latestChange.value(next)
                }
            }
    ) {
        val l = state.cropL * size.width
        val t = state.cropT * size.height
        val r = state.cropR * size.width
        val b = state.cropB * size.height
        val shade = Color.Black.copy(alpha = 0.45f)
        drawRect(shade, topLeft = Offset(0f, 0f), size = Size(size.width, t))
        drawRect(shade, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
        drawRect(shade, topLeft = Offset(0f, t), size = Size(l, b - t))
        drawRect(shade, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

        // 只在拖动调节时出现九宫格，松手立刻消失
        if (corner >= 0) {
            val gridColor = Color.White.copy(alpha = 0.38f)
            for (i in 1..2) {
                val gx = l + (r - l) * i / 3f
                val gy = t + (b - t) * i / 3f
                drawLine(gridColor, Offset(gx, t), Offset(gx, b), strokeWidth = 1f)
                drawLine(gridColor, Offset(l, gy), Offset(r, gy), strokeWidth = 1f)
            }
        }

        // 四角 L 形角标：不画贯穿画面的整圈边框，只留角上的短臂
        data class Bracket(val x: Float, val y: Float, val dx: Float, val dy: Float)
        val brackets = listOf(
            Bracket(l, t, 1f, 1f),
            Bracket(r, t, -1f, 1f), // 右上：水平向左、垂直向下
            Bracket(l, b, 1f, -1f),
            Bracket(r, b, -1f, -1f)
        )
        brackets.forEach { br ->
            val hx = br.x + br.dx * arm
            val vy = br.y + br.dy * arm
            // 深色影子保证亮画面也能看清
            drawLine(Color.Black.copy(alpha = 0.55f), Offset(br.x, br.y), Offset(hx, br.y), strokeWidth = shadowWidth)
            drawLine(Color.Black.copy(alpha = 0.55f), Offset(br.x, br.y), Offset(br.x, vy), strokeWidth = shadowWidth)
            drawLine(Color.White.copy(alpha = 0.95f), Offset(br.x, br.y), Offset(hx, br.y), strokeWidth = strokeWidth)
            drawLine(Color.White.copy(alpha = 0.95f), Offset(br.x, br.y), Offset(br.x, vy), strokeWidth = strokeWidth)
        }
    }
}

/** 滤镜宫格：四列，点一下应用，强度在上面的滑杆里调。 */
@Composable
private fun FilterGrid(
    entries: List<FilterEntry>,
    current: String,
    thumbs: Map<String, ImageBitmap>,
    onPick: (FilterEntry) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        entries.forEach { entry ->
            val active = entry.id == current
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(78.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF111111))
                    .then(
                        if (active) Modifier.border(1.5.dp, Color(0xFFE08A2E), RoundedCornerShape(12.dp))
                        else Modifier
                    )
                    .clickable { onPick(entry) }
            ) {
                thumbs[entry.id]?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = entry.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color(0xCC000000)
                            )
                        )
                )
                Text(
                    // 内置 LUT 全名带「N-Log 转 Rec709」，格子只有 64dp，去掉尾巴后名称才看得全
                    entry.label.replace(" N-Log 转 Rec709", "").replace("N-Log 转 Rec709", ""),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 5.dp, end = 4.dp, bottom = 5.dp)
                )
                if (active) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(12.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color(0xFFE08A2E))
                    )
                }
            }
        }
    }
}

/** Catmull-Rom 插值：五个控制点之间平滑过渡，越界自动夹回 0..1。 */
private fun curveValue(points: List<Float>, x: Float): Float {
    if (points.size < 2) return x
    val n = points.size - 1
    val t = (x.coerceIn(0f, 1f)) * n
    val i = t.toInt().coerceIn(0, n - 1)
    val f = t - i
    fun p(k: Int) = points[k.coerceIn(0, n)]
    val p0 = p(i - 1)
    val p1 = p(i)
    val p2 = p(i + 1)
    val p3 = p(i + 2)
    val v = 0.5f * (
        (2f * p1) +
            (-p0 + p2) * f +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * f * f +
            (-p0 + 3f * p1 - 3f * p2 + p3) * f * f * f
        )
    return v.coerceIn(0f, 1f)
}

private fun needsCurve(points: List<Float>): Boolean =
    points.size != 5 || points[0] != 0f || points[1] != 0.25f || points[2] != 0.5f ||
        points[3] != 0.75f || points[4] != 1f

private fun buildCurveLut(points: List<Float>): IntArray =
    IntArray(256) { i -> (curveValue(points, i / 255f) * 255f).roundToInt().coerceIn(0, 255) }

@Composable
private fun BasicRow(state: EditState, onState: (EditState) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 旋转 / 翻转和下面的比例卡尽量贴在一起，别在中间留一大块空白。
        // 小屏一行放不下时左右滑，不再把最后几个入口挤出屏幕。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
        ) {
            BaseChip("旋转 90°", Icons.Rounded.CropRotate) {
                onState(state.copy(quarterTurns = (state.quarterTurns + 1) % 4))
            }
            BaseChip(
                if (state.flipHorizontal) "已左右翻" else "水平翻转",
                Icons.Rounded.Flip
            ) {
                onState(state.copy(flipHorizontal = !state.flipHorizontal))
            }
            BaseChip(
                if (state.flipVertical) "已上下翻" else "垂直翻转",
                Icons.Rounded.Flip
            ) {
                onState(state.copy(flipVertical = !state.flipVertical))
            }
            BaseChip("旋转 -90°", Icons.Rounded.CropRotate) {
                onState(state.copy(quarterTurns = (state.quarterTurns + 3) % 4))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, top = 0.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start)
        ) {
            val free = state.cropFree
            // 自由裁剪紧挨着「原始」，不再作为第 7 个卡片藏在最右边。
            val options = CROP_RATIOS.flatMap { ratio ->
                if (ratio == "原始") listOf(ratio, "__free__") else listOf(ratio)
            }
            options.forEach { option ->
                if (option == "__free__") {
                    CropOptionCard(
                        label = if (free) "自由·开" else "自由裁剪",
                        icon = Icons.Rounded.CropFree,
                        active = free
                    ) {
                        onState(
                            if (free) state.copy(cropFree = false, cropRatio = "原始")
                            else state.copy(
                                cropFree = true,
                                cropRatio = "原始",
                                cropL = 0f, cropT = 0f, cropR = 1f, cropB = 1f
                            )
                        )
                    }
                } else {
                    val active = !free && state.cropRatio == option
                    CropOptionCard(option, ratioIcon(option), active) {
                        onState(state.copy(cropRatio = option, cropFree = false))
                    }
                }
            }
        }
    }
}

private fun ratioIcon(ratio: String): ImageVector = when (ratio) {
    "1:1" -> Icons.Rounded.CropSquare
    "3:4", "9:16" -> Icons.Rounded.CropPortrait
    "4:3", "16:9" -> Icons.Rounded.CropLandscape
    else -> Icons.Rounded.Crop
}

@Composable
private fun CropOptionCard(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(54.dp)
            .height(58.dp)
            .doukFlatSurface(RoundedCornerShape(11.dp))
            .background(
                if (active) Color(0xFFFBF0E3).copy(alpha = 0.92f) else Color.Transparent,
                RoundedCornerShape(11.dp)
            )
            .then(
                if (active) Modifier.border(1.5.dp, Color(0xFFE08A2E), RoundedCornerShape(11.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Color(0xFFE08A2E) else Color(0xFF555555),
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            label,
            fontSize = 9.sp,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) Color(0xFFE08A2E) else Color(0xFF666666),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AdjustRow(
    active: AdjustItem,
    state: EditState,
    onPick: (AdjustItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ADJUSTS.forEach { item ->
            val picked = item.label == active.label
            val changed = abs(item.get(state)) > 0.5f
            Text(
                item.label,
                fontSize = 12.sp,
                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    picked -> Color(0xFFE08A2E)
                    changed -> Color(0xFF333333)
                    else -> Color(0xFF777777)
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        when {
                            picked -> Color(0xFFFFEEDC)
                            changed -> Color(0xFFE7E7E7)
                            else -> Color(0xFFEFEFEF)
                        }
                    )
                    .clickable { onPick(item) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun BaseChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .doukFlatSurface(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = Color(0xFF444444), modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = Color(0xFF333333))
    }
}

@Composable
private fun SmallPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xFFE9E9E9))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Color(0xFF555555) else Color(0xFFBBBBBB),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) Color(0xFF555555) else Color(0xFFBBBBBB)
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .doukFlatSurface(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Color(0xFF222222) else Color(0xFFC2C2C2),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// 真正的图像处理
// ---------------------------------------------------------------------------

/** 预览：长边按设置压到 720/1080/1440/2048/3072/4096，滑动时用 FAST 小图，松手后按这个值精算 */
private fun renderPreview(src: Bitmap, s: EditState, maxSide: Int, runtime: FilterRuntime): Bitmap {
    val scaled = scaleToMax(src, maxSide.coerceIn(480, 4096))
    return render(scaled, s, runtime)
}

/** 保存：对原图重算一遍；7000 长边以内保留原始像素，超大图才收到 7000 长边避免 OOM */
private fun renderFull(src: Bitmap, s: EditState, runtime: FilterRuntime): Bitmap {
    val scaled = scaleToMax(src, MAX_EXPORT_SIDE)
    return render(scaled, s, runtime)
}

/** 永远返回一份新的 Bitmap：后面的像素运算会原地改它，绝不能改到预览源上 */
private fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
    val long = maxOf(src.width, src.height)
    // 已经不超过上限就直接复用：render() 只会在 getPixelsArray/putPixels 里新建副本，
// 不会原地修改 src；这里再 copy 一份 45MP 的 ARGB 只会白白多占 ~180MB。
    if (long <= maxSide) return src
    val ratio = maxSide.toFloat() / long
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, w, h, true)
}

/** 编辑保存专用：先按 bounds 算 inSampleSize，再解码到长边 ≤ maxSide，峰值内存比全图解码低一个量级。 */
private fun decodeSampledForEdit(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    // 长边超过目标 2 倍才采样；同时给 1 亿像素这类超大图留一个内存上限，
    // 45MP（8256×5504）保持 inSampleSize=1，导出不会被原先 7300 的硬上限削掉分辨率。
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2 ||
        (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample).toLong() > 65_000_000L
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    val long = maxOf(bitmap.width, bitmap.height)
    if (long > maxSide) {
        val ratio = maxSide.toFloat() / long
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        bitmap = scaled
    }
    // BitmapFactory 不按 EXIF 方向转，预览走 ImageDecoder 已经转正；
    // 保存时也要先把底图转成和预览一致的方向，否则导出会横竖颠倒。
    val orientation = GalleryFileOps.exifOrientationOf(file)
    if (orientation != android.media.ExifInterface.ORIENTATION_NORMAL) {
        val upright = GalleryFileOps.applyExifOrientation(bitmap, orientation)
        if (upright !== bitmap) bitmap.recycle()
        bitmap = upright
    }
    return bitmap
}

private fun render(
    src: Bitmap,
    s: EditState,
    runtime: FilterRuntime,
    quick: Boolean = false,
    activeDetail: String = ""
): Bitmap {
    var work = geometry(src, s)
    work = if (s.cropFree) cropByRect(work, s.cropL, s.cropT, s.cropR, s.cropB)
    else cropByRatio(work, s.cropRatio)
    // Ultra HDR / 硬件解码出来的位图是不可变的，setPixels 会直接抛 IllegalStateException
    val target = if (work.isMutable && work.config == Bitmap.Config.ARGB_8888) {
        work
    } else {
        work.copy(Bitmap.Config.ARGB_8888, true) ?: work
    }
    val pixels = target.getPixelsArray()
    applyColor(pixels, s, runtime)
    // 拖动时只算当前正在调的那一个细节项，其余细节等松手后的高清预览补
    if (s.denoise > 0.5f && (!quick || activeDetail == "降噪")) {
        blurInto(pixels, target.width, target.height, (s.denoise / 100f * 0.6f))
    }
    if (s.sharpen > 0.5f && (!quick || activeDetail == "锐化")) {
        unsharp(pixels, target.width, target.height, s.sharpen / 100f)
    }
    if (s.clarity > 0.5f && (!quick || activeDetail == "清晰度")) {
        unsharp(pixels, target.width, target.height, s.clarity / 100f * 0.5f)
    }
    if (s.grain > 0.5f && (!quick || activeDetail == "颗粒")) {
        addGrain(pixels, s.grain / 100f)
    }
    return target.putPixels(pixels)
}

/** 旋转 + 翻转；90/270 度时宽高对调 */
private fun geometry(src: Bitmap, s: EditState): Bitmap {
    if (s.quarterTurns == 0 && s.fineRotate == 0f && !s.flipHorizontal && !s.flipVertical) return src
    val m = Matrix()
    if (s.flipHorizontal) m.postScale(-1f, 1f, src.width / 2f, src.height / 2f)
    if (s.flipVertical) m.postScale(1f, -1f, src.width / 2f, src.height / 2f)
    m.postRotate(s.quarterTurns * 90f + s.fineRotate)
    return runCatching { Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) }
        .getOrDefault(src)
}

/** 按比例中心裁切（自由裁切＝保持原始比例，不做处理） */
private fun cropByRatio(src: Bitmap, ratio: String): Bitmap {
    val target = when (ratio) {
        "1:1" -> 1f
        "3:4" -> 3f / 4f
        "4:3" -> 4f / 3f
        "9:16" -> 9f / 16f
        "16:9" -> 16f / 9f
        else -> return src
    }
    val w = src.width.toFloat()
    val h = src.height.toFloat()
    val current = w / h
    return if (abs(current - target) < 0.001f) {
        src
    } else if (current > target) {
        val newW = (h * target).toInt().coerceAtLeast(1)
        val x = ((w - newW) / 2).toInt()
        runCatching { Bitmap.createBitmap(src, x, 0, newW, src.height) }.getOrDefault(src)
    } else {
        val newH = (w / target).toInt().coerceAtLeast(1)
        val y = ((h - newH) / 2).toInt()
        runCatching { Bitmap.createBitmap(src, 0, y, src.width, newH) }.getOrDefault(src)
    }
}

private fun cropByRect(src: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap {
    val left = (l.coerceIn(0f, 0.95f) * src.width).toInt()
    val top = (t.coerceIn(0f, 0.95f) * src.height).toInt()
    val right = (r.coerceIn(0.05f, 1f) * src.width).toInt()
    val bottom = (b.coerceIn(0.05f, 1f) * src.height).toInt()
    val w = (right - left).coerceAtLeast(2)
    val h = (bottom - top).coerceAtLeast(2)
    return runCatching { Bitmap.createBitmap(src, left, top, w, h) }.getOrDefault(src)
}

private fun Bitmap.getPixelsArray(): IntArray {
    val out = IntArray(width * height)
    getPixels(out, 0, width, 0, 0, width, height)
    return out
}

private fun Bitmap.putPixels(pixels: IntArray): Bitmap {
    setPixels(pixels, 0, width, 0, 0, width, height)
    return this
}

/** 颜色那一整段（曝光 / 亮度 / 对比度 / 高光 / 阴影 / 饱和度 / 自然饱和度 / 色温 / 色调 + 滤镜微调）。
 * 逐像素在 0..1 浮点区间算，最后夹回 0..255。
 */
private fun applyColor(px: IntArray, s: EditState, runtime: FilterRuntime) {
    val amount = s.filterAmount / 100f
    val look = FILTER_LOOKS[s.filter] ?: FilterLook()
    val ev = 2.0.pow(((s.exposure + look.exposure * amount) / 100f * 2f).toDouble()).toFloat()
    val bright = s.brightness / 100f * 0.4f
    val finalContrast = 1f + (s.contrast + look.contrast * amount) / 100f
    val hi = s.highlight / 100f
    val sh = s.shadow / 100f
    val finalSat = (1f + (s.saturation + look.saturation * amount) / 100f).coerceAtLeast(0f)
    val vib = s.vibrancy / 100f
    var temp = (s.temperature + look.temperature * amount) / 100f
    var tintV = (s.tint + look.tint * amount) / 100f
    val fade = look.fade * amount / 100f * 0.18f
    val mono = (look.mono * amount / 100f).coerceIn(0f, 1f)

    // 曲线：主曲线 + RGB 通道曲线，0..255 映射表先算好，循环里只查表
    val masterLut = if (!needsCurve(s.curve)) null else buildCurveLut(s.curve)
    val redLut = if (!needsCurve(s.curveR)) null else buildCurveLut(s.curveR)
    val greenLut = if (!needsCurve(s.curveG)) null else buildCurveLut(s.curveG)
    val blueLut = if (!needsCurve(s.curveB)) null else buildCurveLut(s.curveB)
    // HSL：只作用于选中的目标色相范围
    val hueShift = s.hslHue / 360f
    val hslSatK = 1f + s.hslSat / 100f
    val hslLightK = s.hslLight / 100f * 0.5f
    val hslOn = s.hslHue != 0f || s.hslSat != 0f || s.hslLight != 0f
    val targetHue = HSL_RANGES[s.hslRange.coerceIn(0, HSL_RANGES.lastIndex)].first / 360f

    for (i in px.indices) {
        val c = px[i]
        var r = ((c shr 16) and 0xFF) / 255f
        var g = ((c shr 8) and 0xFF) / 255f
        var b = (c and 0xFF) / 255f

        r *= ev; g *= ev; b *= ev
        r += bright; g += bright; b += bright

        r = (r - 0.5f) * finalContrast + 0.5f
        g = (g - 0.5f) * finalContrast + 0.5f
        b = (b - 0.5f) * finalContrast + 0.5f

        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        // 高光只压亮部，阴影只提暗部，互不打架
        if (hi != 0f) {
            val wgt = ((lum - 0.45f) / 0.55f).coerceIn(0f, 1f)
            val delta = -hi * wgt * 0.45f
            r += delta; g += delta; b += delta
        }
        if (sh != 0f) {
            val wgt = ((0.55f - lum) / 0.55f).coerceIn(0f, 1f)
            val delta = sh * wgt * 0.45f
            r += delta; g += delta; b += delta
        }

        // 色温：暖 +R-B，冷 -R+B；色调：偏洋红 +R+B，偏绿 -G
        if (temp != 0f) {
            r += temp * 0.18f
            b -= temp * 0.18f
        }
        if (tintV != 0f) {
            g -= tintV * 0.12f
            r += tintV * 0.04f
            b += tintV * 0.04f
        }

        val gray = 0.299f * r + 0.587f * g + 0.114f * b
        r = gray + (r - gray) * finalSat
        g = gray + (g - gray) * finalSat
        b = gray + (b - gray) * finalSat

        // 自然饱和度：只对本来不艳的颜色补一点，避免人脸过饱和
        if (vib != 0f) {
            val mx = maxOf(r, maxOf(g, b))
            val mn = minOf(r, minOf(g, b))
            val curSat = if (mx <= 0f) 0f else (mx - mn) / mx
            val boost = vib * (1f - curSat) * 0.5f
            r = gray + (r - gray) * (1f + boost)
            g = gray + (g - gray) * (1f + boost)
            b = gray + (b - gray) * (1f + boost)
        }

        // 曲线：主曲线先压亮度，再逐通道微调
        if (masterLut != null) {
            r = masterLut[clamp255(r)] / 255f
            g = masterLut[clamp255(g)] / 255f
            b = masterLut[clamp255(b)] / 255f
        }
        if (redLut != null) r = redLut[clamp255(r)] / 255f
        if (greenLut != null) g = greenLut[clamp255(g)] / 255f
        if (blueLut != null) b = blueLut[clamp255(b)] / 255f

        // HSL：转 HSL，只改选中色相附近的像素，避免整张图偏色
        if (hslOn) {
            val mx = maxOf(r, maxOf(g, b))
            val mn = minOf(r, minOf(g, b))
            var hue = when {
                mx - mn < 1e-6f -> 0f
                mx == r -> ((g - b) / (mx - mn)) / 6f
                mx == g -> (2f + (b - r) / (mx - mn)) / 6f
                else -> (4f + (r - g) / (mx - mn)) / 6f
            }
            if (hue < 0f) hue += 1f
            var lig = (mx + mn) / 2f
            var satH = if (mx - mn < 1e-6f) 0f else
                if (lig > 0.5f) (mx - mn) / (2f - mx - mn + 1e-6f)
                else (mx - mn) / (mx + mn + 1e-6f)
            val dist = minOf(abs(hue - targetHue), 1f - abs(hue - targetHue))
            val weight = (1f - dist / 0.13f).coerceIn(0f, 1f)

            if (satH > 0.05f && weight > 0f) {
                hue = ((hue + hueShift * weight) % 1f + 1f) % 1f
                satH = (satH * (1f + (hslSatK - 1f) * weight)).coerceIn(0f, 1f)
                lig = (lig + hslLightK * weight).coerceIn(0f, 1f)
            }

            val ch = (1f - abs(2f * lig - 1f)) * satH
            val hp = hue * 6f
            val xc = ch * (1f - abs(hp % 2f - 1f))
            val m0 = lig - ch / 2f
            when (hp.toInt()) {
                0 -> { r = ch + m0; g = xc + m0; b = m0 }
                1 -> { r = xc + m0; g = ch + m0; b = m0 }
                2 -> { r = m0; g = ch + m0; b = xc + m0 }
                3 -> { r = m0; g = xc + m0; b = ch + m0 }
                4 -> { r = xc + m0; g = m0; b = ch + m0 }
                else -> { r = ch + m0; g = m0; b = xc + m0 }
            }
        }

        // 褪色：把黑位抬起来，模拟胶片发灰
        if (fade > 0f) {
            r = r * (1f - fade) + fade * 0.22f
            g = g * (1f - fade) + fade * 0.22f
            b = b * (1f - fade) + fade * 0.22f
        }
        // 黑白：按强度向亮度灰混合
        if (mono > 0f) {
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            r = r * (1f - mono) + l * mono
            g = g * (1f - mono) + l * mono
            b = b * (1f - mono) + l * mono
        }

        px[i] = (c and 0xFF000000.toInt()) or
            (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
    }

    // 导入滤镜：真正查 3D LUT，再按强度混回调节后的像素
    if (s.filter.startsWith("res:") && amount > 0f) {
        runtime.lut(s.filter)?.let { lut ->
            val out = FloatArray(3)
            val k = amount.coerceIn(0f, 1f)
            for (i in px.indices) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                lut.map(r, g, b, out)
                val nr = clamp255(r + (out[0] - r) * k)
                val ng = clamp255(g + (out[1] - g) * k)
                val nb = clamp255(b + (out[2] - b) * k)
                px[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }
}

private fun clamp255(v: Float): Int {
    val x = (v * 255f).toInt()
    return if (x < 0) 0 else if (x > 255) 255 else x
}

/** 降噪：3×3 均值与原像素按强度混合 */
private fun blurInto(px: IntArray, w: Int, h: Int, strength: Float) {
    val src = px.copyOf()
    val k = strength.coerceIn(0f, 1f)
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            var r = 0; var g = 0; var b = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val c = src[(y + dy) * w + (x + dx)]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
            }
            val or0 = ((src[idx] shr 16) and 0xFF).toFloat()
            val og0 = ((src[idx] shr 8) and 0xFF).toFloat()
            val ob0 = (src[idx] and 0xFF).toFloat()
            val nr = (or0 * (1 - k) + (r / 9f) * k).toInt()
            val ng = (og0 * (1 - k) + (g / 9f) * k).toInt()
            val nb = (ob0 * (1 - k) + (b / 9f) * k).toInt()
            px[idx] = (src[idx] and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}

/** 锐化 / 清晰度：3×3 拉普拉斯高通，按强度叠回去 */
private fun unsharp(px: IntArray, w: Int, h: Int, strength: Float) {
    val src = px.copyOf()
    val k = strength.coerceIn(0f, 1.5f)
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            val c = src[idx]
            val r0 = (c shr 16) and 0xFF
            val g0 = (c shr 8) and 0xFF
            val b0 = c and 0xFF
            val rN = ((src[idx - 1] shr 16) and 0xFF) + ((src[idx + 1] shr 16) and 0xFF) +
                ((src[idx - w] shr 16) and 0xFF) + ((src[idx + w] shr 16) and 0xFF)
            val gN = ((src[idx - 1] shr 8) and 0xFF) + ((src[idx + 1] shr 8) and 0xFF) +
                ((src[idx - w] shr 8) and 0xFF) + ((src[idx + w] shr 8) and 0xFF)
            val bN = (src[idx - 1] and 0xFF) + (src[idx + 1] and 0xFF) +
                (src[idx - w] and 0xFF) + (src[idx + w] and 0xFF)
            val nr = (r0 + (r0 * 4 - rN) / 4f * k).toInt().coerceIn(0, 255)
            val ng = (g0 + (g0 * 4 - gN) / 4f * k).toInt().coerceIn(0, 255)
            val nb = (b0 + (b0 * 4 - bN) / 4f * k).toInt().coerceIn(0, 255)
            px[idx] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}

/** 颗粒：固定种子，滑杆来回不会闪 */
private fun addGrain(px: IntArray, strength: Float) {
    val rnd = java.util.Random(20261116L)
    val amp = (strength * 26f).toInt().coerceAtLeast(1)
    for (i in px.indices) {
        val n = rnd.nextInt(amp * 2 + 1) - amp
        val c = px[i]
        val r = (((c shr 16) and 0xFF) + n).coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) + n).coerceIn(0, 255)
        val b = ((c and 0xFF) + n).coerceIn(0, 255)
        px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}

/** 保存：算出全尺寸结果，写进 Pictures/连镜，文件名 = 原名 + _edit + 序号 */
private fun geometryNeutral(s: EditState): Boolean =
    !s.cropFree &&
        s.cropRatio == "原始" &&
        s.quarterTurns == 0 &&
        s.fineRotate == 0f &&
        !s.flipHorizontal &&
        !s.flipVertical

private fun canReuseOriginalGain(s: EditState): Boolean =
    geometryNeutral(s) &&
        // HDR 专属调色改了底图亮度和颜色，原片 GainMap 的对应关系会偏；
        // 调过色就按新底图重新生成，没调色才复用相机原始 GainMap。
        HdrGrade.from(s).isNeutral

private fun saveEdited(
    context: Context,
    item: GalleryItem,
    s: EditState,
    runtime: FilterRuntime,
    srcFormat: String = item.formats.firstOrNull()?.uppercase() ?: "JPG",
    format: String = DoukPrefs.editorFormat,
    quality: Int = DoukPrefs.editorQuality,
    hdr: Boolean = false,
    hdrStrength: Float = 1f
): Result<String> {
    val strength = hdrStrength.coerceIn(0f, 1f)
    val file = resolveSourceFile(item, srcFormat)
        ?: return Result.failure(IllegalStateException("只有应用内下载的照片可以编辑，相机与手机相册的原图请先保存到应用"))
    var src: Bitmap? = null
    var rendered: Bitmap? = null
    var intermediate: Bitmap? = null
    var reusePair: ImageDecode.HdrPair? = null
    try {
        // 30MP 原图按 7300 长边保留；更夸张的尺寸才采样，避免一亿像素整图 OOM。
        // RAW（NEF/DNG）先走 LibRaw 真解码；失败才退回相机内嵌原图，保证导出不中断。
        val decoded = if (ImageDecode.isRawFile(file.name)) {
            RawDecode.decode(file, MAX_EXPORT_SIDE, preview = false)?.also { src = it }
                ?: ImageDecode.extractEmbeddedPreview(file, MAX_EXPORT_SIDE)?.asAndroidBitmap()
        } else {
            decodeSampledForEdit(file, MAX_EXPORT_SIDE)
        } ?: return Result.failure(IllegalStateException("这张图解码失败，可能格式不支持"))
        src = decoded
        val output = renderFull(decoded, s, runtime)
        rendered = output
        intermediate = output
        // HDR 质感调色只叠加在 HDR 输出上；不调 HDR 时原样走 SDR。
        val grade = HdrGrade.from(s)
        val graded = if (hdr) {
            applyHdrGrade(output, grade).also { if (it !== output) rendered = it }
        } else output
        // JPG+HDR 走 Ultra HDR：底图必须是编辑后的原图，不能再先做 hdrTone。
        // 以前所有 HDR 格式都先压一遍高光，写进 Ultra HDR 的底图已经变灰，
        // 系统相册/本 App 再读出来就只剩一层灰片。
        // 只有 PNG/WebP 这类没有 HDR 容器的格式才用 hdrTone 做视觉上的高光滚降。
        val finalBmp = if (hdr && format != "JPG") hdrTone(graded).also {
            if (it !== graded) rendered = it
        } else graded
        val base = file.nameWithoutExtension
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            "连镜"
        )
        if (!dir.exists()) dir.mkdirs()

        val ext = when (format) {
            "PNG" -> "png"
            "WebP" -> "webp"
            else -> "jpg"
        }
        val compressFormat = when (format) {
            "PNG" -> Bitmap.CompressFormat.PNG
            "WebP" -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val q = quality.coerceIn(60, 100)

        var name = "${base}_edit${if (hdr) "_hdr" else ""}.$ext"
        var index = 2
        while (File(dir, name).exists()) {
            name = "${base}_edit${if (hdr) "_hdr" else ""}${index}.$ext"
            index++
        }
        val targetFile = File(dir, name)

        val attempt = runCatching {
            // JPG + HDR 开关：写真正的 Ultra HDR（JPEG + gainmap），
            // 系统相册 / 本 App 图库都会识别成 HDR 图，亮部会真的超过 SDR 白。
            var ultraHdrWritten = false
            val reuseGainmap = hdr && format == "JPG" &&
                android.os.Build.VERSION.SDK_INT >= 34 &&
                canReuseOriginalGain(s) &&
                !ImageDecode.isRawFile(file.name)
            if (reuseGainmap) {
                // 没有裁剪 / 旋转 / 翻转时，编辑后的底图和原片保持同一构图。
                // 直接复用原片的 GainMap 像素和 ISO 21496-1 参数，比按亮度
                // 直方图反推一张假增益图更接近相机真实的高光层次。
                reusePair = runCatching { ImageDecode.decodeHdrPair(file, 1024) }.getOrNull()
                // 只需要 GainMap，底图只是解码时的副产物；先释放大图。
                runCatching { reusePair?.base?.recycle() }
                com.lutcamera.lutcamerapro.log.DoukLog.i(
                    "PhotoEditor", "HDR_GAIN_REUSE",
                    "src" to file.name,
                    "gain" to (reusePair?.gain?.let { "${it.width}x${it.height}" } ?: "none"),
                    "max" to (reusePair?.ratioMax?.toString() ?: "-")
                )
            }
            if (hdr && format == "JPG" && android.os.Build.VERSION.SDK_INT >= 34 && strength > 0.02f) {
                var rawGainBmp: Bitmap? = null
                try {
                    // RAW 源：直接给 Ultra HDR 写一张由传感器 CFA 原始像素生成的 GainMap。
                    // 这一步不再从 8bit 底图反推，高光细节取决于 NEF/DNG 自身的数据。
                    // 一旦裁剪/旋转/翻转，RAW 传感器坐标和成品底图就对不上，
                    // 这时不能硬套 RAW GainMap，退回按成品底图生成的增益层。
                    var rawGainMax = 1f + 2.4f * strength
                    if (ImageDecode.isRawFile(file.name) && geometryNeutral(s)) {
                        val highlightStart =
                            (0.36f + 0.50f * (s.hdrHighlight / 100f)).coerceIn(0.30f, 0.90f)
                        val rawHdr = RawDecode.hdrGain(file, 2048, rawGainMax, highlightStart)
                        if (rawHdr != null) {
                            rawGainBmp = rawHdr.gain
                            rawGainMax = rawHdr.ratioMax
                            com.lutcamera.lutcamerapro.log.DoukLog.i(
                                "PhotoEditor", "HDR_RAW_GAIN",
                                "file" to file.name,
                                "gain" to "${rawHdr.gain.width}x${rawHdr.gain.height}",
                                "max" to rawHdr.ratioMax.toString(),
                                "start" to highlightStart.toString()
                            )
                        }
                    }
                    ultraHdrWritten = runCatching {
                        writeUltraHdrJpeg(
                            targetFile, finalBmp, q,
                            sourcePair = reusePair,
                            sourceOrientation = GalleryFileOps.exifOrientationOf(file),
                            hdrStrength = strength,
                            rawSensorGain = rawGainBmp,
                            rawSensorGainMax = rawGainMax
                        )
                    }.getOrDefault(false)
                } finally {
                    runCatching { rawGainBmp?.recycle() }
                }
                if (!ultraHdrWritten) {
                    com.lutcamera.lutcamerapro.log.DoukLog.w("PhotoEditor", "ULTRA_HDR_FAIL")
                }
            }
            if (!ultraHdrWritten) {
                // JPG 的 Ultra HDR 写入失败时才退回「高光滚降」的 SDR JPG；
                // PNG/WebP 的 finalBmp 已经是滚降后的版本，直接用。
                val fallback = if (hdr && format == "JPG") hdrTone(finalBmp) else finalBmp
                try {
                    targetFile.outputStream().use { out ->
                        fallback.compress(compressFormat, q, out)
                    }
                } finally {
                    if (fallback !== finalBmp) runCatching { fallback.recycle() }
                }
            }
            // Bitmap.compress 不带 EXIF。JPG 出来后把原片的拍摄参数、镜头、时间、
            // GPS 定位等补回去；方向和尺寸按编辑后的成品重写，避免二次旋转。
            if (format == "JPG") {
                runCatching { copyExifFrom(file, targetFile, finalBmp.width, finalBmp.height) }
                    .onFailure {
                        com.lutcamera.lutcamerapro.log.DoukLog.w(
                            "PhotoEditor", "EXIF_COPY_FAIL", "msg" to (it.message ?: "")
                        )
                    }
            }
            // 不能再用 MediaStore.Images.Media.insertImage(path)：它会把文件
            // 重新编码/复制一份，Ultra HDR 的 APP2 MPF、GainMap 和 EXIF 会被
            // 整段丢掉，系统图库最终只看到一张 SDR 底图，也就是用户说的“灰”。
            // 文件已经写在 Pictures/连镜，只需让媒体库扫描这个原文件。
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("image/jpeg")
                ) { _, _ -> }
            }
            name
        }
        return attempt.fold(
            onSuccess = { Result.success(it) },
            onFailure = { e -> Result.failure(IllegalStateException("保存失败：${e.message}")) }
        )
    } catch (e: OutOfMemoryError) {
        return Result.failure(IllegalStateException("这张图太大，手机内存不足；请先降低分辨率再编辑"))
    } finally {
        // 成功/失败都释放，避免异常路径把全尺寸 ARGB 留在堆里等 GC。
        if (src !== rendered) runCatching { src?.recycle() }
        if (intermediate != null && intermediate !== rendered && intermediate !== src) {
            runCatching { intermediate?.recycle() }
        }
        runCatching { rendered?.recycle() }
        runCatching { reusePair?.gain?.recycle() }
    }
}

/**
 * 把原片 EXIF 整段补到编辑后的 JPG。
 *
 * 不能逐 tag 用 ExifInterface 重写：Android 从 10 起会把 GPS 等位置信息
 * 对普通应用做脱敏，读出来就是 0/0/0；而且重写 APP1 会丢掉厂商私有 tag。
 * 这里直接把原片 APP1（Exif\0\0）原样搬过去，GPS、镜头、曝光、厂商 MakerNote
 * 全部保留；同时按 APP1 长度差修正 Ultra HDR 的 MPF 增益图偏移。
 */
internal fun copyExifFrom(source: File, target: File, width: Int, height: Int) {
    if (!source.isFile || !target.isFile) return
    val srcBytes = source.readBytes()
    val dstBytes = target.readBytes()
    val srcExif = findExifApp1(srcBytes) ?: return
    // 原片 EXIF 直接整段搬过来会带来两个问题：
    // 1) 原片的 Orientation 还在，底图其实已经按方向摆正，图库会再转一次；
    // 2) 原片记录的像素尺寸和编辑后的成品不一致。
    // 这里只改这两个字段，其余 GPS / 镜头 / 厂商 MakerNote 全部原样保留。
    val srcSegment = normalizeExifSegment(
        srcBytes.copyOfRange(srcExif.first, srcExif.second), width, height
    )
    val dstExif = findExifApp1(dstBytes)
    val delta: Int
    var out: ByteArray
    if (dstExif != null) {
        delta = srcSegment.size - (dstExif.second - dstExif.first)
        out = ByteArray(dstBytes.size + delta)
        System.arraycopy(dstBytes, 0, out, 0, dstExif.first)
        System.arraycopy(srcSegment, 0, out, dstExif.first, srcSegment.size)
        System.arraycopy(
            dstBytes, dstExif.second, out, dstExif.first + srcSegment.size,
            dstBytes.size - dstExif.second
        )
    } else {
        delta = srcSegment.size
        out = ByteArray(dstBytes.size + delta)
        System.arraycopy(dstBytes, 0, out, 0, 2)
        System.arraycopy(srcSegment, 0, out, 2, srcSegment.size)
        System.arraycopy(dstBytes, 2, out, 2 + srcSegment.size, dstBytes.size - 2)
    }
    // Ultra HDR 的 GainMap 是一张追加在主图后面的 JPEG，主图通过 APP2 MPF
    // 里的 32 位偏移找到它。APP1 变长/变短后，MPF 头和图尾的 GainMap 一起
    // 平移，偏移字段本身不用变；但不同写入路径可能留下旧偏移，所以这里按
    // 实际字节位置重新写一次，保证系统图库一定能找到 GainMap。
    out = repairMpfGainmapOffset(out)
    target.writeBytes(out)
}

/**
 * 按实际文件布局修正 MPF 里 GainMap 的偏移和长度。
 *
 * 相机原片的 APP1 可能有几十 KB，Ultra HDR 写入时只有 1 KB 左右；直接把
 * 原片 APP1 换进去后，如果偏移字段还是旧值，部分图库会找不到 GainMap，
 * 于是只显示 SDR 底图，观感就是整张 HDR 发灰。
 */
private fun repairMpfGainmapOffset(data: ByteArray): ByteArray {
    if (data.size < 16) return data
    var mpfMarker = -1
    var i = 2
    var mainEoi = -1
    while (i + 4 <= data.size) {
        if (data[i] != 0xFF.toByte()) break
        val marker = data[i + 1].toInt() and 0xFF
        if (marker == 0xD9) break
        if (marker == 0xD8) { i += 2; continue }
        if (marker == 0xDA) {
            var p = i + 2
            while (p + 1 < data.size) {
                if (data[p] == 0xFF.toByte() && data[p + 1] == 0xD9.toByte()) {
                    mainEoi = p
                    break
                }
                p++
            }
            break
        }
        val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
        if (len < 2) break
        val end = i + 2 + len
        if (end > data.size) break
        if (marker == 0xE2 && data[i + 4] == 'M'.code.toByte() &&
            data[i + 5] == 'P'.code.toByte() && data[i + 6] == 'F'.code.toByte() &&
            data[i + 7] == 0.toByte()
        ) {
            mpfMarker = i
            break
        }
        i = end
    }
    if (mpfMarker < 0 || mainEoi < 0) return data
    // 主图 EOI 之后的第一张 SOI 就是追加的 GainMap。
    var gainStart = -1
    var p = mainEoi + 2
    while (p + 1 < data.size) {
        if (data[p] == 0xFF.toByte() && data[p + 1] == 0xD8.toByte()) {
            gainStart = p
            break
        }
        p++
    }
    if (gainStart < 0) return data
    // APP2 的布局是：FF E2 len(2) 'MPF\0'(4) TIFF头。mpfMarker 指向 FF，
    // 所以 TIFF 头在 mpfMarker + 4（跳过 marker/len）+ 4（跳过 'MPF\0'）。
    // 之前只加了 4，IFD 和 MPEntry 全读到 'MPF\0' 上，写出来的偏移是错的。
    val tiff = mpfMarker + 8
    if (tiff + 8 > data.size) return data
    val le = data[tiff] == 'I'.code.toByte() && data[tiff + 1] == 'I'.code.toByte()
    fun u16(o: Int): Int = if (le) {
        (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)
    } else {
        ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
    }
    fun u32(o: Int): Int = if (le) {
        (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8) or
            ((data[o + 2].toInt() and 0xFF) shl 16) or ((data[o + 3].toInt() and 0xFF) shl 24)
    } else {
        ((data[o].toInt() and 0xFF) shl 24) or ((data[o + 1].toInt() and 0xFF) shl 16) or
            ((data[o + 2].toInt() and 0xFF) shl 8) or (data[o + 3].toInt() and 0xFF)
    }
    fun w32(o: Int, v: Int) {
        if (le) {
            data[o] = (v and 0xFF).toByte()
            data[o + 1] = ((v ushr 8) and 0xFF).toByte()
            data[o + 2] = ((v ushr 16) and 0xFF).toByte()
            data[o + 3] = ((v ushr 24) and 0xFF).toByte()
        } else {
            data[o] = ((v ushr 24) and 0xFF).toByte()
            data[o + 1] = ((v ushr 16) and 0xFF).toByte()
            data[o + 2] = ((v ushr 8) and 0xFF).toByte()
            data[o + 3] = (v and 0xFF).toByte()
        }
    }
    val ifd0 = tiff + u32(tiff + 4)
    if (ifd0 + 2 > data.size) return data
    val count = u16(ifd0)
    if (count !in 0..64) return data
    var mpEntryOffset = -1
    for (k in 0 until count) {
        val e = ifd0 + 2 + k * 12
        if (e + 12 > data.size) break
        if (u16(e) == 0xB002) {
            mpEntryOffset = u32(e + 8)
            break
        }
    }
    if (mpEntryOffset < 0) return data
    val entries = tiff + mpEntryOffset
    // 第 0 项是主图（偏移意义特殊，保持 0），第 1 项是 GainMap。
    val gainEntry = entries + 16
    if (gainEntry + 12 > data.size) return data
    w32(gainEntry + 8, gainStart - tiff)
    val gainSize = data.size - gainStart
    if (gainSize > 0) w32(gainEntry + 4, gainSize)
    return data
}

/**
 * 只改 APP1 里的 Orientation 和像素尺寸，保留其余 EXIF 原样。
 * 支持 II / MM 两种字节序；字段不在预期位置时静默跳过，绝不破坏原片。
 */
private fun normalizeExifSegment(segment: ByteArray, width: Int, height: Int): ByteArray {
    val out = segment.copyOf()
    if (out.size < 18) return out
    val tiff = 10
    val le = out[tiff] == 'I'.code.toByte() && out[tiff + 1] == 'I'.code.toByte()
    fun u16(o: Int): Int = if (o < 0 || o + 1 >= out.size) 0 else if (le) {
        (out[o].toInt() and 0xFF) or ((out[o + 1].toInt() and 0xFF) shl 8)
    } else {
        ((out[o].toInt() and 0xFF) shl 8) or (out[o + 1].toInt() and 0xFF)
    }
    fun u32(o: Int): Int = if (o < 0 || o + 3 >= out.size) 0 else if (le) {
        (out[o].toInt() and 0xFF) or ((out[o + 1].toInt() and 0xFF) shl 8) or
            ((out[o + 2].toInt() and 0xFF) shl 16) or ((out[o + 3].toInt() and 0xFF) shl 24)
    } else {
        ((out[o].toInt() and 0xFF) shl 24) or ((out[o + 1].toInt() and 0xFF) shl 16) or
            ((out[o + 2].toInt() and 0xFF) shl 8) or (out[o + 3].toInt() and 0xFF)
    }
    fun w16(o: Int, v: Int) {
        if (o < 0 || o + 1 >= out.size) return
        if (le) {
            out[o] = (v and 0xFF).toByte()
            out[o + 1] = ((v ushr 8) and 0xFF).toByte()
        } else {
            out[o] = ((v ushr 8) and 0xFF).toByte()
            out[o + 1] = (v and 0xFF).toByte()
        }
    }
    fun w32(o: Int, v: Int) {
        if (o < 0 || o + 3 >= out.size) return
        if (le) {
            out[o] = (v and 0xFF).toByte()
            out[o + 1] = ((v ushr 8) and 0xFF).toByte()
            out[o + 2] = ((v ushr 16) and 0xFF).toByte()
            out[o + 3] = ((v ushr 24) and 0xFF).toByte()
        } else {
            out[o] = ((v ushr 24) and 0xFF).toByte()
            out[o + 1] = ((v ushr 16) and 0xFF).toByte()
            out[o + 2] = ((v ushr 8) and 0xFF).toByte()
            out[o + 3] = (v and 0xFF).toByte()
        }
    }
    var exifIfd = 0
    fun patchIfd(ifdOffset: Int) {
        val ifd = tiff + ifdOffset
        if (ifd < 0 || ifd + 2 >= out.size) return
        val count = u16(ifd)
        if (count !in 0..512) return
        for (k in 0 until count) {
            val e = ifd + 2 + k * 12
            if (e + 12 > out.size) break
            val tag = u16(e)
            val type = u16(e + 2)
            val n = u32(e + 4)
            if (n != 1) {
                if (tag == 0x8769 && type == 4) exifIfd = u32(e + 8)
                continue
            }
            when (tag) {
                0x0112 -> if (type == 3) w16(e + 8, 1)
                0x0100, 0xA002 -> if (type == 3) w16(e + 8, width) else if (type == 4) w32(e + 8, width)
                0x0101, 0xA003 -> if (type == 3) w16(e + 8, height) else if (type == 4) w32(e + 8, height)
                0x8769 -> if (type == 4) exifIfd = u32(e + 8)
            }
        }
    }
    val ifd0Offset = u32(tiff + 4)
    if (ifd0Offset <= 0 || tiff + ifd0Offset + 2 > out.size) return out
    patchIfd(ifd0Offset)
    if (exifIfd > 0) patchIfd(exifIfd)
    return out
}

private fun findExifApp1(data: ByteArray): Pair<Int, Int>? {
    var i = 2
    while (i + 10 < data.size) {
        if (data[i] != 0xFF.toByte()) break
        val marker = data[i + 1].toInt() and 0xFF
        if (marker == 0xDA || marker == 0xD9) break
        if (marker == 0xD8) { i += 2; continue }
        val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
        if (len < 2) break
        val end = i + 2 + len
        if (end > data.size) break
        if (marker == 0xE1 &&
            data[i + 4] == 'E'.code.toByte() && data[i + 5] == 'x'.code.toByte() &&
            data[i + 6] == 'i'.code.toByte() && data[i + 7] == 'f'.code.toByte() &&
            data[i + 8] == 0.toByte() && data[i + 9] == 0.toByte()
        ) return i to end
        i = end
    }
    return null
}

/**
 * 写 Ultra HDR JPEG（Android 14 起的标准 HDR 照片格式）：
 * 一张普通 8bit JPEG 当底图 + 一张单通道 gainmap（ALPHA_8），
 * 亮部在 gainmap 里带 0–2.6x 的额外亮度，HDR 屏打开时会真的更亮。
 * 失败返回 false，调用方回退成普通 JPEG。
 */
internal fun writeUltraHdrJpeg(
    target: File,
    bmp: Bitmap,
    quality: Int,
    sourcePair: ImageDecode.HdrPair? = null,
    sourceOrientation: Int = android.media.ExifInterface.ORIENTATION_NORMAL,
    hdrStrength: Float = 1f,
    rawSensorGain: Bitmap? = null,
    rawSensorGainMax: Float = 2.9f
): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 34) return false
    val strength = hdrStrength.coerceIn(0.02f, 1f)
    var base: Bitmap? = null
    var gainContents: Bitmap? = null
    var orientedSourceGain: Bitmap? = null
    return try {
        base = if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable) {
            bmp
        } else {
            bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return false
        }

        var gw: Int
        var gh: Int
        val gainFromSource: Boolean
        val gm: android.graphics.Gainmap
        if (sourcePair != null && sourcePair.gain.width > 0 && sourcePair.gain.height > 0) {
            // 无裁剪 / 旋转 / 翻转：直接复用原片 GainMap 的像素和 ISO 21496-1 参数。
            // GainMap 只记录「底图亮度 -> 额外增益」的比值，原片和编辑后的构图一致时
            // 不需要重新按直方图猜一张；调色/滤镜只改变底图，高光层次仍沿用相机那套。
            val rawGain = sourcePair.gain
            val gain = if (!sourcePair.orientationApplied &&
                sourceOrientation != android.media.ExifInterface.ORIENTATION_NORMAL
            ) {
                GalleryFileOps.applyExifOrientation(rawGain, sourceOrientation).also {
                    if (it !== rawGain) orientedSourceGain = it
                }
            } else {
                rawGain
            }
            gw = gain.width
            gh = gain.height
            val alpha = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
            val src = IntArray(gw * gh)
            gain.getPixels(src, 0, gw, 0, 0, gw, gh)
            val dst = IntArray(gw * gh)
            for (i in src.indices) {
                val p = src[i]
                val v = if (gain.config == Bitmap.Config.ALPHA_8) {
                    (p ushr 24) and 0xFF
                } else {
                    (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                }
                dst[i] = v shl 24
            }
            alpha.setPixels(dst, 0, gw, 0, 0, gw, gh)
            gainContents = alpha
            val ratioMax = (1f + (sourcePair.ratioMax.coerceIn(1f, 16f) - 1f) * strength)
                .coerceIn(1f, 16f)
            val ratioMin = sourcePair.ratioMin.coerceIn(0.01f, ratioMax)
            gm = android.graphics.Gainmap(alpha)
            gm.setRatioMin(ratioMin, ratioMin, ratioMin)
            gm.setRatioMax(ratioMax, ratioMax, ratioMax)
            gm.setGamma(
                sourcePair.gamma.coerceIn(0.05f, 8f),
                sourcePair.gamma.coerceIn(0.05f, 8f),
                sourcePair.gamma.coerceIn(0.05f, 8f)
            )
            gm.setEpsilonSdr(
                sourcePair.epsSdr.coerceIn(0f, 1f),
                sourcePair.epsSdr.coerceIn(0f, 1f),
                sourcePair.epsSdr.coerceIn(0f, 1f)
            )
            gm.setEpsilonHdr(
                sourcePair.epsHdr.coerceIn(0f, 1f),
                sourcePair.epsHdr.coerceIn(0f, 1f),
                sourcePair.epsHdr.coerceIn(0f, 1f)
            )
            gm.setDisplayRatioForFullHdr(
                (1f + (sourcePair.displayRatioFull - 1f) * strength)
                    .coerceAtLeast(ratioMin)
            )
            gm.setMinDisplayRatioForHdrTransition(
                sourcePair.minDisplayRatio.coerceIn(0.01f, 20f)
            )
            gainFromSource = true
        } else if (rawSensorGain != null &&
            rawSensorGain.width > 0 && rawSensorGain.height > 0
        ) {
            // RAW 传感器原始数据生成的 GainMap：没有经过 8bit SDR 预览反推，
            // 高光是否还有细节直接来自 CFA 原始像素。alpha 0..255 对应
            // 1..rawSensorGainMax，参数与生成端完全一致。
            gw = rawSensorGain.width
            gh = rawSensorGain.height
            val alpha = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
            val src = IntArray(gw * gh)
            rawSensorGain.getPixels(src, 0, gw, 0, 0, gw, gh)
            val dst = IntArray(gw * gh)
            for (i in src.indices) {
                val p = src[i]
                val v = if (rawSensorGain.config == Bitmap.Config.ALPHA_8) {
                    (p ushr 24) and 0xFF
                } else {
                    (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                }
                dst[i] = v shl 24
            }
            alpha.setPixels(dst, 0, gw, 0, 0, gw, gh)
            gainContents = alpha
            val ratioMax = rawSensorGainMax.coerceIn(1.02f, 5f)
            gm = android.graphics.Gainmap(alpha)
            gm.setRatioMin(1f, 1f, 1f)
            gm.setRatioMax(ratioMax, ratioMax, ratioMax)
            gm.setGamma(1f, 1f, 1f)
            gm.setEpsilonSdr(0.01f, 0.01f, 0.01f)
            gm.setEpsilonHdr(0.01f, 0.01f, 0.01f)
            gm.setDisplayRatioForFullHdr(ratioMax)
            gm.setMinDisplayRatioForHdrTransition(1f)
            gainFromSource = true
        } else {
            // gainmap 用一半边长就行，但必须和底图保持完全相同的宽高比。
            // 以前 gw、gh 分别 coerce 到 1024，4096×2730 会被压成 1024×1024 方图，
            // 手机图库/系统 HDR 解码器认为增益图和底图不匹配，于是只显示 SDR 灰片。
            // 现在长边最多 2048，和原片 GainMap（约 1152×2048）同一档，亮部过渡更细。
            val gainScale = minOf(1f, 2048f / maxOf(base.width, base.height).toFloat())
            gw = (base.width * gainScale).toInt().coerceAtLeast(1)
            gh = (base.height * gainScale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(base, gw, gh, true)
            val src = IntArray(gw * gh)
            scaled.getPixels(src, 0, gw, 0, 0, gw, gh)
            if (scaled !== base) scaled.recycle()

            // ---- SDR → HDR 反色调映射（inverse tone mapping）----
            // 普通 JPG 只有 8bit、没有 HDR 数据层，这里按画面自己的亮度分布「造」一张增益图：
            // 先取亮度直方图，用 96 分位当高光起点，再按幂函数把高光往上推，最高 2.8x，
            // 暗部/中间调基本不动，避免把整张图洗白。思路与 google/libultrahdr 的
            // gain map 生成一致（SDR 底图 + 单通道增益），只是没有真实 HDR 参考图可对照，
            // 所以用局部亮度分布估算，而不是逐像素做物理还原。
            val lum = FloatArray(src.size)
            for (i in src.indices) {
                val p = src[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                lum[i] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            }
            // 96 分位：既保证只有最亮的一小撮被当成「超白」候选，又不会因为整张偏暗而全图爆掉
            val hist = IntArray(64)
            lum.forEach { y -> hist[(y * 63f).toInt().coerceIn(0, 63)]++ }
            var acc = 0
            var hi = 63
            val targetCount = (lum.size * 0.04f).toInt().coerceAtLeast(1)
            for (bin in 63 downTo 0) {
                acc += hist[bin]
                if (acc >= targetCount) { hi = bin; break }
            }
            val hiLum = ((hi + 1) / 64f).coerceIn(0.32f, 0.90f)
            val maxBoost = 1f + 2f * strength
            val alpha = IntArray(gw * gh)
            for (i in src.indices) {
                val y = lum[i]
                // 高光起点以下不动；往上用 1.35 次幂平滑加速，避免出现硬边
                val t = ((y - hiLum) / (1f - hiLum + 1e-3f)).coerceIn(0f, 1f)
                val expanded = Math.pow(t.toDouble(), 1.35).toFloat()
                // 越靠近纯白，再叠加一点「灯感」，让亮部在 HDR 屏上真的顶出去
                val boost = 1f + expanded * (maxBoost - 1f)
                val a = (((boost - 1f) / (maxBoost - 1f)) * 255f).toInt().coerceIn(0, 255)
                alpha[i] = a shl 24
            }
            gainContents = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
            gainContents.setPixels(alpha, 0, gw, 0, 0, gw, gh)
            gm = android.graphics.Gainmap(gainContents)
            gm.setRatioMin(1f, 1f, 1f)
            gm.setRatioMax(maxBoost, maxBoost, maxBoost)
            gm.setGamma(1f, 1f, 1f)
            gm.setEpsilonSdr(0.01f, 0.01f, 0.01f)
            gm.setEpsilonHdr(0.01f, 0.01f, 0.01f)
            gm.setDisplayRatioForFullHdr(maxBoost)
            gm.setMinDisplayRatioForHdrTransition(1.0f)
            gainFromSource = false
        }
        base.setGainmap(gm)

        target.outputStream().use { out ->
            base.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(60, 100), out)
        }
        val ok = target.length() > 0
        com.lutcamera.lutcamerapro.log.DoukLog.i(
            "PhotoEditor", "ULTRA_HDR",
            "size" to target.length().toString(),
            "gain" to "${gw}x${gh}",
            "from" to if (gainFromSource) "source" else "synthetic",
            "ok" to ok.toString()
        )
        ok
    } catch (e: Throwable) {
        com.lutcamera.lutcamerapro.log.DoukLog.w("PhotoEditor", "ULTRA_HDR_ERR", "msg" to (e.message ?: ""))
        false
    } finally {
        if (base !== bmp) runCatching { base?.recycle() }
        runCatching { gainContents?.recycle() }
        runCatching { orientedSourceGain?.recycle() }
    }
}

/**
 * 编辑器里真正把 GainMap 合到底图上，生成一张肉眼可见的 HDR 预览。
 *
 * 系统 ImageView + Gainmap 在部分小米机型上经过 HDR 窗口 / 截图色调映射后，
 * 高光只抬几个灰阶，开关几乎看不出变化。这里按 ISO 21496-1 的公式
 * `D = (B + epsSdr) * exp(L * W) - epsHdr` 直接计算，再把结果映射回 sRGB，
 * 让用户明确看到高光扩展；导出仍然走真正的 Ultra HDR 文件。
 */
private fun buildHdrPreviewComposite(
    src: Bitmap,
    layer: HdrPreviewLayer,
    grade: HdrGrade = HdrGrade()
): Bitmap? {
    if (src.isRecycled || src.width < 2 || src.height < 2) return null
    val previewScale = minOf(1f, 1024f / maxOf(src.width, src.height).toFloat())
    val outW = (src.width * previewScale).toInt().coerceAtLeast(1)
    val outH = (src.height * previewScale).toInt().coerceAtLeast(1)
    val base = Bitmap.createScaledBitmap(src, outW, outH, true)
    val gain = Bitmap.createScaledBitmap(layer.gain, outW, outH, true)
    return try {
        val basePixels = IntArray(outW * outH)
        val gainPixels = IntArray(outW * outH)
        base.getPixels(basePixels, 0, outW, 0, 0, outW, outH)
        gain.getPixels(gainPixels, 0, outW, 0, 0, outW, outH)
        val ratioMin = layer.ratioMin.coerceAtLeast(1f)
        val ratioMax = layer.ratioMax.coerceAtLeast(ratioMin + 0.02f)
        val gamma = layer.gamma.coerceIn(0.05f, 8f)
        val epsSdr = layer.epsSdr.coerceIn(0f, 1f)
        val epsHdr = layer.epsHdr.coerceIn(0f, 1f)
        val logMin = kotlin.math.ln(ratioMin.toDouble())
        val logMax = kotlin.math.ln(ratioMax.toDouble())
        val out = IntArray(outW * outH)
        for (i in basePixels.indices) {
            val graded = applyHdrGradePixel(basePixels[i], grade)
            val p = basePixels[i]
            val r = srgbToLinear(((graded shr 16) and 0xFF) / 255f)
            val g = srgbToLinear(((graded shr 8) and 0xFF) / 255f)
            val b = srgbToLinear((graded and 0xFF) / 255f)
            val gp = gainPixels[i]
            val gv = if (layer.gain.config == Bitmap.Config.ALPHA_8) {
                ((gp ushr 24) and 0xFF) / 255f
            } else {
                ((((gp shr 16) and 0xFF) + ((gp shr 8) and 0xFF) + (gp and 0xFF)) / 3f) / 255f
            }
            val logGain = logMin + (logMax - logMin) * gv.pow(gamma)
            val boost = kotlin.math.exp(logGain).toFloat()
            val dr = ((r + epsSdr) * boost - epsHdr).coerceIn(0f, 4f)
            val dg = ((g + epsSdr) * boost - epsHdr).coerceIn(0f, 4f)
            val db = ((b + epsSdr) * boost - epsHdr).coerceIn(0f, 4f)
            // 真正的 Gainmap 合成不是把增益无差别乘到每个像素：中间调硬乘会整片发白，
            // 旧的 (1-r)*0.55 上限又会让亮部互相挤压，只剩“高光被拉高”。
            // 现在按亮度分区释放增益，并用肩部滚降把 >1.0 的线性光收回 SDR；
            // 输出增益限制在 0.94–1.32，并且只在高光区加一点饱和度，
            // 让开关前后的差异更明显，同时保留中间调的层次。
            val baseLum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val hdrLum = 0.2126f * dr + 0.7152f * dg + 0.0722f * db
            val hdrWeight = smoothstep(0.08f, 0.78f, baseLum)
            val displayLum = baseLum + (hdrShoulder(hdrLum) - baseLum).coerceAtLeast(0f) * hdrWeight
            val scale = if (baseLum > 1e-5f) (displayLum / baseLum).coerceIn(0.94f, 1.32f) else 1f
            // 高光多出来的能量也会让颜色更实；只在高光区轻微加饱和，阴影不染色。
            val saturation = 1f + 0.08f * smoothstep(0.35f, 0.90f, baseLum)
            val outR = (baseLum + (r - baseLum) * saturation * scale).coerceIn(0f, 1f)
            val outG = (baseLum + (g - baseLum) * saturation * scale).coerceIn(0f, 1f)
            val outB = (baseLum + (b - baseLum) * saturation * scale).coerceIn(0f, 1f)
            val rr = (linearToSrgb(outR) * 255f).roundToInt().coerceIn(0, 255)
            val gg = (linearToSrgb(outG) * 255f).roundToInt().coerceIn(0, 255)
            val bb = (linearToSrgb(outB) * 255f).roundToInt().coerceIn(0, 255)
            out[i] = (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, outW, 0, 0, outW, outH)
        result
    } catch (_: Throwable) {
        null
    } finally {
        if (base !== src) runCatching { base.recycle() }
        runCatching { gain.recycle() }
    }
}

private fun srgbToLinear(v: Float): Float =
    if (v <= 0.04045f) v / 12.92f else ((v + 0.075f) / 1.075f).pow(2.4f)

private fun linearToSrgb(v: Float): Float =
    if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

/** HDR 预览的亮度肩部：0.48 以下保持原反差，0.48 以上平滑滚降，顶部封在 0.98 不切白。 */
private fun hdrShoulder(linear: Float): Float {
    val x = linear.coerceAtLeast(0f)
    if (x <= 0.48f) return x
    return 0.48f + 0.50f * (1f - kotlin.math.exp(-(x - 0.48f) / 0.34f))
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 给编辑预览生成一张「高光增益图」：底图还是当前调好的 SDR 预览，
 * 从 18% 亮度开始按比例抬升，让中高调一起参与 HDR 预览；不是只把最亮的高光拉白。
 * 这是可视化预览，不参与导出；导出仍走 writeUltraHdrJpeg 的真实增益图。
 */
private fun buildHdrPreviewGain(
    src: Bitmap,
    maxBoost: Float = 2.9f,
    highlightStart: Float = 0.36f
): Bitmap? {
    if (src.isRecycled || src.width < 2 || src.height < 2) return null
    val previewScale = minOf(1f, 720f / maxOf(src.width, src.height).toFloat())
    val outW = (src.width * previewScale).toInt().coerceAtLeast(1)
    val outH = (src.height * previewScale).toInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(src, outW, outH, true)
    val pixels = IntArray(outW * outH)
    small.getPixels(pixels, 0, outW, 0, 0, outW, outH)
    val start = highlightStart.coerceIn(0.05f, 0.95f)
    val span = (1f - start).coerceAtLeast(0.02f)
    val lut = IntArray(256) { v ->
        val x = v / 255f
        val t = ((x - start) / span).coerceIn(0f, 1f)
        val ratio = 1f + (maxBoost - 1f) * Math.pow(t.toDouble(), 1.35).toFloat()
        (kotlin.math.ln(ratio) / kotlin.math.ln(maxBoost) * 255f).toInt().coerceIn(0, 255)
    }
    for (i in pixels.indices) {
        val p = pixels[i]
        val lum = (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f +
            (p and 0xFF) * 0.114f).toInt().coerceIn(0, 255)
        val g = lut[lum]
        pixels[i] = (p and 0xFF000000.toInt()) or (g shl 16) or (g shl 8) or g
    }
    val gain = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    gain.setPixels(pixels, 0, outW, 0, 0, outW, outH)
    if (small !== src) small.recycle()
    return gain
}

/**
 * HDR 效果：把亮度按「高光滚降」重新分配——中间调保持，接近 1.0 的高光压回
 * 可显示范围而不是直接切平，暗部抬一点，输出成普通 JPG 也能看出层次。
 * 这是把 16bit / gainmap 的动态范围折进 8bit 的常见做法，不是真 HDR 容器。
 */
private fun hdrTone(src: Bitmap): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
    val w = out.width
    val h = out.height
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    val lut = IntArray(256) { v ->
        val x = v / 255f
        // filmic 曲线：暗部轻抬、高光平滑滚降
        val y = (x * (1.06f + 0.08f * x) / (1f + 0.14f * x)).coerceIn(0f, 1f)
        (y * 255f).toInt().coerceIn(0, 255)
    }
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = lut[(p shr 16) and 0xFF]
        val g = lut[(p shr 8) and 0xFF]
        val b = lut[p and 0xFF]
        pixels[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

/**
 * HDR 专属质感调色：暗部细节、色彩浓度、色温。
 *
 * 和「调节」里的全局参数分开：只有 HDR 预览和 HDR 导出会叠加这一层，
 * 关闭 HDR 时原图完全不受影响。所有调整都限制在很小的幅度，避免 HDR
 * 高光扩展之后再被过度染色。
 */
private fun applyHdrGrade(src: Bitmap, grade: HdrGrade): Bitmap {
    if (grade.isNeutral || src.isRecycled) return src
    val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) {
        src
    } else {
        src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
    }
    val w = out.width
    val h = out.height
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        pixels[i] = applyHdrGradePixel(pixels[i], grade)
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

private fun applyHdrGradePixel(p: Int, grade: HdrGrade): Int {
    if (grade.isNeutral) return p
    val alpha = p and 0xFF000000.toInt()
    var r = ((p shr 16) and 0xFF) / 255f
    var g = ((p shr 8) and 0xFF) / 255f
    var b = (p and 0xFF) / 255f
    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b

    // 暗部细节：只抬最暗的 30%，用四次方权重保证中间调不动，不会整片发灰。
    if (grade.shadow > 0.5f) {
        val lift = (grade.shadow / 100f) * 0.18f
        val wShadow = (1f - lum).coerceIn(0f, 1f).pow(4f)
        r += lift * wShadow * (1f - r)
        g += lift * wShadow * (1f - g)
        b += lift * wShadow * (1f - b)
    }

    // 色彩浓度：50 为原色，100 最多 +55%，0 最多 -55%；高光区权重更大。
    if (kotlin.math.abs(grade.color - 50f) > 0.5f) {
        val richness = (grade.color - 50f) / 50f
        val weight = 0.35f + 0.65f * smoothstep(0.10f, 0.90f, lum)
        val s = 1f + richness * 0.55f
        val sat = 1f + (s - 1f) * weight
        r = lum + (r - lum) * sat
        g = lum + (g - lum) * sat
        b = lum + (b - lum) * sat
    }

    // 色温：负值偏冷（蓝多一点），正值偏暖（红多一点），主要作用在中高光。
    if (kotlin.math.abs(grade.warmth) > 0.5f) {
        val warm = grade.warmth / 100f
        val weight = smoothstep(0.20f, 0.95f, lum)
        r += warm * 0.060f * weight
        g += warm * 0.012f * weight
        b -= warm * 0.060f * weight
    }

    val rr = (r.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    val gg = (g.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    val bb = (b.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    return alpha or (rr shl 16) or (gg shl 8) or bb
}
