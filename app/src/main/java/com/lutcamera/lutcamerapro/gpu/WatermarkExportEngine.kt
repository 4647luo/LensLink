package com.lutcamera.lutcamerapro.gpu

import android.graphics.*
import com.lutcamera.lutcamerapro.log.Log
import androidx.exifinterface.media.ExifInterface
import com.lutcamera.lutcamerapro.model.BorderCategory
import com.lutcamera.lutcamerapro.model.BorderMode
import com.lutcamera.lutcamerapro.model.BorderState
import com.lutcamera.lutcamerapro.model.WatermarkFont
import com.lutcamera.lutcamerapro.model.WatermarkLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 🚀 专业边框与水印渲染引擎 (支持 30 款风格差异化边框，修复全部变形、撞字、NPE 与 OOM 漏洞)
 */
object WatermarkExportEngine {

    private const val TAG = "WatermarkExportEngine"
    private const val MAX_PIXELS = 24_000_000 // 2400 万像素安全上限，防堆内存溢出
                suspend fun generateWatermark(
        originalBitmap: Bitmap,
        exifData: ExifInterface?,
        state: BorderState
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 1. 2400 万像素防爆保护（等比抗锯齿矩阵缩减）
                val currentPixels = originalBitmap.width.toLong() * originalBitmap.height.toLong()
            val safeBitmap = if (currentPixels > MAX_PIXELS) {
                val scale = sqrt(MAX_PIXELS.toDouble() / currentPixels).toFloat()
                val matrix = Matrix().apply { postScale(scale, scale) }
                Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
            } else {
                originalBitmap
            }

            val imgW = safeBitmap.width
            val imgH = safeBitmap.height

            // 2. 比例基准短边（防长宽比失真变形）
                val baseSize = min(imgW, imgH).toFloat()

            // 3. 基础留白计算
                val pTop = baseSize * state.paddingTop
            val pBottom = baseSize * state.paddingBottom
            val pLeft = baseSize * state.paddingLeft
            val pRight = baseSize * state.paddingRight

            // 4. 画布尺寸根据边框模式自适应
                val (canvasW, canvasH, imageLeft, imageTop) = calculateCanvasMetrics(
                state.mode, imgW, imgH, baseSize, pTop, pBottom, pLeft, pRight
            )

            val finalBitmap = Bitmap.createBitmap(canvasW.roundToInt(), canvasH.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)

            // 5. 绘制专属背景底色与纹理
                drawBorderBackground(canvas, state.mode, canvasW, canvasH, safeBitmap, baseSize)

            // 6. Draw the main photo with rounded corners, a shadow, and decorative lines.
                val imageRectF = RectF(imageLeft, imageTop, imageLeft + imgW, imageTop + imgH)
            val cornerRadius = when (state.mode) {
                BorderMode.MINIMAL_CORNER_BADGE, BorderMode.CINEMATIC_LETTERBOX -> 0f
                else -> baseSize * (state.cornerRadius / 500f)
            }

            // 照片阴影绘制
                if (state.shadowElevation > 0 && state.mode != BorderMode.MINIMAL_CORNER_BADGE && state.mode != BorderMode.CINEMATIC_LETTERBOX) {
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    val shadowRadius = baseSize * (state.shadowElevation / 800f)
                    setShadowLayer(shadowRadius, 0f, shadowRadius * 0.4f, Color.argb(150, 0, 0, 0))
                }
                canvas.drawRoundRect(imageRectF, cornerRadius, cornerRadius, shadowPaint)
            }

            // 绘制照片内容 (抗锯齿平滑着色器)
                val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val shaderMatrix = Matrix().apply { setTranslate(imageLeft, imageTop) }
            val shader = BitmapShader(safeBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(shaderMatrix)
            imagePaint.shader = shader
            canvas.drawRoundRect(imageRectF, cornerRadius, cornerRadius, imagePaint)

            // 7. 绘制各边框独特的装饰元素 (线条、标牌、挂件、齿孔等)
                drawBorderAccents(canvas, state.mode, imageRectF, canvasW, canvasH, baseSize)

            // 8. 绘制水印与 EXIF 参数铭牌
                if (state.showExif) {
                drawWatermarkContent(
                    canvas = canvas,
                    state = state,
                    exifData = exifData,
                    imageRectF = imageRectF,
                    canvasW = canvasW,
                    canvasH = canvasH,
                    baseSize = baseSize
                )
            }

            // 清理临时防爆位图
                if (safeBitmap != originalBitmap && !safeBitmap.isRecycled) {
                safeBitmap.recycle()
            }

            return@withContext finalBitmap
        } catch (e: Exception) {
            Log.e(TAG, "边框导出渲染异常: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * 计算画布尺寸与主体图片摆放位置（自适应不同边框特殊比例）     */
    private fun calculateCanvasMetrics(
        mode: BorderMode,
        imgW: Int,
        imgH: Int,
        baseSize: Float,
        pTop: Float,
        pBottom: Float,
        pLeft: Float,
        pRight: Float
    ): FloatArray {
        return when (mode) {
            // 极简无边悬浮章：画布与图片完全等大
                BorderMode.MINIMAL_CORNER_BADGE -> {
                floatArrayOf(imgW.toFloat(), imgH.toFloat(), 0f, 0f)
            }
            // 电影宽银幕 2.35:1：宽度撑满，上下加纯黑电影遮幅
                BorderMode.CINEMATIC_LETTERBOX -> {
                val targetH = (imgW / 2.35f).roundToInt()
                val finalH = max(imgH, targetH)
                val extraV = (finalH - imgH) / 2f
                val topMargin = max(extraV, baseSize * 0.08f)
                floatArrayOf(imgW.toFloat(), (imgH + topMargin * 2), 0f, topMargin)
            }
            // 拍立得大下巴与复古相ֽ：底部留白加深
                BorderMode.FUJI_INSTAX_SQUARE, BorderMode.POLAROID_VINTAGE -> {
                val extraBottom = baseSize * 0.08f
                floatArrayOf(
                    imgW + pLeft + pRight,
                    imgH + pTop + pBottom + extraBottom,
                    pLeft,
                    pTop
                )
            }
            // 报刊版式：顶部增加报头留白
                BorderMode.EDITORIAL_GAZETTE -> {
                val extraTop = baseSize * 0.06f
                floatArrayOf(
                    imgW + pLeft + pRight,
                    imgH + pTop + extraTop + pBottom,
                    pLeft,
                    pTop + extraTop
                )
            }
            // 普通标准留白
                else -> {
                floatArrayOf(
                    imgW + pLeft + pRight,
                    imgH + pTop + pBottom,
                    pLeft,
                    pTop
                )
            }
        }
    }

    /**
     * 绘制 30 种边框独特的专属背景与底色     */
    private fun drawBorderBackground(
        canvas: Canvas,
        mode: BorderMode,
        canvasW: Float,
        canvasH: Float,
        safeBitmap: Bitmap,
        baseSize: Float
    ) {
        when (mode) {
            // 纯白相纸系列
                BorderMode.LEICA_CLASSIC_WHITE,
            BorderMode.SONY_ALPHA_STUDIO,
            BorderMode.FUJI_INSTAX_SQUARE,
            BorderMode.POLAROID_VINTAGE,
            BorderMode.GALLERY_PASSEPARTOUT,
            BorderMode.FLOATING_SHADOW_MAT,
            BorderMode.MODERN_DOUBLE_LINE,
            BorderMode.SWISS_TYPOGRAPHY,
            BorderMode.MAGAZINE_COVER,
            BorderMode.SLIDE_MOUNT_35MM -> {
                canvas.drawColor(Color.WHITE)
            }

            // 纯黑与深空灰系列
                BorderMode.CANON_RED_RING,
            BorderMode.RETRO_GOLD_PLATE,
            BorderMode.MATTE_BLACK_STUDIO,
            BorderMode.CINEMATIC_LETTERBOX,
            BorderMode.CYBERPUNK_HUD,
            BorderMode.STREET_DARKROOM,
            BorderMode.FUJI_CLASSIC_CHROME,
            BorderMode.CONTACT_SHEET_STRIP -> {
                canvas.drawColor(Color.BLACK)
            }

            // 徕卡红标签名：纯净极简深炭火
                BorderMode.LEICA_RED_DOT_PRO -> {
                canvas.drawColor(Color.parseColor("#141416"))
            }

            // 柯达金 200：经典暖金黄色调
                BorderMode.KODAK_GOLD_200 -> {
                canvas.drawColor(Color.parseColor("#FFF4D4"))
            }

            // 柯达波特拉 160：优雅米白暖调底
                BorderMode.KODAK_PORTRA_160 -> {
                canvas.drawColor(Color.parseColor("#F7F4EC"))
            }

            // 温润复古牛皮纸：自然木浆色
                BorderMode.VINTAGE_KRAFT_PAPER -> {
                canvas.drawColor(Color.parseColor("#EFE5D5"))
            }

            // 哈苏中画幅：高级象牙灰白
                BorderMode.HASSELBLAD_ORANGE -> {
                canvas.drawColor(Color.parseColor("#F6F6F6"))
            }

            // 香槟金几何线：典雅象牙白
                BorderMode.CHAMPAGNE_GOLD_LINE -> {
                canvas.drawColor(Color.parseColor("#FCFAF6"))
            }

            // 复古报刊：泛黄新闻纸
                BorderMode.EDITORIAL_GAZETTE -> {
                canvas.drawColor(Color.parseColor("#F5F1E9"))
            }

            // 旅行明信片：温暖明信片卡片
                BorderMode.TRAVELER_POSTCARD -> {
                canvas.drawColor(Color.parseColor("#F9F7F1"))
            }

            // 手写便签：浅米黄相纸
                BorderMode.HANDWRITTEN_MEMO -> {
                canvas.drawColor(Color.parseColor("#FAF8F5"))
            }

            // 包豪斯非对称：几何极简白
                BorderMode.ASYMMETRIC_BALANCE -> {
                canvas.drawColor(Color.parseColor("#F4F4F6"))
            }

            // 极简无边悬浮章：原图全铺，底色无需单独上色
                BorderMode.MINIMAL_CORNER_BADGE -> {}

            // 环境光晕毛玻璃氛围
                BorderMode.BLUR_AMBIENT_GLOW -> {
                try {
                    val tinyBitmap = Bitmap.createScaledBitmap(safeBitmap, 48, 48, true)
                    val bgRect = Rect(0, 0, canvasW.toInt(), canvasH.toInt())
                    val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(tinyBitmap, null, bgRect, blurPaint)
                    tinyBitmap.recycle()

                    val bgGradient = RadialGradient(
                        canvasW / 2f, canvasH / 2f, canvasW,
                        intArrayOf(Color.argb(80, 0, 0, 0), Color.argb(220, 0, 0, 0)),
                        floatArrayOf(0.3f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                    val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bgGradient }
                    canvas.drawRect(0f, 0f, canvasW, canvasH, gradPaint)
                } catch (e: Exception) {
                    canvas.drawColor(Color.parseColor("#1C1C1E"))
                }
            }
        }
    }

    /**
     * 绘制 30 种边框独特的专属视觉点缀 (齿孔、红圈、标牌、折角线条等)
     */
    private fun drawBorderAccents(
        canvas: Canvas,
        mode: BorderMode,
        imgRect: RectF,
        canvasW: Float,
        canvasH: Float,
        baseSize: Float
    ) {
        when (mode) {
            // 1. 经典徕卡白：底部左下角红色小圆标印记
                BorderMode.LEICA_CLASSIC_WHITE -> {
                val badgeRadius = baseSize * 0.022f
                val badgeCx = imgRect.left + badgeRadius + baseSize * 0.01f
                val badgeCy = imgRect.bottom + (canvasH - imgRect.bottom) / 2f

                val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D32F2F") }
                canvas.drawCircle(badgeCx, badgeCy, badgeRadius, redPaint)

                val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    textSize = badgeRadius * 1.05f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("Leica", badgeCx, badgeCy + badgeRadius * 0.35f, badgeTextPaint)
            }

            // 2. 徕卡红标签名：右下角极简红圆点 + 居中纤细签名分割线
                BorderMode.LEICA_RED_DOT_PRO -> {
                val dotRadius = baseSize * 0.012f
                val dotCx = imgRect.right - dotRadius
                val dotCy = imgRect.bottom + (canvasH - imgRect.bottom) / 2f
                val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
                canvas.drawCircle(dotCx, dotCy, dotRadius, redPaint)

                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(60, 255, 255, 255)
                    strokeWidth = baseSize * 0.0015f
                }
                val lineY = imgRect.bottom + (canvasH - imgRect.bottom) * 0.28f
                canvas.drawLine(imgRect.left, lineY, imgRect.right, lineY, linePaint)
            }

            // 3. 哈苏橙标方框：居中橙色方形 Logo 框
                BorderMode.HASSELBLAD_ORANGE -> {
                val boxSize = baseSize * 0.038f
                val boxLeft = canvasW / 2f - boxSize / 2f
                val boxTop = imgRect.bottom + (canvasH - imgRect.bottom) * 0.22f
                val orangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6A00") }
                canvas.drawRoundRect(RectF(boxLeft, boxTop, boxLeft + boxSize, boxTop + boxSize), 6f, 6f, orangePaint)

                val hPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textSize = boxSize * 0.65f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("H", canvasW / 2f, boxTop + boxSize * 0.72f, hPaint)
            }

            // 4. 索尼微单影棚白：左下角橙色伪 α 标
                BorderMode.SONY_ALPHA_STUDIO -> {
                val alphaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FF6600")
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    textSize = baseSize * 0.045f
                    textAlign = Paint.Align.LEFT
                }
                val textBaseline = imgRect.bottom + (canvasH - imgRect.bottom) * 0.65f
                canvas.drawText("伪", imgRect.left, textBaseline, alphaPaint)
            }

            // 5. 佳能红圈典藏：照片顶部一道标志性奢华红圈线条
                BorderMode.CANON_RED_RING -> {
                val redLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#C62828") // 佳能标志红
                strokeWidth = baseSize * 0.004f
                }
                val lineY = imgRect.top - baseSize * 0.015f
                canvas.drawLine(imgRect.left, lineY, imgRect.right, lineY, redLinePaint)
            }

            // 6. 复古黄铜铭牌：炭黑底加黄铜金双细线与复古铭牌
                BorderMode.RETRO_GOLD_PLATE -> {
                val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#D4AF37") // 黄铜色
                style = Paint.Style.STROKE
                    strokeWidth = baseSize * 0.0018f
                }
                val margin = baseSize * 0.015f
                canvas.drawRect(margin, margin, canvasW - margin, canvasH - margin, goldPaint)

                // 复古黄色小标
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFD54F")
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textSize = baseSize * 0.024f
                    textAlign = Paint.Align.LEFT
                }
                canvas.drawText("连镜", imgRect.left, imgRect.bottom + (canvasH - imgRect.bottom) * 0.45f, labelPaint)
            }

            // 7. 宝丽来彩虹标：底部彩虹条 (红橙黄绿靛
                BorderMode.POLAROID_VINTAGE -> {
                val rainbowColors = intArrayOf(
                    Color.parseColor("#E53935"), // 红
                Color.parseColor("#FB8C00"), // 橙
                Color.parseColor("#FDD835"), // 黄
                Color.parseColor("#43A047"), // 绿
                Color.parseColor("#1E88E5")  // 蓝
                )
                val stripeH = baseSize * 0.0035f
                val stripeW = baseSize * 0.05f
                val startX = canvasW / 2f - (stripeW * 2.5f)
                val stripeY = imgRect.bottom + (canvasH - imgRect.bottom) * 0.22f

                rainbowColors.forEachIndexed { index, colorInt ->
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt }
                    val x = startX + index * stripeW
                    canvas.drawRect(x, stripeY, x + stripeW, stripeY + stripeH, p)
                }
            }

            // 8. 富士正片与柯达齿孔胶片 (绘制上下胶片齿孔)
                BorderMode.FUJI_CLASSIC_CHROME, BorderMode.KODAK_GOLD_200, BorderMode.CONTACT_SHEET_STRIP -> {
                val isKodak = mode == BorderMode.KODAK_GOLD_200
                val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isKodak) Color.BLACK else Color.WHITE
                }
                val holeW = baseSize * 0.022f
                val holeH = baseSize * 0.016f
                val holeGap = baseSize * 0.045f
                val count = ((canvasW - baseSize * 0.04f) / holeGap).toInt()
                val topHoleY = (imgRect.top - holeH) / 2f
                val bottomHoleY = imgRect.bottom + (canvasH - imgRect.bottom - holeH) / 2f

                for (i in 0 until count) {
                    val x = baseSize * 0.03f + i * holeGap
                    canvas.drawRoundRect(RectF(x, topHoleY, x + holeW, topHoleY + holeH), 4f, 4f, holePaint)
                    canvas.drawRoundRect(RectF(x, bottomHoleY, x + holeW, bottomHoleY + holeH), 4f, 4f, holePaint)
                }

                // 胶片品牌编号印记
                val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isKodak) Color.parseColor("#FF8F00") else Color.parseColor("#4CAF50")
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = baseSize * 0.022f
                }
                val tag = if (isKodak) "KODAK GOLD 200 路 36 EXP" else "FUJIFILM PROVIA 100F 路 135-36"
                canvas.drawText(tag, imgRect.left, imgRect.top - baseSize * 0.01f, stampPaint)
            }

            // 9. 香槟金几何内折角
                BorderMode.CHAMPAGNE_GOLD_LINE -> {
                val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#D4AF37")
                    strokeWidth = baseSize * 0.0022f
                    style = Paint.Style.STROKE
                }
                val offset = baseSize * 0.018f
                val fold = baseSize * 0.03f

                val path = Path().apply {
                    // 左上
                moveTo(imgRect.left - offset + fold, imgRect.top - offset)
                    lineTo(imgRect.right + offset - fold, imgRect.top - offset)
                    // 右上内折
                lineTo(imgRect.right + offset, imgRect.top - offset + fold)
                    lineTo(imgRect.right + offset, imgRect.bottom + offset - fold)
                    // 右下内折
                lineTo(imgRect.right + offset - fold, imgRect.bottom + offset)
                    lineTo(imgRect.left - offset + fold, imgRect.bottom + offset)
                    // 左下内折
                lineTo(imgRect.left - offset, imgRect.bottom + offset - fold)
                    lineTo(imgRect.left - offset, imgRect.top - offset + fold)
                    close()
                }
                canvas.drawPath(path, goldPaint)
            }

            // 10. 现代实虚双线
                BorderMode.MODERN_DOUBLE_LINE -> {
                val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#222222")
                    strokeWidth = baseSize * 0.002f
                    style = Paint.Style.STROKE
                }
                val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#888888")
                    strokeWidth = baseSize * 0.0012f
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(baseSize * 0.01f, baseSize * 0.008f), 0f)
                }

                val offsetOuter = baseSize * 0.02f
                val offsetInner = baseSize * 0.01f
                canvas.drawRect(imgRect.left - offsetOuter, imgRect.top - offsetOuter, imgRect.right + offsetOuter, imgRect.bottom + offsetOuter, solidPaint)
                canvas.drawRect(imgRect.left - offsetInner, imgRect.top - offsetInner, imgRect.right + offsetInner, imgRect.bottom + offsetInner, dashPaint)
            }

            // 11. 艺术馆斜切厚卡纸：双层细腻立体阴影边框
                BorderMode.GALLERY_PASSEPARTOUT -> {
                val matLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E0E0E0")
                    strokeWidth = baseSize * 0.0015f
                    style = Paint.Style.STROKE
                }
                val offset = baseSize * 0.015f
                canvas.drawRect(imgRect.left - offset, imgRect.top - offset, imgRect.right + offset, imgRect.bottom + offset, matLinePaint)
            }

            // 12. 哑光纯黑监视框：照片四周 1px 防眩高光内衬线
                BorderMode.MATTE_BLACK_STUDIO -> {
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#2A2A2A")
                    strokeWidth = baseSize * 0.0012f
                    style = Paint.Style.STROKE
                }
                canvas.drawRect(imgRect, linePaint)
            }

            // 13. 复古新闻报刊：顶部报头与双实线
                BorderMode.EDITORIAL_GAZETTE -> {
                val gazettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1C1C1C")
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    textSize = baseSize * 0.045f
                    textAlign = Paint.Align.CENTER
                }
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1C1C1C")
                    strokeWidth = baseSize * 0.002f
                }
                val headY = imgRect.top - baseSize * 0.025f
                canvas.drawText("THE PHOTO GAZETTE", canvasW / 2f, headY, gazettePaint)
                canvas.drawLine(imgRect.left, headY + baseSize * 0.01f, imgRect.right, headY + baseSize * 0.01f, linePaint)
            }

            // 14. 旅行航空明信片：右上角复古邮票与航空线条
                BorderMode.TRAVELER_POSTCARD -> {
                val stampW = baseSize * 0.07f
                val stampH = baseSize * 0.09f
                val stampRight = imgRect.right
                val stampTop = baseSize * 0.015f

                val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#EFEBE9")
                    style = Paint.Style.FILL
                }
                val stampBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#8D6E63")
                    style = Paint.Style.STROKE
                    strokeWidth = baseSize * 0.0015f
                    pathEffect = DashPathEffect(floatArrayOf(baseSize * 0.005f, baseSize * 0.003f), 0f)
                }
                val stampRect = RectF(stampRight - stampW, stampTop, stampRight, stampTop + stampH)
                canvas.drawRect(stampRect, stampPaint)
                canvas.drawRect(stampRect, stampBorder)

                // 航空Բ形邮戳
                val stampCirc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#B71C1C")
                    style = Paint.Style.STROKE
                    strokeWidth = baseSize * 0.0012f
                }
                canvas.drawCircle(stampRight - stampW / 2f, stampTop + stampH / 2f, stampW * 0.42f, stampCirc)
            }

            // 15. 电子取景器 HUD：绿色十字准心与刻度
                BorderMode.CYBERPUNK_HUD -> {
                val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#00E676") // 荧光绿
                strokeWidth = baseSize * 0.0015f
                    style = Paint.Style.STROKE
                }
                // 中心十字对焦线
                val cx = imgRect.centerX()
                val cy = imgRect.centerY()
                val crossLen = baseSize * 0.035f
                canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, hudPaint)
                canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, hudPaint)
                canvas.drawCircle(cx, cy, crossLen * 0.6f, hudPaint)

                // 四角 HUD 取景刻度
                val cornerLen = baseSize * 0.04f
                val m = baseSize * 0.015f
                // 左上
                canvas.drawLine(imgRect.left + m, imgRect.top + m, imgRect.left + m + cornerLen, imgRect.top + m, hudPaint)
                canvas.drawLine(imgRect.left + m, imgRect.top + m, imgRect.left + m, imgRect.top + m + cornerLen, hudPaint)
                // 右下
                canvas.drawLine(imgRect.right - m, imgRect.bottom - m, imgRect.right - m - cornerLen, imgRect.bottom - m, hudPaint)
                canvas.drawLine(imgRect.right - m, imgRect.bottom - m, imgRect.right - m, imgRect.bottom - m - cornerLen, hudPaint)
            }

            // 其他模式无需额外的装饰线
                else -> {}
        }
    }

    /**
     * 绘制水印文字、相机型号与 EXIF 参数（内置严格的防撞字、防 NPE、自动对比度反色逻辑）     */
    private fun drawWatermarkContent(
        canvas: Canvas,
        state: BorderState,
        exifData: ExifInterface?,
        imageRectF: RectF,
        canvasW: Float,
        canvasH: Float,
        baseSize: Float
    ) {
        val isDarkBg = when (state.mode) {
            BorderMode.CANON_RED_RING,
            BorderMode.RETRO_GOLD_PLATE,
            BorderMode.MATTE_BLACK_STUDIO,
            BorderMode.CINEMATIC_LETTERBOX,
            BorderMode.CYBERPUNK_HUD,
            BorderMode.STREET_DARKROOM,
            BorderMode.FUJI_CLASSIC_CHROME,
            BorderMode.CONTACT_SHEET_STRIP,
            BorderMode.LEICA_RED_DOT_PRO,
            BorderMode.BLUR_AMBIENT_GLOW -> true
            else -> false
        }

        // 电影宽银幕模式默认使用电影字幕黄色，其余遵循智能反色
                val defaultColor = when {
            state.mode == BorderMode.CINEMATIC_LETTERBOX -> Color.parseColor("#FFEB3B")
            state.mode == BorderMode.CYBERPUNK_HUD -> Color.parseColor("#00E676")
            state.mode == BorderMode.VINTAGE_KRAFT_PAPER -> Color.parseColor("#4E342E")
            isDarkBg -> Color.WHITE
            else -> Color.parseColor("#111111")
        }

        val textColor = if (state.textColorInt != 0) state.textColorInt else defaultColor

        val systemTypeface = when (state.fontType) {
            WatermarkFont.SANS_SERIF -> Typeface.SANS_SERIF
            WatermarkFont.SERIF -> Typeface.SERIF
            WatermarkFont.MONOSPACE -> Typeface.MONOSPACE
            WatermarkFont.HANDWRITTEN -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            typeface = Typeface.create(systemTypeface, Typeface.BOLD)
        }

        val fontScale = state.fontSizeScale.coerceIn(0.5f, 2.5f)
        var line1TextSize = baseSize * 0.028f * fontScale
        var line2TextSize = baseSize * 0.018f * fontScale

        val dragX = (state.watermarkOffsetX / 300f) * baseSize
        val dragY = (state.watermarkOffsetY / 300f) * baseSize

        // Read the camera model and brand safely.
                val line1 = if (state.isCustomWatermark) {
            state.customLine1
        } else {
            val make = exifData?.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
            val model = exifData?.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
            val cameraName = when {
                make.isNotBlank() && model.isNotBlank() -> if (model.startsWith(make, ignoreCase = true)) model else "$make $model"
                model.isNotBlank() -> model
                make.isNotBlank() -> make
                else -> "LutCamera Pro"
            }
            if (state.showLogo) cameraName else ""
        }

        // 提取拍摄参数（缺失时优雅降级为当前日期）
                val line2 = if (state.isCustomWatermark) {
            state.customLine2
        } else {
            val extracted = extractExifParamString(exifData)
            if (extracted.isBlank()) {
                SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date())
            } else {
                extracted
            }
        }

        // 极简无边悬浮章特殊绘制：右下角磨砂圆角小铭牌
                if (state.mode == BorderMode.MINIMAL_CORNER_BADGE) {
            drawCornerFloatingBadge(canvas, line1, line2, canvasW, canvasH, baseSize, textPaint, systemTypeface, dragX, dragY)
            return
        }

        val borderAreaCenterY = imageRectF.bottom + (canvasH - imageRectF.bottom) / 2f + dragY

        // Layout A: two horizontal columns with collision checks and proportional text scaling.
                if (state.watermarkLayout == WatermarkLayout.HORIZONTAL) {
            val margin = baseSize * 0.035f
            val availableWidth = imageRectF.width() - (margin * 2)
            val textBaselineY = borderAreaCenterY + (line1TextSize / 3f)

            textPaint.textSize = line1TextSize
            val w1 = if (line1.isNotEmpty()) textPaint.measureText(line1) else 0f

            textPaint.textSize = line2TextSize
            val w2 = if (line2.isNotEmpty()) textPaint.measureText(line2) else 0f

            // 防撞字重叠：当左边文字 + 右边文字超过可用宽度时，动态等比缩放字号
                val safeGap = baseSize * 0.03f
            if (w1 + w2 + safeGap > availableWidth && availableWidth > 0) {
                val shrinkFactor = availableWidth / (w1 + w2 + safeGap)
                line1TextSize *= shrinkFactor
                line2TextSize *= shrinkFactor
            }

            if (line1.isNotEmpty()) {
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.textSize = line1TextSize
                textPaint.typeface = Typeface.create(systemTypeface, Typeface.BOLD)
                canvas.drawText(line1, imageRectF.left + margin + dragX, textBaselineY, textPaint)
            }

            if (line2.isNotEmpty()) {
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.textSize = line2TextSize
                textPaint.typeface = Typeface.create(systemTypeface, Typeface.NORMAL)
                textPaint.alpha = if (state.textColorInt != 0) 255 else 195
                canvas.drawText(line2, imageRectF.right - margin + dragX, textBaselineY, textPaint)
            }
        }
        // 🚀 布局模式 B：纵向居中排鐗
                else {
            textPaint.textAlign = Paint.Align.CENTER
            val centerX = (canvasW / 2f) + dragX
            val lineSpacing = line1TextSize * 0.35f

            val hasBothLines = line1.isNotEmpty() && line2.isNotEmpty()
            val totalTextHeight = if (hasBothLines) (line1TextSize + lineSpacing + line2TextSize) else line1TextSize
            var startY = borderAreaCenterY - (totalTextHeight / 2f) + line1TextSize

            if (line1.isNotEmpty()) {
                textPaint.textSize = line1TextSize
                textPaint.typeface = Typeface.create(systemTypeface, Typeface.BOLD)
                canvas.drawText(line1, centerX, startY, textPaint)
                startY += line1TextSize + lineSpacing
            }

            if (line2.isNotEmpty()) {
                textPaint.textSize = line2TextSize
                textPaint.typeface = Typeface.create(systemTypeface, Typeface.NORMAL)
                textPaint.alpha = if (state.textColorInt != 0) 255 else 195
                canvas.drawText(line2, centerX, startY, textPaint)
            }
        }
    }

    /**
     * 绘制无边框极简右下角磨砂悬浮参数铭牌     */
    private fun drawCornerFloatingBadge(
        canvas: Canvas,
        line1: String,
        line2: String,
        canvasW: Float,
        canvasH: Float,
        baseSize: Float,
        textPaint: Paint,
        typeface: Typeface,
        dragX: Float,
        dragY: Float
    ) {
        val badgeFontScale = 0.85f
        val l1Size = baseSize * 0.024f * badgeFontScale
        val l2Size = baseSize * 0.016f * badgeFontScale

        textPaint.textSize = l1Size
        val w1 = if (line1.isNotEmpty()) textPaint.measureText(line1) else 0f
        textPaint.textSize = l2Size
        val w2 = if (line2.isNotEmpty()) textPaint.measureText(line2) else 0f
        val maxTextW = max(w1, w2)

        val paddingH = baseSize * 0.025f
        val paddingV = baseSize * 0.018f
        val badgeW = maxTextW + paddingH * 2
        val badgeH = l1Size + l2Size + baseSize * 0.01f + paddingV * 2

        val margin = baseSize * 0.04f
        val badgeRight = canvasW - margin + dragX
        val badgeBottom = canvasH - margin + dragY
        val badgeRect = RectF(badgeRight - badgeW, badgeBottom - badgeH, badgeRight, badgeBottom)

        // 鍗婇€忔槑暗黑磨砂背板
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 20, 20, 22) }
        canvas.drawRoundRect(badgeRect, 16f, 16f, bgPaint)

        // 白色文字
                textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT

        val startX = badgeRect.left + paddingH
        var textY = badgeRect.top + paddingV + l1Size

        if (line1.isNotEmpty()) {
            textPaint.textSize = l1Size
            textPaint.typeface = Typeface.create(typeface, Typeface.BOLD)
            canvas.drawText(line1, startX, textY, textPaint)
            textY += l2Size + baseSize * 0.008f
        }

        if (line2.isNotEmpty()) {
            textPaint.textSize = l2Size
            textPaint.typeface = Typeface.create(typeface, Typeface.NORMAL)
            textPaint.alpha = 200
            canvas.drawText(line2, startX, textY, textPaint)
        }
    }

    /**
     * 真实高精度提取 EXIF 拍摄参数字符串（焦段、光圈、快门、ISO）     */
    private fun extractExifParamString(exifData: ExifInterface?): String {
        if (exifData == null) return ""

        val focal = exifData.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
            ?: exifData.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { parseFocalLength(it) }

        val fNumRaw = exifData.getAttribute(ExifInterface.TAG_F_NUMBER)
        val fNum = fNumRaw?.let { if (it.startsWith("f/", ignoreCase = true)) it else "f/$it" }

        val exp = exifData.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { formatExposureTime(it) }

        val isoRaw = exifData.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            ?: exifData.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
        val iso = isoRaw?.let { if (it.startsWith("ISO", ignoreCase = true)) it else "ISO $it" }

        val params = listOfNotNull(focal, fNum, exp, iso).filter { it.isNotBlank() }
        return if (params.isEmpty()) "" else params.joinToString("  ")
    }

    private fun parseFocalLength(focalStr: String): String {
        try {
            val parts = focalStr.split("/")
            if (parts.size == 2) {
                val value = parts[0].toFloat() / parts[1].toFloat()
                return "${value.toInt()}mm"
            } else if (focalStr.toFloatOrNull() != null) {
                return "${focalStr.toFloat().toInt()}mm"
            }
        } catch (ignored: Exception) { }
        return focalStr
    }

    private fun formatExposureTime(timeStr: String): String {
        try {
            val time = timeStr.toFloat()
            if (time > 0 && time < 1.0f) {
                val denominator = Math.round(1.0f / time)
                return "1/${denominator}s"
            } else if (time >= 1.0f) {
                return if (time % 1.0f == 0f) "${time.toInt()}s" else "${time}s"
            }
        } catch (ignored: Exception) { }
        return "${timeStr}s"
    }
}


