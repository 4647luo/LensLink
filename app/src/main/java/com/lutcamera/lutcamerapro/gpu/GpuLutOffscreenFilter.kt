package com.lutcamera.lutcamerapro.gpu

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.opengl.GLUtils
import com.lutcamera.lutcamerapro.log.Log
import com.lutcamera.lutcamerapro.lut.LutData
import com.lutcamera.lutcamerapro.lut.XmpData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A render owns its EGL context and textures; texture IDs never cross context boundaries. */
object GpuLutOffscreenFilter {
    private const val TAG = "GpuLutOffscreenFilter"
    private val VERTEX_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val TEXTURE_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    @Synchronized
    fun applyLut(
        originalBitmap: Bitmap,
        lutData: LutData? = null,
        intensity: Float = 1.0f,
        exposure: Float = 0.0f,
        temperature: Float = 0.0f,
        tint: Float = 0.0f,
        enableACES: Boolean = true,
        useTetrahedral: Boolean = true,
        xmpData: XmpData? = null
    ): Bitmap {
        require(!originalBitmap.isRecycled) { "输入照片已经释放" }
        require(intensity.isFinite() && exposure.isFinite() && temperature.isFinite() && tint.isFinite()) {
            "调色参数含无效数值"
        }
        val width = originalBitmap.width
        val height = originalBitmap.height
        val byteCount = width.toLong() * height * 4
        require(byteCount <= Int.MAX_VALUE) { "照片过大，请选择 1080P 成片后重试" }

        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "无法获取 GPU 显示连接" }
        var eglContext = EGL14.EGL_NO_CONTEXT
        var eglSurface = EGL14.EGL_NO_SURFACE
        var madeCurrent = false
        var program = 0
        val fbo = IntArray(1)
        val fboTexture = IntArray(1)
        val imageTexture = IntArray(1)
        val lutTexture = IntArray(1)
        var result: Bitmap? = null
        var succeeded = false
        try {
            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "GPU 初始化失败" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            )
            check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                "设备不支持所需的 OpenGL ES 3 成片渲染"
            }
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "无法创建成片 GPU 上下文" }
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "无法创建成片 GPU 画布" }
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "无法启用成片 GPU 上下文" }
            madeCurrent = true
            val maxTextureSize = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            require(width <= maxTextureSize[0] && height <= maxTextureSize[0]) {
                "照片尺寸超过 GPU 上限 " + maxTextureSize[0] + "，请选择 1080P 成片后重试"
            }
            val maxViewport = IntArray(2)
            GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, maxViewport, 0)
            require(width <= maxViewport[0] && height <= maxViewport[1]) {
                "照片尺寸超过 GPU 画布上限，请选择 1080P 成片后重试"
            }
            program = LutShaders.createProgram()
            check(program != 0) { "成片调色着色器编译失败" }
            GLES30.glUseProgram(program)

            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glGenTextures(1, fboTexture, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, fboTexture[0], 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "无法分配成片 GPU 内存，请选择 1080P 后重试"
            }

            GLES30.glGenTextures(1, imageTexture, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, originalBitmap, 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uImageTexture"), 0)

            // Bind an identity 3D texture even for XMP-only processing. The shader samples
            // the LUT at intensity zero. Never use the preview's global texture cache.
            val data = lutData ?: identityLut()
            require(data.size >= 2 && data.size.toLong() * data.size * data.size * 3 == data.data.size.toLong()) {
                "LUT 数据不完整"
            }
            require(data.data.all { it.isFinite() }) { "LUT 含无效颜色数据" }
            val max3dSize = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, max3dSize, 0)
            require(data.size <= max3dSize[0]) { "LUT 阶数超过当前 GPU 上限" }
            val lutBuffer = ByteBuffer.allocateDirect(data.data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(data.data).apply { position(0) }
            GLES30.glGenTextures(1, lutTexture, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, data.size, data.size,
                data.size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, lutBuffer)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLutTexture"), 1)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uLutSize"), data.size.toFloat())
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uIntensity"), if (lutData == null) 0f else intensity)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uExposure"), exposure)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uTemperature"), temperature)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uTint"), tint)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uUseACES"), if (enableACES) 1 else 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uUseTetrahedral"), if (useTetrahedral) 1 else 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uShowHighlightWarning"), 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uShowShadowWarning"), 0)
            if (xmpData != null) {
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uEnableXmp"), 1)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpExposure"), xmpData.exposure)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpContrast"), xmpData.contrast)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpHighlights"), xmpData.highlights)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpShadows"), xmpData.shadows)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpWhites"), xmpData.whites)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpBlacks"), xmpData.blacks)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpTemperature"), xmpData.temperature)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpTint"), xmpData.tint)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpVibrance"), xmpData.vibrance)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpSaturation"), xmpData.saturation)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpClarity"), xmpData.clarity)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uXmpDehaze"), xmpData.dehaze)
                GLES30.glUniform1fv(GLES30.glGetUniformLocation(program, "uXmpHueAdjust"), 8, xmpData.hueAdjustments, 0)
                GLES30.glUniform1fv(GLES30.glGetUniformLocation(program, "uXmpSatAdjust"), 8, xmpData.saturationAdjustments, 0)
                GLES30.glUniform1fv(GLES30.glGetUniformLocation(program, "uXmpLumAdjust"), 8, xmpData.luminanceAdjustments, 0)
            } else {
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uEnableXmp"), 0)
            }

            val positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
            val coordHandle = GLES30.glGetAttribLocation(program, "aTextureCoord")
            val vertices = ByteBuffer.allocateDirect(VERTEX_COORDS.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(VERTEX_COORDS).apply { position(0) }
            val coords = ByteBuffer.allocateDirect(TEXTURE_COORDS.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(TEXTURE_COORDS).apply { position(0) }
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, vertices)
            GLES30.glEnableVertexAttribArray(coordHandle)
            GLES30.glVertexAttribPointer(coordHandle, 2, GLES30.GL_FLOAT, false, 0, coords)
            checkGl("成片纹理上传")
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGl("成片调色")
            val pixels = ByteBuffer.allocateDirect(byteCount.toInt()).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            checkGl("成片像素读取")
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pixels.position(0)
            result.copyPixelsFromBuffer(pixels)
            succeeded = true
            return result
        } finally {
            if (!succeeded) result?.recycle()
            if (madeCurrent) {
                GLES30.glDeleteTextures(1, imageTexture, 0)
                GLES30.glDeleteTextures(1, fboTexture, 0)
                GLES30.glDeleteTextures(1, lutTexture, 0)
                GLES30.glDeleteFramebuffers(1, fbo, 0)
                if (program != 0) GLES30.glDeleteProgram(program)
            }
            try {
                if (previousContext != EGL14.EGL_NO_CONTEXT && previousDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext)
                } else if (madeCurrent) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                // The default EGLDisplay is also used by live preview; do not terminate it.
            } catch (cleanupFailure: Exception) {
                Log.e(TAG, "成片 GPU 清理失败", cleanupFailure)
            }
        }
    }

    private fun checkGl(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            stage + "失败 (GL 0x" + error.toString(16) + ")，请选择 1080P 后重试"
        }
    }

    private fun identityLut(): LutData {
        val values = FloatArray(24)
        var i = 0
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            values[i++] = r.toFloat(); values[i++] = g.toFloat(); values[i++] = b.toFloat()
        }
        return LutData("Offscreen identity", 2, values)
    }
}

