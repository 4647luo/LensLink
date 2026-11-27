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

    private var matrix = Matrix()
    private var lastFrameTag: String? = null

    private var gainAlphaCache: Bitmap? = null
    private var gainAlphaSource: java.lang.ref.WeakReference<Bitmap>? = null
    private var gainAlphaMaxW = 0
    private var gainAlphaMaxH = 0

    /** SDR 兜底合成：单线程队列 + 序号，只上屏最后一次请求的结果 */
    private val bakeQueue = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "hdr-bake").apply { isDaemon = true }
    }
    private var bakeSeq = 0
    private var lastBakeSig = 0

    var onFrameReady: ((String?) -> Unit)? = null

    /** 保持与旧版 HDR 视图同一套调用契约：当前图像 key 与首帧日志标记。 */
    var sourceTag: String? = null
    var loggedReady = false

    init {
        scaleType = ScaleType.MATRIX
        setBackgroundColor(Color.TRANSPARENT)
        adjustViewBounds = false
    }

    fun setBitmap(bmp: Bitmap?, own: Boolean = false) {
        if (bmp == null) {
            clear()
            return
        }
        if (bitmap === bmp) return
        val old = ownedBitmap
        ownedBitmap = if (own) bmp else null
        bitmap = bmp
        // 上一次若走过系统 Gainmap 路径，scaleType 会被改成 FIT_CENTER；
        // 回到自定义 Matrix 绘制（缩放/平移/旋转都靠它）。
        scaleType = ScaleType.MATRIX
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
        minDisplayRatioValue: Float,
        /** 当前「高光层次」0–1；只影响 SDR 兜底合成的膝点，缺省按 0.8 处理 */
        highlight01: Float? = null
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
            }
            com.lutcamera.lutcamerapro.log.DoukLog.i(
                "HdrView", "GAIN_ATTACH",
                "result" to (attached.getOrNull() != null),
                "viewGainmap" to (attached.getOrNull()?.gainmap != null),
                "err" to (attached.exceptionOrNull()?.let { "${it::class.java.simpleName}:${it.message}" } ?: "-"),
                "base" to "${base.width}x${base.height}",
                "gain" to "${gain.width}x${gain.height}",
                "ratioMax" to ratioMaxValue, "gamma" to gammaValue
            )
            if (attached.getOrNull() != null) {
                val result = attached.getOrNull()!!
                val old = ownedBitmap
                ownedBitmap = result
                bitmap = result
                // 走标准 Drawable 绘制路径：FIT_CENTER + 不设自定义 Matrix。
                // Matrix（ScaleType.MATRIX）会让 HWUI 用自己那套 bitmap 变换，
                // GainMap 合成被跳过，SurfaceFlinger 里就永远不会出现 HDR 图层。
                scaleType = ScaleType.FIT_CENTER
                imageMatrix = null
                super.setImageBitmap(result)
                if (old != null && old !== result && !old.isRecycled) {
                    runCatching { old.recycle() }
                }
                invalidate()
                return
            }
        }
        // Android 14 以下系统压根没有 Gainmap 合成：只显示底图的话，编辑器预览
        // 和「导出文件在 App 里的样子」就是两副面孔（一个提亮过、一个原样）。
        // 这里自己把增益按导出写进 XMP 的同一套参数烘进底图，
        // 预览 / 查看器 / 导出从此用同一份数学。合成放到后台线程，
        // 主线程只负责上屏，拖动滑杆时不会因为一次离屏渲染掉帧。
        if (gain != null && Build.VERSION.SDK_INT < 34 && !gain.isRecycled && !base.isRecycled) {
            val sig = System.identityHashCode(base) * 31 + System.identityHashCode(gain) * 17 +
                ratioMaxValue.hashCode() * 7 + gammaValue.hashCode() * 3 +
                (highlight01 ?: 0.8f).hashCode()
            if (sig != lastBakeSig) {
                lastBakeSig = sig
                bakeAsync(base, gain, ratioMaxValue, ratioMinValue, gammaValue,
                    epsilonSdr, epsilonHdr, highlight01 ?: 0.8f)
                return
            }
        }
        setBitmap(base)
    }

    /**
     * 后台把增益烘进底图；合成串行排队，只有最后一次请求会真正上屏，
     * 拖动滑杆时旧帧直接丢弃，不会越拖越积压。
     */
    private fun bakeAsync(
        base: Bitmap,
        gain: Bitmap,
        ratioMax: Float,
        ratioMin: Float,
        gamma: Float,
        epsSdr: Float,
        epsHdr: Float,
        highlight01: Float
    ) {
        val seq = ++bakeSeq
        bakeQueue.execute {
            val composed = runCatching {
                if (base.isRecycled || gain.isRecycled) null
                else softComposite(base, gain, ratioMax, ratioMin, gamma, epsSdr, epsHdr, highlight01)
            }.getOrNull()
            post {
                if (seq != bakeSeq) {
                    if (composed != null && !composed.isRecycled) runCatching { composed.recycle() }
                } else if (composed != null && !composed.isRecycled) {
                    setBitmap(composed, own = true)
                }
            }
        }
    }

    /**
     * 没有系统 Gainmap 合成时的 SDR 兜底：按 hdrgm 的 log2 量程 + gamma 把增益烘进底图。
     * 参数就是导出 JPEG 里 XMP 那三个量（GainMapMax / Gamma / eps），
     * 所以预览与成品是同一份数学算出来的，不再各显一套。
     */
    private fun softComposite(
        base: Bitmap,
        gain: Bitmap,
        ratioMax: Float,
        ratioMin: Float,
        gamma: Float,
        epsSdr: Float,
        epsHdr: Float,
        highlight01: Float
    ): Bitmap? {
        val rMax = ratioMax.coerceAtLeast(1.02f)
        val rMin = ratioMin.coerceIn(0.5f, rMax)
        // 膝点尽量高：导出文件的 gainmap 是线性展开（不由我们压高光），
        // 这里压得越狠，预览高光就越平，和成品一对比就是「两副面孔」。
        val knee = (0.92f + 0.05f * highlight01).coerceIn(0.45f, 0.99f)
        return HdrGpuCompositor.composite(
            base, gain,
            HdrGpuCompositor.CompositeParams(
                // 底图已经做过调色 + look，这里只跑增益合成，不再二次调色
                shadow = 0f,
                color = 0f,
                warmth = 0f,
                logMin = kotlin.math.ln(rMin),
                logMax = kotlin.math.ln(rMax),
                gamma = gamma.coerceIn(0.05f, 8f),
                epsSdr = epsSdr,
                epsHdr = epsHdr,
                strength = 1f,
                highlight = highlight01,
                knee = knee
            )
        )
    }

    /**
     * GainMap 的 ALPHA_8 灰度版本，按「源图身份 + 目标尺寸」缓存。
     * 拖动滑杆时每帧都要重新挂一次 gainmap，这里命中缓存就只剩一次 Gainmap 构造。
     */
    private fun cachedGainAlpha(gain: Bitmap, maxW: Int, maxH: Int): Bitmap? {
        val cached = gainAlphaCache
        val sameSource = gainAlphaSource?.get() === gain
        if (cached != null && !cached.isRecycled && sameSource &&
            gainAlphaMaxW == maxW && gainAlphaMaxH == maxH
        ) {
            return cached
        }
        val scaled = if (gain.width > maxW || gain.height > maxH) {
            Bitmap.createScaledBitmap(gain, maxW.coerceAtLeast(1), maxH.coerceAtLeast(1), true)
        } else gain
        val w = scaled.width
        val h = scaled.height
        if (w <= 0 || h <= 0) {
            if (scaled !== gain) runCatching { scaled.recycle() }
            return null
        }
        val isAlpha8 = scaled.config == Bitmap.Config.ALPHA_8
        val alpha = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val v = if (isAlpha8) {
                (p ushr 24) and 0xFF
            } else {
                (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
            }
            px[i] = v shl 24
        }
        alpha.setPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== gain) runCatching { scaled.recycle() }
        gainAlphaCache = alpha
        gainAlphaSource = java.lang.ref.WeakReference(gain)
        gainAlphaMaxW = maxW
        gainAlphaMaxH = maxH
        return alpha
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
        // GainMap 按 Ultra HDR 的常规比例（底图 1/2 长边）存放：既省内存，
        // 也避开部分 ROM 对「GainMap 与底图同尺寸」直接不做 HDR 合成的限制。
        val maxW = (base.width / 2).coerceAtLeast(1)
        val maxH = (base.height / 2).coerceAtLeast(1)
        // GainMap 的灰度重采样在同一张图上会被每帧重复调用一次（拖动滑杆时一帧一次），
        // 1920×1088 缩到半尺寸再逐像素取灰度是纯 CPU 活，实测一次十几毫秒，
        // 直接把拖动打成 300ms+ 的卡顿。这里按「源图 + 目标尺寸」缓存 ALPHA_8 结果，
        // 拖动期间只新建 Gainmap 壳子（微秒级），不再重采样。
        val alpha = cachedGainAlpha(gain, maxW, maxH) ?: return null
        val gw = alpha.width
        val gh = alpha.height

        val gm = android.graphics.Gainmap(alpha)
        gm.setRatioMin(ratioMin, ratioMin, ratioMin)
        gm.setRatioMax(ratioMax, ratioMax, ratioMax)
        gm.setGamma(gamma, gamma, gamma)
        gm.setEpsilonSdr(epsSdr, epsSdr, epsSdr)
        gm.setEpsilonHdr(epsHdr, epsHdr, epsHdr)
        // 导出写的是源片真实量程（例如 4.9），预览必须用同一个值，
        // 否则同屏同参数下预览给满、成品只显示一小段，观感必然对不上。
        // 屏幕能给多少由系统自己按 SDR→HDR 比例裁剪，不需要在这里预先截断。
        com.lutcamera.lutcamerapro.log.DoukLog.i(
            "HdrView", "GAIN_RATIO",
            "full" to displayRatioFull.toString(),
            "min" to minDisplayRatio.toString(),
            "max" to ratioMax.toString()
        )
        gm.setDisplayRatioForFullHdr(displayRatioFull.coerceAtLeast(minDisplayRatio))
        gm.setMinDisplayRatioForHdrTransition(minDisplayRatio.coerceAtMost(displayRatioFull))

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
        // 视图清空后，还在队列里的合成结果一律作废
        bakeSeq++
        lastBakeSig = 0
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
