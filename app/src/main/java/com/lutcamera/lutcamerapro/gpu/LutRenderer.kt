package com.lutcamera.lutcamerapro.gpu

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.lutcamera.lutcamerapro.log.Log
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.lut.XmpData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class LutRenderer : GLSurfaceView.Renderer {

    companion object {
        /** 波形离屏纹理：256 列对应画面宽度，128 行对应亮度轴 */
        private const val WAVE_WIDTH = 256
        private const val WAVE_HEIGHT = 128
        /** 每列采样多少行（总采样点 = 256 × 160 = 40960，GPU 上很轻） */
        private const val WAVE_ROWS = 160
    }

    private var programId: Int = 0
    private var aPositionHandle: Int = 0
    private var aTextureCoordHandle: Int = 0
    private var uImageTextureHandle: Int = 0
    private var uLutTextureHandle: Int = 0
    private var uLutSizeHandle: Int = 0
    private var uIntensityHandle: Int = 0
    private var uExposureHandle: Int = 0
    private var uTemperatureHandle: Int = 0
    private var uTintHandle: Int = 0
    private var uUseACESHandle: Int = 0
    private var uUseTetrahedralHandle: Int = 0
    private var uShowHighlightWarningHandle: Int = 0
    private var uShowShadowWarningHandle: Int = 0

    // XMP Uniforms
                private var uEnableXmpHandle: Int = 0
    private var uXmpExposureHandle: Int = 0
    private var uXmpContrastHandle: Int = 0
    private var uXmpHighlightsHandle: Int = 0
    private var uXmpShadowsHandle: Int = 0
    private var uXmpWhitesHandle: Int = 0
    private var uXmpBlacksHandle: Int = 0
    private var uXmpTemperatureHandle: Int = 0
    private var uXmpTintHandle: Int = 0
    private var uXmpVibranceHandle: Int = 0
    private var uXmpSaturationHandle: Int = 0
    private var uXmpClarityHandle: Int = 0
    private var uXmpDehazeHandle: Int = 0
    private var uXmpHueAdjustHandle: Int = 0
    private var uXmpSatAdjustHandle: Int = 0
    private var uXmpLumAdjustHandle: Int = 0

    private var imageTextureId: Int = 0
    private var lutTextureId: Int = 0
    private var currentLutSize: Float = 33f

    @Volatile var lutIntensity: Float = 1.0f
    @Volatile var exposure: Float = 0.0f
    @Volatile var temperature: Float = 0.0f
    @Volatile var tint: Float = 0.0f
    @Volatile var enableACES: Boolean = true
    @Volatile var autoDetectGpuTier: Boolean = true
    @Volatile var isTetrahedralSupported: Boolean = true
    @Volatile var isHighlightWarning: Boolean = false
    @Volatile var isShadowWarning: Boolean = false

    @Volatile var currentXmpData: XmpData? = null

    // ---- 监看专用：伪色 / GPU 旋转镜像 / GPU 波形 ----
    @Volatile var falseColorEnabled: Boolean = false
    /** 峰值对焦：GPU 边缘检测，阈值 0.02～0.25，越大只显示越硬的边 */
    @Volatile var peakingEnabled: Boolean = false
    @Volatile var peakThreshold: Float = 0.08f
    @Volatile var uvRotation: Int = 0
    @Volatile var uvMirror: Boolean = false
    @Volatile var waveformEnabled: Boolean = false

    /** 波形区域：归一化 [x, y, w, h]，x/y 取左上角、y 轴向下（和 Compose 坐标一致） */
    @Volatile var waveformRect: FloatArray = floatArrayOf(0.04f, 0.66f, 0.42f, 0.30f)

    private var uFalseColorHandle = 0
    private var uPeakingHandle = 0
    private var uPeakThresholdHandle = 0
    private var uUvRotationHandle = 0
    private var uUvMirrorHandle = 0

    private var waveSplatProgram = 0
    private var waveDisplayProgram = 0
    private var waveSplatSrcHandle = 0
    private var waveSplatRowsHandle = 0
    private var waveSplatWeightHandle = 0
    private var waveDisplayTexHandle = 0
    private var waveDisplayGainHandle = 0
    private var waveDisplayPosHandle = 0
    private var waveDisplayUvHandle = 0
    private var waveFbo = 0
    private var waveTexture = 0
    private var surfaceW = 1
    private var surfaceH = 1

    @Volatile private var isGlInitialized = false
    private val pendingBitmap = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
    private var displayedBitmap: Bitmap? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private var textureConfig: Bitmap.Config? = null
    private val pendingLutData = java.util.concurrent.atomic.AtomicReference<LutData?>(null)
    private var displayedLut: LutData? = null
    private var lutTextures = LutTextureManager()
    @Volatile private var requestRenderCallback: (() -> Unit)? = null

    private val vertexData = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
    private val textureData = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f)

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexData).also { it.position(0) }

    private val textureBuffer: FloatBuffer = ByteBuffer.allocateDirect(textureData.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureData).also { it.position(0) }

    fun setImageBitmap(bitmap: Bitmap, callback: () -> Unit) {
        pendingBitmap.set(bitmap)
        this.requestRenderCallback = callback
        if (isGlInitialized) callback()
    }

    fun setLutData(lutData: LutData, callback: () -> Unit) {
        pendingLutData.set(lutData)
        this.requestRenderCallback = callback
        if (isGlInitialized) callback()
    }

    fun setXmpData(xmpData: XmpData?, callback: () -> Unit) {
        currentXmpData = xmpData
        this.requestRenderCallback = callback
        if (isGlInitialized) callback()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        isGlInitialized = false
        // The previous context owns the old texture names; never reuse or delete them here.
        imageTextureId = 0
        lutTextureId = 0
        lutTextures = LutTextureManager()
        val identity = LutData("Preview identity", 2, floatArrayOf(
            0f,0f,0f, 1f,0f,0f, 0f,1f,0f, 1f,1f,0f,
            0f,0f,1f, 1f,0f,1f, 0f,1f,1f, 1f,1f,1f))
        pendingLutData.compareAndSet(null, displayedLut ?: identity)
        textureWidth = 0; textureHeight = 0; textureConfig = null
        pendingBitmap.compareAndSet(null, displayedBitmap)
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)

        val rendererString = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
        if (autoDetectGpuTier) {
            isTetrahedralSupported = isFlagshipGpuRegex(rendererString)
        }

        programId = LutShaders.createProgram()
        if (programId == 0) return

        GLES30.glUseProgram(programId)
        aPositionHandle = GLES30.glGetAttribLocation(programId, "aPosition")
        aTextureCoordHandle = GLES30.glGetAttribLocation(programId, "aTextureCoord")
        uImageTextureHandle = GLES30.glGetUniformLocation(programId, "uImageTexture")
        uLutTextureHandle = GLES30.glGetUniformLocation(programId, "uLutTexture")
        uLutSizeHandle = GLES30.glGetUniformLocation(programId, "uLutSize")
        uIntensityHandle = GLES30.glGetUniformLocation(programId, "uIntensity")
        uExposureHandle = GLES30.glGetUniformLocation(programId, "uExposure")
        uTemperatureHandle = GLES30.glGetUniformLocation(programId, "uTemperature")
        uTintHandle = GLES30.glGetUniformLocation(programId, "uTint")
        uUseACESHandle = GLES30.glGetUniformLocation(programId, "uUseACES")
        uUseTetrahedralHandle = GLES30.glGetUniformLocation(programId, "uUseTetrahedral")
        uShowHighlightWarningHandle = GLES30.glGetUniformLocation(programId, "uShowHighlightWarning")
        uShowShadowWarningHandle = GLES30.glGetUniformLocation(programId, "uShowShadowWarning")
        uFalseColorHandle = GLES30.glGetUniformLocation(programId, "uFalseColor")
        uPeakingHandle = GLES30.glGetUniformLocation(programId, "uPeaking")
        uPeakThresholdHandle = GLES30.glGetUniformLocation(programId, "uPeakThreshold")
        uUvRotationHandle = GLES30.glGetUniformLocation(programId, "uUvRotation")
        uUvMirrorHandle = GLES30.glGetUniformLocation(programId, "uUvMirror")

        // 绑定 XMP Uniforms
                uEnableXmpHandle = GLES30.glGetUniformLocation(programId, "uEnableXmp")
        uXmpExposureHandle = GLES30.glGetUniformLocation(programId, "uXmpExposure")
        uXmpContrastHandle = GLES30.glGetUniformLocation(programId, "uXmpContrast")
        uXmpHighlightsHandle = GLES30.glGetUniformLocation(programId, "uXmpHighlights")
        uXmpShadowsHandle = GLES30.glGetUniformLocation(programId, "uXmpShadows")
        uXmpWhitesHandle = GLES30.glGetUniformLocation(programId, "uXmpWhites")
        uXmpBlacksHandle = GLES30.glGetUniformLocation(programId, "uXmpBlacks")
        uXmpTemperatureHandle = GLES30.glGetUniformLocation(programId, "uXmpTemperature")
        uXmpTintHandle = GLES30.glGetUniformLocation(programId, "uXmpTint")
        uXmpVibranceHandle = GLES30.glGetUniformLocation(programId, "uXmpVibrance")
        uXmpSaturationHandle = GLES30.glGetUniformLocation(programId, "uXmpSaturation")
        uXmpClarityHandle = GLES30.glGetUniformLocation(programId, "uXmpClarity")
        uXmpDehazeHandle = GLES30.glGetUniformLocation(programId, "uXmpDehaze")
        uXmpHueAdjustHandle = GLES30.glGetUniformLocation(programId, "uXmpHueAdjust")
        uXmpSatAdjustHandle = GLES30.glGetUniformLocation(programId, "uXmpSatAdjust")
        uXmpLumAdjustHandle = GLES30.glGetUniformLocation(programId, "uXmpLumAdjust")

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        imageTextureId = textures[0]

        createWaveProgramsAndTarget()

        isGlInitialized = true

        if (pendingBitmap.get() != null || pendingLutData.get() != null) {
            requestRenderCallback?.invoke()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width.coerceAtLeast(1)
        surfaceH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (programId == 0 || !isGlInitialized) return

        pendingBitmap.getAndSet(null)?.let { bmp ->
            load2DTexture(bmp)
            displayedBitmap = bmp
        }

        pendingLutData.getAndSet(null)?.let { lut ->
            lutTextureId = lutTextures.getOrCreateLutTexture(lut)
            currentLutSize = lut.size.toFloat()
            displayedLut = lut
        }

        if (imageTextureId == 0) return

        GLES30.glUseProgram(programId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTextureId)
        GLES30.glUniform1i(uImageTextureHandle, 0)

        if (lutTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glUniform1i(uLutTextureHandle, 1)
        }

        GLES30.glUniform1f(uLutSizeHandle, currentLutSize)
        GLES30.glUniform1f(uIntensityHandle, lutIntensity)
        GLES30.glUniform1f(uExposureHandle, exposure)
        GLES30.glUniform1f(uTemperatureHandle, temperature)
        GLES30.glUniform1f(uTintHandle, tint)
        GLES30.glUniform1i(uUseACESHandle, if (enableACES) 1 else 0)
        GLES30.glUniform1i(uUseTetrahedralHandle, if (isTetrahedralSupported) 1 else 0)
        GLES30.glUniform1i(uShowHighlightWarningHandle, if (isHighlightWarning) 1 else 0)
        GLES30.glUniform1i(uShowShadowWarningHandle, if (isShadowWarning) 1 else 0)
        GLES30.glUniform1i(uFalseColorHandle, if (falseColorEnabled) 1 else 0)
        GLES30.glUniform1i(uPeakingHandle, if (peakingEnabled && !falseColorEnabled) 1 else 0)
        GLES30.glUniform1f(uPeakThresholdHandle, peakThreshold.coerceIn(0.01f, 0.4f))
        GLES30.glUniform1i(uUvRotationHandle, uvRotation)
        GLES30.glUniform1i(uUvMirrorHandle, if (uvMirror) 1 else 0)

        // 填充 XMP 参数
                val xmp = currentXmpData
        if (xmp != null) {
            GLES30.glUniform1i(uEnableXmpHandle, 1)
            GLES30.glUniform1f(uXmpExposureHandle, xmp.exposure)
            GLES30.glUniform1f(uXmpContrastHandle, xmp.contrast)
            GLES30.glUniform1f(uXmpHighlightsHandle, xmp.highlights)
            GLES30.glUniform1f(uXmpShadowsHandle, xmp.shadows)
            GLES30.glUniform1f(uXmpWhitesHandle, xmp.whites)
            GLES30.glUniform1f(uXmpBlacksHandle, xmp.blacks)
            GLES30.glUniform1f(uXmpTemperatureHandle, xmp.temperature)
            GLES30.glUniform1f(uXmpTintHandle, xmp.tint)
            GLES30.glUniform1f(uXmpVibranceHandle, xmp.vibrance)
            GLES30.glUniform1f(uXmpSaturationHandle, xmp.saturation)
            GLES30.glUniform1f(uXmpClarityHandle, xmp.clarity)
            GLES30.glUniform1f(uXmpDehazeHandle, xmp.dehaze)
            GLES30.glUniform1fv(uXmpHueAdjustHandle, 8, xmp.hueAdjustments, 0)
            GLES30.glUniform1fv(uXmpSatAdjustHandle, 8, xmp.saturationAdjustments, 0)
            GLES30.glUniform1fv(uXmpLumAdjustHandle, 8, xmp.luminanceAdjustments, 0)
        } else {
            GLES30.glUniform1i(uEnableXmpHandle, 0)
        }

        GLES30.glEnableVertexAttribArray(aPositionHandle)
        GLES30.glVertexAttribPointer(aPositionHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES30.glVertexAttribPointer(aTextureCoordHandle, 2, GLES30.GL_FLOAT, false, 0, textureBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPositionHandle)
        GLES30.glDisableVertexAttribArray(aTextureCoordHandle)

        // 波形图：全部在 GPU 上算，不再由 CPU 生成 256×128 位图
        if (waveformEnabled && waveFbo != 0) drawWaveform()
    }

    /** 建波形用的两个 program 与离屏目标（RGBA8 加法累加，然后按增益显示）。 */
    private fun createWaveProgramsAndTarget() {
        waveFbo = 0
        waveTexture = 0
        waveSplatProgram = LutShaders.createWaveSplatProgram()
        waveDisplayProgram = LutShaders.createWaveDisplayProgram()
        if (waveSplatProgram != 0) {
            waveSplatSrcHandle = GLES30.glGetUniformLocation(waveSplatProgram, "uSrc")
            waveSplatRowsHandle = GLES30.glGetUniformLocation(waveSplatProgram, "uGridRows")
            waveSplatWeightHandle = GLES30.glGetUniformLocation(waveSplatProgram, "uPointWeight")
        }
        if (waveDisplayProgram != 0) {
            waveDisplayTexHandle = GLES30.glGetUniformLocation(waveDisplayProgram, "uWave")
            waveDisplayGainHandle = GLES30.glGetUniformLocation(waveDisplayProgram, "uGain")
            waveDisplayPosHandle = GLES30.glGetAttribLocation(waveDisplayProgram, "aPosition")
            waveDisplayUvHandle = GLES30.glGetAttribLocation(waveDisplayProgram, "aTextureCoord")
        }

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            WAVE_WIDTH, WAVE_HEIGHT, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, tex[0], 0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("LutRenderer", "波形离屏目标不可用: 0x${Integer.toHexString(status)}")
            GLES30.glDeleteTextures(1, tex, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            return
        }
        waveTexture = tex[0]
        waveFbo = fbo[0]
    }

    /**
     * 波形渲染：pass1 把源帧按「列 → x、亮度 → y」泼溅累加到离屏纹理，
     * pass2 把这张纹理按 [waveformRect] 画到画面内的一个小窗口里。
     */
    private fun drawWaveform() {
        val src = imageTextureId
        if (src == 0 || waveSplatProgram == 0) return

        // ---- pass 1：点泼溅
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, waveFbo)
        GLES30.glViewport(0, 0, WAVE_WIDTH, WAVE_HEIGHT)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(waveSplatProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src)
        GLES30.glUniform1i(waveSplatSrcHandle, 0)
        GLES30.glUniform1f(waveSplatRowsHandle, WAVE_ROWS.toFloat())
        GLES30.glUniform1f(waveSplatWeightHandle, 0.02f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, WAVE_WIDTH * WAVE_ROWS)
        GLES30.glDisable(GLES30.GL_BLEND)

        // ---- pass 2：显示到画面内指定矩形
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val rect = waveformRect
        val px = (rect[0] * surfaceW).toInt().coerceIn(0, surfaceW - 1)
        val pw = (rect[2] * surfaceW).toInt().coerceIn(1, surfaceW - px)
        val ph = (rect[3] * surfaceH).toInt().coerceIn(1, surfaceH - 1)
        // GL 的 viewport 原点在左下，Compose 的 y 从上往下，这里换算一次
        val py = (surfaceH - (rect[1] * surfaceH).toInt() - ph).coerceIn(0, surfaceH - ph)
        GLES30.glViewport(px, py, pw, ph)

        GLES30.glUseProgram(waveDisplayProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, waveTexture)
        GLES30.glUniform1i(waveDisplayTexHandle, 0)
        // RGBA8 累加每个点只加 0.02，显示时放大 ~14 倍才看得见密度
        GLES30.glUniform1f(waveDisplayGainHandle, 14f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnableVertexAttribArray(waveDisplayPosHandle)
        GLES30.glVertexAttribPointer(waveDisplayPosHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glEnableVertexAttribArray(waveDisplayUvHandle)
        GLES30.glVertexAttribPointer(waveDisplayUvHandle, 2, GLES30.GL_FLOAT, false, 0, textureBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(waveDisplayPosHandle)
        GLES30.glDisableVertexAttribArray(waveDisplayUvHandle)
        GLES30.glDisable(GLES30.GL_BLEND)

        // 恢复全屏 viewport，否则下一帧主画面会被裁到波形窗口里
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
    }

    private fun load2DTexture(bitmap: Bitmap) {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        if (textureWidth == bitmap.width && textureHeight == bitmap.height && textureConfig == bitmap.config) {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            textureWidth = bitmap.width
            textureHeight = bitmap.height
            textureConfig = bitmap.config
        }
    }

    private fun isFlagshipGpuRegex(renderer: String): Boolean {
        val patterns = listOf(
            Regex("Adreno\\s*[78]\\d\\d", RegexOption.IGNORE_CASE),
            Regex("Mali-G7\\d\\d?", RegexOption.IGNORE_CASE),
            Regex("Apple", RegexOption.IGNORE_CASE)
        )
        return patterns.any { it.containsMatchIn(renderer) }
    }
}

