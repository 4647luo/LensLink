package com.lutcamera.lutcamerapro.ui.screens.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.view.TextureView
import com.lutcamera.lutcamerapro.gpu.LutRenderer
import com.lutcamera.lutcamerapro.log.Log
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.lut.XmpData

/**
 * 监看用的 GPU 渲染视口。
 *
 * 为什么不用 GLSurfaceView：GLSurfaceView 是 SurfaceView，只能靠
 * `setZOrderMediaOverlay(true)` 抢到 Window 上层，那样它会盖住 Compose 画的
 * 构图线、图例、直方图；改成 false 又会被根布局的黑色背景挡住（SurfaceView 在
 * Window 之下）。TextureView 是普通 View，能正常参与 Compose 层级，所以监看这
 * 一页用它，EGL 由这里自己管，着色器与渲染器全部复用 [LutRenderer]。
 */
class MonitorTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private val renderer = LutRenderer()

    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var pendingRender = true
    @Volatile private var surfaceW = 1
    @Volatile private var surfaceH = 1
    private var renderThread: Thread? = null

    private var lastBitmap: Bitmap? = null
    private var lastLut: LutData? = null
    private var lastIntensity = Float.NaN

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    // ---------------- 对外接口 ----------------

    fun updateLiveFrame(bitmap: Bitmap) {
        if (lastBitmap === bitmap) return
        lastBitmap = bitmap
        renderer.setImageBitmap(bitmap) { requestRender() }
    }

    fun setLut(lutData: LutData?, intensity: Float = 1.0f) {
        val strength = if (lutData == null) 0f else intensity.coerceIn(0f, 1f)
        if (lastLut === lutData && lastIntensity == strength) return
        if (lutData != null && lastLut !== lutData) renderer.setLutData(lutData) { requestRender() }
        lastLut = lutData
        lastIntensity = strength
        renderer.lutIntensity = strength
        renderer.enableACES = lutData != null
        requestRender()
    }

    fun setXmp(xmpData: XmpData?) = renderer.setXmpData(xmpData) { requestRender() }

    fun setFalseColor(enabled: Boolean) {
        if (renderer.falseColorEnabled == enabled) return
        renderer.falseColorEnabled = enabled
        requestRender()
    }

    /** 峰值对焦：全在片元着色器里做，取景帧率不受影响 */
    fun setPeaking(enabled: Boolean, threshold: Float = 0.08f) {
        if (renderer.peakingEnabled == enabled && renderer.peakThreshold == threshold) return
        renderer.peakingEnabled = enabled
        renderer.peakThreshold = threshold
        requestRender()
    }

    fun setUvTransform(rotationDeg: Int, mirror: Boolean) {
        val rot = ((rotationDeg % 360) + 360) % 360
        if (renderer.uvRotation == rot && renderer.uvMirror == mirror) return
        renderer.uvRotation = rot
        renderer.uvMirror = mirror
        requestRender()
    }

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

    fun setWarnings(highlight: Boolean, shadow: Boolean) {
        if (renderer.isHighlightWarning == highlight && renderer.isShadowWarning == shadow) return
        renderer.isHighlightWarning = highlight
        renderer.isShadowWarning = shadow
        requestRender()
    }

    // ---------------- 渲染线程 ----------------

    fun requestRender() {
        synchronized(lock) {
            pendingRender = true
            lock.notifyAll()
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceW = width.coerceAtLeast(1)
        surfaceH = height.coerceAtLeast(1)
        startRenderThread(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceW = width.coerceAtLeast(1)
        surfaceH = height.coerceAtLeast(1)
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopRenderThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun startRenderThread(surface: SurfaceTexture) {
        stopRenderThread()
        running = true
        pendingRender = true
        renderThread = Thread({ renderLoop(surface) }, "monitor-gl").also { it.start() }
    }

    private fun stopRenderThread() {
        val thread = renderThread ?: return
        running = false
        requestRender()
        thread.join(2000)
        renderThread = null
    }

    private fun renderLoop(surface: SurfaceTexture) {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var eglSurface = EGL14.EGL_NO_SURFACE
        var lastW = 0
        var lastH = 0
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.e(TAG, "EGL 初始化失败")
                return
            }
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0 || configs[0] == null
            ) {
                Log.e(TAG, "没有可用的 ES3 EGLConfig")
                return
            }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "EGL 上下文创建失败")
                return
            }
            eglSurface = EGL14.eglCreateWindowSurface(
                display, config, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "EGL Surface 创建失败")
                return
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                Log.e(TAG, "eglMakeCurrent 失败")
                return
            }

            renderer.onSurfaceCreated(null, null)

            while (running) {
                synchronized(lock) {
                    if (!pendingRender) lock.wait(150)
                    pendingRender = false
                }
                if (!running) break
                val w = surfaceW
                val h = surfaceH
                if (w != lastW || h != lastH) {
                    lastW = w
                    lastH = h
                    renderer.onSurfaceChanged(null, w, h)
                }
                renderer.onDrawFrame(null)
                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.e(TAG, "eglSwapBuffers 失败: ${EGL14.eglGetError()}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "监看 GL 线程异常: $t")
        } finally {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
        }
    }

    companion object {
        private const val TAG = "MonitorGL"
        /** EGL14 里没有导出这个常量，值是 EGL_OPENGL_ES3_BIT_KHR */
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    }
}
