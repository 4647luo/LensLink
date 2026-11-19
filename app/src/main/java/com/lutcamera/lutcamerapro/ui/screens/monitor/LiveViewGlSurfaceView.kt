package com.lutcamera.lutcamerapro.ui.screens.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.lutcamera.lutcamerapro.gpu.LutRenderer
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.lut.XmpData

/**
 * 🚀 实时图传专用超低延迟 OpenGL 渲染视口 (LiveView Monitor)
 * 支持将相机 USB/无线回传的视频流以 30fps+ 渲染，并实时叠加 3D LUT 与 XMP 调色效果
 */
class LiveViewGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = LutRenderer()
    private var lastBitmap: Bitmap? = null
    private var lastLut: LutData? = null
    private var lastIntensity = Float.NaN
    private var lastXmp: XmpData? = null
    private var lastWarnings: Pair<Boolean, Boolean>? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)

        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onResume()
        requestRender()
    }

    override fun onDetachedFromWindow() {
        onPause()
        super.onDetachedFromWindow()
    }

    /**
     * 鏋侀€熸洿新实时图传视频帧
     */
    fun updateLiveFrame(bitmap: Bitmap) {
        if (lastBitmap === bitmap) return
        lastBitmap = bitmap
        renderer.setImageBitmap(bitmap) {
            requestRender()
        }
    }

    fun setLut(lutData: LutData?, intensity: Float = 1.0f) {
        val strength = if (lutData == null) 0f else intensity.coerceIn(0f, 1f)
        if (lastLut === lutData && lastIntensity == strength) return
        if (lutData != null && lastLut !== lutData) {
            renderer.setLutData(lutData) { requestRender() }
        }
        lastLut = lutData
        lastIntensity = strength
        renderer.lutIntensity = strength
        renderer.enableACES = lastLut != null || lastXmp != null
        requestRender()
    }

    fun setXmp(xmpData: XmpData?) {
        if (lastXmp === xmpData) return
        lastXmp = xmpData
        renderer.setXmpData(xmpData) { requestRender() }
        renderer.enableACES = lastLut != null || lastXmp != null
        requestRender()
    }

    fun setWarnings(highlight: Boolean, shadow: Boolean) {
        val warnings = highlight to shadow
        if (lastWarnings == warnings) return
        lastWarnings = warnings
        renderer.isHighlightWarning = highlight
        renderer.isShadowWarning = shadow
        requestRender()
    }
}


