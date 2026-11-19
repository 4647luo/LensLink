package com.lutcamera.lutcamerapro.ui.screens.monitor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.lut.XmpData

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun FullscreenNikonPreview(frame: Bitmap?, lut: LutData?, intensity: Float, xmp: XmpData?,
                           warnings: Boolean, onClose: () -> Unit) {
    val activity = LocalContext.current.activity()
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { previous?.let { activity?.requestedOrientation = it } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val ratio = frame?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f/9f)
            val fit = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio, true)
                      else Modifier.fillMaxWidth().aspectRatio(ratio)
            AndroidView(factory = { LiveViewGlSurfaceView(it) }, update = { surface ->
                surface.setLut(lut, intensity)
                surface.setXmp(xmp)
                surface.setWarnings(warnings, warnings)
                frame?.let(surface::updateLiveFrame)
            }, modifier = fit)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).background(Color.Black.copy(alpha = 0.6f))) {
                Icon(Icons.Default.Close, "退出横屏预览", tint = Color.White)
            }
        }
    }
}
