package com.lutcamera.lutcamerapro

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.lutcamera.lutcamerapro.ui.douk.DoukRoot
import com.lutcamera.lutcamerapro.ui.douk.SupportReminder
import com.lutcamera.lutcamerapro.ui.douk.UpdateCenter
import com.lutcamera.lutcamerapro.ui.theme.AppThemeManager
import com.lutcamera.lutcamerapro.ui.theme.LutcameraproTheme
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全面屏：状态栏 / 导航栏都让内容延伸进去，栏的背景由页面自己画（edge-to-edge）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 默认的 enableEdgeToEdge 会给导航条留一层浅色 scrim（部分 ROM 看起来就是一条白带）；
        // 这里显式把两侧 scrim 都设成全透明，手势条只留白色提示线。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.navigationBarDividerColor = Color.TRANSPARENT
        // MIUI 上仅靠 setDecorFitsSystemWindows 有时仍会把 DecorView 压到 2780，
        // 底部 100px 归系统导航栏，应用自己的浅色 chrome 永远铺不过去。
        // 这里补 legacy layout 标志，让根布局真正画到 2880，底部由 DoukRoot 的
        // 56dp chrome 盖住。
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        hideGestureNavigationBar()
        // 初始化黑白主题持久化配置（默认浅色白色苹果风格）
        AppThemeManager.init(this)
        // 主题、日志、设置都已在 DoukApplication 里初始化；这里只补 Activity 生命周期埋点
        Timber.i("MainActivity onCreate")

        setContent {
            LutcameraproTheme {
                DoukRoot()
            }
        }
        // 启动顺序：本月还没弹过赞赏提示就先弹一次，关掉后再检查更新；
        // 本月已弹过则直接检查更新。两个对话框不叠在一起。
        lifecycleScope.launch {
            val supportShown = SupportReminder.showIfDue(this@MainActivity) {
                lifecycleScope.launch {
                    UpdateCenter.checkOnLaunch(this@MainActivity)
                }
            }
            if (!supportShown) {
                UpdateCenter.checkOnLaunch(this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来、或用户上滑临时唤出导航条后，再把手势条收回去，保持底部背景连续。
        hideGestureNavigationBar()
    }

    /**
     * singleTask 下再次点图标或用 USB 插入唤起时不会新建 Activity，
     * 这里接住新 Intent，避免旧实例的 PTP/蓝牙/FTP 会话被第二个界面顶掉。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }


    private fun hideGestureNavigationBar() {
        val gestureNavigation = runCatching {
            android.provider.Settings.Secure.getInt(contentResolver, "navigation_mode", 0) == 2
        }.getOrDefault(false)
        if (!gestureNavigation) return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
