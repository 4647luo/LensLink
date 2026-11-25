package com.lutcamera.lutcamerapro.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AppleDarkColorScheme = darkColorScheme(
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF5856D6),
    tertiary = Color(0xFF34C759),
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF38383A)
)

private val AppleLightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    secondary = Color(0xFF5856D6),
    tertiary = Color(0xFF34C759),
    background = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFFE5E5EA)
)

@Composable
fun LutcameraproTheme(
    content: @Composable () -> Unit
) {
    val isDark by AppThemeManager.isDarkMode.collectAsStateWithLifecycle()
    val colorScheme = if (isDark) AppleDarkColorScheme else AppleLightColorScheme
    val duoColors = if (isDark) DarkDuoPalette else LightDuoPalette
    val appleColors = if (isDark) DarkApplePalette else LightApplePalette

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // 系统栏不铺实色：页面底层是 F6FAFF→E8EFF9→F8F1EA 的渐变，
                // 状态栏一旦涂成背景纯色，顶部就会多出一条比页面更灰的横带，
                // 看着像是「应用内容和状态栏不是一家」。这里保持透明，
                // edge-to-edge 下由根布局的渐变一路铺到屏幕顶端。
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalDuoColors provides duoColors,
        LocalAppleColors provides appleColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

