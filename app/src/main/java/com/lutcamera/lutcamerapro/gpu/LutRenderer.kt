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

        isGlInitialized = true

        if (pendingBitmap.get() != null || pendingLutData.get() != null) {
            requestRenderCallback?.invoke()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
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

