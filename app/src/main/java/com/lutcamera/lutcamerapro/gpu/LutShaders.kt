package com.lutcamera.lutcamerapro.gpu

import android.opengl.GLES30
import com.lutcamera.lutcamerapro.log.Log
/**
 * 🚀 Color Engine V5.0 电影级 GPU 着色器引擎
 * 支持：Adobe XMP 全能调色管线 + 3D LUT 四面体插值 + ACES Filmic 高光映射
 */
object LutShaders {
    private const val TAG = "LutShaders"

    private const val VERTEX_SHADER = """#version 300 es
    in vec4 aPosition;
    in vec2 aTextureCoord;
    out vec2 vTextureCoord;

    void main() {
        gl_Position = aPosition;
        vTextureCoord = aTextureCoord;
    }
    """

    private const val FRAGMENT_SHADER = """#version 300 es
    precision highp float;
    precision highp sampler3D;

    in vec2 vTextureCoord;
    out vec4 fragColor;

    uniform sampler2D uImageTexture;
    uniform sampler3D uLutTexture;

    uniform float uLutSize;
    uniform float uIntensity;
    uniform float uExposure;
    uniform float uTemperature;
    uniform float uTint;
    uniform int uUseACES;
    uniform int uUseTetrahedral;
    uniform int uShowHighlightWarning;
    uniform int uShowShadowWarning;
    // 监看专用：伪色（曝光判断）与 UV 变换（旋转/镜像在 GPU 里做）
    uniform int uFalseColor;
    // 峰值对焦：用屏幕空间导数找亮度边缘，阈值以上染成品红，和伪色互斥使用
    uniform int uPeaking;
    uniform float uPeakThreshold;
    uniform int uUvRotation;
    uniform int uUvMirror;

    // 🌟 XMP 调色引擎专属 Uniforms
                uniform int uEnableXmp;
    uniform float uXmpExposure;
    uniform float uXmpContrast;
    uniform float uXmpHighlights;
    uniform float uXmpShadows;
    uniform float uXmpWhites;
    uniform float uXmpBlacks;
    uniform float uXmpTemperature;
    uniform float uXmpTint;
    uniform float uXmpVibrance;
    uniform float uXmpSaturation;
    uniform float uXmpClarity;
    uniform float uXmpDehaze;
    uniform float uXmpHueAdjust[8];
    uniform float uXmpSatAdjust[8];
    uniform float uXmpLumAdjust[8];

    vec3 sRGBToLinearExact(vec3 c) {
        vec3 low = c / 12.92;
        vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
        return mix(high, low, step(vec3(0.04045), c));
    }

    vec3 linearTosRGBExact(vec3 c) {
        vec3 low = c * 12.92;
        vec3 high = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
        return mix(high, low, step(vec3(0.0031308), c));
    }

    vec3 ACESFilm(vec3 x) {
        float a = 2.51;
        float b = 0.03;
        float c = 2.43;
        float d = 0.59;
        float e = 0.14;
        return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
    }

    vec3 rgb2hsv(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }

    vec3 hsv2rgb(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }

    // XMP HSL 色相分段加权微调
                vec3 applyXmpHsl(vec3 rgb) {
        vec3 hsv = rgb2hsv(rgb);
        float h = hsv.x; // 0.0 ~ 1.0

        // 8 个特征色中心: 红(0/1), 橙(0.08), 黄(0.16), 绿(0.33), 蓝(0.5), 靛(0.66), 紫(0.78), 洋红(0.89)
                float centers[8] = float[8](0.0, 0.083, 0.166, 0.333, 0.5, 0.666, 0.777, 0.888);
        
        float totalWeight = 0.0;
        float deltaH = 0.0;
        float deltaS = 0.0;
        float deltaL = 0.0;

        for (int i = 0; i < 8; i++) {
            float dist = abs(h - centers[i]);
            if (dist > 0.5) dist = 1.0 - dist;
            float w = max(0.0, 1.0 - dist / 0.125);
            totalWeight += w;
            deltaH += uXmpHueAdjust[i] * w;
            deltaS += uXmpSatAdjust[i] * w;
            deltaL += uXmpLumAdjust[i] * w;
        }

        if (totalWeight > 0.0) {
            hsv.x = fract(hsv.x + (deltaH / totalWeight) * 0.1);
            hsv.y = clamp(hsv.y * (1.0 + (deltaS / totalWeight)), 0.0, 1.0);
            hsv.z = clamp(hsv.z * (1.0 + (deltaL / totalWeight)), 0.0, 1.0);
        }

        return hsv2rgb(hsv);
    }

    // 🚀 XMP 核心调色算法管线
                vec3 applyXmp(vec3 linColor) {
        // 1. 色温与色调 (White Balance)
                linColor.r *= (1.0 + uXmpTemperature * 0.2);
        linColor.b *= (1.0 - uXmpTemperature * 0.2);
        linColor.g *= (1.0 - uXmpTint * 0.2);
        linColor = max(linColor, vec3(0.0));

        // 2. 曝光 (Exposure)
                linColor *= pow(2.0, uXmpExposure);

        // 3. 高光与阴影阶梯处理 (Highlights / Shadows / Whites / Blacks)
                float lum = dot(linColor, vec3(0.2126, 0.7152, 0.0722));
        
        // 高光压缩/提升 (只作用于 > 0.4 的明亮区域)
                float highWeight = smoothstep(0.4, 1.0, lum);
        linColor += linColor * (uXmpHighlights * highWeight * 0.5);

        // 阴影提亮/压暗 (只作用于 < 0.6 的暗部区域)
                float shadowWeight = 1.0 - smoothstep(0.0, 0.6, lum);
        linColor += (1.0 - linColor) * (uXmpShadows * shadowWeight * 0.4);

        // 白场与黑场微调
                linColor = linColor * (1.0 + uXmpWhites * 0.25) + vec3(uXmpBlacks * 0.1);
        linColor = max(linColor, vec3(0.0));

        // 4. 对比度 (S 曲线调节)
                vec3 sColor = linColor - vec3(0.18);
        linColor = vec3(0.18) + sColor * (1.0 + uXmpContrast);
        linColor = max(linColor, vec3(0.0));

        // 转为 Gamma 空间做饱和度与 HSL
                vec3 gamma = linearTosRGBExact(clamp(linColor, 0.0, 1.0));

        // 5. 自然饱和度 (Vibrance) 与饱和度 (Saturation)
                float maxVal = max(gamma.r, max(gamma.g, gamma.b));
        float minVal = min(gamma.r, min(gamma.g, gamma.b));
        float curSat = (maxVal - minVal) / (maxVal + 0.001);
        float vibFactor = uXmpVibrance * (1.0 - curSat); // 低饱和优先提升
                float totalSat = 1.0 + uXmpSaturation + vibFactor;
        
        float luma = dot(gamma, vec3(0.299, 0.587, 0.114));
        gamma = mix(vec3(luma), gamma, totalSat);

        // 6. 8 通道 HSL
                gamma = applyXmpHsl(clamp(gamma, 0.0, 1.0));

        return sRGBToLinearExact(clamp(gamma, 0.0, 1.0));
    }

    /**
     * 画面旋转与镜像在 GPU 里做：Compose 的 graphicsLayer 对 SurfaceView 无效，
     * 又不想让用户丢掉「视图 → 旋转」能力，所以把屏幕坐标反解回源纹理坐标。
     * uv 以左上角为原点、v 向下，与纹理坐标一致。
     */
    vec2 mapUv(vec2 uv) {
        vec2 p = uv;
        // 先反解镜像（屏幕 x 以中心翻转等价于 1-x）
        if (uUvMirror == 1) p.x = 1.0 - p.x;
        // 再反解旋转：把"已旋转的屏幕坐标"还原成源图坐标
        if (uUvRotation == 90) {
            p = vec2(p.y, 1.0 - p.x);
        } else if (uUvRotation == 180) {
            p = vec2(1.0 - p.x, 1.0 - p.y);
        } else if (uUvRotation == 270) {
            p = vec2(1.0 - p.y, p.x);
        }
        return p;
    }

    /**
     * 伪色色带：按 0-255 亮度分九段，和监看界面的图例一一对应。
     * 用的是相机原始画面亮度（LUT 之前），这样开 LUT 时也能判断真实曝光。
     */
    vec3 falseColorBand(float lum) {
        if (lum < 6.0) return vec3(0.431, 0.180, 0.910);      // 黑场剪裁：紫
        if (lum < 41.0) return vec3(0.118, 0.420, 1.000);      // 深阴影：蓝
        if (lum < 81.0) return vec3(0.000, 0.700, 0.700);      // 阴影过渡：青
        if (lum < 136.0) return vec3(0.122, 0.816, 0.416);     // 中灰 18%：绿
        if (lum < 176.0) return vec3(0.706, 0.706, 0.706);     // 亮部过渡：灰
        if (lum < 216.0) return vec3(1.000, 0.831, 0.000);     // 接近高光：黄
        if (lum < 241.0) return vec3(1.000, 0.478, 0.000);     // 高光：橙
        if (lum < 253.0) return vec3(1.000, 0.231, 0.188);     // 高光临限：红
        return vec3(1.000, 0.000, 1.000);                       // 过曝：洋红
    }

    vec3 getTexel(sampler3D lut, vec3 iCoord, float size) {        ivec3 clampedCoord = ivec3(clamp(iCoord, vec3(0.0), vec3(size - 1.0)));
        return texelFetch(lut, clampedCoord, 0).rgb;
    }

    vec3 sampleTetrahedralPure(sampler3D lut, vec3 colorGamma, float size) {
        vec3 p = clamp(colorGamma, 0.0, 1.0) * (size - 1.0);
        vec3 i = floor(p);
        vec3 f = fract(p);

        vec3 c000 = getTexel(lut, i, size);
        vec3 c100 = getTexel(lut, i + vec3(1.0, 0.0, 0.0), size);
        vec3 c010 = getTexel(lut, i + vec3(0.0, 1.0, 0.0), size);
        vec3 c001 = getTexel(lut, i + vec3(0.0, 0.0, 1.0), size);
        vec3 c110 = getTexel(lut, i + vec3(1.0, 1.0, 0.0), size);
        vec3 c101 = getTexel(lut, i + vec3(1.0, 0.0, 1.0), size);
        vec3 c011 = getTexel(lut, i + vec3(0.0, 1.0, 1.0), size);
        vec3 c111 = getTexel(lut, i + vec3(1.0, 1.0, 1.0), size);

        vec3 result;
        if (f.x >= f.y) {
            if (f.y >= f.z) {
                result = c000 + f.x * (c100 - c000) + f.y * (c110 - c100) + f.z * (c111 - c110);
            } else if (f.x >= f.z) {
                result = c000 + f.x * (c100 - c000) + f.z * (c101 - c100) + f.y * (c111 - c101);
            } else {
                result = c000 + f.z * (c001 - c000) + f.x * (c101 - c001) + f.y * (c111 - c101);
            }
        } else {
            if (f.z >= f.y) {
                result = c000 + f.z * (c001 - c000) + f.y * (c011 - c001) + f.x * (c111 - c011);
            } else if (f.z >= f.x) {
                result = c000 + f.y * (c010 - c000) + f.z * (c011 - c010) + f.x * (c111 - c011);
            } else {
                result = c000 + f.y * (c010 - c000) + f.x * (c110 - c010) + f.z * (c111 - c110);
            }
        }
        return result;
    }

    void main() {
        vec4 rawColor = texture(uImageTexture, mapUv(vTextureCoord));
        vec3 linearColor = sRGBToLinearExact(rawColor.rgb);

        // 1. 如果启用了 XMP 调色预设，先进行 XMP 调整阶段
                if (uEnableXmp == 1) {
            linearColor = applyXmp(linearColor);
        }

        // 基础曝光与白骞宠
                linearColor *= pow(2.0, uExposure);
        linearColor.r *= (1.0 + uTemperature * 0.15);
        linearColor.b *= (1.0 - uTemperature * 0.15);
        linearColor.g *= (1.0 - uTint * 0.15);
        linearColor = max(linearColor, vec3(0.0));

        vec3 gammaForLut = linearTosRGBExact(clamp(linearColor, 0.0, 1.0));

        // 2. 3D LUT 胶片调色阶段
                vec3 lutColorGamma;
        if (uUseTetrahedral == 1) {
            lutColorGamma = sampleTetrahedralPure(uLutTexture, gammaForLut, uLutSize);
        } else {
            vec3 scale = vec3((uLutSize - 1.0) / uLutSize);
            vec3 offset = vec3(0.5 / uLutSize);
            vec3 correctedCoords = gammaForLut * scale + offset;
            lutColorGamma = texture(uLutTexture, correctedCoords).rgb;
        }

        vec3 blendedGamma = mix(gammaForLut, lutColorGamma, uIntensity);
        vec3 blendedLinear = sRGBToLinearExact(blendedGamma);

        // 3. ACES 电影级高光映射
                if (uUseACES == 1) {
            blendedLinear = ACESFilm(blendedLinear);
        }

        vec3 finalSRGB = linearTosRGBExact(blendedLinear);

        // 4.1 峰值对焦（Peaking）：合焦区域的边缘在片元空间导数会明显跳变，
        // 用原始画面亮度算梯度，超过阈值就把这一段染成品红。
        // 用 dFdx/dFdy 而不是邻域采样，阈值与取景分辨率无关，切画质档不用重调。
        float peakLuma = dot(rawColor.rgb, vec3(0.2126, 0.7152, 0.0722));
        float peakEdge = length(vec2(dFdx(peakLuma), dFdy(peakLuma)));
        if (uPeaking == 1 && peakEdge > uPeakThreshold && uFalseColor == 0) {
            float mixK = clamp((peakEdge - uPeakThreshold) * 6.0, 0.0, 0.92);
            finalSRGB = mix(finalSRGB, vec3(1.0, 0.12, 0.85), mixK);
        }

        // 4. 曝光警告分析
                float relativeLuminance = dot(finalSRGB, vec3(0.2126, 0.7152, 0.0722));
        float maxChannel = max(finalSRGB.r, max(finalSRGB.g, finalSRGB.b));

        if (uShowHighlightWarning == 1 && (relativeLuminance > 0.96 || maxChannel > 0.98)) {
            float pattern = sin((gl_FragCoord.x + gl_FragCoord.y) * 0.25);
            if (pattern > 0.0) finalSRGB = vec3(1.0, 0.1, 0.1);
        } else if (uShowShadowWarning == 1 && relativeLuminance < 0.03) {
            float pattern = sin((gl_FragCoord.x + gl_FragCoord.y) * 0.25);
            if (pattern > 0.0) finalSRGB = vec3(0.1, 0.4, 1.0);
        }

        // 5. 伪色（曝光判断）：按相机原始亮度的 IRE 分段覆盖整屏
        // 注意这里用 rawColor（LUT 之前的原始画面），套了 LUT 也还是判断真实曝光
        if (uFalseColor == 1) {
            float lumRaw = dot(rawColor.rgb, vec3(0.2126, 0.7152, 0.0722)) * 255.0;
            finalSRGB = falseColorBand(lumRaw);
            fragColor = vec4(finalSRGB, rawColor.a);
            return;
        }

        // 6. 8-Bit 电影级抗色阶断层微抖动 (High-Precision Dithering)
        // 针对天空、阴影等平滑渐变区域消除 8-bit 量化断层，极大还原胶片连续影调
                float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
        finalSRGB += (dither - 0.5) / 255.0;
        finalSRGB = clamp(finalSRGB, 0.0, 1.0);

        fragColor = vec4(finalSRGB, rawColor.a);
    }
    """

    // ==================== 波形图（GPU 点泼溅） ====================
    // 源画面按 256 列 × N 行网格采样，每个采样点按「列 → x、亮度 → y」泼溅到
    // 一张 256×128 的离屏纹理（加法混合），再用第二个 pass 显示出来。
    // 全部在 GPU 上做，替掉以前 CPU 逐像素生成 256×128 波形位图的路径。
    private const val WAVE_VERTEX_SHADER = """#version 300 es
    precision highp float;
    uniform sampler2D uSrc;
    uniform float uGridRows;
    uniform float uPointWeight;
    out float vWeight;

    void main() {
        int cols = 256;
        int gx = gl_VertexID % cols;
        int gy = gl_VertexID / cols;
        float sx = (float(gx) + 0.5) / float(cols);
        float sy = (float(gy) + 0.5) / uGridRows;
        vec3 c = textureLod(uSrc, vec2(sx, sy), 0.0).rgb;
        float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
        gl_Position = vec4(sx * 2.0 - 1.0, lum * 2.0 - 1.0, 0.0, 1.0);
        gl_PointSize = 1.0;
        vWeight = uPointWeight;
    }
    """

    private const val WAVE_SPLAT_FRAGMENT = """#version 300 es
    precision highp float;
    in float vWeight;
    out vec4 fragColor;
    void main() { fragColor = vec4(vWeight, vWeight, vWeight, vWeight); }
    """

    private const val WAVE_DISPLAY_FRAGMENT = """#version 300 es
    precision highp float;
    in vec2 vTextureCoord;
    out vec4 fragColor;
    uniform sampler2D uWave;
    uniform float uGain;

    void main() {
        float v = texture(uWave, vTextureCoord).r;
        float g = clamp(v * uGain, 0.0, 1.0);
        // IRE 25 / 50 / 75 参考线，做出监视器刻度感
        float lines = 0.0;
        if (abs(vTextureCoord.y - 0.25) < 0.010) lines = 0.08;
        if (abs(vTextureCoord.y - 0.50) < 0.010) lines = 0.16;
        if (abs(vTextureCoord.y - 0.75) < 0.010) lines = 0.08;
        vec3 col = mix(vec3(0.04, 0.05, 0.06), vec3(0.42, 1.0, 0.55), g);
        col += vec3(lines);
        fragColor = vec4(col, 0.86);
    }
    """

    fun createWaveSplatProgram(): Int = buildProgram(WAVE_VERTEX_SHADER, WAVE_SPLAT_FRAGMENT)

    fun createWaveDisplayProgram(): Int = buildProgram(VERTEX_SHADER, WAVE_DISPLAY_FRAGMENT)

    fun createProgram(): Int = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)

    private fun buildProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexCode)
        if (vertexShader == 0) return 0

        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentCode)
        if (fragmentShader == 0) return 0

        val program = GLES30.glCreateProgram()
        if (program != 0) {
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "链接着色器失败: ${GLES30.glGetProgramInfoLog(program)}")
                GLES30.glDeleteProgram(program)
                return 0
            }
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader != 0) {
            GLES30.glShaderSource(shader, shaderCode)
            GLES30.glCompileShader(shader)

            val compiled = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "编译着色器失败 (${type}): ${GLES30.glGetShaderInfoLog(shader)}")
                GLES30.glDeleteShader(shader)
                return 0
            }
        }
        return shader
    }
}

