package com.lutcamera.lutcamerapro.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 仿苹果设计风格色彩体系 (Apple iOS HIG)
 */
data class AppleColorPalette(
    val isDark: Boolean,
    val background: Color,         // 页面主底色：浅色模式为 iOS 系统灰 #F2F2F7，深色模式为纯黑 #000000
                val surface: Color,            // 卡片背景色：浅色为纯白 #FFFFFF，深色模式为深空灰 #1C1C1E
                val surfaceSecondary: Color,   // 次级卡片底色：浅色为浅灰 #E5E5EA，深色模式为 #2C2C2E
                val cardBackground: Color,     // 高级悬浮卡片背景
                val textPrimary: Color,        // 主标题与正文主色：浅色为深黑 #000000，深色模式为纯白 #FFFFFF
                val textSecondary: Color,      // 副标题与提示文字色：浅灰 #8E8E93
                val textTertiary: Color,       // 三级文本色：浅色 #C7C7CC / 深色 #48484A
                val accent: Color,             // 苹果系统强调色调蓝 #007AFF
                val accentPurple: Color,       // 典雅苹果紫 #5856D6
                val accentGreen: Color,        // 苹果系统成功绿 #34C759
                val accentOrange: Color,       // 温暖橙 #FF9500
                val accentRed: Color,          // 红色 #FF3B30
                val divider: Color,            // 细腻分隔线：浅色 #E5E5EA，深色模式 #2C2C2E
                val border: Color,             // 卡片轮廓描边：浅色 #E5E5EA，深色模式 #38383A
                val chipSelected: Color,       // 标签选中底色
                val chipUnselected: Color
)

val LightApplePalette = AppleColorPalette(
    isDark = false,
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFE5E5EA),
    cardBackground = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFFC7C7CC),
    accent = Color(0xFF007AFF),
    accentPurple = Color(0xFF007AFF),
    accentGreen = Color(0xFF007AFF),
    accentOrange = Color(0xFF007AFF),
    accentRed = Color(0xFFFF3B30),
    divider = Color(0xFFE5E5EA),
    border = Color(0xFFE5E5EA),
    chipSelected = Color(0xFF007AFF),
    chipUnselected = Color(0xFFF2F2F7)
)

val DarkApplePalette = AppleColorPalette(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceSecondary = Color(0xFF2C2C2E),
    cardBackground = Color(0xFF1C1C1E),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFF48484A),
    accent = Color(0xFF0A84FF),
    accentPurple = Color(0xFF0A84FF),
    accentGreen = Color(0xFF0A84FF),
    accentOrange = Color(0xFF0A84FF),
    accentRed = Color(0xFFFF453A),
    divider = Color(0xFF38383A),
    border = Color(0xFF38383A),
    chipSelected = Color(0xFF0A84FF),
    chipUnselected = Color(0xFF2C2C2E)
)

val LocalAppleColors = staticCompositionLocalOf { LightApplePalette }

/**
 * 全局单例封装：Composable 直接通过 AppleTheme.colors.xxx 访问 */
object AppleTheme {
    val colors: AppleColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalAppleColors.current
}

/**
 * 仿苹果全局黑白主题管理器（单例，可观测，带持久化记忆） */
object AppThemeManager {
    private const val PREF_NAME = "apple_theme_prefs"
    private const val KEY_IS_DARK = "is_dark_mode"

    // 默认开启黑白主题 (false = Light)
                private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 默认采用浅色模式偏好
                _isDarkMode.value = sp.getBoolean(KEY_IS_DARK, false)
    }

    fun toggleTheme(context: Context? = null) {
        setDarkMode(!_isDarkMode.value, context)
    }

    fun setDarkMode(dark: Boolean, context: Context? = null) {
        _isDarkMode.value = dark
        context?.let {
            val sp = it.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            sp.edit().putBoolean(KEY_IS_DARK, dark).apply()
        }
    }
}



