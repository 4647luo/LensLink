package com.lutcamera.lutcamerapro.gpu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.lut.XmpData

class LutSurfaceView(context: Context) : GLSurfaceView(context) {

    private val renderer = LutRenderer()
    private var lastBitmap: Bitmap? = null
    private var lastLutData: LutData? = null
    private var lastXmpData: XmpData? = null

    @set:JvmName("setLutIntensityValue")
    var lutIntensity: Float = 0.7f
        set(value) {
            field = value
            queueEvent {
                renderer.lutIntensity = value
                requestRender()
            }
        }

    var isHighlightWarning: Boolean = false
        set(value) {
            field = value
            queueEvent {
                renderer.isHighlightWarning = value
                requestRender()
            }
        }

    var isShadowWarning: Boolean = false
        set(value) {
            field = value
            queueEvent {
                renderer.isShadowWarning = value
                requestRender()
            }
        }

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

    fun setLutIntensity(intensity: Float) {
        queueEvent {
            renderer.lutIntensity = intensity
            requestRender()
        }
    }

    fun setWarnings(isHighlight: Boolean, isShadow: Boolean) {
        queueEvent {
            renderer.isHighlightWarning = isHighlight
            renderer.isShadowWarning = isShadow
            requestRender()
        }
    }

    fun setImageBitmap(bitmap: Bitmap) {
        if (lastBitmap === bitmap) return
        lastBitmap = bitmap

        renderer.setImageBitmap(bitmap) {
            requestRender()
        }
    }

    fun setLutData(lutData: LutData) {
        if (lastLutData === lutData) return
        lastLutData = lutData

        renderer.setLutData(lutData) {
            requestRender()
        }
    }

    fun setXmpData(xmpData: XmpData?) {
        if (lastXmpData === xmpData) return
        lastXmpData = xmpData

        renderer.setXmpData(xmpData) {
            requestRender()
        }
    }
}
