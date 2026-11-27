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
    attrs: AttributeSet? = null,
    /** 全屏监看要传 false：media overlay 会把 SurfaceView 抬到 Window 内容之上，Compose 叠加层就被盖住了 */
    mediaOverlay: Boolean = true
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
        setZOrderMediaOverlay(mediaOverlay)

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

    /** 伪色：在片元着色器里按 IRE 分段上色，整套在 GPU 上，不再做 CPU 查表缩放 */
    fun setFalseColor(enabled: Boolean) {
        if (renderer.falseColorEnabled == enabled) return
        renderer.falseColorEnabled = enabled
        requestRender()
    }

    /** 峰值对焦：GPU 边缘检测，和伪色互斥（伪色开启时不生效） */
    fun setPeaking(enabled: Boolean, threshold: Float = 0.08f) {
        if (renderer.peakingEnabled == enabled && renderer.peakThreshold == threshold) return
        renderer.peakingEnabled = enabled
        renderer.peakThreshold = threshold
        requestRender()
    }

    /**
     * 画面旋转 / 镜像交给着色器做 UV 反解。
     * SurfaceView 不吃 Compose 的 graphicsLayer，旋转镜像只能在 shader 里兜。
     */
    fun setUvTransform(rotationDeg: Int, mirror: Boolean) {
        val rot = ((rotationDeg % 360) + 360) % 360
        if (renderer.uvRotation == rot && renderer.uvMirror == mirror) return
        renderer.uvRotation = rot
        renderer.uvMirror = mirror
        requestRender()
    }

    /**
     * GPU 波形图。rect 为归一化 [x, y, w, h]（左上角 + 宽高，y 向下）；
     * 传 null 表示沿用上一次的位置。
     */
    fun setWaveform(enabled: Boolean, rect: FloatArray? = null) {
        var changed = false
        if (renderer.waveformEnabled != enabled) {
            renderer.waveformEnabled = enabled
            changed = true
        }
        if (rect != null && rect.size >= 4 && !rect.copyOf(4).contentEquals(renderer.waveformRect)) {
            renderer.waveformRect = rect.copyOf(4)
            changed = true
        }
        if (changed) requestRender()
    }
}


