package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import java.io.File
import kotlin.math.min

/**
 * Live 片段播放器：TextureView + MediaPlayer。
 *
 * 为什么不用 VideoView：VideoView 内部是 SurfaceView，会把自己那块区域「挖空」，
 * 首帧出来之前透出的是宿主窗口的黑色 —— 长按的瞬间就会看到一下黑屏。
 * TextureView 是合成到视图层里的，没有内容时完全透明，底下那张静帧继续可见；
 * 这里再等 SurfaceTexture 真正出帧（onSurfaceTextureUpdated）才把 alpha 从 0 拉起来，
 * 于是观感是「静帧直接动起来」，没有黑色过渡。
 */
class LiveClipView(context: Context) : FrameLayout(context) {

    private val texture: TextureView = TextureView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        alpha = 0f
    }

    private var player: MediaPlayer? = null
    private var pendingPath: String? = null
    private var firstFrame = false
    private var videoWidth = 0
    private var videoHeight = 0

    /** 播完回调（主线程）。 */
    var onFinished: (() -> Unit)? = null

    /** 解码失败回调，让界面给一句提示，而不是停在黑块上。 */
    var onError: (() -> Unit)? = null

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(texture)
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
                if (!firstFrame) {
                    firstFrame = true
                    texture.animate().alpha(1f).setDuration(120L).start()
                }
            }
        }
    }

    /** 开始播一段本地片段；重复调用会先停掉上一段。 */
    fun play(file: File) {
        releasePlayer()
        pendingPath = file.absolutePath
        firstFrame = false
        texture.alpha = 0f
        videoWidth = 0
        videoHeight = 0
        prepareIfReady()
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
                applyFitMatrix()
            }
            mp.setOnPreparedListener { p ->
                p.start()
                applyFitMatrix()
            }
            mp.setOnCompletionListener {
                releasePlayer()
                onFinished?.invoke()
            }
            mp.setOnErrorListener { _, _, _ ->
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

    /** 按「等比居中填满」算变换矩阵，画面不会因为宽高比不同被拉扁。 */
    private fun applyFitMatrix() {
        val vw = texture.width
        val vh = texture.height
        if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = min(vw.toFloat() / videoWidth, vh.toFloat() / videoHeight)
        val dx = (vw - videoWidth * scale) / 2f
        val dy = (vh - videoHeight * scale) / 2f
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        texture.setTransform(matrix)
    }

    private fun releasePlayer() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.reset() }
            runCatching { p.release() }
        }
        player = null
    }

    /** 界面收起时调用：彻底释放解码器，别让片段在后台继续跑。 */
    fun stop() {
        pendingPath = null
        releasePlayer()
        texture.alpha = 0f
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
