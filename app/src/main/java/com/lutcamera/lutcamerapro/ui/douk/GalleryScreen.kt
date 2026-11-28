package com.lutcamera.lutcamerapro.ui.douk

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File

/** 图库缩略图解码上限：快速滚动时同时解码太多 320px 图会突然卡一下 */
private val GalleryDecodeGate = Semaphore(3)

/**
 * 网格缩略图只保留最近 48 张、按 cell 尺寸解码（420px）。
 * 之前按 960px 解码且永不回收，滚动几百张就会把几百 MB 留在内存里。
 */
private const val GALLERY_THUMB_SIDE = GalleryThumbCache.THUMB_SIDE

internal enum class GallerySource(val label: String, val hint: String) {
    CAMERA("相机内", "机身存储，经 USB 或 Wi-Fi 读取"),
    APP("应用内", "连镜下载下来的照片"),
    PHONE("手机内", "系统相册里的全部照片");

    companion object {
        /** 设置里存的是中文标签；读到未知值就回到「相机内」，不崩。 */
        fun fromLabel(label: String): GallerySource =
            entries.firstOrNull { it.label == label } ?: CAMERA
    }
}

/** 图库里的一格：相机内（Wi‑Fi / USB）与手机内共用同一套渲染。 */
internal data class GalleryItem(
    val key: Int,
    val fileName: String,
    val sizeBytes: Long,
    val isRaw: Boolean,
    val fromUsb: Boolean,
    /** 来源标记：cam / usb / app / phone，用于 thumb 缓存与多选键去重 */
    val origin: String = "cam",
    /** 拍摄或入库时间，没有可靠时间的来源（PTP 列表）留 null，不伪造日期分组 */
    val dateMillis: Long? = null,
    val isVideo: Boolean = false,
    /** 所属相册：应用内是目录名，手机内是 MediaStore 的 bucket，相机内为 null */
    val album: String? = null,
    /** 像素宽高，用来判断横 / 竖构图；相机 PTP 列表拿不到尺寸时为 null */
    val width: Int? = null,
    val height: Int? = null,
    /** 同一张照片的全部格式（RAW+JPG 合并成一条时是 ["nef","jpg"]），筛选与角标都用它 */
    val formats: List<String> = emptyList(),
    /** 合并后除主图外的其它句柄（RAW 等），保存时一并写出；键是 handle，值是文件名 */
    val extraHandles: List<Pair<Int, String>> = emptyList(),
    /** 应用内同一张 RAW/JPG 配对时，除主图外需要一起预览/删除的文件名 */
    val extraFiles: List<String> = emptyList()
) {
    /** 去重后的格式集合，单格式时就是 ext */
    val formatSet: List<String> get() = formats.ifEmpty { listOf(ext) }.map { it.lowercase() }.distinct()

    /** 左下角格式角标：JPG / NEF / JPG+NEF / MOV… */
    val badgeLabel: String
        get() = formatSet.mapNotNull { format ->
            when (format) {
                "jpg", "jpeg" -> "JPG"
                "nef", "nrw", "dng" -> "RAW"
                "png" -> "PNG"
                "heic", "heif" -> "HEIC"
                "mp4" -> "MP4"
                "mov" -> "MOV"
                else -> null
            }
        }.distinct().joinToString("+").ifBlank { ext.uppercase() }
    /** 跨来源唯一键，避免相机 handle 与相册 id 撞号串图 */
    val stableKey: String get() = "$origin:$key:$fileName"

    /** 小写扩展名，筛选按它分类 */
    val ext: String get() = fileName.substringAfterLast('.', "").lowercase()

    /**
     * 构图：宽 > 高算是横构图，宽 < 高算是竖构图。
     * 尺寸读不到（相机内 PTP 列表）时返回 null，这种条目在构图筛选里不参与。
     */
    val orientation: String?
        get() {
            val w = width ?: return null
            val h = height ?: return null
            return when {
                w > h -> "横构图"
                w < h -> "竖构图"
                else -> null
            }
        }
}

/**
 * 缩略图格式角标的总开关。
 * 放在文件级 Compose state 里：图库「更多」里一拨，网格里所有格子立刻一起变，
 * 不用把参数从 GalleryScreen 一层层传到 ThumbCell。
 */
private val galleryFormatBadge by lazy { mutableStateOf(DoukPrefs.galleryShowFormatBadge) }

/** 图库筛选：类型（图片/视频）、具体格式、横竖构图，三个维度各自独立。 */
internal data class GalleryFilter(
    val kind: String = "全部",
    val format: String = "全部",
    val orientation: String = "全部"
) {
    val isActive: Boolean get() = kind != "全部" || format != "全部" || orientation != "全部"

    fun matches(item: GalleryItem): Boolean {
        if (kind == "图片" && item.isVideo) return false
        if (kind == "视频" && !item.isVideo) return false

        if (format != "全部") {
            // RAW+JPG 合并成一条后，只要其中任意一种格式命中就算命中
            val ok = when (format) {
                "JPG" -> item.formatSet.any { it in setOf("jpg", "jpeg") }
                "RAW" -> item.formatSet.any { it in setOf("nef", "nrw", "dng") }
                "PNG" -> "png" in item.formatSet
                "HEIC" -> item.formatSet.any { it in setOf("heic", "heif") }
                "MP4" -> "mp4" in item.formatSet
                "MOV" -> "mov" in item.formatSet
                else -> true
            }
            if (!ok) return false
        }

        if (orientation != "全部") {
            // 读不到尺寸的条目不参与构图筛选，避免把相机里的照片全筛掉
            val o = item.orientation ?: return true
            if (o != orientation) return false
        }
        return true
    }
}

/** 时间轴分组：同一天的照片归到一组，日期头由小米相册那种「今天 / 昨天 / 11月15日」文案渲染 */
private data class PhotoSection(val label: String, val items: List<GalleryItem>)

private fun buildSections(items: List<GalleryItem>): List<PhotoSection> {
    if (items.isEmpty()) return emptyList()
    val out = mutableListOf<PhotoSection>()
    var currentLabel: String? = null
    var current = mutableListOf<GalleryItem>()
    items.forEach { item ->
        val label = item.dateMillis?.let { dayLabel(it) } ?: "未标注时间"
        if (label != currentLabel) {
            if (current.isNotEmpty()) out += PhotoSection(currentLabel ?: "未标注时间", current)
            current = mutableListOf(item)
            currentLabel = label
        } else {
            current += item
        }
    }
    if (current.isNotEmpty()) out += PhotoSection(currentLabel ?: "未标注时间", current)
    return out
}

private fun dayLabel(ts: Long): String {
    val zone = java.util.TimeZone.getDefault()
    val cal: java.util.Calendar = java.util.Calendar.getInstance(zone)
    val now: java.util.Calendar = java.util.Calendar.getInstance(zone)
    cal.timeInMillis = ts
    val startOfToday = (now.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfItem = (cal.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayDiff = ((startOfToday - startOfItem) / 86_400_000L).toInt()
    return when {
        dayDiff == 0 -> "今天"
        dayDiff == 1 -> "昨天"
        now.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) ->
            "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
        else ->
            "${cal.get(java.util.Calendar.YEAR)}年${cal.get(java.util.Calendar.MONTH) + 1}月"
    }
}

@Composable
fun GalleryScreen(
    state: DoukState,
    switchTick: Int = 0,
    onSwitchConsumed: () -> Unit = {},
    startInCamera: Boolean = false
) {
    var source by remember {
        mutableStateOf(
            if (startInCamera) GallerySource.CAMERA
            else GallerySource.fromLabel(DoukPrefs.galleryDefaultSource)
        )
    }
    var showFilter by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(DoukPrefs.galleryShowOriginal) }
    // 缩略图左下角的格式角标（JPG/RAW/MOV…）可以关掉，纯看图时更干净。
    // 用全局 state 而不是把参数一层层传到 ThumbCell，网格里每一格都能立刻跟着变。
    val showFormatBadge = galleryFormatBadge.value
    var showSourceMenu by remember { mutableStateOf(false) }
    var showTransfers by remember { mutableStateOf(false) }
    val transferRecords by TransferCenter.records.collectAsStateWithLifecycle()
    var previewItem by remember { mutableStateOf<GalleryItem?>(null) }
    var galleryView by remember { mutableStateOf(GalleryView.PHOTOS) }
    var showViewSwitch by remember { mutableStateOf(false) }
    var albumFilter by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(GalleryFilter()) }
    val context = LocalContext.current

    // 在底部 dock 的「图库」上再点一次 → 弹出/收起「照片 / 相册」
    LaunchedEffect(switchTick) {
        if (switchTick > 0) {
            showViewSwitch = !showViewSwitch
            onSwitchConsumed()
        }
    }

    // 缩略图缓存按「来源:键」存，避免相机 handle 与相册 id 撞号后串图。
    // 具体的 LRU 与每格状态在 GalleryThumbCache 里，这里不再持有 map（见该文件注释）。

    // 多选：长按进选择模式，顶部换成「取消 / 已选择 N 项 / 全选」，底部出操作条
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun resetSelection() {
        selecting = false
        selected = emptySet()
    }

    LaunchedEffect(source) {
        resetSelection()
        albumFilter = null
        // 切换来源必须清缓存，否则相机 handle 与相册 id 混在一起会串图，也会一直占内存。
        GalleryThumbCache.clear()
    }

    // 多选态下按返回键只退出选择模式，不离开图库页（和系统相册一致）
    BackHandler(enabled = selecting) { resetSelection() }

    // 把选择状态交给底栏：应用内 -> 删除卡；相机内 -> 保存到应用相册卡
    LaunchedEffect(selecting, selected, source, currentItems) {
        state.gallerySelCount = if (selecting) selected.size else 0
        state.gallerySelKind = when {
            !selecting || selected.isEmpty() -> ""
            source == GallerySource.APP -> "delete"
            source == GallerySource.CAMERA -> "save"
            else -> ""
        }
    }

    // 底栏卡片被点击：真正执行批量动作
    LaunchedEffect(state.galleryBulkTick) {
        if (state.galleryBulkTick <= 0) return@LaunchedEffect
        when (state.gallerySelKind) {
            "delete" -> confirmDelete = true
            "save" -> {
                val targets = currentItems.filter { it.stableKey in selected }
                if (targets.isEmpty()) return@LaunchedEffect
                state.galleryBulkBusy = true
                var ok = 0
                var failed = 0
                try {
                    targets.forEach { target ->
                        val handles = listOf(target.key to target.fileName) + target.extraHandles
                        // 先把整批任务写进持久化队列：中途被杀进程，下次连上相机也能接着传。
                        if (!target.fromUsb) {
                            val thumb = GalleryThumbCache.peek(target.stableKey)
                            handles.forEach { (handle, _) ->
                                val transferId = state.vm.enqueueTransfer(handle)
                                if (transferId != null) TransferCenter.putThumbnail(transferId, thumb)
                            }
                        }
                        handles.forEach { (handle, _) ->
                            val success: Boolean = if (target.fromUsb) {
                                suspendCancellableCoroutine { cont ->
                                    state.saveUsbPhoto(context, handle, target.fileName) { msg ->
                                        if (cont.isActive) cont.resume(msg.startsWith("已保存"))
                                    }
                                }
                            } else {
                                // downloadPhoto 是串行的：上一次没下完就等它，避免直接 return 出去
                                while (state.vm.downloadingPhotoHandles.isNotEmpty()) delay(300)
                                suspendCancellableCoroutine { cont ->
                                    state.vm.downloadPhoto(handle) { r ->
                                        if (cont.isActive) cont.resume(r)
                                    }
                                }
                            }
                            if (success) ok++ else failed++
                        }
                    }
                } finally {
                    state.galleryBulkBusy = false
                }
                state.galleryRefreshTick++
                Toast.makeText(
                    context,
                    if (failed == 0) "已保存 $ok 个文件到应用相册"
                    else "已保存 $ok 个，$failed 个失败（相机可能已断开）",
                    Toast.LENGTH_SHORT
                ).show()
                resetSelection()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

    Column(modifier = Modifier.fillMaxSize()) {
        if (selecting) {
            // 选择模式：和系统相册一样，标题让位给计数与批量操作
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DoukSpace.sm, end = DoukSpace.md, top = DoukSpace.sm, bottom = DoukSpace.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "取消",
                    style = DoukType.body,
                    color = DoukColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { resetSelection() }
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                )
                Text(
                    "已选择 ${selected.size} 项",
                    style = DoukType.h2,
                    color = DoukColors.textPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = DoukSpace.sm)
                )
                Text(
                    if (selected.size == currentItems.size && currentItems.isNotEmpty()) "取消全选" else "全选",
                    style = DoukType.body,
                    color = DoukColors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            selected = if (currentItems.isNotEmpty() && selected.size == currentItems.size) {
                                emptySet()
                            } else {
                                currentItems.map { it.stableKey }.toSet()
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DoukSpace.page, end = DoukSpace.xs, top = DoukSpace.sm, bottom = DoukSpace.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 标题和其它页（设备 / 控制 / 设置）左对齐；「照片 / 相册」改为在底部
                // dock 的「图库」上点第二下弹出，所以这里不再挂点击
                Text(
                    text = "图库",
                    style = DoukType.h1,
                    color = DoukColors.textPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                // 相机内 / 应用内 / 手机内收成这一个按钮，点开才展开，形状照小米相册顶部的「照片 ▾」
                Row(
                    modifier = Modifier
                        .clip(DoukShapes.pill)
                        .background(DoukColors.surfaceMuted)
                        .clickable { showSourceMenu = true }
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        source.label,
                        style = DoukType.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = DoukColors.textPrimary
                    )
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = "切换图片来源",
                        tint = DoukColors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // 传输图片：正在传 / 等待续传的任务常驻入口，角标显示未结束数量
                val transferActive = transferRecords.count { it.isActive }
                Box(modifier = Modifier.size(42.dp)) {
                    IconButton(
                        onClick = { showTransfers = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .doukCardSurface(CircleShape)
                    ) {
                        Icon(
                            Icons.Rounded.CloudUpload,
                            contentDescription = "传输图片",
                            tint = if (transferActive > 0) DoukColors.accent else DoukColors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (transferActive > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(15.dp)
                                .background(DoukColors.accent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (transferActive > 99) "99+" else transferActive.toString(),
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                // 右上角「更多」：按钮本身也是高级材质圆片，弹出的卡片由根 Box 里的浮层渲染
                IconButton(
                    onClick = { showMoreMenu = true },
                    modifier = Modifier
                        .size(42.dp)
                        .doukCardSurface(CircleShape)
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "更多",
                        tint = if (filter.isActive) DoukColors.accent else DoukColors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 当前相册过滤条：从「相册」里选过一个相册才会出现，点叉回到全部
        albumFilter?.let { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DoukSpace.page, end = DoukSpace.page, top = 2.dp, bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(DoukShapes.pill)
                        .background(DoukColors.accentSoft)
                        .clickable { albumFilter = null }
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        album,
                        style = DoukType.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = DoukColors.accent
                    )
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "取消相册过滤",
                        tint = DoukColors.accent,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // 网格与底部操作条同处一个 Column：网格必须 weight(1f) 让位，
        // 否则内部 fillMaxSize 会把选择模式的操作条挤出屏幕
        Box(modifier = Modifier.weight(1f)) {
            if (galleryView == GalleryView.ALBUMS) {
                AlbumGridPanel(
                    source = source,
                    state = state,
                    onOpen = { name ->
                        albumFilter = name
                        galleryView = GalleryView.PHOTOS
                        showViewSwitch = false
                    }
                )
            } else when (source) {
            GallerySource.CAMERA -> CameraGallery(
                state = state,
                                selected = selected,
                filter = filter,
                albumFilter = albumFilter,
                onOpen = { previewItem = it },
                onLongPress = { item ->
                    selecting = true
                    selected = selected + item.stableKey
                },
                onSelect = { item -> selected = selected + item.stableKey },
                onDeselect = { item -> selected = selected - item.stableKey },
                onToggle = { item ->
                    selected = if (item.stableKey in selected) {
                        selected - item.stableKey
                    } else {
                        selected + item.stableKey
                    }
                },
                onItemsReady = { currentItems = it }
            )

            GallerySource.APP -> AppGallery(
                                refreshTick = state.galleryRefreshTick,
                albumFilter = albumFilter,
                filter = filter,
                selected = selected,
                onOpen = { previewItem = it },
                onLongPress = { item ->
                    selecting = true
                    selected = selected + item.stableKey
                },
                onSelect = { item -> selected = selected + item.stableKey },
                onDeselect = { item -> selected = selected - item.stableKey },
                onToggle = { item ->
                    selected = if (item.stableKey in selected) {
                        selected - item.stableKey
                    } else {
                        selected + item.stableKey
                    }
                },
                onItemsReady = { currentItems = it }
            )

            GallerySource.PHONE -> PhoneGallery(
                                albumFilter = albumFilter,
                filter = filter,
                selected = selected,
                onOpen = { previewItem = it },
                onLongPress = { item ->
                    selecting = true
                    selected = selected + item.stableKey
                },
                onSelect = { item -> selected = selected + item.stableKey },
                onDeselect = { item -> selected = selected - item.stableKey },
                onToggle = { item ->
                    selected = if (item.stableKey in selected) {
                        selected - item.stableKey
                    } else {
                        selected + item.stableKey
                    }
                },
                onItemsReady = { currentItems = it }
            )
            }
        }

        // 批量按钮不再放在这里：改成 Douk 底栏上方的悬浮卡（见 DoukRoot）
        // 网格自己的 contentPadding 已经留了 124dp 底部空白（滚到底时越过胶囊），这里只收边。
        Spacer(modifier = Modifier.height(4.dp))
    }

        // 「照片 / 相册」：从底部 dock 的「图库」二次点击弹出，浮在 dock 上方
        if (showViewSwitch) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 112.dp),
                contentAlignment = Alignment.Center
            ) {
                GalleryViewSwitch(
                    current = galleryView,
                    onPick = {
                        galleryView = it
                        showViewSwitch = false
                    }
                )
            }
        }
    }

    if (showTransfers) {
        TransferSheet(onDismiss = { showTransfers = false })
    }

    if (confirmDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 $count 张照片？") },
            text = { Text("这些照片来自应用下载目录（Pictures/连镜、DCIM/连镜），删除后无法恢复；相机与手机相册里的原图不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    val targets = currentItems.filter { it.stableKey in selected }
                    val names = targets.flatMap { listOf(it.fileName) + it.extraFiles }.distinct()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            GalleryFileOps.deleteAppFiles(context, names)
                        }
                        state.galleryRefreshTick++
                        deleting = false
                        resetSelection()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }



    if (showSourceMenu) {
        Dialog(onDismissRequest = { showSourceMenu = false }) {
            Column(
                modifier = Modifier
                    .clip(DoukShapes.card)
                    .doukCardSurface(DoukShapes.card)
                    .padding(vertical = 10.dp)
            ) {
                GallerySource.entries.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                source = item
                                showSourceMenu = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (item == GallerySource.PHONE) Icons.Rounded.Smartphone else Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            tint = DoukColors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, fontSize = 14.sp, color = DoukColors.textPrimary)
                            Text(item.hint, fontSize = 11.sp, color = DoukColors.textTertiary)
                        }
                        if (item == source) {
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
        }
    }

    previewItem?.let { item ->
        // 前后各一张缩略图一起递给查看器：左右滑时它们跟着手指位移，换图不再是硬切
        val list = currentItems
        val idx = list.indexOfFirst { it.stableKey == item.stableKey }
        val prevOf = if (idx > 0) list[idx - 1] else null
        val nextOf = if (idx >= 0 && idx + 1 < list.size) list[idx + 1] else null
        PhotoViewerScreen(
            item = item,
            // 读状态而不是 peek：缩略图后到（或刚被加载完）时查看器能跟着更新，不会一直灰着
            bitmap = GalleryThumbCache.stateOf(item.stableKey).value,
            state = state,
            showOriginal = showOriginal,
            prevItem = prevOf,
            nextItem = nextOf,
            prevBitmap = prevOf?.let { GalleryThumbCache.stateOf(it.stableKey).value },
            nextBitmap = nextOf?.let { GalleryThumbCache.stateOf(it.stableKey).value },
            // 左右滑动换图：在当前来源、当前筛选结果里按顺序取下一张，到头就停
            onSwipe = { dir ->
                val list = currentItems
                val idx = list.indexOfFirst { it.stableKey == item.stableKey }
                val next = idx + dir
                if (idx >= 0 && next in list.indices) previewItem = list[next]
            },
            onDismiss = { previewItem = null },
            onDeleted = {
                previewItem = null
                state.galleryRefreshTick++
            },
            onChanged = {
                previewItem = null
                state.galleryRefreshTick++
            }
        )
    }

    // 「更多」浮层：整屏透明点击层负责“点空白收起”，两张白卡右上角并排
    if (showMoreMenu) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    showMoreMenu = false
                    showFilter = false
                }
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 62.dp, end = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            if (showFilter) {
                GalleryFilterCard(
                    current = filter,
                    onApply = {
                        filter = it
                        showFilter = false
                    },
                    onDismiss = { showFilter = false }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier
                    .width(206.dp)
                    .doukShadow(16.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .doukCardSurface(RoundedCornerShape(28.dp))
                    .padding(vertical = 8.dp)
            ) {
                GalleryMenuRow(
                    icon = Icons.Rounded.FilterList,
                    label = if (filter.isActive) "筛选（已启用）" else "筛选",
                    active = filter.isActive,
                    onClick = { showFilter = !showFilter }
                )
                GalleryMenuRow(
                    icon = Icons.Rounded.Check,
                    label = "始终显示原图",
                    active = showOriginal,
                    trailingCheck = true,
                    onClick = {
                        showOriginal = !showOriginal
                        DoukPrefs.galleryShowOriginal = showOriginal
                    }
                )
                GalleryMenuRow(
                    icon = Icons.Rounded.Label,
                    label = "显示图片格式",
                    active = showFormatBadge,
                    trailingCheck = true,
                    onClick = {
                        val v = !galleryFormatBadge.value
                        galleryFormatBadge.value = v
                        DoukPrefs.galleryShowFormatBadge = v
                    }
                )
            }
        }
    }
}

/** 相机内：USB 通道走 GetObjectHandles，Wi‑Fi 通道走已枚举的存储列表。 */
/**
 * 同一张照片的 RAW+JPG / HEIF+RAW 在机身上是两个 ObjectHandle（文件名主干相同），
 * 图库里只留一条：主图优先 JPG/HEIF，角标把两种格式一起标出来。
 * 同时把 0x1008 里的文件夹名带进 album，相机内也能按真实目录分相册。
 */
private fun mergePairedFormats(
    files: List<com.lutcamera.lutcamerapro.model.CameraStorageFile>,
    fromUsb: Boolean
): List<GalleryItem> {
    val groups = LinkedHashMap<String, MutableList<com.lutcamera.lutcamerapro.model.CameraStorageFile>>()
    files.forEach { file ->
        val base = file.fileName.substringBeforeLast('.', file.fileName).lowercase()
        val folder = file.folder ?: "机身存储"
        // 图片和视频不合并：机身里基名相同的 JPG 与 MOV 是两种素材，混在一起标 JPG+MOV 反而看不懂
        val ext = file.fileName.substringAfterLast('.', "").lowercase()
        val kind = if (ext in setOf("mov", "mp4")) "v" else "i"
        groups.getOrPut("$folder|$base|$kind") { mutableListOf() }.add(file)
    }
    return groups.map { (_, group) ->
        val exts = group.map { it.fileName.substringAfterLast('.', "").lowercase() }.distinct()
        val preferred = group.firstOrNull {
            it.fileName.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "heic", "heif", "mov", "mp4")
        } ?: group.first()
        GalleryItem(
            key = preferred.handle,
            fileName = preferred.fileName,
            sizeBytes = group.sumOf { it.sizeBytes },
            isRaw = group.any { it.isRaw } || exts.any { it in setOf("nef", "nrw", "dng") },
            fromUsb = fromUsb,
            origin = if (fromUsb) "usb" else "cam",
            dateMillis = group.maxOf { it.dateModified }.takeIf { ts -> ts > 0 },
            isVideo = exts.any { it in setOf("mov", "mp4") },
            album = preferred.folder ?: "机身存储",
            formats = exts,
            extraHandles = group.filter { it.handle != preferred.handle }
                .map { it.handle to it.fileName }
        )
    }
}

@Composable
private fun CameraGallery(
    state: DoukState,
    selected: Set<String>,
    onOpen: (GalleryItem) -> Unit,
    onLongPress: (GalleryItem) -> Unit,
    onToggle: (GalleryItem) -> Unit,
    onSelect: (GalleryItem) -> Unit,
    onDeselect: (GalleryItem) -> Unit,
    onItemsReady: (List<GalleryItem>) -> Unit,
    filter: GalleryFilter = GalleryFilter(),
    albumFilter: String? = null
) {
    val usbMode = state.usbOnly

    LaunchedEffect(usbMode, state.usbConnected, state.isConnected, albumFilter) {
        if (usbMode && state.usbConnected) {
            if (state.usbPhotos.isEmpty()) state.loadUsbPhotos()
        }
        // 连接后预加载已经填过列表时不要重复整表重读，否则打开图库会闪一下
        if (!usbMode && state.isConnected && state.cameraPhotos.isEmpty()) {
            // 只读最新一页：逐张读 ObjectInfo 才是贵的那一步，一次 4000 张会把机身
            // PTP 队列塞满（属性轮询全部超时 → 整场会话被判掉线）。下拉再续。
            state.loadCameraPhotos(count = DoukPrefs.galleryPageSize)
        }
    }

    val pageSize = DoukPrefs.galleryPageSize.coerceIn(20, 300)
    var shownCount by remember(usbMode) { mutableStateOf(pageSize) }
    LaunchedEffect(filter, albumFilter, usbMode) {
        shownCount = pageSize
    }

    // PTP 的 GetObjectHandles 只给句柄顺序；0x1008 里带了 CaptureDate，按真实拍摄时间分组
    // USB 的 UsbObject 先转成同一个 CameraStorageFile 模型，才能共用合并/分相册逻辑
    val sourceFiles: List<com.lutcamera.lutcamerapro.model.CameraStorageFile> = if (usbMode) {
        state.usbPhotos.map {
            com.lutcamera.lutcamerapro.model.CameraStorageFile(
                handle = it.handle,
                fileName = it.fileName,
                sizeBytes = it.sizeBytes,
                dateModified = 0L,
                isRaw = it.isRaw
            )
        }
    } else state.cameraPhotos
    val items: List<GalleryItem> = mergePairedFormats(sourceFiles, usbMode)
        .sortedByDescending { it.dateMillis ?: 0L }
    LaunchedEffect(items) { onItemsReady(items) }

    // 筛选（类型 / 格式）对机身列表同样生效；构图维度因为 PTP 拿不到尺寸不参与
    val visibleItems = items.filter { (albumFilter == null || it.album == albumFilter) && filter.matches(it) }

    val notConnected = !state.isConnected && !(usbMode && state.usbConnected)
    if (notConnected) {
        EmptyGallery(
            icon = Icons.Rounded.PhotoLibrary,
            title = "相机未连接",
            desc = "连接相机后可浏览机身存储中的照片"
        )
        return
    }

    if (visibleItems.isEmpty()) {
        if (items.isNotEmpty()) {
            EmptyGallery(
                icon = Icons.Rounded.PhotoLibrary,
                title = "没有符合筛选的照片",
                desc = "换一个类型或格式，或者点筛选里的重置"
            )
            return
        }
        val title = if (usbMode) "正在读取机身照片" else "正在读取机身存储"
        val desc = if (usbMode) {
            state.usbGalleryMessage.ifBlank { "USB PTP 正在枚举机身照片，请稍候" }
        } else {
            state.storageMessage.ifBlank { "PTP 正在枚举相机内的照片，请稍候" }
        }
        EmptyGallery(icon = Icons.Rounded.PhotoLibrary, title = title, desc = desc)
        return
    }

    // 「全部」页默认也分页显示：整表逐张读 ObjectInfo 会把机身堵死，
    // 只有用户在设置里明确打开「一次性加载全部」才自动续读到底。
    val loadAll = DoukPrefs.galleryLoadAll
    val loaded = state.cameraPhotos
    LaunchedEffect(loadAll, state.isConnected, loaded.size) {
        if (loadAll && state.isConnected && state.cameraPhotosHasMore && !state.vm.isLoadingStorageFiles) {
            state.loadCameraPhotos(count = state.cameraPhotos.size + DoukPrefs.galleryPageSize)
        }
    }
    // 「全部」页的下拉续读：每页只多读一页，不再整表重扫。
    LaunchedEffect(albumFilter, state.isConnected, state.cameraPhotosHasMore) {
        if (usbMode || albumFilter != null || !state.isConnected) return@LaunchedEffect
    }

    val displayed = if (loadAll) visibleItems else visibleItems.take(shownCount)
    val hasMore = state.cameraPhotosHasMore || displayed.size < visibleItems.size

    PhotoGrid(
        items = displayed,
                selected = selected,
        headerOverride = "机身照片 ${visibleItems.size} 张（${if (usbMode) "USB PTP" else "Wi‑Fi PTP"}）",
        onOpen = onOpen,
        onLongPress = onLongPress,
        onToggle = onToggle,
        onSelect = onSelect,
        onDeselect = onDeselect,
        hasMore = hasMore,
        onLoadMore = {
            if (loadAll) {
                state.loadCameraPhotos(count = state.cameraPhotos.size + DoukPrefs.galleryPageSize)
            } else {
                shownCount = (shownCount + pageSize).coerceAtMost(600)
                if (state.cameraPhotosHasMore && !state.vm.isLoadingStorageFiles) {
                    state.loadCameraPhotos(count = shownCount)
                }
            }
        },
        loadThumb = { item ->
            if (item.fromUsb) state.usbThumbBitmap(item.key) else state.wifiThumbBitmap(item.key)
        }
    )
}

/** 应用内：App 自己下载到 Pictures/连镜、DCIM/连镜 的照片。 */
@Composable
private fun AppGallery(
    refreshTick: Int,
    selected: Set<String>,
    onOpen: (GalleryItem) -> Unit,
    onLongPress: (GalleryItem) -> Unit,
    onToggle: (GalleryItem) -> Unit,
    onSelect: (GalleryItem) -> Unit,
    onDeselect: (GalleryItem) -> Unit,
    onItemsReady: (List<GalleryItem>) -> Unit,
    albumFilter: String? = null,
    filter: GalleryFilter = GalleryFilter()
) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    LaunchedEffect(refreshTick) {
        // 先读索引：文件没变的话，宽高直接命中，不用再 decode 一遍
        withContext(Dispatchers.IO) { AppMediaIndex.ensureLoaded(ctx) }
        val groups = withContext(Dispatchers.IO) { groupAppFiles(scanAppImages()) }
        files = groups.flatMap { listOf(it.main) + it.extras }
        val cachedBounds = HashMap<Int, Pair<Int, Int>>(groups.size)
        groups.forEachIndexed { index, group ->
            AppMediaIndex.cached(group.main)?.let { cachedBounds[index] = it }
        }
        // 先把列表铺出来：缓存没命中的宽高（构图筛选才用到）放到后台慢慢补，
        // 否则照片一多，readImageBounds 会把首屏一直卡在「正在扫描…」。
        val baseItems = groups.mapIndexed { index, group ->
            val mainExt = group.main.name.substringAfterLast('.', "").lowercase()
            val extraExts = group.extras.map { it.name.substringAfterLast('.', "").lowercase() }
            GalleryItem(
                key = index,
                fileName = group.main.name,
                sizeBytes = group.main.length(),
                isRaw = (mainExt in setOf("nef", "nrw", "dng")) || extraExts.any { it in setOf("nef", "nrw", "dng") },
                fromUsb = false,
                origin = "app",
                dateMillis = group.dateMillis.takeIf { it > 0 },
                album = group.main.parentFile?.name,
                isVideo = mainExt in APP_VIDEO_EXTS,
                formats = (listOf(mainExt) + extraExts).filter { it.isNotBlank() },
                extraFiles = group.extras.map { it.name },
                width = cachedBounds[index]?.first ?: 0,
                height = cachedBounds[index]?.second ?: 0
            )
        }
        items = baseItems
        scanned = true

        launch(Dispatchers.IO) {
            // 公共目录里没进过媒体库的文件，有些机型读出来是残缺的（EXIF/GPS 全是 0），先登记一次
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    ctx, files.map { it.absolutePath }.toTypedArray(), null, null
                )
            }
        }
        launch(Dispatchers.IO) {
            val bounds = HashMap<Int, Pair<Int, Int>>(groups.size)
            groups.forEachIndexed { index, group ->
                // 缓存里已经有的就不用再开文件了
                if (index in cachedBounds) return@forEachIndexed
                val read = readImageBounds(group.main) ?: return@forEachIndexed
                bounds[index] = read
                AppMediaIndex.put(group.main, read.first, read.second)
                if (index > 0 && index % 24 == 0) {
                    val snapshot = HashMap(bounds)
                    withContext(Dispatchers.Main) {
                        items = baseItems.mapIndexed { i, item ->
                            snapshot[i]?.let { (w, h) -> item.copy(width = w, height = h) } ?: item
                        }
                    }
                }
            }
            if (bounds.isNotEmpty()) {
                val snapshot = HashMap(bounds)
                withContext(Dispatchers.Main) {
                    items = baseItems.mapIndexed { i, item ->
                        snapshot[i]?.let { (w, h) -> item.copy(width = w, height = h) } ?: item
                    }
                }
            }
            // 删掉的图顺手清出索引，避免 JSON 越滚越大
            AppMediaIndex.prune(groups.map { it.main.absolutePath }.toSet())
            AppMediaIndex.save(ctx)
        }
    }

    if (files.isEmpty()) {
        EmptyGallery(
            icon = Icons.Rounded.PhotoLibrary,
            title = if (scanned) "应用内暂无照片" else "正在扫描…",
            desc = "控制页从相机下载的照片会出现在这里（Pictures/连镜）"
        )
        return
    }

    // 选了某个相册就只看这个目录下的照片，筛选条件再叠一层
    val visible = items.filter {
        (albumFilter == null || it.album == albumFilter) && filter.matches(it)
    }
    LaunchedEffect(visible) { onItemsReady(visible) }

    if (visible.isEmpty()) {
        EmptyGallery(
            icon = Icons.Rounded.PhotoLibrary,
            title = if (items.isNotEmpty()) "没有符合筛选的照片" else "正在扫描…",
            desc = if (items.isNotEmpty()) {
                "换一个类型或格式，或者点筛选里的重置"
            } else {
                "控制页从相机下载的照片会出现在这里（Pictures/连镜）"
            }
        )
        return
    }

    PhotoGrid(
        items = visible,
                selected = selected,
        headerOverride = "应用内 ${visible.size} 张",
        onOpen = onOpen,
        onLongPress = onLongPress,
        onToggle = onToggle,
        onSelect = onSelect,
        onDeselect = onDeselect,
        loadThumb = { item ->
            val file = withContext(Dispatchers.IO) {
                GalleryFileOps.locateAppFile(item.fileName)
            }
            when {
                file == null -> null
                // JPG/PNG/HEIC 走 Coil：缓存键带文件版本，滚动离开自动取消；
                // Coil 偶发解不动（异常配置、损坏文件）再退回原来的 ImageDecode。
                GalleryThumbs.canUseCoil(item) ->
                    GalleryThumbs.loadThumb(ctx, file, GALLERY_THUMB_SIDE)
                        ?: withContext(Dispatchers.IO) { decodeFileThumb(file) }
                else -> withContext(Dispatchers.IO) { decodeFileThumb(file) }
            }
        }
    )
}

/** 手机内：MediaStore 真实相册。 */
@Composable
private fun PhoneGallery(
    selected: Set<String>,
    onOpen: (GalleryItem) -> Unit,
    onLongPress: (GalleryItem) -> Unit,
    onToggle: (GalleryItem) -> Unit,
    onSelect: (GalleryItem) -> Unit,
    onDeselect: (GalleryItem) -> Unit,
    onItemsReady: (List<GalleryItem>) -> Unit,
    albumFilter: String? = null,
    filter: GalleryFilter = GalleryFilter()
) {
    val context = LocalContext.current
    // Android 13 起读相册是 READ_MEDIA_IMAGES；Android 14 起还要接受
    // READ_MEDIA_VISUAL_USER_SELECTED 的「仅选中照片」部分授权。
    val permissions = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
    }
    fun hasPhotoAccess(): Boolean = ContextCompat.checkSelfPermission(
        context, permissions.first()
    ) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(false) }
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.any { it }
        askedOnce = true
    }
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        granted = hasPhotoAccess()
        // 老用户升级上来时通常只授过「图片」，这里补请求「视频」，否则图库里相机视频不显示。
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && !askedOnce) permissionLauncher.launch(permissions)
    }

    if (!granted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.Smartphone,
                contentDescription = null,
                tint = DoukColors.textTertiary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text("需要读取手机相册", style = DoukType.h2, color = DoukColors.textPrimary)
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                "连镜只读取相册缩略图用于浏览，不会上传或修改你的照片。",
                style = DoukType.bodySmall,
                color = DoukColors.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(16.dp))
            Button(onClick = { permissionLauncher.launch(permissions) }) {
                Text("授予权限")
            }
            if (askedOnce) {
                TextButton(onClick = { openAppPermissionSettings(context) }) {
                    Text("已在系统里拒绝？去设置开启")
                }
            }
        }
        return
    }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        items = withContext(Dispatchers.IO) {
            val result = mutableListOf<GalleryItem>()
            data class MediaQuery(
                val uri: Uri,
                val idCol: String,
                val nameCol: String,
                val sizeCol: String,
                val dateCol: String,
                val bucketCol: String,
                val widthCol: String,
                val heightCol: String,
                val isVideo: Boolean
            )

            val projections = listOf(
                MediaQuery(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    false
                ),
                MediaQuery(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    true
                )
            )

            projections.forEach { q ->
                runCatching {
                    val projection = arrayOf(q.idCol, q.nameCol, q.sizeCol, q.dateCol, q.bucketCol, q.widthCol, q.heightCol)
                    context.contentResolver.query(
                        q.uri,
                        projection,
                        null,
                        null,
                        "${q.dateCol} DESC"
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndex(q.idCol)
                        val nameColumn = cursor.getColumnIndex(q.nameCol)
                        val sizeColumn = cursor.getColumnIndex(q.sizeCol)
                        val dateColumn = cursor.getColumnIndex(q.dateCol)
                        val bucketColumn = cursor.getColumnIndex(q.bucketCol)
                        val widthColumn = cursor.getColumnIndex(q.widthCol)
                        val heightColumn = cursor.getColumnIndex(q.heightCol)
                        var guard = 0
                        while (cursor.moveToNext() && guard < 300) {
                            val id = cursor.getLong(idColumn)
                            val name = cursor.getString(nameColumn) ?: if (q.isVideo) "VID_$id.mp4" else "IMG_$id"
                            val size = cursor.getLong(sizeColumn)
                            // DATE_ADDED 是秒，换成毫秒给日期分组用
                            val added = if (dateColumn >= 0) cursor.getLong(dateColumn) * 1000L else 0L
                            val ext = name.substringAfterLast('.', "").lowercase()
                            val isVideo = q.isVideo || ext in setOf("mp4", "mov", "m4v", "3gp", "mkv", "avi")
                            result += GalleryItem(
                                key = id.toInt(),
                                fileName = name,
                                sizeBytes = size,
                                isRaw = name.lowercase().endsWith(".nef"),
                                fromUsb = false,
                                origin = "phone",
                                dateMillis = added.takeIf { it > 0 },
                                isVideo = isVideo,
                                album = if (bucketColumn >= 0) cursor.getString(bucketColumn) else null,
                                width = if (widthColumn >= 0) cursor.getInt(widthColumn) else null,
                                height = if (heightColumn >= 0) cursor.getInt(heightColumn) else null,
                                formats = listOf(ext).filter { it.isNotBlank() }
                            )
                            guard++
                        }
                    }
                }
            }
            result.sortedByDescending { it.dateMillis ?: 0L }.take(300)
        }
        scanned = true
    }

    val visible = items.filter {
        (albumFilter == null || it.album == albumFilter) && filter.matches(it)
    }
    LaunchedEffect(visible) { onItemsReady(visible) }

    if (visible.isEmpty()) {
        EmptyGallery(
            icon = Icons.Rounded.Smartphone,
            title = when {
                !scanned -> "正在读取手机相册…"
                items.isNotEmpty() -> "没有符合筛选的照片"
                else -> "手机相册里没有照片"
            },
            desc = if (items.isNotEmpty()) {
                "换一个类型或格式，或者点筛选里的重置"
            } else {
                "需要授予相册读取权限后才会显示"
            }
        )
        return
    }

    PhotoGrid(
        items = visible,
                selected = selected,
        headerOverride = "手机相册 ${visible.size} 张",
        onOpen = onOpen,
        onLongPress = onLongPress,
        onToggle = onToggle,
        onSelect = onSelect,
        onDeselect = onDeselect,
        loadThumb = { item ->
            withContext(Dispatchers.IO) {
                if (item.isVideo) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.key.toLong()
                    )
                    runCatching {
                        context.contentResolver.loadThumbnail(
                            uri, android.util.Size(320, 320), null
                        )?.asImageBitmap()
                    }.getOrNull()
                } else {
                    // 手机拍的 Ultra HDR JPEG，系统 loadThumbnail 会返回 HDR 位图，
                    // Compose 直接画会发灰；统一走 ImageDecode 解成 SDR 缩略图。
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.key.toLong()
                    )
                    ImageDecode.decodeUri(context, uri, 320)
                }
            }
        }
    )
}

/**
 * 统一的时间轴网格（照小米相册的样子做的）：按日分组、日期头左对齐、4 列贴边、
 * 长按进多选、选中项盖一层浅色 + 右上角圆形对勾。四个来源共用这一个网格。
 */
@Composable
private fun PhotoGrid(
    items: List<GalleryItem>,
    selected: Set<String>,
    headerOverride: String? = null,
    onOpen: (GalleryItem) -> Unit,
    onLongPress: (GalleryItem) -> Unit,
    onToggle: (GalleryItem) -> Unit,
    /** 滑动选择：拖过的格子强制选中（不切换），用于一次拉一排 */
    onSelect: (GalleryItem) -> Unit = {},
    /** 反向：拖过已选中的格子把它取消掉 */
    onDeselect: (GalleryItem) -> Unit = {},
    loadThumb: suspend (GalleryItem) -> ImageBitmap?,
    hasMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null
) {
    val sections = remember(items) { buildSections(items) }
    val selectionMode = selected.isNotEmpty()
    val gridState = rememberLazyGridState()
    var columns by remember { mutableIntStateOf(DoukPrefs.galleryColumns) }
    val liveColumns by rememberUpdatedState(columns)
    val totalGridItems = sections.size + items.size + if (hasMore) 1 else 0
    // 扁平索引：日期头占一格，其余是照片；拖动选择时按可见格子的 index 反查
    val flatItems = remember(sections, hasMore) {
        buildList<GalleryItem?> {
            sections.forEach { section ->
                add(null)
                addAll(section.items)
            }
            if (hasMore) add(null)
        }
    }
    // 滑动选择靠每个格子自己上报手势；这里把根坐标换算回网格坐标，再命中到具体一张
    var gridRoot by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val dragVisited = remember { mutableSetOf<String>() }
    fun cellAt(root: Offset): GalleryItem? {
        val gc = gridRoot ?: return null
        // 网格层没有旋转/缩放，直接把网格原点换算到根坐标，再按 item 偏移叠上去
        val origin = gc.localToRoot(Offset.Zero)
        gridState.layoutInfo.visibleItemsInfo.forEach { info ->
            val cell = flatItems.getOrNull(info.index) ?: return@forEach
            val x0 = origin.x + info.offset.x
            val y0 = origin.y + info.offset.y
            if (root.x >= x0 && root.x <= x0 + info.size.width &&
                root.y >= y0 && root.y <= y0 + info.size.height
            ) return cell
        }
        return null
    }
    // 这一手是加选还是取消：长按到已选中的格子就进「擦除」模式，长按未选中的就进「加选」模式
    var dragErasing by remember { mutableStateOf(false) }
    val dragStartAt: (Offset) -> Unit = { root ->
        dragVisited.clear()
        cellAt(root)?.let { cell ->
            val already = cell.stableKey in selected
            dragErasing = already
            dragVisited += cell.stableKey
            if (already) onDeselect(cell) else onLongPress(cell)
        }
    }
    val dragAt: (Offset) -> Unit = { root ->
        cellAt(root)?.let { cell ->
            if (dragVisited.add(cell.stableKey)) {
                if (dragErasing) onDeselect(cell) else onSelect(cell)
            }
        }
    }

    LaunchedEffect(gridState, items, hasMore) {
        if (!hasMore || onLoadMore == null) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                // 离底部还有两行时就开始续一批，滚动不会顿在空白处
                if (last >= totalGridItems - 9) onLoadMore()
            }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val startColumns = liveColumns
                    var accum = 1f
                    var lastTarget = startColumns
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            // 每一档 = 缩放 1.22 倍。放大 -> 列数变少（格子变大），缩小 -> 列数变多。
                            accum *= event.calculateZoom()
                            val steps = (kotlin.math.ln(accum) / kotlin.math.ln(1.22f)).toInt()
                            val target = (startColumns - steps).coerceIn(2, 7)
                            if (target != lastTarget) {
                                lastTarget = target
                                if (liveColumns != target) {
                                    columns = target
                                    DoukPrefs.galleryColumns = target
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                        if (!event.changes.any { it.pressed }) break
                    }
                }
            }
            .onGloballyPositioned { gridRoot = it },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        // 底部留出比透明胶囊更高的一块：网格可以滚到胶囊下面，滚到底时最后一行完整露出来
        contentPadding = PaddingValues(
            start = 2.dp, end = 2.dp, top = 2.dp, bottom = 124.dp
        )
    ) {
        sections.forEach { section ->
            item(
                key = "head-${section.label}-${section.items.firstOrNull()?.stableKey}",
                span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
            ) {
                val allUndated = sections.all { it.label == "未标注时间" }
                Text(
                    text = if (allUndated && headerOverride != null) {
                        headerOverride
                    } else {
                        section.items.firstOrNull()?.let { first ->
                            val suffix = when (first.origin) {
                                "cam", "usb" -> "机身"
                                "app" -> "应用"
                                "phone" -> "手机"
                                else -> null
                            }
                            if (suffix != null) "${section.label} ｜ $suffix" else section.label
                        } ?: section.label
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DoukColors.textSecondary,
                    modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp)
                )
            }
            // contentType 让网格能在滚动时复用同类型的 composition slot
            items(section.items, key = { it.stableKey }, contentType = { "thumb" }) { item ->
                ThumbCell(
                    item = item,
                                        selected = item.stableKey in selected,
                    selectionMode = selectionMode,
                    onClick = { if (selectionMode) onToggle(item) else onOpen(item) },
                    onLongPress = { onLongPress(item) },
                    loader = { loadThumb(item) },
                    dragStart = dragStartAt,
                    dragHit = dragAt
                )
            }
        }
        if (hasMore && onLoadMore != null) {
            item(
                key = "load-more",
                span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "继续下拉加载更多",
                        fontSize = 12.sp,
                        color = DoukColors.textTertiary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbCell(
    item: GalleryItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    loader: suspend () -> ImageBitmap?,
    /** 长按起手那一格（根坐标） */
    dragStart: ((Offset) -> Unit)? = null,
    /** 拖动过程中滑到的位置（根坐标），一次跟手可以扫过一整排 */
    dragHit: ((Offset) -> Unit)? = null
) {
    // 每格只观察自己那份状态：别的格子加载完不会让这一格重组。
    // 缩略图被 LRU 淘汰时状态会变回 null，这里跟着重新拉一次。
    val bitmap = GalleryThumbCache.stateOf(item.stableKey).value
    LaunchedEffect(item.stableKey, bitmap != null) {
        if (bitmap == null) {
            GalleryDecodeGate.withPermit { loader() }?.let { GalleryThumbCache.put(item.stableKey, it) }
        }
    }
    // Live 图角标：探测只读文件头 128KB，结果会被 LivePhoto 缓存，滚动复用不会重复读
    val context = LocalContext.current
    var isLive by remember(item.stableKey) { mutableStateOf(LivePhoto.cached(item.stableKey) == true) }
    LaunchedEffect(item.stableKey) {
        if (!isLive && !item.isVideo) isLive = LivePhoto.probe(context, item)
    }
    var cellCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    // 手势协程要长期活着，回调必须是「最新值」而不是创建时那一份
    val startRef = androidx.compose.runtime.rememberUpdatedState(dragStart)
    val hitRef = androidx.compose.runtime.rememberUpdatedState(dragHit)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(DoukColors.surfaceMuted)
            // 长按交给下面的手势处理器统一做（加选/取消都靠它），
            // 这里只在没有传手势处理器时留个兜底，避免长按被两边各处理一次（选了又取消）
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (dragStart == null) onLongPress() }
            )
            .onGloballyPositioned { cellCoords = it }
            // key 只用 stableKey：一旦开始拖，选中集合变化会引起重组，
            // 如果 key 里带上随时会变的 lambda，手势协程会被重启，拖动就断了。
            .pointerInput(item.stableKey) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        cellCoords?.let { c -> startRef.value?.invoke(c.localToRoot(pos)) }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val hit = hitRef.value
                        val c = cellCoords
                        if (hit != null && c != null) hit(c.localToRoot(change.position))
                    }
                )
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                if (item.isRaw) "RAW" else if (item.isVideo) item.badgeLabel else "JPG",
                fontSize = 10.sp,
                color = DoukColors.textSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 视频叠一个播放角标，缩略图取不到时也能一眼看出这是视频
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "视频",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 格式角标统一放左下角：JPG / RAW / JPG+RAW / MOV / MP4…（视频也要显示）
        // 「更多 → 显示图片格式」关掉后整块不再画，网格就是纯图。
        if (galleryFormatBadge.value) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(item.badgeLabel, fontSize = 8.sp, color = Color.White, maxLines = 1)
            }
        }

        // Live 角标：右上角橙色圆点 + 摄像机图标（以前是 "LIVE" 文字，图标更省地方）
        if (isLive && !item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE08A2E))
                    )
                    Icon(
                        Icons.Rounded.Videocam,
                        contentDescription = "Live 图",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DoukColors.accent.copy(alpha = 0.18f))
            )
        }
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) DoukColors.accent else Color.Black.copy(alpha = 0.28f)
                    )
                    .then(
                        if (selected) Modifier else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "已选择",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}


private val APP_IMAGE_EXTS = setOf("jpg", "jpeg", "png", "heic", "heif", "nef", "nrw", "dng")
private val APP_VIDEO_EXTS = setOf("mp4", "mov")

private data class AppGroup(
    val main: File,
    val extras: List<File>,
    val dateMillis: Long
)

/**
 * 应用内图库的元数据索引。
 *
 * 应用图库每次刷新都要遍历 7 个目录、对每组照片 decode bounds（RAW/视频还要开
 * MediaMetadataRetriever）。文件没变就没必要重读，这里按「路径 + mtime + size」
 * 记住宽高，命中就跳过解码；索引落盘成一个小 JSON，冷启动也能直接用。
 */
private data class AppMeta(
    val mtime: Long = 0L,
    val size: Long = 0L,
    val w: Int = 0,
    val h: Int = 0
)

private object AppMediaIndex {
    private val map = HashMap<String, AppMeta>()
    private var loadedPath: String? = null
    private var dirty = false

    @Synchronized
    fun ensureLoaded(ctx: android.content.Context) {
        val path = File(ctx.filesDir, "app_gallery_index.json").absolutePath
        if (loadedPath == path) return
        loadedPath = path
        runCatching {
            val f = File(path)
            if (!f.exists()) return
            val type = object : com.google.gson.reflect.TypeToken<Map<String, AppMeta>>() {}.type
            val parsed: Map<String, AppMeta> =
                com.google.gson.Gson().fromJson(f.readText(), type) ?: emptyMap()
            map.putAll(parsed)
        }
    }

    /** 文件没被改过、且缓存里有宽高才算命中。 */
    @Synchronized
    fun cached(file: File): Pair<Int, Int>? {
        val meta = map[file.absolutePath] ?: return null
        if (meta.w <= 0 || meta.h <= 0) return null
        if (meta.mtime != file.lastModified() || meta.size != file.length()) return null
        return meta.w to meta.h
    }

    @Synchronized
    fun put(file: File, w: Int, h: Int) {
        val old = map[file.absolutePath]
        val meta = AppMeta(file.lastModified(), file.length(), w, h)
        if (old != meta) {
            map[file.absolutePath] = meta
            dirty = true
        }
    }

    @Synchronized
    fun prune(valid: Set<String>) {
        if (map.keys.retainAll(valid)) dirty = true
    }

    fun save(ctx: android.content.Context) {
        val json: String?
        synchronized(this) {
            if (!dirty) return
            dirty = false
            json = runCatching { com.google.gson.Gson().toJson(map) }.getOrNull()
        }
        if (json == null) return
        runCatching { File(ctx.filesDir, "app_gallery_index.json").writeText(json) }
    }
}

private fun appFileBaseName(name: String): String =
    name.substringBeforeLast('.', name)

/** 把同目录、同文件主干的 JPG/RAW/HEIF 合并成一条；视频单独一条，和不与图片混。 */
private fun groupAppFiles(files: List<File>): List<AppGroup> {
    fun ext(f: File) = f.name.substringAfterLast('.', "").lowercase()
    val videos = files.filter { ext(it) in APP_VIDEO_EXTS }
    // 导出视频时会写一张同名 JPG 当封面，这张不要单独占一格，缩略图仍然拿它来显示
    val videoKeys = videos.map {
        (it.parentFile?.absolutePath ?: "") to appFileBaseName(it.name).lowercase()
    }.toSet()
    val images = files.filter { ext(it) in APP_IMAGE_EXTS }.filterNot {
        ((it.parentFile?.absolutePath ?: "") to appFileBaseName(it.name).lowercase()) in videoKeys
    }
    val imageGroups = images.groupBy { file ->
        (file.parentFile?.absolutePath ?: "") to appFileBaseName(file.name).lowercase()
    }.map { (_, members) ->
        val main = members.firstOrNull { ext(it) in setOf("jpg", "jpeg", "heic", "heif", "png") }
            ?: members.first()
        AppGroup(
            main = main,
            extras = members.filterNot { it == main },
            dateMillis = members.maxOfOrNull { it.lastModified() } ?: main.lastModified()
        )
    }
    val videoGroups = videos.map {
        AppGroup(main = it, extras = emptyList(), dateMillis = it.lastModified())
    }
    return (imageGroups + videoGroups).sortedByDescending { it.dateMillis }
}

private fun scanAppImages(): List<File> {
    val roots = listOf(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "连镜"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "连镜"),
        // 旧版本写过的目录继续扫，保证升级后老照片仍在应用图库里
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Douk"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Douk"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "LensLink"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LutCameraPro"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "LutCameraPro")
    )
    // 递归扫：保存到应用相册的会落进 Pictures/连镜/<目录名>/，只扫根目录会漏掉
    return roots.filter { it.exists() }.flatMap { root ->
        root.walkTopDown().filter { file ->
            file.isFile && file.name.substringAfterLast('.', "").lowercase()
                .let { it in APP_IMAGE_EXTS || it in APP_VIDEO_EXTS }
        }.toList()
    }.sortedByDescending { it.lastModified() }
}

/** 读像素宽高：图片走 BitmapFactory，视频走 MediaMetadataRetriever；读不到返回 null */
private fun readImageBounds(file: File): Pair<Int, Int>? = runCatching {
    val ext = file.name.substringAfterLast('.', "").lowercase()
    if (ext in APP_VIDEO_EXTS) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()
            val h = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()
            if (w != null && h != null) w to h else null
        } finally {
            runCatching { retriever.release() }
        }
    } else {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }
}.getOrNull()

private fun decodeFileThumb(file: File): ImageBitmap? = ImageDecode.decodeThumb(file, GALLERY_THUMB_SIDE) ?: runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longSide / sample > GALLERY_THUMB_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    GalleryFileOps.applyExifOrientation(bmp, GalleryFileOps.exifOrientationOf(file)).asImageBitmap()
}.getOrNull()

/** 权限被永久拒绝后只能去系统设置里开，这里直接跳到本应用的详情页。 */
private fun openAppPermissionSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun EmptyGallery(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(DoukColors.surfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = DoukColors.textTertiary, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DoukColors.textSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(desc, fontSize = 12.sp, color = DoukColors.textTertiary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GalleryFilterPage(
    current: GalleryFilter,
    onApply: (GalleryFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(current) }
    val kinds = listOf("全部", "图片", "视频")
    // 格式跟着类型走：选了图片就不该再冒出视频格式，反之亦然
    val formats = when (draft.kind) {
        "图片" -> listOf("全部", "JPG", "RAW", "PNG", "HEIC")
        "视频" -> listOf("全部", "MP4", "MOV")
        else -> listOf("全部", "JPG", "RAW", "PNG", "HEIC", "MP4", "MOV")
    }
    // 筛选改成整屏二级页（和设置页一致，不用弹窗）
    BackHandler { onDismiss() }
    Box(modifier = Modifier.fillMaxSize().background(DoukColors.backgroundBrush)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = DoukColors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text("筛选", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DoukColors.textPrimary)
            }
            Spacer(modifier = Modifier.height(18.dp))

            FilterGroup(
                title = "类型",
                options = kinds,
                selected = draft.kind,
                onPick = { picked ->
                    // 换类型时如果当前格式不适用，一并复位，避免出现「图片 + MP4」这种空结果
                    val stillOk = when (picked) {
                        "图片" -> draft.format in listOf("全部", "JPG", "RAW", "PNG", "HEIC")
                        "视频" -> draft.format in listOf("全部", "MP4", "MOV")
                        else -> true
                    }
                    draft = draft.copy(
                        kind = picked,
                        format = if (stillOk) draft.format else "全部"
                    )
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            FilterGroup(
                title = "格式",
                options = formats,
                selected = draft.format,
                onPick = { draft = draft.copy(format = it) }
            )


            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "重置",
                    style = DoukType.caption,
                    color = DoukColors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            draft = GalleryFilter()
                            onApply(draft)
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "应用",
                    style = DoukType.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = DoukColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onApply(draft)
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 「更多」卡片里的一行：玻璃卡上的深色字，勾选态用主色 */
@Composable
private fun GalleryMenuRow(
    icon: ImageVector,
    label: String,
    active: Boolean,
    trailingCheck: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!trailingCheck) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) DoukColors.accent else DoukColors.textTertiary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = DoukColors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (trailingCheck && active) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = DoukColors.accent,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * 筛选卡片：和「更多」卡片并排出现的白底大圆角卡，点外面或再点筛选就收起。
 * 内容与整屏筛选页一致（类型 / 格式 / 构图），只是不再占满全屏。
 */
@Composable
private fun GalleryFilterCard(
    current: GalleryFilter,
    onApply: (GalleryFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(current) }
    val kinds = listOf("全部", "图片", "视频")
    val formats = when (draft.kind) {
        "图片" -> listOf("全部", "JPG", "RAW", "PNG", "HEIC")
        "视频" -> listOf("全部", "MP4", "MOV")
        else -> listOf("全部", "JPG", "RAW", "PNG", "HEIC", "MP4", "MOV")
    }

    Column(
        modifier = Modifier
            .width(300.dp)
            .doukShadow(14.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .doukCardSurface(RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text("筛选", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DoukColors.textPrimary)
            Spacer(modifier = Modifier.height(12.dp))
            FilterGroupOnWhite("类型", kinds, draft.kind) { picked ->
                val stillOk = when (picked) {
                    "图片" -> draft.format in listOf("全部", "JPG", "RAW", "PNG", "HEIC")
                    "视频" -> draft.format in listOf("全部", "MP4", "MOV")
                    else -> true
                }
                draft = draft.copy(kind = picked, format = if (stillOk) draft.format else "全部")
            }
            Spacer(modifier = Modifier.height(10.dp))
            FilterGroupOnWhite("格式", formats, draft.format) { draft = draft.copy(format = it) }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "重置",
                    fontSize = 13.sp,
                    color = DoukColors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            draft = GalleryFilter()
                            onApply(draft)
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "应用",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DoukColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onApply(draft)
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
    }
}

/** 玻璃卡上的胶囊组：跟随主题，深色模式下也能看清 */
@Composable
private fun FilterGroupOnWhite(
    title: String,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit
) {
    Text(title, fontSize = 12.sp, color = DoukColors.textTertiary)
    Spacer(modifier = Modifier.height(5.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    val active = option == selected
                    Text(
                        option,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) DoukColors.accent else DoukColors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (active) DoukColors.accentSoft else DoukColors.surfaceMuted)
                            .clickable { onPick(option) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** 一行「标题 + 几排可选胶囊」，选项多了自动折行，点哪个就换哪个 */
@Composable
private fun FilterGroup(
    title: String,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit
) {
    Text(title, fontSize = 12.sp, color = DoukColors.textTertiary)
    Spacer(modifier = Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val active = option == selected
                    Text(
                        option,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) DoukColors.accent else DoukColors.textSecondary,
                        modifier = Modifier
                            .clip(DoukShapes.pill)
                            .background(if (active) DoukColors.accentSoft else DoukColors.surfaceMuted)
                            .clickable { onPick(option) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

/** 图库的两个视图：照片时间轴 / 相册。 */
internal enum class GalleryView { PHOTOS, ALBUMS }

/**
 * 「照片 / 相册」切换条：照参考图做成一条浮起的白色圆角胶囊，
 * 选中项是嵌在里面的一枚浅灰圆角块，不是选中态那种整条变色。
 */
@Composable
private fun GalleryViewSwitch(
    current: GalleryView,
    modifier: Modifier = Modifier,
    onPick: (GalleryView) -> Unit
) {
    // 宽度写死而不是由内容撑：内容撑的时候在 Column 里会算歪，落到屏幕右侧
    Row(
        modifier = modifier
            .width(232.dp)
            .doukShadow(10.dp, RoundedCornerShape(percent = 50))
            .clip(RoundedCornerShape(percent = 50))
            .doukCardSurface(RoundedCornerShape(percent = 50))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            ViewOption(
                label = "照片",
                icon = Icons.Rounded.Image,
                selected = current == GalleryView.PHOTOS,
                modifier = Modifier.weight(1f)
            ) {
                onPick(GalleryView.PHOTOS)
            }
            ViewOption(
                label = "相册",
                icon = Icons.Rounded.PhotoAlbum,
                selected = current == GalleryView.ALBUMS,
                modifier = Modifier.weight(1f)
            ) {
                onPick(GalleryView.ALBUMS)
            }
    }
}

@Composable
private fun ViewOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) DoukColors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) DoukColors.accent else DoukColors.textSecondary,
            modifier = Modifier.size(25.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) DoukColors.textPrimary else DoukColors.textSecondary
        )
    }
}

/** 相册卡片：相册名 + 张数 + 封面（取这个相册里最新的一张）。 */
private data class AlbumCard(
    val name: String,
    val count: Int,
    val coverPath: String?,
    val coverMediaId: Long?,
    val coverIsVideo: Boolean = false
)

@Composable
private fun AlbumGridPanel(
    source: GallerySource,
    state: DoukState,
    onOpen: (String) -> Unit
) {
    val context = LocalContext.current
    var cards by remember { mutableStateOf<List<AlbumCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(source) {
        cards = withContext(Dispatchers.IO) { loadAlbumCards(context, source, state) }
        loading = false
    }

    when {
        loading -> EmptyGallery(
            icon = Icons.Rounded.PhotoAlbum,
            title = "正在读相册…",
            desc = "按来源把照片归到各自的相册里"
        )
        cards.isEmpty() -> EmptyGallery(
            icon = Icons.Rounded.PhotoAlbum,
            title = "还没有相册",
            desc = when (source) {
                GallerySource.CAMERA -> "连上相机后，机身存储会作为一个相册出现"
                GallerySource.APP -> "下载下来的照片会先进 Pictures/连镜"
                GallerySource.PHONE -> "授予相册权限后，系统里的相册会出现在这里"
            }
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(
                start = DoukSpace.page, end = DoukSpace.page, top = 14.dp, bottom = 124.dp
            )
        ) {
            items(cards, key = { it.name }, contentType = { "album" }) { card ->
                AlbumCardCell(card = card, onClick = { onOpen(card.name) })
            }
        }
    }
}

@Composable
private fun AlbumCardCell(card: AlbumCard, onClick: () -> Unit) {
    val context = LocalContext.current
    var cover by remember(card.name) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(card.name) {
        cover = GalleryDecodeGate.withPermit {
            withContext(Dispatchers.IO) { loadAlbumCover(context, card) }
        }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .doukCardSurface(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(7.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(DoukColors.surfaceMuted)
        ) {
            cover?.let {
                Image(
                    bitmap = it,
                    contentDescription = card.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            card.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = DoukColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp)
        )
        Text(
            "${card.count} 张",
            fontSize = 11.sp,
            color = DoukColors.textTertiary,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
        )
    }
}

private fun loadAlbumCards(context: Context, source: GallerySource, state: DoukState): List<AlbumCard> {
    when (source) {
        GallerySource.APP -> {
            val groups = LinkedHashMap<String, MutableList<File>>()
            GalleryFileOps.appRoots().filter { it.exists() }.forEach { root ->
                root.listFiles()?.filter { it.isFile }?.let { files ->
                    if (files.isNotEmpty()) groups.getOrPut(root.name) { mutableListOf() }.addAll(files)
                }
                root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                    val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
                    if (files.isNotEmpty()) {
                        groups.getOrPut(dir.name) { mutableListOf() }.addAll(files)
                    }
                }
            }
            return groups.map { (name, files) ->
                val newest = files.maxByOrNull { it.lastModified() }
                AlbumCard(name, files.size, newest?.absolutePath, null)
            }.sortedByDescending { it.count }
        }
        GallerySource.PHONE -> {
            val groups = LinkedHashMap<String, Triple<Long, Boolean, Int>>()
            val sources = listOf(
                Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media._ID, false),
                Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media._ID, true)
            )
            sources.forEach { (uri, idCol, isVideo) ->
                runCatching {
                    val projection = arrayOf(idCol, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    context.contentResolver.query(
                        uri,
                        projection,
                        null,
                        null,
                        "${MediaStore.Images.Media.DATE_ADDED} DESC"
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndex(idCol)
                        val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val name = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "其他" else "其他"
                            val id = if (idColumn >= 0) cursor.getLong(idColumn) else -1L
                            val old = groups[name]
                            // 合并图片与视频；按时间倒序扫，第一次遇到的那张就是封面
                            groups[name] = if (old == null) {
                                Triple(id, isVideo, 1)
                            } else {
                                Triple(old.first, old.second, old.third + 1)
                            }
                        }
                    }
                }
            }
            return groups.map { (name, triple) ->
                AlbumCard(name, triple.third, null, triple.first, triple.second)
            }.sortedByDescending { it.count }
        }
        GallerySource.CAMERA -> {
            // USB 也转成统一模型（folder 为空 → 归到「机身存储」）
            val files: List<com.lutcamera.lutcamerapro.model.CameraStorageFile> = if (state.usbOnly) {
                state.usbPhotos.map {
                    com.lutcamera.lutcamerapro.model.CameraStorageFile(
                        handle = it.handle, fileName = it.fileName,
                        sizeBytes = it.sizeBytes, dateModified = 0L, isRaw = it.isRaw
                    )
                }
            } else state.cameraPhotos
            // 机身真实目录（DCIM/100NCZ、101NCZ…）各算一个相册，不再笼统并成一个「机身存储」
            val groups = LinkedHashMap<String, Int>()
            files.forEach { file ->
                val name = file.folder?.takeIf { it.isNotBlank() } ?: "机身存储"
                groups[name] = (groups[name] ?: 0) + 1
            }
            return groups.map { (name, count) -> AlbumCard(name, count, null, null) }
                .sortedByDescending { it.count }
        }
    }
}

private fun loadAlbumCover(context: Context, card: AlbumCard): ImageBitmap? = runCatching {
    when {
        card.coverPath != null -> decodeFileThumb(File(card.coverPath))
        card.coverMediaId != null && card.coverMediaId > 0 -> {
            val collection = if (card.coverIsVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = ContentUris.withAppendedId(collection, card.coverMediaId)
            context.contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null)?.asImageBitmap()
        }
        else -> null
    }
}.getOrNull()
