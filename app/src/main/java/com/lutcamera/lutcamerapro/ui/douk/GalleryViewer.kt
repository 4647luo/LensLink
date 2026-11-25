package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import android.net.Uri
import android.provider.MediaStore
import com.lutcamera.lutcamerapro.log.DoukHttp
import okhttp3.Request
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.viewinterop.AndroidView
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全屏看图的内存上限。8200px 的 45MP 图单张 ARGB 约 180MB，
 * 再叠加 HDR gainmap/缓存很容易 OOM；6144 长边仍有足够细节，先守住稳定性。
 * 后续接 telephoto/sub-sampling 时再把这层替换成分块解码。
 */
private const val MAX_VIEW_SIDE = 6144

/**
 * 收藏只是本地标记，不动原图：存在 SharedPreferences 里，按「来源:键」去重。
 */
internal object GalleryFavorites {
    private const val PREFS = "douk_gallery_favorites"

    fun all(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("keys", emptySet())!!.toSet()

    fun toggle(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet("keys", emptySet())!!.toMutableSet()
        val nowOn = if (key in current) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        prefs.edit().putStringSet("keys", current).apply()
        return nowOn
    }
}

/**
 * 全屏看图页，照小米相册 5.x 的样子：
 * 顶部是「← 日期 / 时间」+ 旋转视图，底部是一条浮起来的圆角操作条
 * （发送 / 收藏 / 编辑 / 删除 / 更多），相机内的照片多一个「保存」。
 * 点一下图片把上下两条 UI 收掉，再点一下回来；点「更多」弹出贴按钮的窄卡：
 * 保存到 / 详情 / 重命名。
 */
/**
 * 看图页打开时，让 MainActivity 根布局在最顶部补一条纯黑底。
 *
 * Dialog 窗口在部分 ROM 上仍会被系统限制在状态栏下方（即使设置
 * decorFitsSystemWindows=false / setLayout(MATCH_PARENT)），状态栏区域会露出
 * 图库页的浅灰背景。这里用根布局的黑色顶层盖住，退出看图页立刻撤掉。
 */
internal object ViewerChrome {
    var open by androidx.compose.runtime.mutableStateOf(false)
}

/** 看图页里打开的白底详情页：让 Activity 根布局把状态栏/导航条也铺成白底，和页面本体一致。 */
internal object DetailChrome {
    var open by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
internal fun PhotoViewerScreen(
    item: GalleryItem,
    bitmap: ImageBitmap?,
    state: DoukState,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    onChanged: () -> Unit,
    onSwipe: (Int) -> Unit = {},
    showOriginal: Boolean = false,
    /** 左右两边各一张（含缩略图），跟手滑动时它们一起位移，松手才真正换图 */
    prevItem: GalleryItem? = null,
    nextItem: GalleryItem? = null,
    prevBitmap: ImageBitmap? = null,
    nextBitmap: ImageBitmap? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    DisposableEffect(Unit) {
        ViewerChrome.open = true
        // 部分 ROM 的 Dialog 窗口不会真正覆盖状态栏，状态栏区域露出的是 Activity
        // 自己的浅灰 decor 背景。这里直接把 Activity 的 decor 底也刷成黑色，
        // 状态栏和挖孔区就不会再有一条浅灰带。
        val activity = context.findActivity()
        val oldDecorBg = activity?.window?.decorView?.background
        activity?.window?.decorView?.setBackgroundColor(android.graphics.Color.BLACK)
        onDispose {
            ViewerChrome.open = false
            if (activity != null) activity.window.decorView.background = oldDecorBg
        }
    }

    // 这些状态绑定到当前这一张：换图时立即归零，避免新图第一帧还带着上一张的
    // 缩放/位移/滑页偏移，看起来就是“闪一下”或“抽一下”。
    var scale by remember(item.stableKey) { mutableStateOf(1f) }
    var offsetX by remember(item.stableKey) { mutableStateOf(0f) }
    var offsetY by remember(item.stableKey) { mutableStateOf(0f) }
    // 单指横滑时这一张的实时位移：图片真跟着手指走，松手再决定回弹还是翻页
    var dragX by remember(item.stableKey) { mutableStateOf(0f) }
    // 点一下图片进「纯看图」：顶栏和底部条一起收起来，再点一下回来
    var uiHidden by remember { mutableStateOf(false) }
    // 右上角那个按钮不再是分享，改成把当前这张转 90° 看（不改文件）
    var rotation by remember(item.stableKey) { mutableStateOf(0f) }
    var editing by remember { mutableStateOf(false) }
    // 视频单独走「播放 + 套 LUT 导出」页，图片编辑仍然走 PhotoEditor
    var videoOpen by remember { mutableStateOf(false) }
    // 网格里的是 320–400px 的缩略图，全屏要单独加载一张大的，否则放大就糊
    var hd by remember { mutableStateOf<ImageBitmap?>(null) }
    // 当前这张和前后各一张的大图缓存：左右滑时直接拿大图上屏，不再先显示缩略图。
    val fullCache = remember { mutableStateMapOf<String, ImageBitmap>() }
    // 高清图也有归属：换图首帧不能把上一张的大图顶上来
    var hdKey by remember { mutableStateOf<String?>(null) }
    // 真 HDR：Ultra HDR JPEG 的「底图 + 增益图」，或 PQ/HLG 图直接解出的 16bit 位图
    var hdrPair by remember { mutableStateOf<ImageDecode.HdrPair?>(null) }
    var hdrPq by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // HDR 数据属于哪一张：换图瞬间旧数据立刻失效，避免 HdrBitmapView 还挂着上一张的帧
    var hdrKey by remember { mutableStateOf<String?>(null) }
    // HdrBitmapView 真正画出新位图的 key；没有它就用缩略图盖着，避免翻页闪黑
    var hdrFrameKey by remember { mutableStateOf<String?>(null) }
    // 从编辑器返回时才需要把 HDR 数据补回来；初次进入不能和上面的 item 加载同时再解一遍，
    // 否则同一张 4096 图会并发解码两次，打开就是明显的“卡一下”。
    var needHdrReload by remember { mutableStateOf(false) }
    // 「显示原图」：这张单独从相机拉全尺寸（或跟「始终显示原图」总开关走）
    var wantOriginal by remember { mutableStateOf(false) }
    // 这张相机图是否已经取过全尺寸原图；不能再用 hd == null 判断，
    // 因为进页面时 hd 会先塞缩略图，条件永远不成立，菜单项就“消失”了。
    var originalLoaded by remember { mutableStateOf(false) }
    var loadingOriginal by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(item.stableKey in GalleryFavorites.all(context)) }
    var more by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf(false) }
    // 「细节查看」：独立一页用 Telephoto 分块渲染，能看到 100% 以上的像素细节。
    // 不塞进这个看图页，是因为 zoomable 会吞掉包括双击在内的全部手势，
    // 横滑换图、Live 长按、上滑分享都会被顶掉。
    var zoomDetail by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 「更多」那一层的空白点击和图片的单点会撞在同一次触摸上，用一个时间戳把后者挡掉
    var lastDismissAt by remember { mutableStateOf(0L) }
    // 双击判定：两次轻点间隔小于 320ms 就当双击放大
    var lastTapAt by remember { mutableStateOf(0L) }
    // 查看器是独立窗口，必须用自己的 HazeState：图片层做源，底部操作条才有东西可糊
    val viewerHaze = remember { dev.chrisbanes.haze.HazeState() }

    // 提示条自己消失，免得一直挂在图片下面
    androidx.compose.runtime.LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            message = null
        }
    }

    fun appFile(): File? = if (item.origin == "app") GalleryFileOps.locateAppFile(item.fileName) else null

    fun phoneUri(): Uri? = if (item.origin == "phone") {
        val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        ContentUris.withAppendedId(collection, item.key.toLong())
    } else null

    /**
     * HDR 解析用的文件：应用内直接用原文件；手机相册里的图先落一份缓存副本，
     * 这样 gainmap / PQ 两套解码都能复用同一套文件实现。以前只认 origin=="app"，
     * 手机里带 HDR 的照片就被当成普通 SDR 显示了。
     */
    suspend fun hdrSourceFile(): File? = withContext(Dispatchers.IO) {
        if (item.origin == "app") GalleryFileOps.locateAppFile(item.fileName)?.let { return@withContext it }
        if (item.origin != "phone" || item.isVideo) return@withContext null
        val uri = phoneUri() ?: return@withContext null
        ImageDecode.materialize(context, uri, "hdr-${item.key}")
    }

    // Live 图：先只是探测标记，点开播才把尾部视频切出来，避免每次翻页都读整张图
    var isLive by remember { mutableStateOf(false) }
    var liveOpen by remember { mutableStateOf(false) }
    var liveFile by remember { mutableStateOf<File?>(null) }
    var liveBusy by remember { mutableStateOf(false) }

    /**
     * 视频改成应用内播放：MOV / HEVC 10-bit / N-Log 这些系统播放器经常直接拒播，
     * 这里用 MediaPlayer + GL 自己上屏，还能当场套还原 LUT。
     * 相机内还没落盘的先提示保存，避免点了没反应。
     */
    fun playVideo() {
        when (item.origin) {
            "phone", "app" -> videoOpen = true
            else -> message = "相机内视频请先点「保存」存到手机，再播放"
        }
    }

    /** 点击 LIVE 胶囊或长按照片都走这里，避免两套抽取逻辑。 */
    fun playLive() {
        if (!isLive || item.isVideo || liveBusy) return
        liveBusy = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { LivePhoto.extractVideo(context, item) }
            liveFile = f
            liveBusy = false
            if (f != null) {
                liveOpen = true
            } else {
                message = "这段 Live 视频取不出来，可能已被裁剪过"
            }
        }
    }

    suspend fun fetchOriginal() {
        loadingOriginal = true
        message = "正在从相机取原图…"
        hd = withContext(Dispatchers.IO) { state.cameraOriginalBitmap(item.key) }
        hdKey = item.stableKey
        loadingOriginal = false
        originalLoaded = hd != null
        message = if (hd == null) "取原图失败，先显示缩略图" else null
    }

    androidx.compose.runtime.LaunchedEffect(item.stableKey) {
        // 横滑换到另一张时，缩放、旋转、浮层、收藏状态全部按新这张重置
        scale = 1f; offsetX = 0f; offsetY = 0f; rotation = 0f
        dragX = 0f
        uiHidden = false; more = false; detail = false; editing = false; videoOpen = false
        wantOriginal = false
        originalLoaded = false
        liveOpen = false; liveFile = null; liveBusy = false
        favorite = item.stableKey in GalleryFavorites.all(context)
        // 先把网格里已经解码好的缩略图直接铺上，用户点开就是有画面的；
        // 高清图再在后台替换。以前这里先 hd=null 等 decode，5120 万像素的
        // 原图要几百毫秒，看起来就是「点开先黑一下」。
        hd = fullCache[item.stableKey] ?: bitmap
        hdKey = item.stableKey
        hdrPair = null
        hdrPq = null
        hdrKey = null
        hdrFrameKey = null
        // 真 HDR 先探针：手机相册里的 Ultra HDR 图如果先按普通大图解一遍，
        // 再解一遍底图 + 增益图，同一张图要解码两次，打开会明显顿一下。
        val hdrFile = if (!item.isVideo && android.os.Build.VERSION.SDK_INT >= 30) {
            hdrSourceFile()
        } else null
        val isHdr = hdrFile != null &&
            withContext(Dispatchers.IO) { ImageDecode.probeHdr(hdrFile) }
        if (item.origin == "cam" && !item.isVideo && showOriginal) {
            // 「始终显示原图」总开关：每张打开就直接拉全尺寸
            fetchOriginal()
        } else if (!isHdr) {
            val cached = fullCache[item.stableKey]
            if (cached != null) {
                hd = cached
                hdKey = item.stableKey
            } else {
                val large = withContext(Dispatchers.IO) { loadLargeBitmap(context, item) }
                if (large != null) {
                    hd = large
                    hdKey = item.stableKey
                    fullCache[item.stableKey] = large
                    trimFullCache(fullCache, item.stableKey)
                }
            }
        }
        // Live 图探测：只读文件头，几十毫秒；真正播放再抽视频
        isLive = withContext(Dispatchers.IO) { LivePhoto.probe(context, item) }

        // 真 HDR：只有确实带 gainmap / PQ 的图才走这条，普通 SDR 图不折腾
        if (isHdr && hdrFile != null) {
            val pair = withContext(Dispatchers.IO) { ImageDecode.decodeHdrPair(hdrFile, MAX_VIEW_SIDE) }
            val pq = if (pair == null) {
                withContext(Dispatchers.IO) { ImageDecode.decodeHdrBitmap(hdrFile, MAX_VIEW_SIDE) }
            } else null
            hdrPair = pair
            hdrPq = pq
            hdrKey = item.stableKey
            com.lutcamera.lutcamerapro.log.DoukLog.i(
                "HdrViewer", "HDR_LOAD",
                "file" to hdrFile.name,
                "pair" to (pair != null).toString(),
                "ratio" to (pair?.ratioMax?.toString() ?: "-"),
                "pq" to (pq != null).toString()
            )
            // 连增益图都取不到时才退回普通大图，起码不要留一张缩略图
            if (pair == null && pq == null) {
                val large = withContext(Dispatchers.IO) { loadLargeBitmap(context, item) }
                if (large != null) {
                    hd = large
                    hdKey = item.stableKey
                }
            }
        } else if (hdrFile != null) {
            com.lutcamera.lutcamerapro.log.DoukLog.i(
                "HdrViewer", "HDR_SKIP",
                "file" to hdrFile.name,
                "msg" to "no hdr marker"
            )
        }
    }

    // 40MP 原片单张已接近 160MB，不再预取前后两张；左右滑时按当前页即时解码，
    // 用一次短暂加载换取不会因为两张 40MP 同时驻留而 OOM。

    // 从编辑器回来（editing=false）才把 HDR 数据补加载回来；初次进入不重复解码。
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing || !needHdrReload) return@LaunchedEffect
        needHdrReload = false
        if (!item.isVideo && android.os.Build.VERSION.SDK_INT >= 30 &&
            hdrPair == null && hdrPq == null
        ) {
            val file = hdrSourceFile()
            if (file != null && withContext(Dispatchers.IO) { ImageDecode.probeHdr(file) }) {
                val pair = withContext(Dispatchers.IO) { ImageDecode.decodeHdrPair(file, MAX_VIEW_SIDE) }
                hdrPair = pair
                if (pair == null) {
                    hdrPq = withContext(Dispatchers.IO) { ImageDecode.decodeHdrBitmap(file, MAX_VIEW_SIDE) }
                }
                hdrKey = item.stableKey
            }
        }
    }

    fun share() {        val uri = phoneUri() ?: appFile()?.let { file ->
            // 应用内的图片在 MediaStore 里有记录时才能给出 content uri
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.DISPLAY_NAME}=?",
                    arrayOf(file.name), null
                )?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                    ) else null
                }
            }.getOrNull()
        }
        if (uri == null) {
            message = "这张还没进手机相册，先在「更多」里添加到手机相册再发送"
            return
        }
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = GalleryFileOps.mimeOf(item.fileName)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "发送照片"))
        }.onFailure { message = "没有可以接收图片的应用" }
    }

    fun deleteNow() {
        if (item.origin != "app") {
            message = when (item.origin) {
                "phone" -> "手机相册里的照片请到系统相册删除"
                else -> "相机里的照片请到相机上删除"
            }
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                GalleryFileOps.deleteAppFiles(context, listOf(item.fileName) + item.extraFiles)
            }
            if (ok) {
                state.galleryRefreshTick++
                onDeleted()
            } else {
                message = "删除失败，文件可能被占用"
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // 查看器只留纯黑：照片上下不再透出 App 的浅色界面，系统栏也一起融进去
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val oldStatus = window?.statusBarColor
            val oldNav = window?.navigationBarColor
            val controller = window?.let { WindowCompat.getInsetsController(it, dialogView) }
            val oldLightStatus = controller?.isAppearanceLightStatusBars
            val oldLightNav = controller?.isAppearanceLightNavigationBars
            val oldColorMode = window?.colorMode
            window?.let { w ->
                // Dialog 默认会为系统栏留出 inset；即使 content 是黑色，状态栏
                // 那一条仍可能露出底层 Activity 的浅灰。先强制 edge-to-edge，
                // 让根 Box 的黑底真正铺到屏幕最上方，再画日期渐变。
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                // 查看器自己的 dim 会把编辑器打开后垫在下面的暖白 chrome 压成深灰。
                // 这里不要背景压暗，纯黑看图页本身已经够沉浸。
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0f)
                w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                w.decorView.fitsSystemWindows = false
                w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                w.decorView.setBackgroundColor(android.graphics.Color.BLACK)
                w.decorView.setPadding(0, 0, 0, 0)
                // 看图页在编辑器打开后仍垫在下面。它的 floating window 底边停在
                // 2780，系统阴影继续往下约 80px，正好透出编辑器底部那条渐变。
                w.setElevation(0f)
                // 关键：Compose Dialog 仍会带 NAVIGATION_BARS 的 fitInsetsTypes，
                // 系统因此把窗口压到 2780 高，底部 100px 露出底层图库。
                // 清空 fitInsetsTypes 后再 MATCH_PARENT，窗口才会铺满 2880。
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
                // Compose 的 DialogLayout 会在首帧 measure 时把窗口重新压回 2780，
                // 所以等它布局完再按真实屏幕尺寸补一次，才能盖住导航栏。
                w.decorView.post { w.setLayout(fullW, fullH) }
                w.attributes?.let { attrs ->
                    attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    w.attributes = attrs
                }
                // Android 15 起 statusBarColor 本身会被忽略；设成透明让根布局的黑底
                // 透上来，比设黑色更可靠（部分 ROM 会给旧式黑色栏加一层灰 scrim）。
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                w.isNavigationBarContrastEnforced = false
                w.isStatusBarContrastEnforced = false
            }
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                oldStatus?.let { window?.statusBarColor = it }
                oldNav?.let { window?.navigationBarColor = it }
                oldLightStatus?.let { controller?.isAppearanceLightStatusBars = it }
                oldLightNav?.let { controller?.isAppearanceLightNavigationBars = it }
                oldColorMode?.let { mode -> window?.colorMode = mode }
            }
        }
        // 编辑器打开后，看图页窗口仍然占着最上面的 100px，它自己的状态栏图标
        // 也得从白色切成深色，否则暖白底上会浮一排看不见的白图标。
        androidx.compose.runtime.LaunchedEffect(EditorChrome.open) {
            val w = (dialogView.parent as? DialogWindowProvider)?.window
            if (w != null) {
                val c = WindowCompat.getInsetsController(w, dialogView)
                c.isAppearanceLightStatusBars = EditorChrome.open
                c.isAppearanceLightNavigationBars = EditorChrome.open
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (EditorChrome.open) Color(0xFFEFEAE2) else Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 一个手势状态机管完：双指缩放、单指横滑换图、单指上滑分享、单击收 UI、双击放大
                    .pointerInput(item.stableKey, isLive) {
                        coroutineScope {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val downAt = System.currentTimeMillis()
                            var pointers = 1
                            // 已经放大时，这一手改成单指平移，而不是横滑换图。
                            var zoomed = scale > 1.01f
                            var totalX = 0f
                            var totalY = 0f
                            var moved = false
                            var longPressed = false
                            // Live 图长按：手指按住 500ms 不动就播尾部视频；
                            // 移动、第二根手指或已经放大都会取消，不抢横滑/缩放。
                            val longPressJob = if (isLive && !item.isVideo) launch {
                                kotlinx.coroutines.delay(500)
                                if (!moved && !zoomed && pointers == 1 && scale <= 1.01f) {
                                    longPressed = true
                                    dialogView.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.LONG_PRESS
                                    )
                                    playLive()
                                }
                            } else null
                            while (true) {
                                val event = awaitPointerEvent()
                                pointers = maxOf(pointers, event.changes.count { it.pressed })
                                val zoomChange = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (pointers >= 2 || zoomChange != 1f) zoomed = true
                                if (zoomed) {
                                    val next = (scale * zoomChange).coerceIn(1f, 6f)
                                    if (next <= 1f) {
                                        offsetX = 0f; offsetY = 0f
                                    } else {
                                        // 平移边界按“图片实际显示区域”算，不是按整个屏幕。
                                        // 横图在竖屏上上下有留白，按屏幕算会让图片被推出可视区，
                                        // 放大后看起来就只剩中间一小块。
                                        val rawW = bitmap?.width ?: item.width ?: 1
                                        val rawH = bitmap?.height ?: item.height ?: 1
                                        val quarter = (rotation % 180f) != 0f
                                        val aspect = if (quarter) rawH.toFloat() / rawW else rawW.toFloat() / rawH
                                        val viewAspect = size.width.toFloat() / size.height
                                        val fitW = if (aspect > viewAspect) size.width.toFloat()
                                        else size.height.toFloat() * aspect
                                        val fitH = if (aspect > viewAspect) size.width.toFloat() / aspect
                                        else size.height.toFloat()
                                        val maxX = ((fitW * next - size.width) / 2f).coerceAtLeast(0f)
                                        val maxY = ((fitH * next - size.height) / 2f).coerceAtLeast(0f)
                                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                    }
                                    scale = next
                                    event.changes.forEach { it.consume() }
                                } else {
                                    totalX += pan.x
                                    totalY += pan.y
                                    if (kotlin.math.abs(totalX) > 24f || kotlin.math.abs(totalY) > 24f) moved = true
                                    // 单指横滑：图片跟着手指实时位移，左右邻图一起拉进来；
                                    // 到头了（没有上一张/下一张）只给 30% 的橡皮筋位移
                                    if (kotlin.math.abs(totalX) > 6f) {
                                        val raw = totalX
                                        val limited = when {
                                            raw > 0f && prevItem == null -> raw * 0.3f
                                            raw < 0f && nextItem == null -> raw * 0.3f
                                            else -> raw.coerceIn(-size.width.toFloat(), size.width.toFloat())
                                        }
                                        dragX = limited
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                if (!event.changes.any { it.pressed }) break
                            }
                            longPressJob?.cancel()
                            if (longPressed) return@awaitEachGesture
                            val elapsed = System.currentTimeMillis() - downAt
                            val horizontal = kotlin.math.abs(totalX)
                            val vertical = kotlin.math.abs(totalY)
                            val widthPx = size.width.toFloat()
                            // 松手：过阈值就把这一张滑出屏幕并真正换图，否则弹回原位
                            val swipeDir = when {
                                zoomed || pointers != 1 || scale > 1.01f -> 0
                                vertical >= widthPx * 0.2f -> 0
                                horizontal <= widthPx * 0.2f -> 0
                                totalX < 0f && nextItem != null -> 1
                                totalX > 0f && prevItem != null -> -1
                                else -> 0
                            }
                            if (swipeDir != 0) {
                                // 手势协程是受限作用域，动画放到页面 scope 里跑，动画结束才真正换图
                                scope.launch {
                                    val from = dragX
                                    val to = if (swipeDir == 1) -widthPx else widthPx
                                    animate(0f, 1f, animationSpec = tween(200, easing = FastOutSlowInEasing)) { v, _ ->
                                        dragX = from + (to - from) * v
                                    }
                                    // 先换图，拖到位移不要立刻归零：父层真正换 item 之前
                                    // 归零会让旧图先弹回中间一帧，看起来就是“抽搐”。
                                    // 新 item 的 LaunchedEffect 会统一把 dragX 复位。
                                    onSwipe(swipeDir)
                                }
                            } else {
                                if (dragX != 0f) {
                                    scope.launch {
                                        val from = dragX
                                        animate(0f, 1f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { v, _ ->
                                            dragX = from * (1f - v)
                                        }
                                        dragX = 0f
                                    }
                                }
                            when {
                                // 已经缩放过的这一手只管缩放，不触发换图和分享
                                zoomed -> Unit
                                // 单指上滑：直接拉起分享面板
                                pointers == 1 && totalY < -size.height * 0.16f &&
                                    horizontal < size.width * 0.16f -> share()
                                // 轻点：先判双击放大，再判收 / 显示上下两条 UI
                                elapsed < 280L && !moved -> {
                                    val now = System.currentTimeMillis()
                                    // 视频没有可看的大图；中间那个播放键的点击会被这层手势吃掉，
                                    // 所以轻点整屏就直接进播放页，不再走双击放大那套。
                                    if (item.isVideo) {
                                        if (now - lastDismissAt > 150L) playVideo()
                                    } else if (now - lastTapAt < 320L) {
                                        if (scale > 1f) {
                                            scale = 1f; offsetX = 0f; offsetY = 0f
                                        } else {
                                            scale = 2.5f
                                        }
                                        lastTapAt = 0L
                                    } else {
                                        lastTapAt = now
                                        // 刚点空白关掉「更多」的那一下，不要再顺手把 UI 收起来
                                        if (now - lastDismissAt > 150L) {
                                            uiHidden = !uiHidden
                                        }
                                    }
                                }
                            }
                            }
                        }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // 图片这一层做模糊源：底部操作条压在它上面，才能糊到这张图本身
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(viewerHaze),
                    contentAlignment = Alignment.Center
                ) {
                val shown = fullCache[item.stableKey]
                    ?: if (hdKey == item.stableKey) (hd ?: bitmap) else bitmap
                val prevShown = prevItem?.let { fullCache[it.stableKey] } ?: prevBitmap
                val nextShown = nextItem?.let { fullCache[it.stableKey] } ?: nextBitmap
                val pair = if (hdrKey == item.stableKey) hdrPair else null
                val pq = if (hdrKey == item.stableKey) hdrPq else null
                // 拖动时 HdrBitmapView 仍然常驻，拖动时在上面盖一层普通 Image，
                // 松手后移走这层。这样换图不会销毁位图、重新建 EGL，也就不会灰闪。
                val dragging = dragX != 0f
                val hasHdr = pair != null || pq != null
                val hdrDialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                // 系统只有在 HDR 窗口里才会把 Gainmap 和底图合成；不设这个模式，
                // 标准 ImageView 也只会画出 SDR 底图，看起来就是“HDR 没了”。
                androidx.compose.runtime.LaunchedEffect(hasHdr, item.stableKey) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        hdrDialogWindow?.colorMode = if (hasHdr) ActivityInfo.COLOR_MODE_HDR
                        else ActivityInfo.COLOR_MODE_DEFAULT
                    }
                }
                val hdrFrameReady = hdrFrameKey == item.stableKey
                // 新 HDR 纹理还没上屏前，缩略图先盖在上面；上屏后立刻撤掉。
                // 这里不能用 120ms 淡入：animateFloat 初始值是 0，会先露出 HdrBitmapView
                // 的黑底/旧帧，再慢慢把缩略图淡进来，看起来就是“卡一下才显示”。
                val hdrOverlayAlpha = when {
                    !hasHdr -> 1f
                    !hdrFrameReady -> 1f
                    dragging || editing -> 1f
                    else -> 0f
                }
                // 这一层必须常驻：换图时如果把它从组合里拿掉，Surface 会被销毁，
                // 新图回来再重建 GL，就会看到“抽一下”的灰闪/旧帧。
                if (!editing) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val quarter = (rotation % 180f) != 0f
                        val w = if (quarter) maxHeight else maxWidth
                        val h = if (quarter) maxWidth else maxHeight
                        AndroidView(
                            factory = { ctx -> HdrBitmapView(ctx) },
                            update = { view ->
                                val key = item.stableKey
                                view.sourceTag = if (pair != null || pq != null) key else null
                                view.onFrameReady = { tag ->
                                    if (tag == key) hdrFrameKey = tag
                                }
                                if (pair != null) {
                                    view.setGainPair(
                                        pair.base, pair.gain,
                                        pair.ratioMax, pair.ratioMin, pair.gamma,
                                        pair.epsSdr, pair.epsHdr,
                                        pair.displayRatioFull, pair.minDisplayRatio
                                    )
                                } else if (pq != null) {
                                    view.setBitmap(pq)
                                } else {
                                    // 没有 HDR 时不要让 Surface 被销毁：保留一个已经初始化好的
                                    // 黑底 Surface，下一次打开 HDR 图就不用重新建 EGL / 等首帧。
                                    view.clear()
                                }
                                view.setTransform(scale, offsetX, offsetY, quarter)
                                // HdrBitmapView 绘制在当前 View 自身，普通 View 可以随拖动位移；
                                // 拖动时遮罩盖住它，松手后归位即可。
                                view.translationX = dragX
                                view.visibility = View.VISIBLE
                                if (!view.loggedReady) {
                                    view.loggedReady = true
                                    com.lutcamera.lutcamerapro.log.DoukLog.i(
                                        "HdrViewer", "HDR_VIEW",
                                        "pair" to (pair != null).toString()
                                    )
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(w, h)
                        )
                    }
                }
                if (editing || dragging || !hasHdr || !hdrFrameReady) {
                    if (shown != null || dragging) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 转到 90/270 度时把可用宽高对调，否则竖图旋转后会撑出屏幕
                        val quarter = (rotation % 180f) != 0f
                        val w = if (quarter) maxHeight else maxWidth
                        val h = if (quarter) maxWidth else maxHeight
                        val pageWidth = with(density) { maxWidth.toPx() }
                        // 上一张 / 下一张跟着手指一起位移，滑到哪一张都是连续的画面
                        if (dragX > 0f && prevShown != null) {
                            Image(
                                bitmap = prevShown,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(w, h)
                                    .graphicsLayer { translationX = dragX - pageWidth }
                            )
                        }
                        if (dragX < 0f && nextShown != null) {
                            Image(
                                bitmap = nextShown,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(w, h)
                                    .graphicsLayer { translationX = dragX + pageWidth }
                            )
                        }
                        // 有 HDR 层时，当前这张由 HdrBitmapView 出；新位图还没上屏
                        // 或正在淡出遮罩时，这里继续画缩略图，避免黑闪。
                        if (shown != null && (editing || !hasHdr || !hdrFrameReady ||
                                hdrOverlayAlpha > 0.01f)
                        ) {
                            Image(
                                bitmap = shown,
                                contentDescription = item.fileName,
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.High,
                                alpha = if (hasHdr) hdrOverlayAlpha else 1f,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(w, h)
                                    .graphicsLayer {
                                        scaleX = scale; scaleY = scale
                                        translationX = offsetX + dragX; translationY = offsetY
                                        rotationZ = rotation
                                    }
                            )
                        }
                    }
                    }
                } else if (!item.isVideo && !hasHdr) {
                    // 只有「没有 HDR 层、也没有缩略图」才是真的没图。
                    // HDR 图静止时画面由 SurfaceView 出，这里再画一句兜底文字会盖在照片上。
                    Text("原图未加载", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                }

                if (item.isVideo && !uiHidden) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { playVideo() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "播放视频",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
                }
            }

            // Live 播放层：系统 VideoView 足够播这段几秒的片段
            if (liveOpen && liveFile != null) {
                Dialog(
                    onDismissRequest = { liveOpen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                android.widget.VideoView(ctx).apply {
                                    setVideoPath(liveFile!!.absolutePath)
                                    setOnCompletionListener { liveOpen = false }
                                    start()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 640.dp)
                        )
                        IconButton(
                            onClick = { liveOpen = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                        ) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "关闭", tint = Color.White)
                        }
                    }
                }
            }

            // 顶部只保留返回、日期时间、旋转三个控件，不再铺全屏黑色渐变。
            // 白色图标在浅色照片上容易看不见，所以给按钮各加一枚很小的局部底衬。
            // 编辑器打开时编辑器窗口顶部会空出导航栏高度，这里必须整行收起，
            // 否则日期时间会从编辑器上方的缝里透出来。
            if (!uiHidden && !EditorChrome.open) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.26f))
                        ) {
                            Icon(
                                Icons.Rounded.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = GalleryFileOps.shortDateText(item.dateMillis) ?: "相机内照片",
                                    style = TextStyle(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        shadow = Shadow(
                                            color = Color.Black.copy(alpha = 0.55f),
                                            offset = Offset(0f, 1f),
                                            blurRadius = 6f
                                        )
                                    )
                                )
                                // Live 标识挪到日期旁边：不再压一秒画面，也更像系统相册的标记
                                if (isLive && !item.isVideo) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(Color.Black.copy(alpha = 0.30f))
                                            .border(
                                                0.7.dp,
                                                Color.White.copy(alpha = 0.28f),
                                                RoundedCornerShape(percent = 50)
                                            )
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { playLive() }
                                            .padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (liveBusy) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.size(9.dp),
                                                color = Color.White,
                                                strokeWidth = 1.3.dp
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFF2A15C))
                                            )
                                        }
                                        Text(
                                            "LIVE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            GalleryFileOps.timeText(item.dateMillis)?.let {
                                Text(
                                    it,
                                    style = TextStyle(
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.72f),
                                        shadow = Shadow(
                                            color = Color.Black.copy(alpha = 0.50f),
                                            offset = Offset(0f, 1f),
                                            blurRadius = 5f
                                        )
                                    )
                                )
                            }
                        }
                        IconButton(
                            onClick = { rotation = (rotation + 90f) % 360f },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.26f))
                        ) {
                            Icon(
                                Icons.Rounded.RotateRight,
                                contentDescription = "旋转视图",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
            }

            // 底部浮起的操作条：发送 / 收藏（星星） / 编辑 / 删除 / 更多
            if (!uiHidden) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .doukCardSurface(
                                shape = RoundedCornerShape(percent = 50),
                                haze = viewerHaze,
                                fillAlpha = 0.80f
                            )
                            .padding(horizontal = 7.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PillAction(
                            icon = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            label = if (favorite) "已收藏" else "收藏",
                            tint = if (favorite) DoukColors.accent else Color(0xFF4A4A4A),
                            onClick = {
                                favorite = GalleryFavorites.toggle(context, item.stableKey)
                                message = if (favorite) "已加入收藏" else "已取消收藏"
                            }
                        )
                        PillAction(
                            icon = Icons.Rounded.Edit,
                            label = if (item.isVideo) "视频 LUT" else "编辑",
                            onClick = {
                                if (item.isVideo) {
                                    videoOpen = true
                                } else {
                                    // 编辑器还要再解一张 4096 的图，先把 HDR 那两张放掉腾内存
                                    hdrPair = null
                                    hdrPq = null
                                    hdrKey = null
                                    hdrFrameKey = null
                                    needHdrReload = true
                                    editing = true
                                }
                            }
                        )
                        PillAction(
                            icon = Icons.Rounded.DeleteOutline,
                            label = "删除",
                            onClick = { confirmDelete = true }
                        )
                        PillAction(
                            icon = Icons.Rounded.MoreHoriz,
                            label = "更多",
                            onClick = { more = true }
                        )
                    }
                }
            }

            // 提示条独立浮在操作条上方，避免和 pill 抢同一块布局打架
            message?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 132.dp, start = 24.dp, end = 24.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.66f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (more) {
                // 参考图那样：窄卡贴在「更多」按钮右上方，不是一个居中的大长条；
                // 底下铺一层透明点击层，点空白就收起来
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            lastDismissAt = System.currentTimeMillis()
                            more = false
                        }
                )
                MoreMenuCard(
                    sendLabel = if (item.fromUsb) "保存" else "发送",
                    canRename = item.origin == "app",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 104.dp)
                        .width(204.dp),
                    onSend = {
                        more = false
                        if (item.fromUsb) {
                            if (!saving) {
                                saving = true
                                state.saveUsbPhoto(context, item.key, item.fileName) {
                                    message = it
                                    saving = false
                                }
                            }
                        } else {
                            share()
                        }
                    },
                    onAddTo = { more = false; addTo = true },
                    onDetail = { more = false; detail = true },
                    onRename = { more = false; renaming = true },
                    onShowOriginal = if (item.origin == "cam" && !item.isVideo && !originalLoaded) {
                        {
                            more = false
                            wantOriginal = true
                            scope.launch { fetchOriginal() }
                        }
                    } else null,
                    onZoomDetail = if (item.isVideo) null else {
                        {
                            more = false
                            zoomDetail = true
                        }
                    },
                    onDismiss = { more = false }
                )
            }

            if (confirmDelete) {
                ConfirmSheet(
                    title = "删除",
                    body = "确认删除这 1 项内容？删除只作用于应用下载目录里的副本，相机与手机相册的原图不受影响。",
                    confirmText = "删除",
                    onConfirm = { confirmDelete = false; deleteNow() },
                    onDismiss = { confirmDelete = false }
                )
            }

            if (addTo) {
                AddToSheet(
                    item = item,
                    readFiles = {
                        when (item.origin) {
                            "cam" -> {
                                val out = ArrayList<Pair<String, ByteArray>>()
                                state.cameraPhotoBytes(item.key)?.let { out.add(item.fileName to it) }
                                item.extraHandles.forEach { (h, n) ->
                                    state.cameraPhotoBytes(h)?.let { out.add(n to it) }
                                }
                                out
                            }
                            "usb" -> {
                                val out = ArrayList<Pair<String, ByteArray>>()
                                state.usbPhotoBytes(context, item.key)?.let { out.add(item.fileName to it) }
                                item.extraHandles.forEach { (h, n) ->
                                    state.usbPhotoBytes(context, h)?.let { out.add(n to it) }
                                }
                                out
                            }
                            "app" -> listOfNotNull(
                                GalleryFileOps.locateAppFile(item.fileName)
                                    ?.let { item.fileName to it.readBytes() }
                            )
                            else -> listOfNotNull(
                                runCatching {
                                    ContentUris.withAppendedId(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.key.toLong()
                                    )
                                }.getOrNull()?.let { uri ->
                                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                        ?.let { item.fileName to it }
                                }
                            )
                        }
                    },
                    onResult = {
                        message = it
                        addTo = false
                    },
                    onDismiss = { addTo = false }
                )
            }
        }
    }

    if (videoOpen) {
        // 看图层本身是一个全屏 Dialog（独立窗口），视频页直接写在它外面会被这个窗口盖住，
        // 看起来就是「点了没反应」。所以视频页自己再开一个全屏 Dialog，叠在最上面。
        Dialog(
            onDismissRequest = { videoOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            VideoLutScreen(
                item = item,
                onDismiss = { videoOpen = false },
                onExported = { name ->
                    videoOpen = false
                    message = "已导出 $name"
                    onChanged()
                }
            )
        }
    }

    if (editing) {
        PhotoEditorScreen(
            item = item,
            source = hd ?: bitmap,
            onDismiss = { editing = false },
            onSaved = { newName ->
                editing = false
                message = "已保存为 $newName"
                // 编辑器写出的是新文件，先让图库重新扫，再把大图换成编辑后的结果
                onChanged()
                scope.launch {
                    hd = withContext(Dispatchers.IO) { loadLargeBitmap(context, item) }
                }
            }
        )
    }

    if (detail) {
        DetailPage(
            item = item,
            onDismiss = { detail = false }
        )
    }

    // 细节查看：独立全屏窗口，手势归 Telephoto，本页的横滑/Live/分享不受影响。
    // 用当前这张已经解好的高清图；hd 还没跟上这张的话，就先用屏上这张。
    val zoomSource = if (hdKey == item.stableKey) (hd ?: bitmap) else bitmap
    if (zoomDetail && zoomSource != null) {
        ImageDetailScreen(
            item = item,
            bitmap = zoomSource,
            onDismiss = { zoomDetail = false }
        )
    }

    if (renaming) {
        RenameDialog(
            current = item.fileName,
            onDismiss = { renaming = false },
            onConfirm = { newName ->
                renaming = false
                val result = GalleryFileOps.renameAppFile(context, item.fileName, newName)
                if (result.isSuccess) {
                    message = "已重命名为 $newName"
                    onChanged()
                } else {
                    message = result.exceptionOrNull()?.message ?: "重命名失败"
                }
            }
        )
    }
}

@Composable
private fun PillAction(
    icon: ImageVector,
    label: String,
    tint: Color = Color(0xFF4A4A4A),
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 4.dp)
            .heightIn(min = 42.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(modifier = Modifier.height(1.dp))
        Text(label, fontSize = 10.sp, color = tint)
    }
}

/** 「更多」弹出的分组菜单：白色窄卡、最大圆角，照参考图贴在「更多」上方。 */
@Composable
private fun MoreMenuCard(
    sendLabel: String?,
    canRename: Boolean,
    modifier: Modifier = Modifier,
    onSend: () -> Unit,
    onAddTo: () -> Unit,
    onDetail: () -> Unit,
    onRename: () -> Unit,
    onShowOriginal: (() -> Unit)? = null,
    onZoomDetail: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Column(
        modifier = modifier
            .doukCardSurface(RoundedCornerShape(24.dp))
            .padding(vertical = 8.dp)
    ) {
        if (sendLabel != null) {
            MenuRow(
                if (sendLabel == "保存") Icons.Rounded.SaveAlt else Icons.Rounded.Share,
                sendLabel,
                onSend
            )
        }
        MenuRow(Icons.Rounded.Add, "保存到", onAddTo)
        if (onShowOriginal != null) {
            MenuRow(Icons.Rounded.Image, "显示原图", onShowOriginal)
        }
        if (onZoomDetail != null) {
            MenuRow(Icons.Rounded.ZoomIn, "细节查看", onZoomDetail)
        }
        MenuRow(Icons.Rounded.Info, "详情", onDetail)
        if (canRename) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(Color(0xFFEDEDED))
            )
            MenuRow(Icons.Rounded.DriveFileRenameOutline, "重命名", onRename)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = Color(0xFF333333), modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = Color(0xFF222222))
    }
}

/** 底部浮起的确认面板（删除用），照参考图的圆角大面板。 */
@Composable
private fun ConfirmSheet(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp)
                .doukCardSurface(RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF222222))
            Spacer(modifier = Modifier.height(10.dp))
            Text(body, fontSize = 13.sp, color = Color(0xFF666666), textAlign = TextAlign.Start, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetButton("取消", Color(0xFFF2F2F2), Color(0xFF333333), Modifier.weight(1f), onDismiss)
                SheetButton(confirmText, Color(0xFFF9E7E7), Color(0xFFD9363E), Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
private fun SheetButton(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 15.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

/** 保存到：按来源给不同出口。相机内三处落盘，手机内/应用内可复制或移动，避免重复保存。 */
@Composable
private fun AddToSheet(
    item: GalleryItem,
    readFiles: suspend () -> List<Pair<String, ByteArray>>,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val isCamera = item.origin == "cam" || item.origin == "usb"
    val appDir = GalleryFileOps.PHONE_FOLDER + "/" + (item.album ?: "连镜")

    fun allowed(name: String): Boolean {
        if (!isCamera || item.isVideo) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (DoukPrefs.saveFormat) {
            "JPG" -> ext in setOf("jpg", "jpeg", "heic", "heif")
            "RAW" -> ext in setOf("nef", "nrw", "dng")
            "JPG + RAW" -> ext in setOf("jpg", "jpeg", "heic", "heif", "nef", "nrw", "dng")
            else -> true
        }
    }

    /** 读文件 → 按格式挑 → 写进目标（相册目录或 SAF 目录） */
    fun deliver(provider: suspend () -> List<Pair<String, ByteArray>>, dir: String?, tree: Uri?, label: String) {
        if (busy) return
        busy = true
        scope.launch {
            val files = runCatching { provider() }.getOrNull().orEmpty()
            val picked = files.filter { allowed(it.first) }
            if (picked.isEmpty()) {
                busy = false
                onResult(
                    if (files.isEmpty()) "没能读取到原图，请重试"
                    else "按「从相机下载的格式」（${DoukPrefs.saveFormat}）没有可保存的文件"
                )
                return@launch
            }
            var ok = 0
            var lastErr = "未知错误"
            withContext(Dispatchers.IO) {
                picked.forEach { (name, bytes) ->
                    val result = if (tree != null) {
                        GalleryFileOps.writeBytesToTree(context, tree, name, bytes)
                    } else {
                        GalleryFileOps.writeBytesToGallery(
                            context, dir ?: GalleryFileOps.PHONE_FOLDER, name, bytes
                        )
                    }
                    if (result.isSuccess) ok++ else lastErr = result.exceptionOrNull()?.message ?: lastErr
                }
            }
            busy = false
            onResult(
                when {
                    ok == picked.size -> "已保存到$label（${ok} 个文件）"
                    ok > 0 -> "部分保存：$ok/${picked.size}，$lastErr"
                    else -> "保存失败：$lastErr"
                }
            )
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && pendingDelete.isNotEmpty()) {
            runCatching { pendingDelete.forEach { context.contentResolver.delete(it, null, null) } }
            pendingDelete = emptyList()
            onResult("已移动到应用相册，手机相册那份已删除")
        } else {
            pendingDelete = emptyList()
            onResult("已复制到应用相册；手机相册原图保留")
        }
    }

    fun saveFromSource(label: String, galleryDir: String, movePhone: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            val files = runCatching { readFiles() }.getOrNull().orEmpty()
            if (files.isEmpty()) {
                busy = false
                onResult("没能读取到原图，请重试")
                return@launch
            }
            var ok = 0
            var lastErr = "未知错误"
            withContext(Dispatchers.IO) {
                files.forEach { (name, bytes) ->
                    val r = GalleryFileOps.writeBytesToGallery(context, galleryDir, name, bytes)
                    if (r.isSuccess) ok++ else lastErr = r.exceptionOrNull()?.message ?: lastErr
                }
            }
            busy = false
            if (!movePhone || ok == 0) {
                onResult(
                    if (ok == files.size) "已保存到$label（$ok 个文件）"
                    else "已保存 $ok/${files.size}：$lastErr"
                )
                return@launch
            }
            val uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.key.toLong()
            )
            val deleted = runCatching { context.contentResolver.delete(uri, null, null) }.isSuccess
            if (deleted) {
                onResult("已移动到应用相册，手机相册那份已删除")
            } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                pendingDelete = listOf(uri)
                val sender = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                onResult("已复制到应用相册；系统版本不支持自动删原图，请在手机相册手动删除")
            }
        }
    }

    fun moveAppToAlbum() {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                GalleryFileOps.moveAppFileToAlbum(context, item.fileName, appDir)
            }
            busy = false
            onResult(
                result.fold(
                    { "已移动到应用相册：${it.name}" },
                    { "移动失败：${it.message}" }
                )
            )
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            deliver({ readFiles() }, null, uri, "自定义文件夹")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp)
                .doukCardSurface(RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text("保存到", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF222222))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                when {
                    isCamera -> "按「从相机下载的格式」（${DoukPrefs.saveFormat}）只保存选中的文件，RAW+JPG 会按设置挑。"
                    item.origin == "phone" -> "手机里的照片可以复制一份进应用，也可以移动进应用，不再重复保存。"
                    else -> "应用里的照片可以另存到手机相册，或整理进应用相册；移动不重复占空间。"
                },
                fontSize = 12.sp,
                color = Color(0xFF888888)
            )
            Spacer(modifier = Modifier.height(14.dp))

            if (isCamera) {
                AddRow("应用相册", "$appDir · 图库「应用内」可见") {
                    deliver({ readFiles() }, appDir, null, "应用相册")
                }
                AddRow("手机相册", GalleryFileOps.PHONE_ALBUM + " · 系统相册可见") {
                    deliver({ readFiles() }, GalleryFileOps.PHONE_ALBUM, null, "手机相册")
                }
                AddRow("自定义文件夹", "选任意文件夹，直接存原文件") { pickFolder.launch(null) }
            } else if (item.origin == "phone") {
                AddRow("保存到应用相册", "$appDir · 复制一份，手机相册原图保留") {
                    saveFromSource("应用相册", appDir)
                }
                AddRow("移动到应用内", "复制后删掉手机相册那份，不重复占空间") {
                    saveFromSource("应用相册", appDir, movePhone = true)
                }
            } else {
                AddRow("保存到手机相册", GalleryFileOps.PHONE_ALBUM + " · 系统相册可见") {
                    saveFromSource("手机相册", GalleryFileOps.PHONE_ALBUM)
                }
                AddRow("移动到应用相册", "$appDir · 整理进相册，原位置不再保留") {
                    moveAppToAlbum()
                }
            }

            if (busy) {
                Spacer(modifier = Modifier.height(10.dp))
                Text("正在下载/写入，请稍等…", fontSize = 12.sp, color = Color(0xFF139B6A))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("取消", fontSize = 13.sp, color = Color(0xFF666666))
            }
        }
    }
}

@Composable
private fun AddRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF7F7F7))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF222222))
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, fontSize = 11.sp, color = Color(0xFF999999))
        }
        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color(0xFF999999), modifier = Modifier.size(18.dp))
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** 详情页：左边灰色标签、右边蓝色值，和参考图一致。 */
@Composable
private fun DetailPage(item: GalleryItem, onDismiss: () -> Unit) {
    // HDR 判断：应用内直接探原文件；手机相册里的图先落一份缓存副本再探（和打开大图时同一套逻辑）。
    // 以前只认 origin=="app"，手机里的 HDR 图一律落到 null，详情里就永远是「未检测」。
    var hdrFlag by remember { mutableStateOf<Boolean?>(null) }
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(item.stableKey) {
        hdrFlag = withContext(Dispatchers.IO) {
            if (item.isVideo) return@withContext false
            val file = when (item.origin) {
                "app" -> GalleryFileOps.locateAppFile(item.fileName)
                "phone" -> runCatching {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.key.toLong()
                    )
                    ImageDecode.materialize(ctx, uri, "hdr-${item.key}")
                }.getOrNull()
                else -> null
            }
            file?.let { ImageDecode.probeHdr(it) }
        }
    }

    val context = LocalContext.current
    var meta by remember { mutableStateOf<GalleryFileOps.Meta?>(null) }

    androidx.compose.runtime.LaunchedEffect(item.stableKey) {
        meta = withContext(Dispatchers.IO) {
            when (item.origin) {
                "app" -> GalleryFileOps.locateAppFile(item.fileName)
                    ?.let { f -> GalleryFileOps.metaOfFile(f, item.fileName) }
                "phone" -> GalleryFileOps.metaOfUri(
                    context,
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.key.toLong()),
                    item.fileName,
                    item.sizeBytes,
                    null
                )
                else -> null
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // 详情页是白底独立 Dialog；Dialog 在部分 ROM 上盖不住状态栏，
        // 这里同时把 Activity decor 和根布局顶层 chrome 刷成白色，上下不再露出黑边。
        DisposableEffect(Unit) {
            DetailChrome.open = true
            val activity = context.findActivity()
            val oldDecorBg = activity?.window?.decorView?.background
            activity?.window?.decorView?.setBackgroundColor(android.graphics.Color.WHITE)
            val controller = activity?.window?.let {
                WindowCompat.getInsetsController(it, it.decorView)
            }
            val oldLightStatus = controller?.isAppearanceLightStatusBars
            val oldLightNav = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = true
            controller?.isAppearanceLightNavigationBars = true
            onDispose {
                DetailChrome.open = false
                if (activity != null) activity.window.decorView.background = oldDecorBg
                oldLightStatus?.let { controller?.isAppearanceLightStatusBars = it }
                oldLightNav?.let { controller?.isAppearanceLightNavigationBars = it }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Color(0xFF222222))
                }
                Text(
                    "详情",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF222222),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            val m = meta
            DetailRow(
                "拍摄时间",
                GalleryFileOps.dateText(m?.takenAt ?: item.dateMillis) ?: "相机内未提供拍摄时间"
            )
            DetailRow(
                "文件信息",
                buildString {
                    append(item.fileName)
                    append("\n")
                    append(GalleryFileOps.fileSizeText(item.sizeBytes))
                    m?.let { meta2 ->
                        if (meta2.width != null && meta2.height != null) {
                            append("   ${meta2.width}×${meta2.height}px")
                        }
                    }
                }
            )
            if (m?.camera != null || m?.exposureLine != null) {
                DetailRow(
                    "拍摄参数",
                    listOfNotNull(m?.camera, m?.lens, m?.exposureLine).joinToString("\n")
                )
            }
            DetailRow(
                "文件路径",
                when (item.origin) {
                    "app" -> m?.path ?: "内部存储/${GalleryFileOps.PHONE_FOLDER}/${item.fileName}"
                    "phone" -> m?.path ?: item.fileName
                    else -> "相机机身存储 · 通过 PTP 读取，未落到本机"
                }
            )
            // HDR 检测（替换掉原来的 EXIF 长列表）
            DetailRow(
                "HDR",
                when {
                    item.isVideo -> "视频 · 不做 HDR 检测"
                    hdrFlag == true -> "是 · 带 HDR 增益图（Ultra HDR），HDR 屏上高光会更亮"
                    hdrFlag == false -> "否 · 普通 SDR 图片"
                    item.origin == "cam" || item.origin == "usb" ->
                        "未检测 · 相机内原图请先保存到手机"
                    else -> "未检测 · 文件读不到，可能已被移动或删除"
                }
            )
            // IPTC 字段一个都没有就整行不显示，不再写「这张没有写入 IPTC」这种废话
            val iptcLines = buildList {
                m?.title?.let { add("标题：$it") }
                m?.keywords?.let { add("关键词：$it") }
                m?.artist?.let { add("作者：$it") }
                m?.copyright?.let { add("版权：$it") }
            }
            if (iptcLines.isNotEmpty()) {
                DetailRow("IPTC", iptcLines.joinToString("\n"))
            }
            // 有 EXIF GPS 才给「拍摄地点」和下面那张小地图
            val latValue = m?.lat
            val lonValue = m?.lon
            if (latValue != null && lonValue != null) {
                val openMap = {
                    val geo = Uri.parse("geo:$latValue,$lonValue?q=$latValue,$lonValue")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                        .onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$latValue,$lonValue"))
                                )
                            }
                        }
                    Unit
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "拍摄地点：",
                        fontSize = 13.sp,
                        color = Color(0xFFAAAAAA),
                        maxLines = 1,
                        modifier = Modifier.width(100.dp)
                    )
                    Icon(
                        Icons.Rounded.Place,
                        contentDescription = null,
                        tint = Color(0xFF3A7BD5),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", latValue, lonValue),
                        fontSize = 13.sp,
                        color = Color(0xFF3A7BD5),
                        lineHeight = 20.sp
                    )
                }
                MapThumb(
                    lat = latValue,
                    lon = lonValue,
                    onClick = openMap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(172.dp)
                )
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 13.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                "$label：",
                fontSize = 13.sp,
                color = Color(0xFFAAAAAA),
                maxLines = 1,
                modifier = Modifier.width(100.dp)
            )
            Text(value, fontSize = 13.sp, color = Color(0xFF3A7BD5), lineHeight = 20.sp)
        }
        DetailDivider()
    }
}

/** 详情页行之间的细分隔线，照小米相册那种几乎看不见的浅灰。 */
@Composable
private fun DetailDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp)
            .height(0.6.dp)
            .background(Color(0xFFF1F1F1))
    )
}

/** 详情页里带一个可点动作的行（比如经纬度后面的「地图查看」）。 */
/**
 * 详情页里的迷你地图：按经纬度取 3×3 张地图瓦片拼一张，裁出中间一条，
 * 正中间压一个定位针。点一下交给系统地图打开。
 *
 * 瓦片走高德公开瓦片接口（国内可直连）；OpenStreetMap 在国内网络下基本拉不到，
 * 所以不用它。坐标先做 WGS-84 → GCJ-02 转换，否则针会偏几百米。
 * 8 秒内没凑齐就别再等，直接显示灰底提示，不假装有图。
 */
@Composable
private fun MapThumb(
    lat: Double,
    lon: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var map by remember(lat, lon) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(lat, lon) { mutableStateOf(false) }
    // 默认不自动联网：照片 GPS 是敏感信息，只有用户主动点“加载在线地图”才把坐标发给高德。
    var requested by remember(lat, lon) { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF2F2F2))
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val wPx = with(density) { maxWidth.toPx().toInt() }
        val hPx = with(density) { maxHeight.toPx().toInt() }
        androidx.compose.runtime.LaunchedEffect(lat, lon, wPx, hPx, requested) {
            if (!requested) return@LaunchedEffect
            val loaded = withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(9000L) { loadTileMap(lat, lon, wPx, hPx) }
            }
            if (loaded != null) map = loaded else failed = true
        }
        map?.let {
            Image(
                bitmap = it,
                contentDescription = "地图预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (map == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        failed -> "这里暂时画不出地图，点一下用系统地图看"
                        !requested -> "点击加载在线地图（会向高德发送照片坐标）"
                        else -> "地图加载中…"
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        Icon(
            Icons.Rounded.Place,
            contentDescription = null,
            tint = Color(0xFFD9363E),
            modifier = Modifier.align(Alignment.Center).size(24.dp)
        )
        Box(
            modifier = Modifier.fillMaxSize().clickable {
                if (map == null && !requested) requested = true else onClick()
            }
        )
    }
}

/** WGS-84 → GCJ-02：国内地图瓦片用的是加密坐标，不转换定位针会偏 300–600 米。 */
private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
    // 国外坐标不做偏移
    if (lon < 73.66 || lon > 135.05 || lat < 3.86 || lat > 53.55) return lat to lon
    val a = 6378245.0
    val ee = 0.00669342162296594323
    var dLat = transformLat(lon - 105.0, lat - 35.0)
    var dLon = transformLon(lon - 105.0, lat - 35.0)
    val radLat = lat / 180.0 * Math.PI
    var magic = Math.sin(radLat)
    magic = 1 - ee * magic * magic
    val sqrtMagic = Math.sqrt(magic)
    dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
    dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
    return (lat + dLat) to (lon + dLon)
}

private fun transformLat(x: Double, y: Double): Double {
    var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
    ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    return ret
}

private fun transformLon(x: Double, y: Double): Double {
    var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
    ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    return ret
}

/** 把中心点周围的 3×3 张瓦片拼起来，按卡片宽高比裁中间一条，中心就是给定坐标。 */
private fun loadTileMap(lat: Double, lon: Double, wPx: Int, hPx: Int): ImageBitmap? {
    if (wPx <= 0 || hPx <= 0) return null
    return try {
        val (gLat, gLon) = wgs84ToGcj02(lat, lon)
        val z = 16
        val n = Math.pow(2.0, z.toDouble())
        val tx = (gLon + 180.0) / 360.0 * n
        val rad = Math.toRadians(gLat)
        val ty = (1.0 - kotlin.math.ln(kotlin.math.tan(rad) + 1.0 / kotlin.math.cos(rad)) / Math.PI) / 2.0 * n
        val cx = kotlin.math.floor(tx).toInt()
        val cy = kotlin.math.floor(ty).toInt()
        val tile = 256
        val full = android.graphics.Bitmap.createBitmap(
            tile * 3, tile * 3, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(full)
        val paint = android.graphics.Paint()
        var fails = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (fails >= 4) break
                val bmp = downloadTile(
                    "https://webrd0${(dx + 2).coerceIn(1, 4)}.is.autonavi.com/appmaptile" +
                        "?lang=zh_cn&size=1&scale=1&style=8&x=${cx + dx}&y=${cy + dy}&z=$z"
                )
                if (bmp == null) {
                    fails++
                    continue
                }
                fails = 0
                canvas.drawBitmap(bmp, ((dx + 1) * tile).toFloat(), ((dy + 1) * tile).toFloat(), paint)
            }
        }
        if (fails >= 4) return null
        // 定位针在拼接图里的位置（拼接图以中心瓦片为第 2 列第 2 行）
        val pinX = (tx - cx + 1.0) * tile
        val pinY = (ty - cy + 1.0) * tile
        val cropW = full.width
        val cropH = (cropW.toFloat() * hPx / wPx).toInt().coerceIn(64, full.height)
        // 横向上整幅拼图都保留，竖向上让定位针落在正中
        val top = (pinY - cropH / 2.0).toInt().coerceIn(0, maxOf(0, full.height - cropH))
        android.graphics.Bitmap.createBitmap(full, 0, top, cropW, cropH).asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}

private fun downloadTile(url: String): android.graphics.Bitmap? = runCatching {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android) LianJing/1.0")
        .header("Referer", "https://www.amap.com/")
        .build()
    DoukHttp.client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@runCatching null
        response.body?.byteStream()?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        }
    }
}.getOrNull()

/** 大图缓存只留当前和下一张，40MP 长边 7000 时两张 ARGB 已接近 260MB，不能再多。 */
private fun trimFullCache(cache: MutableMap<String, ImageBitmap>, keep: String) {
    // 40MP 单张 ARGB 约 160MB，只保留当前这一张，翻页时释放上一张再解下一张。
    if (cache.size <= 1) return
    cache.keys.filter { it != keep }.forEach { cache.remove(it) }
}

/**
 * 全屏用的大图：应用内直接解原文件到长边 MAX_VIEW_SIDE，手机内让系统按同样上限出缩略。
 * 相机内没有本地文件。
 */
private fun loadLargeBitmap(context: Context, item: GalleryItem): ImageBitmap? = runCatching {
    // 6144 长边对 40MP 仍保留细节；比 8200 少约一半像素，单张 ARGB 从约 180MB 降到约 100MB。
    // 大图缓存只留当前 1 张，避免两张 40MP ARGB 同时驻留把内存吃满。
    val screenLong = maxOf(
        runCatching {
            maxOf(
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels
            )
        }.getOrDefault(2400),
        MAX_VIEW_SIDE
    ).coerceIn(1600, MAX_VIEW_SIDE)
    when (item.origin) {
        "app" -> {
            val file = GalleryFileOps.locateAppFile(item.fileName)
            if (file == null) null else ImageDecode.decodeFile(file, screenLong)
        }
        "phone" -> {
            val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = ContentUris.withAppendedId(collection, item.key.toLong())
            ImageDecode.decodeUri(context, uri, screenLong)
        }
        else -> null
    }
}.getOrNull()

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val ext = current.substringAfterLast('.', "")
    var base by remember { mutableStateOf(current.substringBeforeLast('.')) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it.take(80) },
                    singleLine = true,
                    label = { Text("文件名") },
                    suffix = if (ext.isNotEmpty()) { { Text(".$ext") } } else null
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "只改应用下载目录里的这一份，相机和系统相册里的原图不受影响。",
                    fontSize = 11.sp,
                    color = Color(0xFF999999)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = base.isNotBlank() && "$base.$ext" != current,
                onClick = { onConfirm(if (ext.isNotEmpty()) "$base.$ext" else base) }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}
