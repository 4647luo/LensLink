package com.lutcamera.lutcamerapro.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 🦉 多邻国（Duolingo）全套高保真设计调色板 */
data class DuoColorPalette(
    val isDark: Boolean,
    val green: Color = Color(0xFF58CC02),
    val greenShadow: Color = Color(0xFF58A700),
    val sky: Color = Color(0xFF1CB0F6),
    val skyShadow: Color = Color(0xFF1899D6),
    val yellow: Color = Color(0xFFFFC800),
    val yellowShadow: Color = Color(0xFFE6B400),
    val fox: Color = Color(0xFFFF9600),
    val foxShadow: Color = Color(0xFFE07D00),
    val red: Color = Color(0xFFFF4B4B),
    val redShadow: Color = Color(0xFFEA2B2B),
    val purple: Color = Color(0xFFCE82FF),
    val purpleShadow: Color = Color(0xFFA559D8),

    val background: Color,
    val surface: Color,
    val surfaceSecondary: Color,
    val cardBackground: Color,
    val border: Color,
    val borderSecondary: Color,

    // 文字色
                val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    val divider: Color
)

val LightDuoPalette = DuoColorPalette(
    isDark = false,
    background = Color(0xFFF7F7F7),
    surface = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFF0F0F0),
    cardBackground = Color(0xFFFFFFFF),
    border = Color(0xFFE5E5E5),
    borderSecondary = Color(0xFFDCDCDC),
    textPrimary = Color(0xFF3C3C3C),
    textSecondary = Color(0xFF777777),
    textTertiary = Color(0xFFAFAFAF),
    divider = Color(0xFFE5E5E5)
)

val DarkDuoPalette = DuoColorPalette(
    isDark = true,
    background = Color(0xFF131F24),
    surface = Color(0xFF202F36),
    surfaceSecondary = Color(0xFF18272F),
    cardBackground = Color(0xFF202F36),
    border = Color(0xFF37464F),
    borderSecondary = Color(0xFF2B3A42),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA5B6BE),
    textTertiary = Color(0xFF6D7D86),
    divider = Color(0xFF37464F)
)

val LocalDuoColors = staticCompositionLocalOf { LightDuoPalette }

object DuoTheme {
    val colors: DuoColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalDuoColors.current
}



