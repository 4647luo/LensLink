package com.lutcamera.lutcamerapro.gpu

import android.opengl.EGL14
import android.opengl.GLES30
import com.lutcamera.lutcamerapro.log.Log
import com.lutcamera.lutcamerapro.lut.LutData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 🚀 V4.0 GPU 显存 3D 纹理 LRU 自动淘汰池
 * 特性：严格 EGL 线程校验、动态 RGB16F 兼容性降级、防 OOM 自动释放
 */
// One instance per renderer / EGL context. GL names cannot be shared across contexts.
class LutTextureManager {
    private val TAG = "LutTextureManager"
    private val MAX_CACHE_SIZE = 3

    private val textureCache = object : LinkedHashMap<LutData, Int>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LutData, Int>?): Boolean {
            if (size > MAX_CACHE_SIZE) {
                eldest?.value?.let { textureId ->
                    if (textureId != 0 && GLES30.glIsTexture(textureId)) {
                        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                        Log.d(TAG, "Released LUT: ${eldest.key.title}")
                    }
                }
                return true
            }
            return false
        }
    }

    // 🚀 强力拦截：确保调用者一定在 GL Thread
                private fun assertGlThread() {
        require(EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) {
            "Fatal Error: LutTextureManager 必须在激活了 EGLContext 的 GL 线程中调用！"
        }
    }

    @Synchronized
    fun getOrCreateLutTexture(lutData: LutData): Int {
        assertGlThread()

        if (textureCache.containsKey(lutData)) {
            val cachedId = textureCache[lutData] ?: 0
            if (cachedId != 0 && GLES30.glIsTexture(cachedId)) return cachedId
        }

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        if (textureId == 0) return 0

        // 🚀 动态兼容性检测：是否支持浮点纹理，不支持则降级为常规 RGB 8bit
                val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        val supportFloat16 = extensions.contains("GL_OES_texture_half_float") || extensions.contains("GL_EXT_color_buffer_float")
        val internalFormat = if (supportFloat16) GLES30.GL_RGB16F else GLES30.GL_RGB

        val floatBuffer = ByteBuffer.allocateDirect(lutData.data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(lutData.data)
            .also { it.position(0) }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, internalFormat,
            lutData.size, lutData.size, lutData.size,
            0, GLES30.GL_RGB, GLES30.GL_FLOAT, floatBuffer
        )

        textureCache[lutData] = textureId
        Log.d(TAG, "LUT uploaded [${lutData.title}] -> ID: $textureId (Float16: $supportFloat16)")
        return textureId
    }

    @Synchronized
    fun releaseAll() {
        if (textureCache.isNotEmpty()) {
            val ids = textureCache.values.toIntArray()
            GLES30.glDeleteTextures(ids.size, ids, 0)
            textureCache.clear()
        }
    }
}

