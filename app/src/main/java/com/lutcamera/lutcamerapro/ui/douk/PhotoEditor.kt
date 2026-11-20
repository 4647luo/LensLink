package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
    val filter: String = "原图"
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

private val ADJUST_MAX = 100f
/**
 * 拖动滑杆时的快速预览长边。
 *
 * 512px 逐像素算色 + 曲线 / LUT，在手机上单帧常常超过 20ms；手指快速拖动时
 * 旧的一帧还没算完，新的一帧已经在排队，画面就会慢半拍。降到 320px 后像素量
 * 只有原来的 39%，配合下面的「只保留最新一帧」队列，数值和画面能同时跟手。
 * 松手后仍按设置里的 previewSide 重算高清图。
 */
private const val FAST_PREVIEW_SIDE = 320

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
 * 预览在长边 1080 的副本上算（滑动才跟手），保存时对原图（长边最多 4096）重算一遍再落盘。
 */
@Composable
internal fun PhotoEditorScreen(
    item: GalleryItem,
    source: ImageBitmap?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
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
    var filterEntries by remember { mutableStateOf(FILTERS.map { FilterEntry(it, it, "自带") }) }
    var filterCategory by remember { mutableStateOf("自带") }
    var previewEpoch by remember { mutableStateOf(0) }
    var highQualityPreview by remember { mutableStateOf(true) }
    var fastBase by remember { mutableStateOf<Bitmap?>(null) }
    var hdBase by remember { mutableStateOf<Bitmap?>(null) }
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

    // 预览底座只缩一次：快速档与高清档各缓存一张，拖动时不再从原图重复缩放
    LaunchedEffect(item.stableKey, source, previewSide) {
        val bmp = source?.asAndroidBitmap()
        if (bmp != null) {
            val pair = withContext(Dispatchers.IO) {
                scaleToMax(bmp, FAST_PREVIEW_SIDE) to scaleToMax(bmp, previewSide)
            }
            fastBase = pair.first
            hdBase = pair.second
        }
    }

    // 滤镜：读取资源页导入的 LUT / 尼康预设，生成缩略图
    LaunchedEffect(item.stableKey, source) {
        val bmp = source?.asAndroidBitmap()
        val library = withContext(Dispatchers.IO) { FilterLibrary.load(context) }
        filterRuntime = library.runtime
        filterEntries = FILTERS.map { FilterEntry(it, it, "自带") } + library.entries
        filterThumbs = if (bmp == null) emptyMap() else withContext(Dispatchers.IO) {
            val small = scaleToMax(bmp, 160)
            filterEntries.associate { entry ->
                entry.id to runCatching {
                    // render() 是原地改像素（putPixels 返回 this），每个滤镜必须拿独立副本，
                    // 否则 18 个缩略图全指向同一张 bitmap，看起来就都跟原图一样。
                    val copy = small.copy(Bitmap.Config.ARGB_8888, true) ?: return@runCatching null
                    renderPreview(copy, EditState(filter = entry.id, filterAmount = 100f), 128, filterRuntime)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF3F3F3))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶部：退出 / 撤销 / 重做 ……… 保存
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RoundIconButton(Icons.Rounded.Close, "退出") { onDismiss() }
                RoundIconButton(
                    Icons.Rounded.Undo,
                    "撤销",
                    enabled = undoIndex > 0
                ) { undo() }
                RoundIconButton(
                    Icons.Rounded.Redo,
                    "重做",
                    enabled = undoIndex < undoStack.lastIndex
                ) { redo() }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .doukCardSurface(RoundedCornerShape(percent = 50))
                        .clickable(enabled = !saving && source != null) {
                            saving = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { saveEdited(context, item, state, filterRuntime) }
                                saving = false
                                result.onSuccess { name ->
                                    onSaved(name)
                                }.onFailure {
                                    message = it.message ?: "保存失败"
                                }
                            }
                        }
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Text(
                        if (saving) "保存中…" else "保存",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF222222)
                    )
                }
            }

            // 画面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
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
                                    if (canCompare && source != null && !state.cropFree)
                                        Modifier.pointerInput(item.stableKey) {
                                            detectTapGestures(onPress = {
                                                comparePressed = true
                                                tryAwaitRelease()
                                                comparePressed = false
                                            })
                                        } else Modifier
                                )
                        ) {
                            Image(
                                bitmap = if (comparePressed && source != null) source else shown,
                                contentDescription = "编辑预览",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (showGrid && !state.cropFree) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val c = Color.White.copy(alpha = 0.45f)
                                    for (i in 1..2) {
                                        drawLine(c, Offset(size.width * i / 3f, 0f),
                                            Offset(size.width * i / 3f, size.height), strokeWidth = 1f)
                                        drawLine(c, Offset(0f, size.height * i / 3f),
                                            Offset(size.width, size.height * i / 3f), strokeWidth = 1f)
                                    }
                                }
                            }
                            if (state.cropFree && tab == "裁剪") {
                                CropOverlay(state) { push(it) }
                            }
                            if (comparePressed) {
                                Text(
                                    "原图",
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 10.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        if (working) "正在生成预览…" else "这张图暂时读不出来",
                        fontSize = 13.sp,
                        color = Color(0xFF999999)
                    )
                }
            }

            // 底部：滑杆 + 分组页签 + 选项
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallPill(
                        icon = Icons.Rounded.Restore,
                        label = "还原",
                        enabled = !state.isPristine
                    ) { push(EditState()) }
                    if (tab == "滤镜") {
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val categories = listOf("自带") +
                                filterEntries.map { it.category }.filter { it != "自带" }.distinct()
                            categories.forEach { name ->
                                val on = name == filterCategory
                                Text(
                                    name,
                                    fontSize = 11.sp,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (on) Color(0xFFE08A2E) else Color(0xFF777777),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(if (on) Color(0xFFFFEEDC) else Color(0xFFEDEDED))
                                        .clickable { filterCategory = name }
                                        .padding(horizontal = 11.dp, vertical = 5.dp)
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Text(
                        if (working && highQualityPreview) "计算中…" else "",
                        fontSize = 11.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }

                when (tab) {
                    "裁剪" -> {
                        SliderRow(state, "裁剪", activeAdjust, { live(it) }, { commit() })
                        BasicRow(state) { push(it) }
                    }
                    "调节" -> {
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
                            else -> {
                                AdjustSlider(
                                    item = activeAdjust,
                                    state = state,
                                    onLive = { live(it) },
                                    onCommit = { commit() }
                                )
                                AdjustGroups(activeAdjust, state) { activeAdjust = it }
                            }
                        }
                    }
                    else -> {
                        val shown = filterEntries.filter { it.category == filterCategory }
                        SliderRow(
                            state = state.copy(filterAmount = state.filterAmount),
                            tab = "滤镜强度",
                            activeAdjust = AdjustItem("滤镜强度", Icons.Rounded.FilterVintage, { it.filterAmount }) { s, v ->
                                s.copy(filterAmount = v)
                            },
                            onLive = { live(it) },
                            onCommit = { commit() }
                        )
                        FilterGrid(shown, state.filter, filterThumbs) { entry ->
                            push(state.copy(filter = entry.id))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                MainTabBar(tab) { name ->
                    tab = name
                    if (name == "调节" && ADJUSTS.none { it.label == activeAdjust.label }) {
                        activeAdjust = ADJUSTS.first()
                    }
                    if (name == "滤镜") {
                        filterCategory = filterEntries.firstOrNull { it.id == state.filter }?.category
                            ?: if (filterCategory == "自带") "自带" else filterCategory
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            message?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .doukCardSurface(RoundedCornerShape(18.dp))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        tabs.forEach { (name, icon) ->
            val active = current == name
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPick(name) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = name,
                    tint = if (active) Color(0xFFE08A2E) else Color(0xFF8A8A8A),
                    modifier = Modifier.size(21.dp)
                )
                Text(
                    name,
                    fontSize = 11.sp,
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
    var touching by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val thumbRadius = with(density) { 14.dp.toPx() }
    val padPx = thumbRadius + with(density) { 2.dp.toPx() }
    val trackHeight = with(density) { 20.dp.toPx() }
    val bubblePaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.84f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.label, fontSize = 12.sp, color = Color(0xFF888888))
                Spacer(modifier = Modifier.weight(1f))
                Text(shown, fontSize = 12.sp, color = Color(0xFFE08A2E), fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .pointerInput(item.label) {
                        awaitEachGesture {
                            fun apply(x: Float) {
                                val fraction = ((x - padPx) / (size.width - padPx * 2f)).coerceIn(0f, 1f)
                                val v = range.start + (range.endInclusive - range.start) * fraction
                                latestLive.value(item.set(latestState.value, v))
                            }
                            val down = awaitFirstDown()
                            touching = true
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
                    if (touching) {
                        val tw = with(density) { 50.dp.toPx() }
                        val th = with(density) { 26.dp.toPx() }
                        val left = (cx - tw / 2f).coerceIn(0f, size.width - tw)
                        drawRoundRect(
                            color = Color(0xE6222222),
                            topLeft = Offset(left, 0f),
                            size = Size(tw, th),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(th / 2f)
                        )
                        bubblePaint.textSize = with(density) { 12.sp.toPx() }
                        drawContext.canvas.nativeCanvas.drawText(
                            shown,
                            left + tw / 2f,
                            th * 0.68f,
                            bubblePaint
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
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
            val value = item.get(state)
            val changed = abs(value) > 0.5f
            Column(
                modifier = Modifier
                    .width(56.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) Color(0xFFFBF0E3) else Color(0xFFEDEDED))
                    .then(
                        if (on) Modifier.border(1.5.dp, Color(0xFFE08A2E), RoundedCornerShape(11.dp))
                        else Modifier
                    )
                    .clickable { onPick(item) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (on && changed) {
                    Text(
                        formatAdjust(value),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF222222),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (on) Color(0xFF222222) else Color(0xFF4A4A4A),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
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
            val grid = Color(0xFFD4D4D4)
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
                    drawLine(Color(0x33000000), it, pt, strokeWidth = shadowW)
                    drawLine(Color.White, it, pt, strokeWidth = lineW)
                }
                prev = pt
            }
            points.forEachIndexed { index, v ->
                val p = Offset(w * index / (points.size - 1f), (1f - v) * h)
                drawCircle(Color(0xFF9E9E9E), radius = handleR + 2f, center = p)
                drawCircle(Color.White, radius = handleR, center = p)
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
                            if (index == channel) Modifier.border(2.dp, Color(0xFF555555), CircleShape)
                            else Modifier
                        )
                        .background(color)
                        .clickable { channel = index }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "重置曲线",
                tint = Color(0xFF555555),
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
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
                    .background(Color(0xFFE4E4E4))
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = "收起曲线",
                    tint = Color(0xFF333333),
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
                            if (index == state.hslRange) Modifier.border(2.dp, Color(0xFF555555), CircleShape)
                            else Modifier
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
                    .background(Color(0xFFE4E4E4))
                    .clickable(onClick = onCollapse),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = "收起 HSL",
                    tint = Color(0xFF333333),
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
                Text(label, fontSize = 12.sp, color = Color(0xFF333333))
                Spacer(modifier = Modifier.weight(1f))
                Text(formatAdjust(value), fontSize = 12.sp, color = Color(0xFF222222))
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
            .doukCardSurface(RoundedCornerShape(12.dp))
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
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
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            val free = state.cropFree
            CROP_RATIOS.forEach { ratio ->
                val active = !free && state.cropRatio == ratio
                CropOptionCard(ratio, ratioIcon(ratio), active) {
                    onState(state.copy(cropRatio = ratio, cropFree = false))
                }
            }
            CropOptionCard(
                label = if (free) "自由·开" else "自由裁剪",
                icon = Icons.Rounded.CropFree,
                active = free
            ) {
                onState(
                    if (free) state.copy(cropFree = false)
                    else state.copy(cropFree = true, cropL = 0f, cropT = 0f, cropR = 1f, cropB = 1f)
                )
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
            .width(56.dp)
            .height(64.dp)
            .doukCardSurface(RoundedCornerShape(11.dp))
            .background(
                if (active) Color(0xFFFBF0E3).copy(alpha = 0.92f) else Color.Transparent,
                RoundedCornerShape(11.dp)
            )
            .then(
                if (active) Modifier.border(1.5.dp, Color(0xFFE08A2E), RoundedCornerShape(11.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Color(0xFFE08A2E) else Color(0xFF555555),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
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
            .doukCardSurface(RoundedCornerShape(percent = 50))
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
            .doukCardSurface(CircleShape)
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

/** 预览：长边按设置压到 720/1080/1440，滑动时算得快 */
private fun renderPreview(src: Bitmap, s: EditState, maxSide: Int, runtime: FilterRuntime): Bitmap {
    val scaled = scaleToMax(src, maxSide.coerceIn(480, 2048))
    return render(scaled, s, runtime)
}

/** 保存：对原图（长边最多 4096，避免大图 OOM）重算一遍 */
private fun renderFull(src: Bitmap, s: EditState, runtime: FilterRuntime): Bitmap {
    val scaled = scaleToMax(src, 4096)
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
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
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
    val pixels = work.getPixelsArray()
    applyColor(pixels, s, runtime)
    // 拖动时只算当前正在调的那一个细节项，其余细节等松手后的高清预览补
    if (s.denoise > 0.5f && (!quick || activeDetail == "降噪")) {
        blurInto(pixels, work.width, work.height, (s.denoise / 100f * 0.6f))
    }
    if (s.sharpen > 0.5f && (!quick || activeDetail == "锐化")) {
        unsharp(pixels, work.width, work.height, s.sharpen / 100f)
    }
    if (s.clarity > 0.5f && (!quick || activeDetail == "清晰度")) {
        unsharp(pixels, work.width, work.height, s.clarity / 100f * 0.5f)
    }
    if (s.grain > 0.5f && (!quick || activeDetail == "颗粒")) {
        addGrain(pixels, s.grain / 100f)
    }
    return work.putPixels(pixels)
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

/** 保存：算出全尺寸结果，写进 Pictures/Douk，文件名 = 原名 + _edit + 序号 */
private fun saveEdited(context: Context, item: GalleryItem, s: EditState, runtime: FilterRuntime): Result<String> {
    val file = GalleryFileOps.locateAppFile(item.fileName)
        ?: return Result.failure(IllegalStateException("只有应用内下载的照片可以编辑，相机与手机相册的原图请先保存到应用"))
    var src: Bitmap? = null
    var rendered: Bitmap? = null
    try {
        // 45MP 不再整图 decode；先采样到 4096 长边，峰值内存从 ~270MB 降到 ~90MB。
        val decoded = decodeSampledForEdit(file, 4096)
            ?: return Result.failure(IllegalStateException("这张图解码失败，可能格式不支持"))
        src = decoded
        val output = renderFull(decoded, s, runtime)
        rendered = output
        val base = file.nameWithoutExtension
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            "Douk"
        )
        if (!dir.exists()) dir.mkdirs()

        val format = DoukPrefs.editorFormat
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
        val quality = DoukPrefs.editorQuality.coerceIn(60, 100)

        var name = "${base}_edit.$ext"
        var index = 2
        while (File(dir, name).exists()) {
            name = "${base}_edit${index}.$ext"
            index++
        }
        val targetFile = File(dir, name)

        val attempt = runCatching {
            targetFile.outputStream().use { out ->
                output.compress(compressFormat, quality, out)
            }
            // MediaStore 里补一条记录，其它相册应用也能立刻看到
            runCatching {
                MediaStore.Images.Media.insertImage(
                    context.contentResolver,
                    targetFile.absolutePath,
                    name,
                    "由连镜编辑"
                )
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
        runCatching { rendered?.recycle() }
    }
}
