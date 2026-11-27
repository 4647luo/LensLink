package com.lutcamera.lutcamerapro.ui.douk

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * 编辑器 HDR 预览的「调色（grade）+ GainMap 合成」放到 GPU 上跑。
 *
 * 之前这条链路是纯 Kotlin 的 IntArray 循环：1024×580 一帧 60 万像素，每像素要算
 * sRGB↔线性、ACES 折叠、S 曲线、饱和度、GainMap 幂函数，实测一帧 300ms 以上，
 * 拖滑杆时明显排队。现在把同一套数学写成一个 GLSL ES 片元着色器，
 * 用 EGL 离屏 pbuffer 渲染再把像素读回 Bitmap：
 *
 *  - 数学与 CPU 版本逐行对齐（同一个 srgbToLinear / ACES Filmic / contrastCurve /
 *    smoothstep 权重 / whiteGuard），因此开关 GPU 只影响耗时，不影响观感；
 *  - 每次调用自建 EGL 上下文与程序（预览跑在 Dispatchers.Default 的多线程上，
 *    EGL 上下文是线程绑定的，缓存反而会踩线程），上下文初始化约几毫秒，
 *    相对 300ms 的 CPU 循环可以忽略；
 *  - 任何一步失败都返回 null，调用方回落到 CPU 实现，画面不会变空。
 */
internal object HdrGpuCompositor {

    data class CompositeParams(
        val shadow: Float,
        val color: Float,
        val warmth: Float,
        val logMin: Float,
        val logMax: Float,
        val gamma: Float,
        val epsSdr: Float,
        val epsHdr: Float,
        val strength: Float,
        val highlight: Float
    )

    data class GradeParams(
        val shadow: Float,
        val color: Float,
        val warmth: Float
    )

    private const val VERTEX_SRC = """
        attribute vec4 aPos;
        attribute vec2 aTex;
        varying vec2 vTex;
        void main() {
            gl_Position = aPos;
            vTex = aTex;
        }
    """

    private const val FUNCS = """
        float srgbToLinear(float v) {
            if (v <= 0.04045) { return v / 12.92; }
            return pow((v + 0.075) / 1.075, 2.4);
        }
        float linearToSrgb(float v) {
            if (v <= 0.0031308) { return v * 12.92; }
            return 1.055 * pow(v, 1.0 / 2.4) - 0.055;
        }
        float step01(float e0, float e1, float x) {
            float t = clamp((x - e0) / max(e1 - e0, 1e-5), 0.0, 1.0);
            return t * t * (3.0 - 2.0 * t);
        }
        float acesFilmic(float x) {
            float v = max(x, 0.0);
            float den = v * (2.43 * v + 0.59) + 0.14;
            if (den <= 1e-6) { return 0.0; }
            return clamp(v * (2.51 * v + 0.03) / den, 0.0, 1.0);
        }
        float contrastCurve(float v, float amount) {
            if (amount <= 0.01) { return v; }
            float t = clamp(v, 0.0, 1.0);
            float a = clamp(amount, 0.0, 1.0);
            return clamp(0.45 + (t - 0.45) * (1.0 + 0.55 * a), 0.0, 1.0);
        }
        vec3 gradePixel(vec3 c, float shadow, float color, float warmth) {
            float lum = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
            if (shadow > 0.5) {
                float lift = (shadow / 100.0) * 0.18;
                float wShadow = pow(clamp(1.0 - lum, 0.0, 1.0), 4.0);
                c.r += lift * wShadow * (1.0 - c.r);
                c.g += lift * wShadow * (1.0 - c.g);
                c.b += lift * wShadow * (1.0 - c.b);
            }
            if (abs(color - 50.0) > 0.5) {
                float richness = (color - 50.0) / 50.0;
                float weight = 0.35 + 0.65 * step01(0.10, 0.90, lum);
                float s = 1.0 + richness * 0.55;
                float sat = 1.0 + (s - 1.0) * weight;
                c.r = lum + (c.r - lum) * sat;
                c.g = lum + (c.g - lum) * sat;
                c.b = lum + (c.b - lum) * sat;
            }
            if (abs(warmth) > 0.5) {
                float warm = warmth / 100.0;
                float weight = step01(0.20, 0.95, lum);
                c.r += warm * 0.060 * weight;
                c.g += warm * 0.012 * weight;
                c.b -= warm * 0.060 * weight;
            }
            return clamp(c, 0.0, 1.0);
        }
    """

    private const val COMPOSITE_FS = "precision highp float;\n" + FUNCS + """
        uniform sampler2D uBase;
        uniform sampler2D uGain;
        uniform float uShadow;
        uniform float uColor;
        uniform float uWarmth;
        uniform float uLogMin;
        uniform float uLogMax;
        uniform float uGamma;
        uniform float uEpsSdr;
        uniform float uEpsHdr;
        uniform float uStrength;
        uniform float uHighlight;
        varying vec2 vTex;

        void main() {
            vec3 graded = gradePixel(texture2D(uBase, vTex).rgb, uShadow, uColor, uWarmth);
            float r = srgbToLinear(graded.r);
            float g = srgbToLinear(graded.g);
            float b = srgbToLinear(graded.b);
            vec3 gc = texture2D(uGain, vTex).rgb;
            float gv = (gc.r + gc.g + gc.b) / 3.0;
            float boost = exp(uLogMin + (uLogMax - uLogMin) * pow(gv, uGamma));
            float baseLum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            float liftWeight = step01(0.02, 0.42, baseLum) * (1.0 - step01(0.45, 0.82, baseLum));
            float exposure = 1.0 + (0.66 + 0.58 * uHighlight) * uStrength * liftWeight;
            float gainWeight = 0.75 * step01(0.02, 0.45, baseLum) *
                (1.0 - 0.7 * step01(0.55, 0.95, baseLum));
            float expanded = (baseLum + uEpsSdr) * exposure *
                (1.0 + (boost - 1.0) * gainWeight) - uEpsHdr;
            float toned = contrastCurve(acesFilmic(expanded), 0.40 * uStrength);
            float displayLum = baseLum > 0.72 ? min(toned, baseLum) : max(baseLum * 0.90, toned);
            float scale = baseLum > 1e-5 ? clamp(displayLum / baseLum, 0.90, 2.40) : 1.0;
            float whiteGuard = step01(0.78, 0.96, baseLum);
            float effScale = scale + (1.0 - scale) * whiteGuard;
            float saturation = 1.0 + 0.22 * uStrength * step01(0.30, 0.90, baseLum);
            float outR = clamp((baseLum + (r - baseLum) * saturation) * effScale, 0.0, 1.0);
            float outG = clamp((baseLum + (g - baseLum) * saturation) * effScale, 0.0, 1.0);
            float outB = clamp((baseLum + (b - baseLum) * saturation) * effScale, 0.0, 1.0);
            gl_FragColor = vec4(linearToSrgb(outR), linearToSrgb(outG), linearToSrgb(outB), 1.0);
        }
    """

    private const val GRADE_FS = "precision highp float;\n" + FUNCS + """
        uniform sampler2D uBase;
        uniform float uShadow;
        uniform float uColor;
        uniform float uWarmth;
        varying vec2 vTex;

        void main() {
            gl_FragColor = vec4(gradePixel(texture2D(uBase, vTex).rgb, uShadow, uColor, uWarmth), 1.0);
        }
    """

    fun composite(base: Bitmap, gain: Bitmap, p: CompositeParams): Bitmap? {
        if (base.isRecycled || gain.isRecycled) return null
        if (base.width < 2 || base.height < 2) return null
        val start = SystemClock.elapsedRealtime()
        val gainArgb = toGrayArgb(gain) ?: return null
        return try {
            val out = render(
                base, gainArgb, COMPOSITE_FS,
                floatUniforms = mapOf(
                    "uShadow" to p.shadow,
                    "uColor" to p.color,
                    "uWarmth" to p.warmth,
                    "uLogMin" to p.logMin,
                    "uLogMax" to p.logMax,
                    "uGamma" to p.gamma,
                    "uEpsSdr" to p.epsSdr,
                    "uEpsHdr" to p.epsHdr,
                    "uStrength" to p.strength,
                    "uHighlight" to p.highlight
                )
            )
            if (out != null) {
                // 诊断日志只在前几次调用时打：预热跑通后就不再拉低帧率
                val n = callCount.incrementAndGet()
                if (n <= 3 || n % 24 == 0) {
                    com.lutcamera.lutcamerapro.log.DoukLog.i(
                        "PhotoEditor", "HDR_COMPOSITE_GPU",
                        "px" to (base.width * base.height),
                        "ms" to (SystemClock.elapsedRealtime() - start),
                        "mean" to runCatching { "${meanOf(base)}->${meanOf(out)}" }.getOrDefault("-")
                    )
                    sampleDebug(base, gainArgb, out)
                }
            }
            out
        } catch (e: Throwable) {
            com.lutcamera.lutcamerapro.log.DoukLog.w(
                "PhotoEditor", "HDR_GPU_FAIL", "msg" to (e.message ?: e.javaClass.simpleName)
            )
            null
        } finally {
            if (gainArgb !== gain) runCatching { gainArgb.recycle() }
        }
    }

    fun grade(src: Bitmap, p: GradeParams): Bitmap? {
        if (src.isRecycled) return null
        return try {
            render(
                src, null, GRADE_FS,
                floatUniforms = mapOf(
                    "uShadow" to p.shadow,
                    "uColor" to p.color,
                    "uWarmth" to p.warmth
                )
            )
        } catch (e: Throwable) {
            com.lutcamera.lutcamerapro.log.DoukLog.w(
                "PhotoEditor", "HDR_GPU_GRADE_FAIL", "msg" to (e.message ?: e.javaClass.simpleName)
            )
            null
        }
    }

    // ---------------------------------------------------------------- GL 渲染

    private fun render(
        base: Bitmap,
        gain: Bitmap?,
        fragmentSrc: String,
        floatUniforms: Map<String, Float>
    ): Bitmap? {
        val w = base.width
        val h = base.height
        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        return try {
            val egl = EGLContext.getEGL() as EGL10
            eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!egl.eglInitialize(eglDisplay, version)) return null
            val configAttrs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT_VALUE,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!egl.eglChooseConfig(eglDisplay, configAttrs, configs, 1, num) || num[0] <= 0) return null
            val config = configs[0] ?: return null
            eglContext = egl.eglCreateContext(
                eglDisplay, config, EGL10.EGL_NO_CONTEXT,
                intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
            )
            eglSurface = egl.eglCreatePbufferSurface(
                eglDisplay, config,
                intArrayOf(EGL10.EGL_WIDTH, w, EGL10.EGL_HEIGHT, h, EGL10.EGL_NONE)
            )
            if (eglContext == null || eglSurface == null) return null
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return null

            val program = buildProgram(fragmentSrc)
            if (program == 0) return null
            GLES20.glUseProgram(program)
            val baseTex = uploadTexture(base)
            val gainTex = gain?.let { uploadTexture(it) } ?: 0

            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uBase"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTex)
            if (gainTex != 0) {
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uGain"), 1)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gainTex)
            }
            for ((name, v) in floatUniforms) {
                val loc = GLES20.glGetUniformLocation(program, name)
                if (loc >= 0) GLES20.glUniform1f(loc, v)
            }

            drawQuad(program)
            GLES20.glFinish()

            val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            out.copyPixelsFromBuffer(buffer)
            out.density = Bitmap.DENSITY_NONE
            out
        } catch (e: Throwable) {
            com.lutcamera.lutcamerapro.log.DoukLog.w(
                "PhotoEditor", "HDR_GPU_RENDER_FAIL", "msg" to (e.message ?: e.javaClass.simpleName)
            )
            null
        } finally {
            runCatching {
                val egl = EGLContext.getEGL() as EGL10
                if (eglDisplay != null) {
                    egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                    eglSurface?.let { egl.eglDestroySurface(eglDisplay, it) }
                    eglContext?.let { egl.eglDestroyContext(eglDisplay, it) }
                    egl.eglTerminate(eglDisplay)
                }
            }
        }
    }

    private fun drawQuad(program: Int) {
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val texLoc = GLES20.glGetAttribLocation(program, "aTex")
        // GL 的帧缓冲原点在左下，纹理 V 反过来写，读回的像素就正好是正立的图
        val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        val fb: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, fb)
        GLES20.glEnableVertexAttribArray(texLoc)
        fb.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, fb)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun uploadTexture(bmp: Bitmap): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return tex[0]
    }

    private fun buildProgram(fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SRC)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            com.lutcamera.lutcamerapro.log.DoukLog.w(
                "PhotoEditor", "HDR_GPU_LINK_FAIL",
                "log" to GLES20.glGetProgramInfoLog(program).take(300)
            )
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            com.lutcamera.lutcamerapro.log.DoukLog.w(
                "PhotoEditor", "HDR_GPU_COMPILE_FAIL",
                "type" to type, "log" to GLES20.glGetShaderInfoLog(shader).take(400)
            )
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    // ------------------------------------------------------------ 诊断 / 工具

    /** 缩到 48px 再算平均亮度：够用来做 CPU/GPU 一致性诊断，又不拖慢主路径。 */
    private fun meanOf(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 48, 48, true)
        val px = IntArray(48 * 48)
        small.getPixels(px, 0, 48, 0, 0, 48, 48)
        if (small !== bmp) small.recycle()
        var sum = 0L
        for (p in px) {
            sum += (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
        }
        return (sum / px.size).toInt()
    }

    /** 诊断：按几个固定采样点把 底图 / GainMap / GPU 输出 三者的 RGB 打进日志。 */
    private fun sampleDebug(base: Bitmap, gain: Bitmap, out: Bitmap) {
        runCatching {
            val w = minOf(base.width, out.width, gain.width)
            val h = minOf(base.height, out.height, gain.height)
            val pts = listOf(0.12 to 0.25, 0.5 to 0.5, 0.8 to 0.75, 0.3 to 0.86)
            val sb = StringBuilder()
            for ((fx, fy) in pts) {
                val x = (w * fx).toInt().coerceIn(0, w - 1)
                val y = (h * fy).toInt().coerceIn(0, h - 1)
                val bp = base.getPixel(x, y)
                val op = out.getPixel(x, y)
                val gp = gain.getPixel(x, y)
                sb.append("[$x,$y b=${bp shr 16 and 0xFF},${bp shr 8 and 0xFF},${bp and 0xFF}")
                sb.append(" g=${gp shr 16 and 0xFF} o=${op shr 16 and 0xFF},${op shr 8 and 0xFF},${op and 0xFF}] ")
            }
            com.lutcamera.lutcamerapro.log.DoukLog.i("PhotoEditor", "HDR_GPU_SAMPLES", "v" to sb.toString())
        }
    }

    /** GPU 采样只认 RGB，ALPHA_8 的 GainMap 先摊成灰度 ARGB（值不变，仍是 0–255 灰度）。 */
    private fun toGrayArgb(gain: Bitmap): Bitmap? {
        if (gain.config != Bitmap.Config.ALPHA_8) return gain
        val w = gain.width
        val h = gain.height
        val px = IntArray(w * h)
        gain.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val a = (px[i] ushr 24) and 0xFF
            px[i] = (0xFF000000.toInt()) or (a shl 16) or (a shl 8) or a
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    private const val EGL_OPENGL_ES2_BIT_VALUE = 4

    private val callCount = java.util.concurrent.atomic.AtomicInteger(0)
}
