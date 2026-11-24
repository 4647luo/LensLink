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
import android.os.Build
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
 * 增益图重建按 ISO 21496-1；高光肩部参考 Apache-2.0 的 JonaNorman/HDRSample
 * 与 bjzhou/PhotonCamera 的公开实现，但没有复制 GPL 项目（LocalViewer）的代码。
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
    private var ratioMin = 1f
    private var mapGamma = 1f
    private var epsSdr = 1f / 64f
    private var epsHdr = 1f / 64f
    private var fullRatio = 1f
    private var minDisplayRatio = 1f
    private var gainOn = false
    private var pqInput = false
    // 已经在 GL 里的那张底图 / 增益图：换了图必须重新上传，
    // 以前只在 texId==0 时上传，翻到下一张会继续显示上一张的纹理。
    private var texBitmap: Bitmap? = null
    private var texGain: Bitmap? = null
    private var vPos = 0
    private var vTex = 0
    private var uTex = 0
    private var uGain = 0
    private var uGainOn = 0
    private var uRatioMax = 0
    private var uRatioMin = 0
    private var uGamma = 0
    private var uEpsSdr = 0
    private var uEpsHdr = 0
    private var uWeight = 0
    private var uTonemap = 0
    private var uPqInput = 0
    private var uPeakNits = 0
    private var uZoom = 0
    private var uPan = 0
    private var uFit = 0
    private var uQuarter = 0
    private var quad: FloatBuffer? = null

    /** 这一层是否真的用上了 PQ 交换链（false = SDR 回退） */
    var hdrActive: Boolean = false
        private set

    /**
     * 当前纹理属于哪一张图。翻页时上层用缩略图盖住旧帧，等这里真正画完新纹理
     * 再撤掉遮罩，避免 SurfaceView 先黑一帧再出图。
     */
    var sourceTag: String? = null
        set(value) {
            if (field != value) {
                field = value
                if (value == null) lastFrameTag = null
            }
        }

    /** 新纹理已经完成一次 eglSwapBuffers 后回调（主线程） */
    var onFrameReady: ((String?) -> Unit)? = null
    private var lastFrameTag: String? = null

    /** EGL 窗口是否按 BT2020_PQ 建成功；只有它为 true 才允许输出 PQ 数值 */
    private var pqSurface = false

    /** 选到的是 10bit RGBA_1010102 配置；PQ 数据必须走 10bit 交换链才不会断层 */
    private var tenBitConfig = false

    /** 只用来给外部打一次诊断日志，避免重复刷 log */
    var loggedReady = false

    // 缩放 / 平移 / 旋转（SurfaceView 不受 Compose 的 graphicsLayer 影响，必须在 GL 里做）
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var quarter = false

    fun setTransform(z: Float, px: Float, py: Float, q: Boolean) {
        if (zoom == z && panX == px && panY == py && quarter == q) return
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
        if (bmp == null) {
            // 保留上一帧：翻页时新图还没解出来，清空 Surface 会先黑一下。
            // 上层会用缩略图遮住，真正的新纹理上传完成后再撤遮罩。
            return
        }
        if (bitmap === bmp && !gainOn) return
        bitmap = bmp
        gainBmp = null; gainOn = false
        pqInput = true
        handler.post { renderOnce() }
    }

    /** 没有 HDR 时清成黑底，但保留 EGL / Surface，下一次打开 HDR 不用重新初始化。 */
    fun clear() {
        if (bitmap == null && gainBmp == null) return
        bitmap = null
        gainBmp = null
        gainOn = false
        pqInput = false
        handler.post { renderOnce() }
    }

    /**
     * Ultra HDR：底图是普通 8bit SDR，gain 是逐像素增益图（灰度）。
     * 着色器里按 ratioMax^gain 还原线性亮度再编码成 PQ，这才是真 HDR 数据。
     */
    fun setGainPair(
        base: Bitmap?,
        gain: Bitmap?,
        ratioMaxValue: Float,
        ratioMinValue: Float,
        gammaValue: Float,
        epsilonSdr: Float,
        epsilonHdr: Float,
        displayRatioFull: Float,
        minDisplayRatioValue: Float
    ) {
        if (bitmap === base && gainBmp === gain) return
        bitmap = base
        gainBmp = gain
        val contentRatio = displayRatioFull.takeIf { it.isFinite() && it >= 1.05f }
        // libultrahdr 的 ratioMax 经常是 10000/203≈49（编码器能力上限），
        // 并不等于这张图真实的高光能力。直接用 49 倍会把高光整片推爆。
        // HDRCapacityMax（displayRatioForFullHdr）才是内容峰值，超过它的部分不能再用。
        ratioMax = (contentRatio?.let { minOf(ratioMaxValue, it) } ?: ratioMaxValue)
            .coerceIn(1f, 16f)
        ratioMin = ratioMinValue.coerceIn(0.01f, ratioMax)
        mapGamma = gammaValue.coerceIn(0.05f, 8f)
        epsSdr = epsilonSdr.coerceIn(0f, 1f)
        epsHdr = epsilonHdr.coerceIn(0f, 1f)
        fullRatio = ratioMax
        minDisplayRatio = minDisplayRatioValue.coerceAtLeast(0.01f)
        gainOn = gain != null
        pqInput = false
        handler.post { renderOnce() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        handler.post {
            initGl()
            // 只有 EGL 窗口本身按 PQ 建成功，才敢把数据写成 PQ。
            // 以前不管 EGL 成没成，SurfaceControl.setDataSpace 一返回就把 hdrActive 置 true，
            // 结果 8bit/sRGB 交换链里塞的是 PQ 数值，整张图被当成 sRGB 解读 → 一打开就是灰的。
            // EGL 的 PQ colorspace 属性在这台机器上会被拒绝（EGL_BAD_ATTRIBUTE），
            // 但 10bit 交换链本身是好的。只要拿到 10bit 配置，就用 SurfaceControl
            // 直接给这一层标 BT2020_PQ，让 SurfaceFlinger 按 HDR 合成。
            if (tenBitConfig) applyHdrDataSpace()
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
            // dataspace 切换后必须再画一帧，否则第一帧仍按旧的 SDR 解释。
            renderOnce()
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
        lastFrameTag = null
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
            tenBitConfig = false
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
        } else {
            tenBitConfig = true
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
        uRatioMin = GLES20.glGetUniformLocation(program, "uRatioMin")
        uGamma = GLES20.glGetUniformLocation(program, "uGamma")
        uEpsSdr = GLES20.glGetUniformLocation(program, "uEpsSdr")
        uEpsHdr = GLES20.glGetUniformLocation(program, "uEpsHdr")
        uWeight = GLES20.glGetUniformLocation(program, "uWeight")
        uTonemap = GLES20.glGetUniformLocation(program, "uTonemap")
        uPqInput = GLES20.glGetUniformLocation(program, "uPqInput")
        uPeakNits = GLES20.glGetUniformLocation(program, "uPeakNits")
        uZoom = GLES20.glGetUniformLocation(program, "uZoom")
        uPan = GLES20.glGetUniformLocation(program, "uPan")
        uFit = GLES20.glGetUniformLocation(program, "uFit")
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
        // 有些机型 surfaceCreated 之后才第一次到画面，surfaceChanged 不一定再来一次；
        // 初始化完主动画一帧，避免停在黑/灰底。
        renderOnce()
    }

    private fun createWindowSurface(display: EGLDisplay, config: EGLConfig, pq: Boolean): EGLSurface =
        runCatching {
            val a = if (pq) intArrayOf(
                EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT,
                EGL14.EGL_NONE
            ) else intArrayOf(EGL14.EGL_NONE)
            val s = EGL14.eglCreateWindowSurface(display, config, holder.surface, a, 0)
            if (pq) {
                pqSurface = s != EGL14.EGL_NO_SURFACE
                hdrActive = pqSurface
                DoukLog.i(
                    "HdrImage", "PQ_SURFACE",
                    "ok" to pqSurface.toString(),
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
            uniform vec2 uFit;
            uniform float uQuarter;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = (uQuarter > 0.5) ? aTex.yx : aTex;
                gl_Position = vec4(aPos * uFit * uZoom + uPan, 0.0, 1.0);
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
            uniform float uRatioMin;
            uniform float uGamma;
            uniform float uEpsSdr;
            uniform float uEpsHdr;
            uniform float uWeight;
            uniform float uTonemap;
            uniform float uPqInput;
            uniform float uPeakNits;
            out vec4 fragColor;

            const float m1 = 0.1593017578125;
            const float m2 = 78.84375;
            const float c1 = 0.8359375;
            const float c2 = 18.8515625;
            const float c3 = 18.6875;
            const float SDR_NITS = 203.0;

            vec3 pq(vec3 nits) {
                vec3 Y = clamp(nits / 10000.0, 0.0001, 1.0);
                vec3 Yp = pow(Y, vec3(m1));
                return pow((c1 + c2 * Yp) / (1.0 + c3 * Yp), vec3(m2));
            }

            // PQ 码值反解成绝对亮度（nits），用于直接解码出来的 PQ 位图。
            vec3 pqToNits(vec3 code) {
                vec3 e = pow(clamp(code, 0.0, 1.0), vec3(1.0 / m2));
                vec3 num = max(e - c1, 0.0);
                vec3 den = max(c2 - c3 * e, 1e-6);
                return 10000.0 * pow(num / den, vec3(1.0 / m1));
            }

            vec3 srgbToLinear(vec3 c) {
                vec3 lo = c / 12.92;
                vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
                return mix(lo, hi, step(vec3(0.04045), c));
            }

            vec3 linearToSrgb(vec3 c) {
                vec3 lo = c * 12.92;
                vec3 hi = 1.055 * pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.4)) - 0.055;
                return mix(lo, hi, step(vec3(0.0031308), c));
            }

            // 线性 sRGB -> 线性 BT.2020，PQ 交换链必须以 BT.2020 原色输出
            vec3 srgbToBt2020(vec3 c) {
                return vec3(
                    dot(c, vec3(0.6274, 0.3293, 0.0433)),
                    dot(c, vec3(0.0691, 0.9195, 0.0114)),
                    dot(c, vec3(0.0164, 0.0880, 0.8956))
                );
            }

            vec3 bt2020ToSrgb(vec3 c) {
                return vec3(
                    dot(c, vec3(1.0280, -0.0268, -0.0012)),
                    dot(c, vec3(-0.0562, 1.0702, -0.0140)),
                    dot(c, vec3(-0.0023, -0.0070, 1.0093))
                );
            }

            /**
             * 高光肩部：75% 峰值以下不动，之上平滑趋近峰值。
             * 直接输出 800~1000 nits 的 PQ，在 400~600 nits 的面板上会被整片裁白；
             * 这里先把细节压进面板真实峰值，再编码，亮部层次才留得住。
             */
            vec3 softClip(vec3 nits, float peak) {
                vec3 safe = max(nits, 0.0);
                float y = dot(safe, vec3(0.2627, 0.6780, 0.0593));
                if (y <= 1e-5) return safe;
                float knee = peak * 0.72;
                float mapped = y;
                if (y > knee) {
                    float room = max(peak - knee, 1.0);
                    mapped = knee + room * (1.0 - exp(-(y - knee) / room));
                }
                float scale = mapped / y;
                return min(safe * scale, vec3(peak));
            }

            void main() {
                vec4 c = texture(uTex, vTexCoord);
                float peak = max(uPeakNits, 1.0);
                if (uPqInput > 0.5) {
                    vec3 nits = softClip(pqToNits(c.rgb), peak);
                    if (uTonemap > 0.5) {
                        vec3 srgb = clamp(bt2020ToSrgb(nits / SDR_NITS), 0.0, 1.0);
                        fragColor = vec4(linearToSrgb(srgb), 1.0);
                    } else {
                        fragColor = vec4(pq(nits), 1.0);
                    }
                } else if (uGainOn > 0.5) {
                    float g = texture(uGain, vTexCoord).r;
                    // ISO 21496-1：L = mix(log(minBoost), log(maxBoost), G^1/gamma)
                    // D = (SDR + epsSdr) * exp(L * W) - epsHdr
                    // W 由显示器当前 HDR/SDR 比决定，不能无脑放满 maxBoost。
                    float recovery = pow(clamp(g, 0.0, 1.0), 1.0 / max(uGamma, 0.05));
                    float logBoost = mix(log(max(uRatioMin, 0.01)), log(max(uRatioMax, 0.01)), recovery);
                    vec3 lin = srgbToLinear(clamp(c.rgb, 0.0, 1.0));
                    vec3 hdr = (lin + uEpsSdr) * exp(logBoost * uWeight) - uEpsHdr;
                    hdr = max(hdr, 0.0);
                    vec3 nits = softClip(srgbToBt2020(hdr * SDR_NITS), peak);
                    if (uTonemap > 0.5) {
                        vec3 srgb = clamp(bt2020ToSrgb(nits / SDR_NITS), 0.0, 1.0);
                        fragColor = vec4(linearToSrgb(srgb), 1.0);
                    } else {
                        fragColor = vec4(pq(nits), 1.0);
                    }
                } else if (uTonemap > 0.5) {
                    fragColor = c;
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

    /**
     * 当前屏幕能真实显示到的 HDR/SDR 比。比如 420nits 面板 / 203nits SDR 白 ≈ 2.07，
     * 那么 maxBoost=4 的照片只放 2.07 倍，剩下的高光交给系统 tone map，
     * 这样高光不会一律冲成死白。
     */
    private fun displayWeight(): Float {
        val displayRatio = (displayPeakNits() / 203f).coerceIn(1f, 20f)
        val lo = minDisplayRatio.coerceAtLeast(0.01f)
        val hi = fullRatio.coerceAtLeast(lo + 0.01f)
        val den = kotlin.math.ln(hi) - kotlin.math.ln(lo)
        val w = if (den > 0.001f) {
            (kotlin.math.ln(displayRatio) - kotlin.math.ln(lo)) / den
        } else 1f
        return w.coerceIn(0f, 1f)
    }

    /**
     * 面板真实 HDR 峰值。desiredMaxLuminance 在部分 ROM 上返回 0 或 1000+，
     * 直接拿来算 boost 会把高光推过头；这里只接受合理区间，否则按这块平板
     * 常见的 420 nits 保守处理。
     */
    private fun displayPeakNits(): Float {
        val desired = if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { context.display?.hdrCapabilities?.desiredMaxLuminance }.getOrNull() ?: 0f
        } else 0f
        if (desired in 100f..2000f) return desired
        return if (hdrActive) 420f else 203f
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
            }
            if (texBitmap !== bmp) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                runCatching { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0) }
                    .onFailure { DoukLog.w("HdrImage", "TEX_UPLOAD_FAIL", "err" to it.message) }
                texBitmap = bmp
            }
            val gain = gainBmp
            if (gain != null && !gain.isRecycled) {
                if (gainTex == 0) {
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    gainTex = ids[0]
                }
                if (texGain !== gain) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gainTex)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    runCatching { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, gain, 0) }
                        .onFailure { DoukLog.w("HdrImage", "GAIN_UPLOAD_FAIL", "err" to it.message) }
                    texGain = gain
                }
            }
            // 不再把 glViewport 缩到 letterbox：那样一放大，viewport 本身就把
            // 画面裁在中间一条，横图在竖屏上尤其明显。改成全屏 viewport，
            // 用 uFit 在顶点上做等比 letterbox，放大和平移都发生在全屏内。
            val baseRatio = bmp.width.toFloat() / bmp.height
            val imgRatio = if (quarter) 1f / baseRatio else baseRatio
            val viewRatio = w.toFloat() / h
            val fitX = if (imgRatio > viewRatio) 1f else imgRatio / viewRatio
            val fitY = if (imgRatio > viewRatio) viewRatio / imgRatio else 1f
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform1i(uGain, 1)
            GLES20.glUniform1f(uGainOn, if (gainOn && gainTex != 0) 1f else 0f)
            GLES20.glUniform1f(uRatioMax, ratioMax)
            GLES20.glUniform1f(uRatioMin, ratioMin)
            GLES20.glUniform1f(uGamma, mapGamma)
            GLES20.glUniform1f(uEpsSdr, epsSdr)
            GLES20.glUniform1f(uEpsHdr, epsHdr)
            GLES20.glUniform1f(uWeight, if (hdrActive) displayWeight() else 0f)
            GLES20.glUniform1f(uTonemap, if (hdrActive) 0f else 1f)
            GLES20.glUniform1f(uPqInput, if (pqInput) 1f else 0f)
            GLES20.glUniform1f(uPeakNits, if (hdrActive) displayPeakNits() else 203f)
            if (gainTex != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gainTex)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
            GLES20.glUniform2f(uZoom, zoom, zoom)
            GLES20.glUniform2f(uFit, fitX, fitY)
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
        val tag = sourceTag
        if (tag != null && tag != lastFrameTag) {
            lastFrameTag = tag
            post { onFrameReady?.invoke(tag) }
        }
    }

    private fun releaseGl() {
        if (texId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
            texId = 0
        }
        if (gainTex != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(gainTex), 0) }
            gainTex = 0
        }
        texBitmap = null
        texGain = null
        lastFrameTag = null
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
