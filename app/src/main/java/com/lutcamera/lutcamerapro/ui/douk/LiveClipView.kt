package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import java.io.File

/**
 * Live 片段播放器：TextureView + MediaPlayer。
 *
 * 为什么不用 VideoView：VideoView 内部是 SurfaceView，会把自己那块区域「挖空」，
 * 首帧出来之前透出的是宿主窗口的黑色 —— 长按的瞬间就会看到一下黑屏。
 * TextureView 是合成到视图层里的，没有内容时完全透明，底下那张静帧继续可见；
 * 这里再等 SurfaceTexture 真正出帧（onSurfaceTextureUpdated）才把 alpha 从 0 拉起来，
 * 于是观感是「静帧直接动起来」，没有黑色过渡。
 *
 * 「长按秒播」的做法：进图时就把尾部视频切出来并 prepare（不 start），
 * 长按只负责 start()，省掉抽取 + 解析这两段最耗时的等待。
 */
class LiveClipView(context: Context) : FrameLayout(context) {

    private val texture: TextureView = TextureView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        alpha = 0f
    }

    private var player: MediaPlayer? = null
    private var pendingPath: String? = null
    /** 当前已经 prepare 过的文件，重复 prepare 同一段直接跳过 */
    private var preparedPath: String? = null
    private var firstFrame = false
    private var videoWidth = 0
    private var videoHeight = 0
    /** 视频自带旋转（竖拍 MOV 常见 90/270）。MediaPlayer 会把画面转正，但上报的宽高仍是编码尺寸。 */
    private var rotationDeg = 0
    /** 用真实首帧的方向兜底：某些 HEVC 的 display matrix 读不到，旋转恒为 0 */
    private var frameW = 0
    private var frameH = 0

    /** 播完回调（主线程）。 */
    var onFinished: (() -> Unit)? = null

    /** 解码失败回调，让界面给一句提示，而不是停在黑块上。 */
    var onError: (() -> Unit)? = null

    /** 视频真正显示出来的尺寸（已按旋转转正）。 */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(texture)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyFitMatrix() }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                prepareIfReady()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyFitMatrix()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                if (!firstFrame) firstFrame = true
                // 只有明确「要播放了」才显示。预加载 seekTo(0) 也会吐一帧，
                // 那帧如果直接拉起来 alpha，静帧上就会再叠一张视频首帧 —— 看起来像「打开两个」。
                texture.alpha = if (visible) 1f else 0f
            }
        }
    }

    /**
     * 预加载：把视频准备好、停在第 0 帧，但不显示也不占用画面。
     * 长按的时候 [startNow] 一叫就出画面，不用再等抽取/解析。
     */
    fun prepare(file: File) {
        val path = file.absolutePath
        if (preparedPath == path && player != null) return
        releasePlayer()
        pendingPath = path
        preparedPath = null
        wantPlay = false
        rotationDeg = readRotation(file)
        frameW = 0
        frameH = 0
        probeFrameDirection(file)
        visible = false
        preheating = false
        firstFrame = false
        texture.alpha = 0f
        videoWidth = 0
        videoHeight = 0
        prepareIfReady()
    }

    /** 长按已经按下、但 prepare 还没完成时记下来，prepared 一到就起播 */
    private var wantPlay = false

    /** 画面是否允许显示：预加载阶段一律透明，避免视频首帧叠在静帧上 */
    private var visible = false

    /** 预加载空跑阶段：此时播完不算「用户看完」，别去关播放态 */
    private var preheating = false

    /** 不等显示，先把播放跑起来（手指按下就叫）。阈值到了 [reveal] 直接看到在动 */
    fun warmStart() {
        wantPlay = true
        preheating = false
        val p = player ?: return
        if (preparedPath == null) return
        runCatching {
            if (p.currentPosition > 0) p.seekTo(0)
            if (!p.isPlaying) p.start()
        }
    }

    /** 让画面可见（长按判定通过时调用）。 */
    fun reveal() {
        visible = true
        if (firstFrame) texture.alpha = 1f
    }

    /** 从头开始播（长按 / 点 LIVE 胶囊）。 */
    fun startNow() {
        warmStart()
        reveal()
    }

    /** 停住并回到第一帧，但保留准备好的播放器，下次长按还是秒起。 */
    fun rewind() {
        wantPlay = false
        visible = false
        // 还在 prepare 途中就 seek 会撞 IllegalState，等 onPrepared 自己停在第 0 帧就行
        if (preparedPath != null) {
            runCatching {
                player?.let {
                    if (it.isPlaying) it.pause()
                    it.seekTo(0)
                }
            }
        }
        texture.alpha = 0f
        firstFrame = false
    }

    /**
     * 读视频旋转角。MediaPlayer 在 Surface 模式下会把画面转正，
     * 但 [MediaPlayer.getVideoWidth] / 回调报的是编码尺寸，不换过来就会把竖拍片段当横的 fit，
     * 结果横图缩小一条、竖图撑满一屏。
     */
    private fun readRotation(file: File): Int = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val raw = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            ((raw % 360) + 360) % 360
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrDefault(0)

    /**
     * 抽一张缩略首帧量方向。HEVC 里 display matrix 有时读不到（小米横拍的动图就是这样：
     * 编码 1008x1792、旋转 90、实际是横的），只看 rotation 会把横视频当竖的塞进横矩形，
     * 结果只露出中间一条 —— 用户看到的「横版 Live 截一半」就是这个。
     */
    private fun probeFrameDirection(file: File) {
        Thread {
            runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(file.absolutePath)
                    val bmp: Bitmap? = mmr.getScaledFrameAtTime(
                        0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        256,
                        256
                    )
                    if (bmp != null) {
                        frameW = bmp.width
                        frameH = bmp.height
                        texture.post { applyFitMatrix() }
                    }
                } finally {
                    runCatching { mmr.release() }
                }
            }
        }.start()
    }

    private fun prepareIfReady() {
        val path = pendingPath ?: return
        val surfaceTexture = texture.surfaceTexture ?: return
        if (player != null) return
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setSurface(Surface(surfaceTexture))
            mp.setDataSource(path)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                notifyVideoSize()
                applyFitMatrix()
                texture.post { applyFitMatrix() }
            }
            mp.setOnPreparedListener {
                preparedPath = path
                // 预加载阶段先空跑一小段（画面是透明的）：MediaPlayer 要跑起来之后才会把
                // 旋转 / buffer 尺寸配置写进 Surface，否则长按起播的头几帧会先上屏一帧
                // 没转过的竖 buffer —— 横图被压成一条，看着像「打开了另一张图」。
                runCatching { it.start() }
                preheating = true
                texture.postDelayed({
                    if (preheating) {
                        preheating = false
                        if (!visible && !wantPlay) {
                            runCatching { if (it.isPlaying) it.pause() }
                            runCatching { it.seekTo(0) }
                        }
                    }
                }, 200)
                if (wantPlay) runCatching { it.start() }
                notifyVideoSize()
                applyFitMatrix()
                texture.post { applyFitMatrix() }
            }
            mp.setOnCompletionListener {
                // 播完不要 release：保留解码器，下一次长按还能马上起
                runCatching { it.seekTo(0) }
                texture.alpha = 0f
                visible = false
                firstFrame = false
                // 预加载空跑跑完不算用户看完，别把播放态关掉
                if (!preheating) onFinished?.invoke()
                preheating = false
            }
            mp.setOnErrorListener { _, what, extra ->
                com.lutcamera.lutcamerapro.log.DoukLog.i(
                    "HdrViewer", "LIVE_ERROR",
                    "what" to what, "extra" to extra, "path" to path
                )
                releasePlayer()
                onError?.invoke()
                true
            }
            mp.prepareAsync()
        }.onFailure {
            releasePlayer()
            onError?.invoke()
        }
    }

    private fun notifyVideoSize() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val swap = needSwap()
        val shownW = if (swap) videoHeight else videoWidth
        val shownH = if (swap) videoWidth else videoHeight
        onVideoSize?.invoke(shownW, shownH)
    }

    /** 首帧方向优先；没有首帧时退回旋转角（会不会误判就靠它兜底） */
    private fun needSwap(): Boolean {
        if (frameW > 0 && frameH > 0 && videoWidth > 0 && videoHeight > 0) {
            return (frameW > frameH) != (videoWidth > videoHeight)
        }
        return rotationDeg == 90 || rotationDeg == 270
    }

    private fun applyFitMatrix() {
        val vw = texture.width
        val vh = texture.height
        if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val swap = needSwap()
        val shownW = if (swap) videoHeight else videoWidth
        val shownH = if (swap) videoWidth else videoHeight
        val scale = maxOf(vw.toFloat() / shownW, vh.toFloat() / shownH)
        val dx = (vw - shownW * scale) / 2f
        val dy = (vh - shownH * scale) / 2f
        // MediaPlayer 写进 SurfaceTexture 时，系统已按 surface 的默认 buffer（等于 view 尺寸）
        // 把画面缩放填满了。上面那个 maxOf 的 CROP 系数是按「视频原始像素」推的，再 apply 一次
        // 就等于把画面又放大 1.43 倍，长按瞬间整屏推近还裁掉一圈边。
        // 视频和照片同比例时正确矩阵就是单位矩阵，交给播放器自己铺满。
        val sameAspect = kotlin.math.abs(shownW.toFloat() / shownH - vw.toFloat() / vh) < 0.01f
        val matrix = Matrix()
        if (!sameAspect) {
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
        }
        texture.setTransform(matrix)
        com.lutcamera.lutcamerapro.log.DoukLog.i(
            "HdrViewer", "LIVE_MATRIX",
            "rot" to rotationDeg, "video" to "${videoWidth}x$videoHeight",
            "frame" to "${frameW}x$frameH", "swap" to swap.toString(),
            "view" to "${vw}x$vh", "shown" to "${shownW}x${shownH}"
        )
    }

    private fun releasePlayer() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.reset() }
            runCatching { p.release() }
        }
        player = null
        preparedPath = null
    }

    /** 界面收起时调用：彻底释放解码器，别让片段在后台继续跑。 */
    fun stop() {
        pendingPath = null
        visible = false
        releasePlayer()
        texture.alpha = 0f
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
