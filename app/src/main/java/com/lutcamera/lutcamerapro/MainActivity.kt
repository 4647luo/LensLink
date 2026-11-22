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
        // 三段式导航栏的系统半透明蒙层会破坏底栏颜色的连续性（minSdk 29，系统都支持这两个开关）
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        // 小米等 ROM 的手势导航条会强制铺一层 #F2F2F7 的浅色底，应用改 navigationBarColor 也无效。
        // 只在「手势导航」模式下隐藏系统导航条，页面渐变才能真正铺到最底；三键导航保留系统按钮。
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
        // 启动自动检查：设置里可关；24 小时内不重复请求，失败静默，只有新版本才弹窗
        lifecycleScope.launch {
            UpdateCenter.checkOnLaunch(this@MainActivity)
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
