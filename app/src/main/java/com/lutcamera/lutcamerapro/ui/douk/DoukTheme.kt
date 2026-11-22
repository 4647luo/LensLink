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
import androidx.compose.ui.graphics.lerp
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

    /** 「外观设置 → 高级材质」开关：卡片磨砂玻璃 + 可调模糊 */
    internal val glassState = mutableStateOf(true)
    /** 卡片不透明度：固定 60%，更透一点，背景模糊更明显 */
    internal val materialAlphaState = mutableStateOf(0.60f)
    /** 卡片/底栏背后模糊半径（dp），用户可调，上限 40dp */
    internal val materialBlurState = mutableStateOf(24f)
    /** 界面对比度固定 80%：1.0 太硬、0.35 发灰，取中间 */
    internal val contrastState = mutableStateOf(0.80f)
    /** 全站共享的背景源状态：页面内容画一次，卡片/底栏从里面取真实模糊。 */
    internal val hazeState = dev.chrisbanes.haze.HazeState()

    val accent: Color get() = accentState.value

    val isDark: Boolean get() = darkState.value

    val glass: Boolean get() = glassState.value

    val materialAlpha: Float get() = materialAlphaState.value

    val materialBlur: Float get() = materialBlurState.value

    val contrast: Float get() = contrastState.value

    /** 文字/描边随对比度向黑或白靠，背景和表面同时拉开层次 */
    private fun boost(color: Color, amount: Float): Color {
        val c = contrastState.value
        if (c <= 0f) return color
        val target = if (darkState.value) Color.White else Color.Black
        return lerp(color, target, (c * amount).coerceIn(0f, 0.55f))
    }

    /** 页面底层：带一点冷暖和主色倾向的渐变，玻璃卡片才有东西可透 */
    val backgroundBrush: Brush
        get() {
            val c = contrastState.value
            return if (darkState.value) Brush.linearGradient(
                listOf(
                    lerp(Color(0xFF0A0C11), Color.Black, c * 0.22f),
                    lerp(Color(0xFF141A23), Color.Black, c * 0.18f),
                    lerp(Color(0xFF0B0E13), Color.Black, c * 0.22f)
                )
            ) else Brush.linearGradient(
                listOf(
                    lerp(Color(0xFFF6FAFF), Color.Black, c * 0.06f),
                    lerp(Color(0xFFE8EFF9), Color.Black, c * 0.05f),
                    lerp(Color(0xFFF8F1EA), Color.Black, c * 0.06f)
                )
            )
        }

    /** 主色的极浅底（选中态、标签底） */
    val accentSoft: Color get() = accentState.value.copy(alpha = 0.10f)

    /** 主色的容器色，比 accentSoft 稍重，用于图标胶囊底 */
    val accentContainer: Color get() = accentState.value.copy(alpha = 0.16f)

    val accentOutline: Color get() = accentState.value.copy(alpha = 0.34f)

    // ===== 表面分级（Material 3 surface container 思路）=====
    val background: Color get() = if (darkState.value) {
        lerp(Color(0xFF0D0E11), Color.Black, contrastState.value * 0.18f)
    } else {
        lerp(Color(0xFFF5F6F8), Color.Black, contrastState.value * 0.035f)
    }
    val surface: Color get() = if (darkState.value) Color(0xFF16181C) else Color(0xFFFFFFFF)
    /** 卡片专用的「浅色」：比页面底色亮，落在 80% 透明度上也不会发灰 */
    val cardLight: Color get() = if (darkState.value) Color(0xFF2A2E36) else Color(0xFFFBFCFE)
    val surfaceRaised: Color get() = if (darkState.value) Color(0xFF1D1F24) else Color(0xFFFBFBFC)
    val surfaceMuted: Color get() = if (darkState.value) {
        boost(Color(0xFF212429), 0.12f)
    } else {
        boost(Color(0xFFF0F1F4), 0.08f)
    }

    // ===== 文本四级 =====
    val textPrimary: Color get() = boost(if (darkState.value) Color(0xFFF3F4F6) else Color(0xFF101114), 0.10f)
    val textSecondary: Color get() = boost(if (darkState.value) Color(0xFFA0A5AE) else Color(0xFF7C8189), 0.45f)
    val textTertiary: Color get() = boost(if (darkState.value) Color(0xFF6E737C) else Color(0xFFB0B4BC), 0.55f)

    // ===== 线与描边 =====
    val divider: Color get() = boost(if (darkState.value) Color(0xFF2A2D33) else Color(0xFFECEDF1), 0.14f)
    val outline: Color get() = boost(if (darkState.value) Color(0xFF3A3E46) else Color(0xFFD8DBE1), 0.18f)

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

    /** 界面对比度固定 100%，旧设置项不再生效 */
    fun applyContrast(value: Float) { contrastState.value = 0.80f }

    /** 高级材质：卡片不透明度固定 80%，只剩下模糊半径可调（0–32dp） */
    fun applyMaterial(alpha: Float, blurDp: Float) {
        materialAlphaState.value = 0.60f
        materialBlurState.value = blurDp.coerceIn(0f, 40f)
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
 * 打开后换成磨砂玻璃：柔光渐变 + 主色光晕 + 高光描边。
 *
 * 性能说明：卡片不再逐张调用 Haze 的真实模糊。页面滚动时几十张卡片同时
 * 重新计算 RenderEffect 是「突然变卡」的主因；这里用统一的半透明底色
 * 模拟磨砂层，不做渐变，底栏单独保留一层真实模糊。
 * [tint] 用于选中/警告态，只染一层很淡的语义色；[baseColor] 用于嵌套的
 * surfaceMuted 小卡，保持原来的深浅层级。
 */
fun Modifier.doukCardSurface(
    shape: Shape = DoukShapes.card,
    tint: Color = Color.Transparent,
    baseColor: Color = Color.Transparent,
    /** 独立窗口（比如图片查看器那种 Dialog）要传自己的 HazeState，全局状态在别的窗口里取不到背景 */
    haze: dev.chrisbanes.haze.HazeState = DoukColors.hazeState,
    /** 卡面不透明度：默认 0.60，压在黑底上时调高一点，字才看得清 */
    fillAlpha: Float = 0.78f
): Modifier {
    val dark = DoukColors.isDark
    // 用户要求：卡片底改成「浅色 + 80% 不透明度」，模糊强度交给设置里的滑杆。
    // 浅色卡面在浅色主题下比纯白更有层次，在深色主题下也比原来的深灰提亮一档，
    // 卡上的文字和图标才不会糊在背景里看不清。
    val cardBase = if (baseColor == Color.Transparent) {
        DoukColors.cardLight
    } else {
        lerp(baseColor, DoukColors.cardLight, 0.55f)
    }
    if (!DoukColors.glass) {
        return background(if (tint == Color.Transparent) cardBase else tint, shape)
    }
    val opacity = fillAlpha
    val fill = cardBase.copy(alpha = opacity)

    var surface = this
        .clip(shape)
        .hazeEffect(
            state = haze,
            style = HazeStyle(
                // 背景色留空，模糊层单独画；浅色卡面自己用 80% 透明度压在上面，
                // 否则模糊图会盖住底色，卡片就成了「透着一堆照片」的雾面，字看不清。
                backgroundColor = Color.Transparent,
                tints = emptyList(),
                // 直接给满用户设置的模糊半径（0–32dp）。之前封在 20dp 且卡片打七五折，
                // 小字和图标透过来还是能认出来，看起来就像「没糊」。
                blurRadius = (DoukColors.materialBlur * 1.9f).coerceIn(0f, 60f).dp,
                noiseFactor = 0f
            )
        )
        // 浅色卡面：78% 不透明度，压住背后的模糊层，字和图标才立得住。
        .background(fill, shape)
        // 再叠一层极淡的斜向高光，玻璃才有厚度。
        .background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) 0.08f else 0.10f),
                    Color.Transparent,
                    Color.White.copy(alpha = if (dark) 0.03f else 0.04f)
                )
            ),
            shape
        )
    if (tint != Color.Transparent) {
        surface = surface.background(tint.copy(alpha = 0.10f), shape)
    }
    val edge = if (dark) {
        Color.White.copy(alpha = 0.28f)
    } else {
        Color(0xFFD3D8E2).copy(alpha = 0.72f)
    }
    return surface
        .border(
            width = 2.4.dp,
            color = Color.White.copy(alpha = if (dark) 0.04f else 0.08f),
            shape = shape
        )
        .border(
            width = 1.2.dp,
            color = edge,
            shape = shape
        )
}

/**
 * 纯实心卡面：设置页、图片编辑器这类「要看清字」的页面专用。
 * 不挂 Haze、不叠高光、不做渐变，就是一层浅色底 + 一条细边。
 */
fun Modifier.doukFlatSurface(
    shape: Shape = DoukShapes.card,
    tint: Color = Color.Transparent,
    baseColor: Color = Color.Transparent
): Modifier {
    val dark = DoukColors.isDark
    val fill = if (baseColor == Color.Transparent) DoukColors.cardLight else baseColor
    val edge = if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFFDCE1E9)
    return this
        .clip(shape)
        .background(if (tint == Color.Transparent) fill else tint, shape)
        .border(1.dp, edge, shape)
}

/**
 * 底部 Douk 栏：背后真实高斯模糊 + 一层 80% 的浅色底，图标文字清楚。
 * 模糊半径直接取「外观设置 → 模糊强度」，0–32dp 实时生效。
 */
fun Modifier.doukGlassDock(shape: Shape): Modifier {
    if (!DoukColors.glass) return background(DoukColors.surface, shape)
    val dark = DoukColors.isDark
    // 底栏同样改成「浅色底压 80%」的画法：模糊层在下、浅色层在上，图标文字才够清楚。
    // 底栏单独按用户要求回到 60%：卡片是 78%，底栏保留更通透的一档
    val fill = if (dark) {
        Color(0xFF20242C).copy(alpha = 0.60f)
    } else {
        Color(0xFFFBFCFE).copy(alpha = 0.60f)
    }
    return this
        .clip(shape)
        .hazeEffect(
            state = DoukColors.hazeState,
            style = HazeStyle(
                backgroundColor = Color.Transparent,
                tints = emptyList(),
                blurRadius = (DoukColors.materialBlur * 1.9f).coerceIn(0f, 60f).dp,
                noiseFactor = 0f
            )
        )
        .background(fill, shape)
        // 主题色只染一点点，不破坏通透感
        .background(DoukColors.accent.copy(alpha = if (dark) 0.05f else 0.04f), shape)
        .drawWithCache {
            // 玻璃反光边：左上角接近纯白（0.95），沿对角线往右下角一路淡到几乎透明。
            // 不用均匀描边——那会变成一圈卡片边框；斜向渐变才像光从左上打过来时的折光。
            val stroke = 1.5.dp.toPx()
            val inset = stroke / 2f
            val radius = androidx.compose.ui.geometry.CornerRadius(
                ((size.height - stroke) / 2f).coerceAtLeast(0f)
            )
            val rim = Brush.linearGradient(
                colorStops = arrayOf(
                    // 左上角最亮 → 沿边往中间淡下去 → 到右下角再亮回来，模拟一块厚玻璃斜向受光时
                    // 两个对角端点的高光，右上/左下两条边自然变浅
                    0.00f to Color.White.copy(alpha = 0.95f),
                    0.16f to Color.White.copy(alpha = 0.62f),
                    0.38f to Color.White.copy(alpha = 0.24f),
                    0.52f to Color.White.copy(alpha = 0.14f),
                    0.68f to Color.White.copy(alpha = 0.30f),
                    0.86f to Color.White.copy(alpha = 0.66f),
                    1.00f to Color.White.copy(alpha = 0.92f)
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
            onDrawWithContent {
                drawContent()
                // 上缘那层很淡的白，模拟玻璃顶部吃光；只在胶囊上半段出现，往下化开。
                // 浅色页面里光靠一圈描边几乎看不见，加这层才有「玻璃」的厚度感。
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White.copy(alpha = 0.34f),
                            0.16f to Color.White.copy(alpha = 0.18f),
                            0.38f to Color.White.copy(alpha = 0.06f),
                            0.60f to Color.Transparent
                        )
                    ),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height)
                )
                drawRoundRect(
                    brush = rim,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(
                        (size.width - stroke).coerceAtLeast(0f),
                        (size.height - stroke).coerceAtLeast(0f)
                    ),
                    cornerRadius = radius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
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

