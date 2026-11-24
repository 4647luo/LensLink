package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.util.AttributeSet
import android.widget.ImageView

/**
 * 标准 HDR 图片视图。
 *
 * Android 14 起，系统图片解码器会把 Ultra HDR JPEG 解成「SDR 底图 + Gainmap」，
 * 只有标准 Drawable / ImageView 在 HDR 窗口里绘制时，系统合成器才会把 Gainmap
 * 真正合成进画面。之前这里用自定义 Canvas 画 Bitmap，绕过了 Drawable 的
 * Gainmap 处理，编辑器里看起来就和普通 SDR 完全一样；现在改成 ImageView
 * + Matrix，缩放、平移、旋转不变，HDR 合成交回系统。
 */
internal class HdrBitmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    private var bitmap: Bitmap? = null
    private var ownedBitmap: Bitmap? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var quarter = false

    private val matrix = Matrix()
    private var lastFrameTag: String? = null

    var onFrameReady: ((String?) -> Unit)? = null

    /** 和旧 HdrImageView 保持同一套调用契约：当前图像 key 与首帧日志标记。 */
    var sourceTag: String? = null
    var loggedReady = false

    init {
        scaleType = ScaleType.MATRIX
        setBackgroundColor(Color.TRANSPARENT)
        adjustViewBounds = false
    }

    fun setBitmap(bmp: Bitmap?) {
        if (bmp == null) {
            clear()
            return
        }
        if (bitmap === bmp) return
        val old = ownedBitmap
        ownedBitmap = null
        bitmap = bmp
        super.setImageBitmap(bmp)
        applyTransform()
        if (old != null && old !== bmp && !old.isRecycled) {
            runCatching { old.recycle() }
        }
        invalidate()
    }

    /**
     * 如果 base 自带 Gainmap（BitmapFactory 解 Ultra HDR 时会挂上），直接交给系统；
     * 如果没有（例如编辑器临时生成的预览），把 gain 挂到 base 的副本上。
     */
    fun setGainPair(
        base: Bitmap?,
        gain: Bitmap?,
        ratioMaxValue: Float,
        ratioMinValue: Float,
        gammaValue: Float,
        epsilonSdr: Float,
        epsilonHdr: Float,
        displayRatioFull: Float,
        minDisplayRatioValue: Float
    ) {
        if (base == null) { clear(); return }
        if (Build.VERSION.SDK_INT >= 34 && base.gainmap == null && gain != null) {
            val attached = runCatching {
                attachGainmap(
                    base, gain,
                    ratioMaxValue, ratioMinValue, gammaValue,
                    epsilonSdr, epsilonHdr,
                    displayRatioFull, minDisplayRatioValue
                )
            }.getOrNull()
            if (attached != null) {
                val old = ownedBitmap
                ownedBitmap = attached
                bitmap = attached
                super.setImageBitmap(attached)
                applyTransform()
                if (old != null && old !== attached && !old.isRecycled) {
                    runCatching { old.recycle() }
                }
                invalidate()
                return
            }
        }
        setBitmap(base)
    }

    private fun attachGainmap(
        base: Bitmap,
        gain: Bitmap,
        ratioMax: Float,
        ratioMin: Float,
        gamma: Float,
        epsSdr: Float,
        epsHdr: Float,
        displayRatioFull: Float,
        minDisplayRatio: Float
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < 34) return null
        val gw = gain.width
        val gh = gain.height
        if (gw <= 0 || gh <= 0) return null

        // Gainmap 内容必须是单通道；很多 ROM 会把 GainMap JPEG 解成 ARGB 灰度，
        // 这里统一取灰度写进 ALPHA_8，避免把 RGB 当成 boost 造成整张图过曝。
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

        val gm = android.graphics.Gainmap(alpha)
        gm.setRatioMin(ratioMin, ratioMin, ratioMin)
        gm.setRatioMax(ratioMax, ratioMax, ratioMax)
        gm.setGamma(gamma, gamma, gamma)
        gm.setEpsilonSdr(epsSdr, epsSdr, epsSdr)
        gm.setEpsilonHdr(epsHdr, epsHdr, epsHdr)
        gm.setDisplayRatioForFullHdr(displayRatioFull)
        gm.setMinDisplayRatioForHdrTransition(minDisplayRatio)

        val mutable = base.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        mutable.setGainmap(gm)
        return mutable
    }

    fun setTransform(z: Float, px: Float, py: Float, q: Boolean) {
        if (zoom == z && panX == px && panY == py && quarter == q) return
        zoom = z.coerceAtLeast(1f)
        panX = px
        panY = py
        quarter = q
        applyTransform()
        invalidate()
    }

    fun clear() {
        val old = ownedBitmap
        bitmap = null
        ownedBitmap = null
        super.setImageDrawable(null)
        imageMatrix = Matrix()
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
        invalidate()
    }

    private fun applyTransform() {
        val bmp = bitmap ?: return
        if (bmp.isRecycled || width <= 0 || height <= 0) return
        val bw = if (quarter) bmp.height.toFloat() else bmp.width.toFloat()
        val bh = if (quarter) bmp.width.toFloat() else bmp.height.toFloat()
        val fit = minOf(width / bw, height / bh)
        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(fit * zoom, fit * zoom)
        if (quarter) matrix.postRotate(90f)
        matrix.postTranslate(width / 2f + panX, height / 2f + panY)
        super.setImageMatrix(matrix)
    }

    override fun onDraw(canvas: Canvas) {
        // 不在空视图上先铺黑色：编辑器图片区是浅色背景，未出首帧时铺黑会
        // 看起来像整个白色界面突然变黑。真正的图片区域由 ImageView 自己画。
        super.onDraw(canvas)
        val tag = sourceTag ?: return
        if (bitmap == null || tag == lastFrameTag) return
        lastFrameTag = tag
        post { onFrameReady?.invoke(tag) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
        invalidate()
    }
}
