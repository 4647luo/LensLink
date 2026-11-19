package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/** 主题色预设，设置页的「app 主题色」用这些名字 */
val DoukAccentPresets = listOf(
    "暖橙", "青蓝", "松绿", "玫粉", "紫晶",
    "珊瑚红", "柠檬黄", "海盐蓝", "薄荷青", "石墨灰"
)

/** 自定义色相 → 主题色：固定较高饱和度和亮度，保证白字按钮可读 */
fun accentFromHue(hue: Int): Color {
    val argb = android.graphics.Color.HSVToColor(
        floatArrayOf((hue.coerceIn(0, 360)).toFloat(), 0.72f, 0.94f)
    )
    return Color(argb)
}

/**
 * Douk 视觉规范（设计 token 层）。
 *
 * 参照 Material 3 的语义色 + Apple HIG 的 8pt 网格与触控尺寸，
 * 页面里不再手写魔法数字，统一从这里取色 / 取间距 / 取字级。
 *
 * accent 与深色底都是可变 State，设置页改动后整站即时跟随。
 */
object DoukColors {
    internal val accentState = mutableStateOf(Color(0xFFFF6900))
    internal val darkState = mutableStateOf(false)

    /** 「外观设置 → 高级材质」开关：95% 不透明度卡片 + 轻度真实模糊 */
    internal val glassState = mutableStateOf(true)
    /** 卡片不透明度（0.55–1.0） */
    internal val materialAlphaState = mutableStateOf(0.95f)
    /** 卡片背后模糊半径（dp） */
    internal val materialBlurState = mutableStateOf(8f)
    /** 全站共享的背景源状态：页面内容画一次，卡片/底栏从里面取真实模糊。 */
    internal val hazeState = dev.chrisbanes.haze.HazeState()

    val accent: Color get() = accentState.value

    val isDark: Boolean get() = darkState.value

    val glass: Boolean get() = glassState.value

    val materialAlpha: Float get() = materialAlphaState.value

    val materialBlur: Float get() = materialBlurState.value

    /** 页面底层：带一点冷暖和主色倾向的渐变，玻璃卡片才有东西可透 */
    val backgroundBrush: Brush
        get() = if (darkState.value) Brush.linearGradient(
            listOf(Color(0xFF0A0C11), Color(0xFF141A23), Color(0xFF0B0E13))
        ) else Brush.linearGradient(
            listOf(Color(0xFFF6FAFF), Color(0xFFE8EFF9), Color(0xFFF8F1EA))
        )

    /** 主色的极浅底（选中态、标签底） */
    val accentSoft: Color get() = accentState.value.copy(alpha = 0.10f)

    /** 主色的容器色，比 accentSoft 稍重，用于图标胶囊底 */
    val accentContainer: Color get() = accentState.value.copy(alpha = 0.16f)

    val accentOutline: Color get() = accentState.value.copy(alpha = 0.34f)

    // ===== 表面分级（Material 3 surface container 思路）=====
    val background: Color get() = if (darkState.value) Color(0xFF0D0E11) else Color(0xFFF5F6F8)
    val surface: Color get() = if (darkState.value) Color(0xFF16181C) else Color(0xFFFFFFFF)
    val surfaceRaised: Color get() = if (darkState.value) Color(0xFF1D1F24) else Color(0xFFFBFBFC)
    val surfaceMuted: Color get() = if (darkState.value) Color(0xFF212429) else Color(0xFFF0F1F4)

    // ===== 文本四级 =====
    val textPrimary: Color get() = if (darkState.value) Color(0xFFF3F4F6) else Color(0xFF101114)
    val textSecondary: Color get() = if (darkState.value) Color(0xFFA0A5AE) else Color(0xFF7C8189)
    val textTertiary: Color get() = if (darkState.value) Color(0xFF6E737C) else Color(0xFFB0B4BC)

    // ===== 线与描边 =====
    val divider: Color get() = if (darkState.value) Color(0xFF2A2D33) else Color(0xFFECEDF1)
    val outline: Color get() = if (darkState.value) Color(0xFF3A3E46) else Color(0xFFD8DBE1)

    // ===== 语义色 =====
    val success = Color(0xFF00B26A)
    val successSoft = Color(0x1400B26A)
    val warning = Color(0xFFF08C00)
    val warningSoft = Color(0x14F08C00)
    val danger = Color(0xFFE5484D)
    val dangerSoft = Color(0x14E5484D)

    val previewBackground = Color(0xFF0B0C0F)

    /** 设置页选主题色 */
    fun applyAccent(name: String) {
        if (name == "自定义") {
            applyAccentHue(DoukPrefs.customAccentHue)
            return
        }
        accentState.value = when (name) {
            "青蓝" -> Color(0xFF2E8BF0)
            "松绿" -> Color(0xFF12A87B)
            "玫粉" -> Color(0xFFE8386D)
            "紫晶" -> Color(0xFF7A5AF0)
            "珊瑚红" -> Color(0xFFFF5A5F)
            "柠檬黄" -> Color(0xFFE6A400)
            "海盐蓝" -> Color(0xFF4C7DFF)
            "薄荷青" -> Color(0xFF12B5B0)
            "石墨灰" -> Color(0xFF5B6472)
            else -> Color(0xFFFF6900)
        }
    }

    /** 自定义色相：0–360，实时预览 */
    fun applyAccentHue(hue: Int) {
        accentState.value = accentFromHue(hue)
    }

    fun applyDark(dark: Boolean) { darkState.value = dark }

    fun applyGlass(enabled: Boolean) { glassState.value = enabled }

    /** 高级材质：不透明度越高越接近实心卡片，模糊半径只影响卡片背后那一层 */
    fun applyMaterial(alpha: Float, blurDp: Float) {
        materialAlphaState.value = alpha.coerceIn(0.55f, 1f)
        materialBlurState.value = blurDp.coerceIn(0f, 20f)
    }
}

/** 间距：8pt 网格，页面水平内边距统一 page=20 */
object DoukSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    val page = 20.dp
}

/** 圆角梯队：只允许这四档 + 胶囊，避免满屏各写各的 */
object DoukShapes {
    val icon = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(20.dp)
    val cardSmall = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(50)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/** 字级：字重 / 行高 / 字距成套定义，颜色交给调用处的 color 参数 */
object DoukType {
    val display = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val h1 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val h2 = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val title = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
    val label = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.sp)
    /** 分组标题：比 label 略大一档，全站统一用它 */
    val section = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.sp)
    val value = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val tab = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.sp)
}

/** 阴影：克制两层，卡片只给一点托起感 */
object DoukElevation {
    val none = 0.dp
    val card = 3.dp
    val raised = 10.dp
    val overlay = 24.dp
}

fun Modifier.doukShadow(
    elevation: Dp = DoukElevation.card,
    shape: Shape = DoukShapes.card
): Modifier = when {
    elevation == 0.dp -> this
    // 玻璃模式不要投影硬边：投影在这一档会被看成「卡片边框线」。
    DoukColors.glass -> this
    else -> shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color(0x14000000),
        spotColor = Color(0x1A000000)
    )
}

/**
 * 卡片表面：关掉「玻璃质感」时是原来的实色；
 * 打开后换成磨砂玻璃：竖向柔光渐变 + 两团模糊光晕 + 高光描边。
 *
 * 不再使用左上 → 右下的线性渐变，避免整张卡出现一条横向亮带；
 * 光晕用径向渐变模拟高斯模糊的扩散，卡片压在内容上时比单纯透明更像实体玻璃。
 * [tint] 用于选中态（例如底栏被选中的 tab），只染一层很淡的主色。
 */
fun Modifier.doukCardSurface(
    shape: Shape = DoukShapes.card,
    tint: Color = Color.Transparent
): Modifier {
    val baseColor = if (tint == Color.Transparent) DoukColors.surface else tint
    if (!DoukColors.glass) {
        return background(if (tint == Color.Transparent) baseColor else tint, shape)
    }
    // 高级材质：95% 不透明度的卡片 + 背后一层轻度真实模糊。
    // 不再做强高斯：卡片本身接近实心，只留一点底层的色与光，文字不会被糊成色块。
    val dark = DoukColors.isDark
    val opacity = DoukColors.materialAlpha
    val glassTint = when {
        tint != Color.Transparent -> tint.copy(alpha = if (dark) 0.46f else 0.40f)
        dark -> Color(0xFF171B22).copy(alpha = opacity)
        else -> Color.White.copy(alpha = opacity)
    }
    return this
        // clip 放在 hazeEffect 外层：RenderEffect 在圆角图层内绘制，四角不会露出方形模糊块
        .clip(shape)
        .hazeEffect(
            state = DoukColors.hazeState,
            style = HazeStyle(
                backgroundColor = glassTint,
                tints = emptyList(),
                blurRadius = DoukColors.materialBlur.dp,
                noiseFactor = 0f
            )
        )
        // 高亮玻璃边：先铺一条更宽的极淡白边当内侧折光，再压一条 1.2dp 亮白边。
        // 两条都画在卡片内侧，圆角处不会露出直角；浅色下接近参考图的白玻璃胶囊，
        // 深色下降低白度，避免夜里出现一圈刺眼的荧光边。
        .border(
            width = 5.dp,
            color = Color.White.copy(alpha = if (dark) 0.07f else 0.15f),
            shape = shape
        )
        .border(
            width = 2.dp,
            color = Color.White.copy(alpha = if (dark) 0.58f else 0.98f),
            shape = shape
        )
}

/**
 * 底部 Douk 栏：与卡片同一套「高级材质」，95% 不透明度 + 轻度模糊。
 * 不再使用 84dp 强模糊，避免和整个界面的材质观感割裂；边缘折光保留。
 */
fun Modifier.doukGlassDock(shape: Shape): Modifier {
    if (!DoukColors.glass) return background(DoukColors.surface, shape)
    val dark = DoukColors.isDark
    val fill = if (dark) {
        Color(0xFF14171D).copy(alpha = DoukColors.materialAlpha)
    } else {
        Color.White.copy(alpha = DoukColors.materialAlpha)
    }
    return this
        .clip(shape)
        .hazeEffect(
            state = DoukColors.hazeState,
            style = HazeStyle(
                backgroundColor = fill,
                tints = emptyList(),
                // Dock 比普通卡片多 4dp，压到栏下的内容只会剩柔和色块
                blurRadius = (DoukColors.materialBlur + 4f).dp,
                noiseFactor = 0f
            )
        )
        // 主题色只做一层很淡的染色，不改变白玻璃的通透感
        .background(DoukColors.accent.copy(alpha = if (dark) 0.10f else 0.08f), shape)
        .drawWithCache {
            val stroke = 1.2.dp.toPx()
            val inner = 1.6.dp.toPx()
            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
            val rim = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.98f),
                    DoukColors.accent.copy(alpha = 0.30f),
                    Color.White.copy(alpha = 0.52f),
                    Color.White.copy(alpha = 0.86f)
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
            onDrawWithContent {
                drawContent()
                // 内侧柔和光晕：模拟厚玻璃边缘吃光，不是一条硬描边
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.16f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 7.dp.toPx())
                )
                // 外圈折光边
                drawRoundRect(
                    brush = rim,
                    cornerRadius = radius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
                // 内圈极淡高光，模拟玻璃厚度
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.14f),
                    topLeft = Offset(inner, inner),
                    size = androidx.compose.ui.geometry.Size(
                        (size.width - inner * 2f).coerceAtLeast(0f),
                        (size.height - inner * 2f).coerceAtLeast(0f)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        ((size.height - inner * 2f) / 2f).coerceAtLeast(0f)
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.6.dp.toPx())
                )
            }
        }
}

/** 动效：曲线与时长成套，微交互 150ms、转场 250ms */
object DoukMotion {
    const val short = 150
    const val medium = 250
    const val long = 380
    val standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val decelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val spring = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1.0f)
}

private val doukTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.sp),
)

@Composable
fun DoukTheme(content: @Composable () -> Unit) {
    val dark = DoukColors.darkState.value

    // edge-to-edge 之后系统栏是透明的，图标深浅必须跟 app 自己的主题走，
    // 否则强制深色主题 + 系统浅色时，状态栏图标会变成黑压黑。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val scheme = if (dark) {
        darkColorScheme(
            primary = DoukColors.accent,
            onPrimary = Color.White,
            primaryContainer = DoukColors.accentContainer,
            onPrimaryContainer = DoukColors.textPrimary,
            background = DoukColors.background,
            onBackground = DoukColors.textPrimary,
            surface = DoukColors.surface,
            onSurface = DoukColors.textPrimary,
            surfaceVariant = DoukColors.surfaceMuted,
            onSurfaceVariant = DoukColors.textSecondary,
            outline = DoukColors.outline,
            outlineVariant = DoukColors.divider,
            error = DoukColors.danger
        )
    } else {
        lightColorScheme(
            primary = DoukColors.accent,
            onPrimary = Color.White,
            primaryContainer = DoukColors.accentContainer,
            onPrimaryContainer = DoukColors.textPrimary,
            secondary = DoukColors.textSecondary,
            background = DoukColors.background,
            onBackground = DoukColors.textPrimary,
            surface = DoukColors.surface,
            onSurface = DoukColors.textPrimary,
            surfaceVariant = DoukColors.surfaceMuted,
            onSurfaceVariant = DoukColors.textSecondary,
            outline = DoukColors.outline,
            outlineVariant = DoukColors.divider,
            error = DoukColors.danger
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = doukTypography,
        content = content
    )
}

