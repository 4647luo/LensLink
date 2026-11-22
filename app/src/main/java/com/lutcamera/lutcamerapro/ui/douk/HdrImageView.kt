package com.lutcamera.lutcamerapro.ui.douk

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.lutcamera.lutcamerapro.log.DoukLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 真 HDR 图片视图：SurfaceView + EGL，窗口 Surface 按 BT.2020 / PQ 声明。
 *
 * 系统相册显示 HDR 照片时就是这条链路：图像保持 PQ 编码的半浮点纹理，
 * 直接把 PQ 值写到 PQ 色彩空间的 Surface，合成器给屏幕下发 HDR metadata，
 * 亮部才会真的超过 SDR 白（本机峰值 420 nits）。
 *
 * 设备/驱动不支持 BT2020_PQ 的 EGL 窗口时退回 SDR 交换链并做一次简单
 * tone map，画面照常显示，只是没有 HDR 高光。
 */
internal class HdrImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val thread = HandlerThread("hdr-image-gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var bitmap: Bitmap? = null
    private var surfaceReady = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texId = 0
    private var gainTex = 0
    private var gainBmp: Bitmap? = null
    private var ratioMax = 1f
    private var gainOn = false
    private var vPos = 0
    private var vTex = 0
    private var uTex = 0
    private var uGain = 0
    private var uGainOn = 0
    private var uRatioMax = 0
    private var uTonemap = 0
    private var uZoom = 0
    private var uPan = 0
    private var uQuarter = 0
    private var quad: FloatBuffer? = null

    /** 是否真的用上了 PQ 交换链（false = SDR 回退） */
    var hdrActive: Boolean = false
        private set

    /** 只用来给外部打一次诊断日志，避免重复刷 log */
    var loggedReady = false

    // 缩放 / 平移 / 旋转（SurfaceView 不受 Compose 的 graphicsLayer 影响，必须在 GL 里做）
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var quarter = false

    fun setTransform(z: Float, px: Float, py: Float, q: Boolean) {
        zoom = z; panX = px; panY = py; quarter = q
        handler.post { renderOnce() }
    }

    init {
        // PQ 是 10bit 的，Surface 必须是 RGBA_1010102，否则建 PQ 交换链会报 EGL_BAD_ATTRIBUTE
        holder.setFormat(android.graphics.PixelFormat.RGBA_1010102)
        holder.addCallback(this)
        setZOrderOnTop(false)
    }
    fun setBitmap(bmp: Bitmap?) {
        if (bitmap === bmp && !gainOn) return
        bitmap = bmp
        gainBmp = null; gainOn = false
        handler.post { renderOnce() }
    }

    /**
     * Ultra HDR：底图是普通 8bit SDR，gain 是逐像素增益图（灰度）。
     * 着色器里按 ratioMax^gain 还原线性亮度再编码成 PQ，这才是真 HDR 数据。
     */
    fun setGainPair(base: Bitmap?, gain: Bitmap?, ratio: Float) {
        if (bitmap === base && gainBmp === gain) return
        bitmap = base
        gainBmp = gain
        ratioMax = ratio.coerceIn(1f, 8f)
        gainOn = gain != null
        handler.post { renderOnce() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        handler.post {
            initGl()
            applyHdrDataSpace()
        }
    }

    /**
     * 这台机器的 libEGL 不认 EGL_GL_COLORSPACE_KHR（试了 PQ/HLG/P3/sRGB 全报
     * EGL_BAD_ATTRIBUTE），所以改走 SurfaceControl 这条路：把这一层的
     * dataSpace 直接标成 BT2020_PQ，SurfaceFlinger 就会按 HDR 合成。
     */
    private fun applyHdrDataSpace() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        runCatching {
            val sc = surfaceControl ?: return
            android.view.SurfaceControl.Transaction()
                .setDataSpace(sc, android.hardware.DataSpace.DATASPACE_BT2020_PQ)
                .apply()
            hdrActive = true
            DoukLog.i("HdrImage", "DATASPACE_PQ", "ok" to "true")
        }.onFailure {
            DoukLog.w("HdrImage", "DATASPACE_FAIL", "msg" to (it.message ?: ""))
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        handler.post { renderOnce() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        handler.post { releaseGl() }
    }

    private fun initGl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) releaseGl()
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            DoukLog.w("HdrImage", "NO_EGL_DISPLAY")
            return
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            DoukLog.w("HdrImage", "EGL_INIT_FAIL")
            return
        }
        eglDisplay = display
        DoukLog.i(
            "HdrImage", "EGL_EXT",
            "hasPq" to (EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)?.contains("EGL_EXT_gl_colorspace_bt2020_pq") == true).toString(),
            "hasP3" to (EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)?.contains("EGL_EXT_gl_colorspace_display_p3") == true).toString()
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        // 先按 10bit 配置找（PQ 必须），找不到再退回 8bit
        var attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // ES3
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
            attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
                DoukLog.w("HdrImage", "NO_EGL_CONFIG")
                return
            }
        }
        val config = configs[0] ?: return
        eglContext = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            DoukLog.w("HdrImage", "NO_EGL_CONTEXT")
            return
        }
        // 优先建 PQ 交换链；这台机器 libEGL 会拒绝（下面 applyHdrDataSpace 再兜底）
        eglSurface = createWindowSurface(display, config, true)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            eglSurface = createWindowSurface(display, config, false)
        }
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            DoukLog.w("HdrImage", "NO_EGL_SURFACE")
            return
        }
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
            DoukLog.w("HdrImage", "EGL_MAKE_CURRENT_FAIL")
            return
        }

        program = buildProgram()
        if (program == 0) {
            DoukLog.w("HdrImage", "SHADER_FAIL")
            return
        }
        vPos = GLES20.glGetAttribLocation(program, "aPos")
        vTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uGain = GLES20.glGetUniformLocation(program, "uGain")
        uGainOn = GLES20.glGetUniformLocation(program, "uGainOn")
        uRatioMax = GLES20.glGetUniformLocation(program, "uRatioMax")
        uTonemap = GLES20.glGetUniformLocation(program, "uTonemap")
        uZoom = GLES20.glGetUniformLocation(program, "uZoom")
        uPan = GLES20.glGetUniformLocation(program, "uPan")
        uQuarter = GLES20.glGetUniformLocation(program, "uQuarter")
        val data = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )
        quad = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }
        DoukLog.i("HdrImage", "GL_READY", "hdr" to hdrActive.toString())
    }

    private fun createWindowSurface(display: EGLDisplay, config: EGLConfig, pq: Boolean): EGLSurface =
        runCatching {
            val a = if (pq) intArrayOf(
                EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT,
                EGL14.EGL_NONE
            ) else intArrayOf(EGL14.EGL_NONE)
            val s = EGL14.eglCreateWindowSurface(display, config, holder.surface, a, 0)
            if (pq) {
                hdrActive = s != EGL14.EGL_NO_SURFACE
                DoukLog.i(
                    "HdrImage", "PQ_SURFACE",
                    "ok" to hdrActive.toString(),
                    "err" to EGL14.eglGetError().toString()
                )
            }
            s
        }.getOrDefault(EGL14.EGL_NO_SURFACE)

    private fun buildProgram(): Int {
        val vs = """
            #version 300 es
            in vec2 aPos;
            in vec2 aTex;
            uniform vec2 uZoom;
            uniform vec2 uPan;
            uniform float uQuarter;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = (uQuarter > 0.5) ? aTex.yx : aTex;
                gl_Position = vec4(aPos * uZoom + uPan, 0.0, 1.0);
            }
        """.trimIndent()
        val fs = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uTex;
            uniform sampler2D uGain;
            uniform float uGainOn;
            uniform float uRatioMax;
            uniform float uTonemap;
            out vec4 fragColor;

            const float m1 = 0.1593017578125;
            const float m2 = 78.84375;
            const float c1 = 0.8359375;
            const float c2 = 18.8515625;
            const float c3 = 18.6875;

            vec3 pq(vec3 nits) {
                vec3 Y = clamp(nits / 10000.0, 0.0001, 1.0);
                vec3 Yp = pow(Y, vec3(m1));
                return pow((c1 + c2 * Yp) / (1.0 + c3 * Yp), vec3(m2));
            }

            void main() {
                vec4 c = texture(uTex, vTexCoord);
                if (uGainOn > 0.5) {
                    float g = texture(uGain, vTexCoord).r;
                    if (uTonemap > 0.5) {
                        // SDR 回退：不带增益，直接给底图，避免过曝
                        fragColor = vec4(c.rgb, 1.0);
                    } else {
                        // sRGB 8bit → 线性，再按增益乘到 SDR 白（203 nits）之上
                        vec3 lin = pow(clamp(c.rgb, 0.0, 1.0), vec3(2.2));
                        float ratio = pow(uRatioMax, g);
                        vec3 nits = lin * ratio * 203.0;
                        fragColor = vec4(pq(nits), 1.0);
                    }
                } else if (uTonemap > 0.5) {
                    // SDR 回退：把 PQ 值粗略拉回线性再压一下高光
                    vec3 l = pow(max(c.rgb, vec3(0.0001)), vec3(2.2));
                    fragColor = vec4(pow(l, vec3(1.0 / 2.2)) * 0.9, 1.0);
                } else {
                    fragColor = c;
                }
            }
        """.trimIndent()
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                DoukLog.w("HdrImage", "SHADER_COMPILE", "log" to GLES20.glGetShaderInfoLog(s))
                GLES20.glDeleteShader(s)
                return 0
            }
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glBindAttribLocation(p, 1, "aTex")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            DoukLog.w("HdrImage", "PROGRAM_LINK", "log" to GLES20.glGetProgramInfoLog(p))
            return 0
        }
        return p
    }

    private fun renderOnce() {
        if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE || program == 0) return
        val bmp = bitmap
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (bmp != null && !bmp.isRecycled) {
            if (texId == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                texId = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                runCatching { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0) }
                    .onFailure { DoukLog.w("HdrImage", "TEX_UPLOAD_FAIL", "err" to it.message) }
            }
            val gain = gainBmp
            if (gain != null && !gain.isRecycled) {
                if (gainTex == 0) {
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    gainTex = ids[0]
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gainTex)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    runCatching { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, gain, 0) }
                        .onFailure { DoukLog.w("HdrImage", "GAIN_UPLOAD_FAIL", "err" to it.message) }
                }
            }
            // 按图片比例做 letterbox，别把人像拉变形（转 90° 时宽高比取倒数）
            val baseRatio = bmp.width.toFloat() / bmp.height
            val imgRatio = if (quarter) 1f / baseRatio else baseRatio
            val viewRatio = w.toFloat() / h
            var vpW = w
            var vpH = h
            var vpX = 0
            var vpY = 0
            if (imgRatio > viewRatio) {
                vpH = (w / imgRatio).toInt()
                vpY = (h - vpH) / 2
            } else {
                vpW = (h * imgRatio).toInt()
                vpX = (w - vpW) / 2
            }
            GLES20.glViewport(vpX, vpY, vpW.coerceAtLeast(1), vpH.coerceAtLeast(1))
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform1i(uGain, 1)
            GLES20.glUniform1f(uGainOn, if (gainOn && gainTex != 0) 1f else 0f)
            GLES20.glUniform1f(uRatioMax, ratioMax)
            GLES20.glUniform1f(uTonemap, if (hdrActive) 0f else 1f)
            if (gainTex != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gainTex)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
            GLES20.glUniform2f(uZoom, zoom, zoom)
            // px 平移换成 NDC；panX 往右为正，屏幕上就是往右
            GLES20.glUniform2f(uPan, panX * 2f / w, -panY * 2f / h)
            GLES20.glUniform1f(uQuarter, if (quarter) 1f else 0f)
            val q = quad ?: return
            q.position(0)
            GLES20.glVertexAttribPointer(vPos, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glEnableVertexAttribArray(vPos)
            q.position(2)
            GLES20.glVertexAttribPointer(vTex, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glEnableVertexAttribArray(vTex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        runCatching { EGL14.eglSwapBuffers(eglDisplay, eglSurface) }
    }

    private fun releaseGl() {
        if (texId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
            texId = 0
        }
        if (program != 0) {
            runCatching { GLES20.glDeleteProgram(program) }
            program = 0
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private companion object {
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3200
    }
}
