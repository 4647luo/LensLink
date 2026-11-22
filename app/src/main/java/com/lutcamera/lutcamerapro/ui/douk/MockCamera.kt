package com.lutcamera.lutcamerapro.ui.douk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 模拟相机：真机不在手边时，用一帧帧合成画面顶替取景。
 *
 * 画面刻意做成「灰片」的样子（低对比、暗部被抬起来），这样：
 * - 不套 LUT 时是一张发灰的画面；
 * - 套上 N-Log → Rec709 之类的还原 LUT 后颜色会明显回来，能一眼看出还原有没有生效。
 * 里面还有会移动的彩色小方块和 18% 灰卡，用来验证直方图 / 波形 / 音频表 / 网格。
 *
 * 只用于测试，不参与任何真实相机流程。
 */
object MockCamera {

    var active by mutableStateOf(false)

    private const val W = 1280
    private const val H = 720

    private var cache: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 26f
    }
    private val rect = RectF()

    fun render(timeMs: Long): Bitmap {
        val bmp = cache ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { cache = it }
        val canvas = Canvas(bmp)

        // 1) 灰片底：整体抬亮、压对比，模拟 log 曲线
        val top = Color.rgb(118, 120, 122)
        val bottom = Color.rgb(86, 88, 92)
        paint.shader = LinearGradient(
            0f, 0f, 0f, H.toFloat(),
            top, bottom, Shader.TileMode.CLAMP
        )
        canvas.drawRect(Rect(0, 0, W, H), paint)
        paint.shader = null

        // 2) 缓慢移动的彩色块（验证 LUT / 波形 / 直方图有没有跟着动）
        val t = timeMs / 1000f
        drawBall(canvas, 220f + 90f * kotlin.math.sin(t * 0.9f), 250f, 96f, Color.rgb(196, 92, 62))
        drawBall(canvas, 520f + 110f * kotlin.math.cos(t * 0.7f), 320f, 84f, Color.rgb(74, 152, 108))
        drawBall(canvas, 900f + 80f * kotlin.math.sin(t * 1.1f), 230f, 72f, Color.rgb(92, 124, 208))

        // 3) 18% 灰卡 + 白块 + 黑块：对着看亮度还原是否正常
        rect.set(120f, 500f, 260f, 640f)
        paint.color = Color.rgb(122, 122, 122)
        canvas.drawRect(rect, paint)
        rect.set(280f, 500f, 420f, 640f)
        paint.color = Color.rgb(198, 198, 198)
        canvas.drawRect(rect, paint)
        rect.set(440f, 500f, 580f, 640f)
        paint.color = Color.rgb(52, 52, 52)
        canvas.drawRect(rect, paint)

        // 4) 时间码 / 角标，确认画面在刷新
        canvas.drawText("MOCK  %.2fs".format(t), 980f, 660f, textPaint)
        canvas.drawText("模拟相机 · 仅用于监看测试", 40f, 60f, textPaint)
        return bmp
    }

    private fun drawBall(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
    }

    fun frameIntervalMs(): Long = 66L // 15fps：够看动态，又不会像 30fps 那样白烧电
}
