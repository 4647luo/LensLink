package com.lutcamera.lutcamerapro.ui.douk

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import com.lutcamera.lutcamerapro.log.DoukLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 编辑器的 GPU 渲染管线。
 *
 * 以前 render() 把所有调色/降噪/锐化/颗粒都放在 IntArray 上逐像素跑，45MP 导出时
 * 光中间数组就要几百 MB，耗时以秒计。这里用同一套离屏 EGL 思路把这几步搬进片元着色器：
 *
 *   pass 1 颜色：曝光 / 亮度 / 对比 / 高光 / 阴影 / 色温 / 色调 / 饱和 / 自然饱和 /
 *               曲线（烘成 256×1 纹理）/ HSL 局部调色 / 褪色 / 黑白
 *   pass 2 降噪：3×3 均值，按强度与原像素混合（和 CPU 版本同款核）
 *   pass 3 锐化：3×3 拉普拉斯高通叠回
 *   pass 4 清晰度：同一核、不同强度，等价 CPU 里第二次 unsharp
 *   pass 5 颗粒：按屏幕坐标哈希噪声，强度换算和 CPU 一致（±k*26）
 *
 * 尺寸与 CPU 版本完全一致，输出仍是 ARGB_8888 位图；任何一步失败返回 null，
 * 调用方会自己落回 CPU 实现。首次使用会跑一次恒等自检（全零参数必须原样输出），
 * 自检不通过就整场禁用，避免把花屏带给用户。
 */
internal object GpuEditPipeline {

    data class Params(
        val ev: Float = 1f,
        val bright: Float = 0f,
        val contrast: Float = 1f,
        val highlight: Float = 0f,
        val shadow: Float = 0f,
        val temperature: Float = 0f,
        val tint: Float = 0f,
        val saturation: Float = 1f,
        val vibrance: Float = 0f,
        val fade: Float = 0f,
        val mono: Float = 0f,
        /** 256 级曲线，null 表示该通道不做曲线 */
        val masterCurve: ByteArray? = null,
        val redCurve: ByteArray? = null,
        val greenCurve: ByteArray? = null,
        val blueCurve: ByteArray? = null,
        val hslOn: Boolean = false,
        val hueShift: Float = 0f,
        val targetHue: Float = 0f,
        val hslSatK: Float = 1f,
        val hslLightK: Float = 0f,
        /** 0..1，小于阈值视为关闭 */
        val denoise: Float = 0f,
        val sharpen: Float = 0f,
        val clarity: Float = 0f,
        /** 0..1 */
        val grain: Float = 0f
    )

    private const val VERTEX = """
#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    // 与 GpuLutOffscreenFilter 保持同一套坐标约定：纹理行与帧缓冲、readPixels
    // 的上下方向三次翻转正好抵消，不再单独翻 V。
    vUv = vec2((aPos.x + 1.0) * 0.5, (aPos.y + 1.0) * 0.5);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val FRAG_COLOR = """
#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform sampler2D uCurves;      // 256x1: R=red LUT, G=green, B=blue, A=master
uniform float uEv, uBright, uContrast, uHi, uSh, uTemp, uTint, uSat, uVib, uFade, uMono;
uniform int uUseMaster, uUseR, uUseG, uUseB;
uniform int uHslOn;
uniform float uHueShift, uTargetHue, uHslSatK, uHslLightK;
in vec2 vUv;
out vec4 fragColor;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec4 curveAt(float v, float channel) {
    float x = (clamp(v, 0.0, 1.0) * 255.0 + 0.5) / 256.0;
    return texture(uCurves, vec2(x, 0.5));
}

void main() {
    vec4 src = texture(uSrc, vUv);
    vec3 c = src.rgb;

    c *= uEv;
    c += uBright;
    c = (c - 0.5) * uContrast + 0.5;

    float lum = luma(c);
    if (abs(uHi) > 1e-6) {
        float w = clamp((lum - 0.45) / 0.55, 0.0, 1.0);
        c -= uHi * w * 0.45;
    }
    if (abs(uSh) > 1e-6) {
        float w = clamp((0.55 - lum) / 0.55, 0.0, 1.0);
        c += uSh * w * 0.45;
    }

    if (abs(uTemp) > 1e-6) { c.r += uTemp * 0.18; c.b -= uTemp * 0.18; }
    if (abs(uTint) > 1e-6) { c.g -= uTint * 0.12; c.r += uTint * 0.04; c.b += uTint * 0.04; }

    float gray = luma(c);
    c = gray + (c - gray) * uSat;

    if (abs(uVib) > 1e-6) {
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float cs = mx <= 0.0 ? 0.0 : (mx - mn) / mx;
        float boost = uVib * (1.0 - cs) * 0.5;
        c = gray + (c - gray) * (1.0 + boost);
    }

    // 曲线：主曲线先压亮度（逐通道查同一张表），再逐通道微调
    if (uUseMaster == 1) {
        c = vec3(
            curveAt(c.r, 0.0).a,
            curveAt(c.g, 0.0).a,
            curveAt(c.b, 0.0).a
        );
    }
    if (uUseR == 1) c.r = curveAt(c.r, 0.0).r;
    if (uUseG == 1) c.g = curveAt(c.g, 0.0).g;
    if (uUseB == 1) c.b = curveAt(c.b, 0.0).b;

    // HSL 局部调色：只动目标色相附近，权重随色相距离线性衰减
    if (uHslOn == 1) {
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float hue = 0.0;
        float lig = (mx + mn) * 0.5;
        float satH = 0.0;
        float diff = mx - mn;
        if (diff > 1e-6) {
            if (mx == c.r) hue = (c.g - c.b) / diff * 0.16666667;
            else if (mx == c.g) hue = (2.0 + (c.b - c.r) / diff) * 0.16666667;
            else hue = (4.0 + (c.r - c.g) / diff) * 0.16666667;
            if (hue < 0.0) hue += 1.0;
            satH = lig > 0.5 ? diff / (2.0 - mx - mn + 1e-6) : diff / (mx + mn + 1e-6);
        }
        float dist = min(abs(hue - uTargetHue), 1.0 - abs(hue - uTargetHue));
        float weight = clamp(1.0 - dist / 0.13, 0.0, 1.0);
        if (satH > 0.05 && weight > 0.0) {
            hue = mod(mod(hue + uHueShift * weight, 1.0) + 1.0, 1.0);
            satH = clamp(satH * (1.0 + (uHslSatK - 1.0) * weight), 0.0, 1.0);
            lig = clamp(lig + uHslLightK * weight, 0.0, 1.0);
        }
        float ch = (1.0 - abs(2.0 * lig - 1.0)) * satH;
        float hp = hue * 6.0;
        float xc = ch * (1.0 - abs(mod(hp, 2.0) - 1.0));
        float m0 = lig - ch * 0.5;
        int seg = int(hp);
        if (seg == 0) c = vec3(ch + m0, xc + m0, m0);
        else if (seg == 1) c = vec3(xc + m0, ch + m0, m0);
        else if (seg == 2) c = vec3(m0, ch + m0, xc + m0);
        else if (seg == 3) c = vec3(m0, xc + m0, ch + m0);
        else if (seg == 4) c = vec3(xc + m0, m0, ch + m0);
        else c = vec3(ch + m0, m0, xc + m0);
    }

    if (uFade > 0.0) c = c * (1.0 - uFade) + uFade * 0.22;
    if (uMono > 0.0) {
        float l = luma(c);
        c = c * (1.0 - uMono) + l * uMono;
    }

    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""

    /** 3×3 均值降噪，按 uMix 与原像素混合 */
    private const val FRAG_BLUR3 = """
#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uMix;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec4 c = texture(uSrc, vUv);
    vec3 sum = vec3(0.0);
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            sum += texture(uSrc, vUv + vec2(float(dx), float(dy)) * uTexel).rgb;
        }
    }
    vec3 avg = sum / 9.0;
    fragColor = vec4(mix(c.rgb, avg, uMix), c.a);
}
"""

    /** 3×3 拉普拉斯锐化，k 可超过 1（清晰度用更小权重走第二遍） */
    private const val FRAG_SHARP = """
#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uK;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec4 c = texture(uSrc, vUv);
    vec3 n = texture(uSrc, vUv + vec2(uTexel.x, 0.0)).rgb
           + texture(uSrc, vUv - vec2(uTexel.x, 0.0)).rgb
           + texture(uSrc, vUv + vec2(0.0, uTexel.y)).rgb
           + texture(uSrc, vUv - vec2(0.0, uTexel.y)).rgb;
    vec3 outC = c.rgb + (c.rgb * 4.0 - n) / 4.0 * uK;
    fragColor = vec4(clamp(outC, 0.0, 1.0), c.a);
}
"""

    /** 颗粒：按像素坐标哈希，强度与 CPU 的 ±k*26 对齐 */
    private const val FRAG_GRAIN = """
#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform float uAmp;
uniform float uSeed;
in vec2 vUv;
out vec4 fragColor;
float hash(vec2 p) {
    return fract(sin(dot(p + uSeed, vec2(12.9898, 78.233))) * 43758.5453);
}
void main() {
    vec4 c = texture(uSrc, vUv);
    float n = (hash(gl_FragCoord.xy) - 0.5) * 2.0 * uAmp;
    fragColor = vec4(clamp(c.rgb + n, 0.0, 1.0), c.a);
}
"""

    @Volatile private var disabled = false
    @Volatile private var selfTested = false
    @Volatile private var loggedFirstSuccess = false
    @Volatile private var loggedFirstBloom = false
    fun render(src: Bitmap, p: Params): Bitmap? {
        if (disabled || src.isRecycled || src.width <= 0 || src.height <= 0) return null
        if (!selfTested) {
            selfTested = true
            if (!runSelfTest()) {
                disabled = true
                DoukLog.w("PhotoEditor", "GPU_EDIT_SELF_TEST_FAIL")
                return null
            }
        }
        return runCatching { renderInternal(src, p) }.onFailure {
            DoukLog.w("PhotoEditor", "GPU_EDIT_RENDER_FAIL", "msg" to (it.message ?: it.javaClass.simpleName))
        }.getOrNull()?.also { out ->
            if (!loggedFirstSuccess) {
                loggedFirstSuccess = true
                DoukLog.i("PhotoEditor", "GPU_EDIT_OK", "w" to out.width, "h" to out.height)
            }
        }
    }

    /** 高光扩散（bloom / halation）：亮部阈值遮罩 → 两次可分离模糊 → 加回原图。 */
    private const val FRAG_BRIGHT = """
#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec3 c = texture(uSrc, vUv + uTexel * vec2(-0.5, -0.5)).rgb;
    c += texture(uSrc, vUv + uTexel * vec2( 0.5, -0.5)).rgb;
    c += texture(uSrc, vUv + uTexel * vec2(-0.5,  0.5)).rgb;
    c += texture(uSrc, vUv + uTexel * vec2( 0.5,  0.5)).rgb;
    c *= 0.25;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float t = clamp((l - 0.815) / 0.173, 0.0, 1.0);
    fragColor = vec4(c * t * t, 1.0);
}
"""

    private const val FRAG_BLUR = """
#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform vec2 uDir;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec2 d = uDir * uTexel;
    vec3 c = texture(uSrc, vUv).rgb * 0.227;
    c += (texture(uSrc, vUv + d).rgb + texture(uSrc, vUv - d).rgb) * 0.194;
    c += (texture(uSrc, vUv + d * 2.0).rgb + texture(uSrc, vUv - d * 2.0).rgb) * 0.121;
    c += (texture(uSrc, vUv + d * 3.0).rgb + texture(uSrc, vUv - d * 3.0).rgb) * 0.054;
    c += (texture(uSrc, vUv + d * 4.0).rgb + texture(uSrc, vUv - d * 4.0).rgb) * 0.016;
    fragColor = vec4(c, 1.0);
}
"""

    private const val FRAG_BLEND = """
#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform sampler2D uBlur;
uniform float uAmount;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec4 s = texture(uSrc, vUv);
    vec3 b = texture(uBlur, vUv).rgb;
    fragColor = vec4(clamp(s.rgb + b * uAmount * 1.15, 0.0, 1.0), s.a);
}
"""

    fun bloom(src: Bitmap, amount01: Float): Bitmap? {
        val amount = amount01.coerceIn(0f, 1f)
        if (disabled || amount <= 0.02f || src.isRecycled || src.width < 16 || src.height < 16) return null
        return runCatching { bloomInternal(src, amount) }.onFailure {
            DoukLog.w("PhotoEditor", "GPU_BLOOM_FAIL", "msg" to (it.message ?: it.javaClass.simpleName))
        }.getOrNull()?.also { out ->
            if (!loggedFirstBloom) {
                loggedFirstBloom = true
                DoukLog.i("PhotoEditor", "GPU_BLOOM_OK", "w" to out.width, "h" to out.height)
            }
        }
    }

    private fun bloomInternal(src: Bitmap, amount: Float): Bitmap? {
        val w = src.width
        val h = src.height
        val scale = 384f / maxOf(w, h).toFloat()
        if (scale >= 0.99f) return null
        val mw = (w * scale).toInt().coerceAtLeast(8)
        val mh = (h * scale).toInt().coerceAtLeast(8)

        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        val programs = IntArray(3)
        val fbos = IntArray(3)
        val textures = IntArray(4)
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] <= 0) return null
            val config = configs[0] ?: return null
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            GLES30.glGenTextures(4, textures, 0)
            GLES30.glGenFramebuffers(3, fbos, 0)
            uploadBitmap(textures[0], src)
            setupTarget(textures[1], fbos[0], mw, mh)
            setupTarget(textures[2], fbos[1], mw, mh)
            setupTarget(textures[3], fbos[2], w, h)

            programs[0] = buildProgram(FRAG_BRIGHT)
            programs[1] = buildProgram(FRAG_BLUR)
            programs[2] = buildProgram(FRAG_BLEND)
            if (programs.any { it == 0 }) return null

            // 阈值遮罩：全图 → 1/4 缩略
            drawSimple(programs[0], textures[0], fbos[0], mw, mh) { }
            // 两轮 水平 + 垂直 高斯，得到低频光晕
            repeat(2) {
                drawSimple(programs[1], textures[1], fbos[1], mw, mh) {
                    GLES30.glUniform2f(loc(programs[1], "uDir"), 1f, 0f)
                }
                drawSimple(programs[1], textures[2], fbos[0], mw, mh) {
                    GLES30.glUniform2f(loc(programs[1], "uDir"), 0f, 1f)
                }
            }
            // 回放大混合：原图 + 光晕
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[2])
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(programs[2])
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
            GLES30.glUniform1i(loc(programs[2], "uSrc"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[1])
            GLES30.glUniform1i(loc(programs[2], "uBlur"), 1)
            GLES30.glUniform1f(loc(programs[2], "uAmount"), amount)
            drawQuad(programs[2])

            val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            out.copyPixelsFromBuffer(buffer)
            out.density = Bitmap.DENSITY_NONE
            out
        } catch (e: Throwable) {
            DoukLog.w("PhotoEditor", "GPU_BLOOM_FAIL", "msg" to (e.message ?: e.javaClass.simpleName))
            null
        } finally {
            runCatching { GLES30.glDeleteProgram(programs[0]) }
            runCatching { GLES30.glDeleteProgram(programs[1]) }
            runCatching { GLES30.glDeleteProgram(programs[2]) }
            if (textures[0] != 0) runCatching { GLES30.glDeleteTextures(4, textures, 0) }
            if (fbos[0] != 0) runCatching { GLES30.glDeleteFramebuffers(3, fbos, 0) }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    /** 恒等参数必须原样输出，用来拦住着色器写错/通道错位这类问题 */
    private fun runSelfTest(): Boolean {
        val w = 32
        val h = 32
        val probe = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val r = x * 255 / (w - 1)
            val g = y * 255 / (h - 1)
            val b = ((x + y) * 255 / (w + h - 2))
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        probe.setPixels(px, 0, w, 0, 0, w, h)
        val out = runCatching { renderInternal(probe, Params()) }.getOrNull()
        probe.recycle()
        if (out == null) return false
        val got = IntArray(w * h)
        out.getPixels(got, 0, w, 0, 0, w, h)
        out.recycle()
        var maxDiff = 0
        for (i in 0 until w * h) {
            val a = px[i]
            val b2 = got[i]
            maxDiff = maxOf(
                maxDiff,
                kotlin.math.abs(((a shr 16) and 0xFF) - ((b2 shr 16) and 0xFF)),
                kotlin.math.abs(((a shr 8) and 0xFF) - ((b2 shr 8) and 0xFF)),
                kotlin.math.abs((a and 0xFF) - (b2 and 0xFF))
            )
        }
        return maxDiff <= 3
    }

    private fun renderInternal(src: Bitmap, p: Params): Bitmap? {
        val w = src.width
        val h = src.height
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        val programs = IntArray(4)
        val fbos = IntArray(2)
        val textures = IntArray(3) // src + ping + pong
        var curveTex = 0
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] <= 0) return null
            val config = configs[0] ?: return null
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            GLES30.glGenTextures(3, textures, 0)
            GLES30.glGenFramebuffers(2, fbos, 0)

            uploadBitmap(textures[0], src)
            setupTarget(textures[1], fbos[0], w, h)
            setupTarget(textures[2], fbos[1], w, h)

            programs[0] = buildProgram(FRAG_COLOR)
            if (programs[0] == 0) return null
            // 曲线纹理：R=红、G=绿、B=蓝、A=主曲线，恒等通道直接铺 0..255 斜坡
            curveTex = buildCurveTexture(p)

            var readTex = textures[0]
            drawColor(programs[0], readTex, fbos[0], curveTex, p, w, h)
            readTex = textures[1]

            if (p.denoise > 0.005f) {
                if (programs[1] == 0) programs[1] = buildProgram(FRAG_BLUR3)
                if (programs[1] == 0) return null
                drawSimple(programs[1], readTex, otherFbo(readTex, textures, fbos), w, h) {
                    GLES30.glUniform1f(loc(programs[1], "uMix"), p.denoise.coerceIn(0f, 1f) * 0.6f)
                }
                readTex = swapped(readTex, textures)
            }
            if (p.sharpen > 0.005f) {
                if (programs[2] == 0) programs[2] = buildProgram(FRAG_SHARP)
                if (programs[2] == 0) return null
                drawSimple(programs[2], readTex, otherFbo(readTex, textures, fbos), w, h) {
                    GLES30.glUniform1f(loc(programs[2], "uK"), p.sharpen.coerceIn(0f, 1.5f))
                }
                readTex = swapped(readTex, textures)
            }
            if (p.clarity > 0.005f) {
                if (programs[2] == 0) programs[2] = buildProgram(FRAG_SHARP)
                if (programs[2] == 0) return null
                drawSimple(programs[2], readTex, otherFbo(readTex, textures, fbos), w, h) {
                    GLES30.glUniform1f(loc(programs[2], "uK"), (p.clarity * 0.5f).coerceIn(0f, 1.5f))
                }
                readTex = swapped(readTex, textures)
            }
            if (p.grain > 0.005f) {
                if (programs[3] == 0) programs[3] = buildProgram(FRAG_GRAIN)
                if (programs[3] == 0) return null
                drawSimple(programs[3], readTex, otherFbo(readTex, textures, fbos), w, h) {
                    GLES30.glUniform1f(loc(programs[3], "uAmp"), (p.grain.coerceIn(0f, 1f) * 26f))
                    GLES30.glUniform1f(loc(programs[3], "uSeed"), 20261116f)
                }
                readTex = swapped(readTex, textures)
            }

            val fboIndex = if (readTex == textures[1]) 0 else 1
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[fboIndex])
            val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            out.copyPixelsFromBuffer(buffer)
            out.density = Bitmap.DENSITY_NONE
            out
        } catch (e: Throwable) {
            DoukLog.w("PhotoEditor", "GPU_EDIT_FAIL", "msg" to (e.message ?: e.javaClass.simpleName))
            null
        } finally {
            runCatching { GLES30.glDeleteProgram(programs[0]) }
            runCatching { GLES30.glDeleteProgram(programs[1]) }
            runCatching { GLES30.glDeleteProgram(programs[2]) }
            runCatching { GLES30.glDeleteProgram(programs[3]) }
            if (textures[0] != 0) runCatching { GLES30.glDeleteTextures(3, textures, 0) }
            if (curveTex != 0) runCatching { GLES30.glDeleteTextures(1, intArrayOf(curveTex), 0) }
            if (fbos[0] != 0) runCatching { GLES30.glDeleteFramebuffers(2, fbos, 0) }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    // ping/pong 交换：读纹理在 1/2 之间切换，写目标永远是“另一张”
    private fun otherFbo(read: Int, textures: IntArray, fbos: IntArray): Int =
        if (read == textures[1]) fbos[1] else fbos[0]

    private fun swapped(read: Int, textures: IntArray): Int =
        if (read == textures[1]) textures[2] else textures[1]

    private fun uploadBitmap(tex: Int, bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val buffer = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
            .order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, bitmap.width, bitmap.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer
        )
    }

    private fun setupTarget(tex: Int, fbo: Int, w: Int, h: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "离屏帧缓冲不完整"
        }
    }

    private fun buildCurveTexture(p: Params): Int {
        val pixels = IntArray(256) { i ->
            val master = p.masterCurve?.getOrNull(i)?.toInt()?.and(0xFF) ?: i
            val r = p.redCurve?.getOrNull(i)?.toInt()?.and(0xFF) ?: i
            val g = p.greenCurve?.getOrNull(i)?.toInt()?.and(0xFF) ?: i
            val b = p.blueCurve?.getOrNull(i)?.toInt()?.and(0xFF) ?: i
            (master shl 24) or (b shl 16) or (g shl 8) or r
        }
        val buffer = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
        pixels.forEach { buffer.putInt(it) }
        buffer.rewind()
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 256, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer
        )
        return tex[0]
    }

    private fun drawColor(
        program: Int,
        srcTex: Int,
        fbo: Int,
        curveTex: Int,
        p: Params,
        w: Int,
        h: Int
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glUniform1i(loc(program, "uSrc"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
        GLES30.glUniform1i(loc(program, "uCurves"), 1)
        GLES30.glUniform1f(loc(program, "uEv"), p.ev)
        GLES30.glUniform1f(loc(program, "uBright"), p.bright)
        GLES30.glUniform1f(loc(program, "uContrast"), p.contrast)
        GLES30.glUniform1f(loc(program, "uHi"), p.highlight)
        GLES30.glUniform1f(loc(program, "uSh"), p.shadow)
        GLES30.glUniform1f(loc(program, "uTemp"), p.temperature)
        GLES30.glUniform1f(loc(program, "uTint"), p.tint)
        GLES30.glUniform1f(loc(program, "uSat"), p.saturation)
        GLES30.glUniform1f(loc(program, "uVib"), p.vibrance)
        GLES30.glUniform1f(loc(program, "uFade"), p.fade)
        GLES30.glUniform1f(loc(program, "uMono"), p.mono)
        GLES30.glUniform1i(loc(program, "uUseMaster"), if (p.masterCurve != null) 1 else 0)
        GLES30.glUniform1i(loc(program, "uUseR"), if (p.redCurve != null) 1 else 0)
        GLES30.glUniform1i(loc(program, "uUseG"), if (p.greenCurve != null) 1 else 0)
        GLES30.glUniform1i(loc(program, "uUseB"), if (p.blueCurve != null) 1 else 0)
        GLES30.glUniform1i(loc(program, "uHslOn"), if (p.hslOn) 1 else 0)
        GLES30.glUniform1f(loc(program, "uHueShift"), p.hueShift)
        GLES30.glUniform1f(loc(program, "uTargetHue"), p.targetHue)
        GLES30.glUniform1f(loc(program, "uHslSatK"), p.hslSatK)
        GLES30.glUniform1f(loc(program, "uHslLightK"), p.hslLightK)
        drawQuad(program)
    }

    private fun drawSimple(
        program: Int,
        srcTex: Int,
        fbo: Int,
        w: Int,
        h: Int,
        setUniforms: () -> Unit
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glUniform1i(loc(program, "uSrc"), 0)
        GLES30.glUniform2f(loc(program, "uTexel"), 1f / w, 1f / h)
        setUniforms()
        drawQuad(program)
    }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(2 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private fun drawQuad(program: Int) {
        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
    }

    private fun loc(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name)

    private fun buildProgram(fragSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES30.glDeleteShader(vs)
            if (fs != 0) GLES30.glDeleteShader(fs)
            return 0
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            DoukLog.w("PhotoEditor", "GPU_EDIT_LINK_FAIL",
                "log" to GLES30.glGetProgramInfoLog(program).take(400))
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            DoukLog.w("PhotoEditor", "GPU_EDIT_COMPILE_FAIL",
                "type" to type, "log" to GLES30.glGetShaderInfoLog(shader).take(400))
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
