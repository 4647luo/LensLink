package com.lutcamera.lutcamerapro.gpu

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.lutcamera.lutcamerapro.util.ExportOutputVerifier
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.lutcamera.lutcamerapro.log.DoukLog
import com.lutcamera.lutcamerapro.ui.douk.Lut3D
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 视频套 LUT 的 GL 管线：解码帧走外置纹理（samplerExternalOES），
 * 直接在三线性过滤的 3D LUT 纹理里取色，再按强度和原始画面混合。
 * 同一套 renderer 既用于导出的离屏渲染，也可给播放器复用。
 */
internal object VideoLutGl {
    /** GLES30 里没有这个常量，值来自 GL_OES_EGL_image_external。 */
    const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    private const val VERTEX = """#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
uniform mat4 uTexMatrix;
out vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

    private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler3D;

uniform samplerExternalOES uVideoTex;
uniform sampler3D uLutTex;
uniform float uLutSize;
uniform float uIntensity;

in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    vec3 src = texture(uVideoTex, vTexCoord).rgb;
    vec3 graded = src;
    if (uLutSize > 1.5) {
        // 3D 纹理是 LINEAR 采样，按 texel 中心缩进来取，边缘不会越界
        float scale = (uLutSize - 1.0) / uLutSize;
        float offset = 1.0 / (2.0 * uLutSize);
        vec3 p = clamp(src, 0.0, 1.0) * scale + offset;
        graded = texture(uLutTex, p).rgb;
    }
    fragColor = vec4(mix(src, graded, uIntensity), 1.0);
}
"""

    fun buildProgram(): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("视频 LUT 着色器链接失败：$log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("视频 LUT 着色器编译失败：$log")
        }
        return shader
    }
}

/** 一帧视频的 GL 渲染核心；必须只在持有 EGL 上下文的线程上调用。 */
internal class VideoLutRenderCore(
    private var frameWidth: Int,
    private var frameHeight: Int
) {
    // 画到屏幕/编码器 Surface 上的尺寸（viewport），跟解码帧尺寸可以不一样
    private var surfaceWidth = frameWidth
    private var surfaceHeight = frameHeight
    private var vpX = 0
    private var vpY = 0
    private var vpWidth = frameWidth
    private var vpHeight = frameHeight
    // 播放时按视频宽高比居中，导出时直接铺满编码尺寸
    private var fitAspect = false

    fun setSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceWidth = width
        surfaceHeight = height
        updateViewport()
    }

    fun setFitAspect(fit: Boolean) {
        fitAspect = fit
        updateViewport()
    }

    private fun updateViewport() {
        if (!fitAspect || frameWidth <= 0 || frameHeight <= 0) {
            vpX = 0; vpY = 0; vpWidth = surfaceWidth; vpHeight = surfaceHeight
            return
        }
        val scale = minOf(
            surfaceWidth.toFloat() / frameWidth,
            surfaceHeight.toFloat() / frameHeight
        )
        vpWidth = (frameWidth * scale).toInt().coerceAtLeast(1)
        vpHeight = (frameHeight * scale).toInt().coerceAtLeast(1)
        vpX = (surfaceWidth - vpWidth) / 2
        vpY = (surfaceHeight - vpHeight) / 2
    }

    /** 播放器拿到视频真实宽高后再改这里；编码导出时尺寸在配置阶段就确定，不用改。 */
    fun setFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        frameWidth = width
        frameHeight = height
        if (this::surfaceTexture.isInitialized) surfaceTexture.setDefaultBufferSize(width, height)
        updateViewport()
    }
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uVideoTex = 0
    private var uLutTex = 0
    private var uLutSize = 0
    private var uIntensity = 0

    private var videoTextureId = 0
    private var lutTextureId = 0
    private var lutSize = 0
    private var lutData: FloatArray? = null

    private val frameSync = Semaphore(0)
    private val texMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

    lateinit var surfaceTexture: SurfaceTexture
        private set

    /** 新帧到达（解码器送来一帧）时回调，预览用它按需触发渲染，不再 60fps 空转 */
    var onFrameAvailable: (() -> Unit)? = null

    fun init(frameHandler: Handler? = null): SurfaceTexture {
        program = VideoLutGl.buildProgram()
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uVideoTex = GLES30.glGetUniformLocation(program, "uVideoTex")
        uLutTex = GLES30.glGetUniformLocation(program, "uLutTex")
        uLutSize = GLES30.glGetUniformLocation(program, "uLutSize")
        uIntensity = GLES30.glGetUniformLocation(program, "uIntensity")

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        videoTextureId = ids[0]
        GLES30.glBindTexture(VideoLutGl.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES30.glTexParameteri(VideoLutGl.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(VideoLutGl.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(VideoLutGl.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(VideoLutGl.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(videoTextureId)
        surfaceTexture.setDefaultBufferSize(frameWidth, frameHeight)
        surfaceTexture.setOnFrameAvailableListener({
            frameSync.release()
            onFrameAvailable?.invoke()
        }, frameHandler ?: Handler(Looper.getMainLooper()))
        return surfaceTexture
    }

    /** 上传（或替换）3D LUT；传 null 表示只播放原始画面。 */
    fun setLut(lut: Lut3D?) {
        if (lut == null) {
            lutSize = 0
            return
        }
        if (lutData == lut.data && lutSize == lut.size) return
        val bytes = ByteBuffer.allocateDirect(lut.size * lut.size * lut.size * 3)
            .order(ByteOrder.nativeOrder())
        for (v in lut.data) {
            bytes.put((v.coerceIn(0f, 1f) * 255f).toInt().toByte())
        }
        bytes.position(0)
        if (lutTextureId == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            lutTextureId = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        }
        // RGB8 每行字节数 = size * 3（33 格就是 99 字节），默认 4 字节对齐会把每行读歪，
        // 上传前必须改成 1，否则取色出来是一整片彩色噪点
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
            lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bytes
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        lutSize = lut.size
        lutData = lut.data
    }

    /** 等解码器把新帧送进 SurfaceTexture；超时返回 false。 */
    fun awaitFrame(timeoutMs: Long): Boolean =
        frameSync.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)

    /** 把最新一帧画到当前 EGLSurface；timestampNs 由解码器给出，用于音画同步。 */
    fun draw(timestampNs: Long, intensity: Float) {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)

        // 先按整个 Surface 清一次底，再把画面画进视频宽高比的那块区域（上下留黑）
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport(vpX, vpY, vpWidth, vpHeight)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(VideoLutGl.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES30.glUniform1i(uVideoTex, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        if (lutTextureId != 0) GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glUniform1i(uLutTex, 1)
        GLES30.glUniform1f(uLutSize, if (lutTextureId != 0) lutSize.toFloat() else 0f)
        GLES30.glUniform1f(uIntensity, intensity.coerceIn(0f, 1f))
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        quad.position(0)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 4 * 4, quad)
        GLES30.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 4 * 4, quad)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() {
        if (videoTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
        if (lutTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
        if (program != 0) GLES30.glDeleteProgram(program)
    }
}

/**
 * 视频套 LUT 导出：MediaExtractor → MediaCodec 解码 → GL 套 LUT → MediaCodec 编码 → MediaMuxer(MP4)。
 * 声音轨道直接从原文件搬 AAC 采样，不重新编码，音质不受影响。
 * 输出统一写 MP4（H.264 + AAC），手机相册、微信、剪辑软件都能直接打开。
 */
internal object VideoLutExporter {

    private const val TIMEOUT_US = 10_000L

    /** 音轨预处理结果：已经搬好或重编码好的采样，等 muxer 起来后按时间写进去。 */
    private class PreparedAudio(
        val format: MediaFormat,
        val samples: List<Pair<MediaCodec.BufferInfo, ByteArray>>
    )

    private fun sampleInfo(size: Int, ptsUs: Long, flags: Int): MediaCodec.BufferInfo =
        MediaCodec.BufferInfo().apply { set(0, size, ptsUs, flags) }

    /**
     * 尼康 N-Log / HLG 的 MOV 里是 24-bit LPCM，MediaMuxer 的 MP4 收不了这种 PCM，
     * 之前直接 addTrack 会失败，导出的片子就成了无声。这里按原样判断：
     * 已经是 AAC 的就直接搬采样，其它 PCM 先转 16-bit 再编成 AAC。
     */
    private fun prepareAudio(
        extractor: MediaExtractor,
        track: Int,
        file: File,
        startUs: Long = 0L,
        endUs: Long = Long.MAX_VALUE
    ): PreparedAudio? {
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        DoukLog.i("VideoLut", "AUDIO_PREP", "mime" to mime, "startUs" to startUs, "endUs" to endUs)
        val samples = ArrayList<Pair<MediaCodec.BufferInfo, ByteArray>>()
        if (mime == "audio/mp4a-latm") {
            extractor.selectTrack(track)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val maxInput = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(256 * 1024).coerceIn(64 * 1024, 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(maxInput)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break
                val time = extractor.sampleTime
                if (time >= endUs) break
                if (time >= startUs) {
                    val bytes = ByteArray(size)
                    buffer.position(0)
                    buffer.get(bytes)
                    samples.add(
                        sampleInfo(
                            size,
                            time - startUs,
                            extractor.sampleFlags
                        ) to bytes
                    )
                }
                extractor.advance()
            }
            runCatching { extractor.unselectTrack(track) }
            return if (samples.isEmpty()) null else PreparedAudio(format, samples)
        }
        if (mime.startsWith("audio/")) {
            val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(48000).let { if (it in 8000..96000) it else 48000 }
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(2).coerceIn(1, 2)
            val pcmEncoding = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                .getOrDefault(21)
            DoukLog.i(
                "VideoLut", "AUDIO_SRC",
                "rate" to sampleRate, "ch" to channels, "pcm" to pcmEncoding, "mime" to mime
            )
            extractor.selectTrack(track)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val maxRaw = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(64 * 1024).coerceIn(16 * 1024, 1024 * 1024)
            val raw = ByteBuffer.allocateDirect(maxRaw).order(ByteOrder.nativeOrder())
            val next = {
                var result: ByteArray? = null
                while (result == null) {
                    raw.clear()
                    val size = extractor.readSampleData(raw, 0)
                    if (size <= 0) {
                        runCatching { extractor.unselectTrack(track) }
                        DoukLog.i("VideoLut", "AUDIO_READ_END", "size" to size)
                        break
                    }
                    val time = extractor.sampleTime
                    if (time >= endUs) {
                        runCatching { extractor.unselectTrack(track) }
                        break
                    }
                    val bytes = pcmTo16(raw, size, pcmEncoding, channels)
                    extractor.advance()
                    if (time >= startUs) result = bytes
                }
                result
            }
            return encodeAac(sampleRate, channels, next)
        }
        // MediaExtractor 压根读不出 MOV 里的 LPCM（小米这代只给 application/octet-stream），
        // 自己走 box 结构拿 PCM 再编 AAC
        val reader = MovAudioReader.open(file) ?: return null
        DoukLog.i(
            "VideoLut", "AUDIO_MOV",
            "format" to reader.format, "ch" to reader.channels,
            "rate" to reader.sampleRate, "bits" to reader.sampleBits,
            "dur" to reader.durationMs
        )
        // MOV 的 PCM 读取器只能从头流式读，按字节数跳过开头、截断结尾；选区外的音频不要带进 Live 图。
        val bytesPerSec = reader.sampleRate.toLong() * reader.channels.coerceIn(1, 2) * 2L
        val skipBytes = if (startUs > 0) (startUs / 1_000_000.0 * bytesPerSec).toLong() else 0L
        val maxBytes = if (endUs != Long.MAX_VALUE) {
            ((endUs - startUs) / 1_000_000.0 * bytesPerSec).toLong()
        } else Long.MAX_VALUE
        val it16 = reader.stream16().iterator()
        var skipped = 0L
        var emitted = 0L
        return encodeAac(reader.sampleRate, reader.channels.coerceIn(1, 2)) {
            var out: ByteArray? = null
            while (out == null) {
                if (emitted >= maxBytes || !it16.hasNext()) break
                val chunk = it16.next()
                var offset = 0
                if (skipped < skipBytes) {
                    val need = (skipBytes - skipped).coerceAtMost(chunk.size.toLong()).toInt()
                    skipped += need
                    offset += need
                }
                if (offset < chunk.size) {
                    val remain = (maxBytes - emitted).coerceAtMost((chunk.size - offset).toLong()).toInt()
                    if (remain > 0) {
                        out = chunk.copyOfRange(offset, offset + remain)
                        emitted += remain
                    }
                }
            }
            out
        }
    }

    /**
     * 把 16-bit 小端 PCM 连续喂给 AAC 编码器，产出一串可写进 MP4 的采样。
     */
    private fun encodeAac(
        sampleRate: Int,
        channels: Int,
        next: () -> ByteArray?
    ): PreparedAudio? {
        val frameBytes = 2 * channels
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val samples = ArrayList<Pair<MediaCodec.BufferInfo, ByteArray>>()
        var outFormat: MediaFormat? = null
        var framesFed = 0L
        val info = MediaCodec.BufferInfo()
        try {
            encoder.configure(
                MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            encoder.start()

            var pending = ByteArray(0)
            var pendingOffset = 0
            var inputDone = false
            var eosQueued = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    if (pendingOffset >= pending.size) {
                        val chunk = next()
                        if (chunk == null || chunk.isEmpty()) {
                            inputDone = true
                            DoukLog.i("VideoLut", "AUDIO_READ_END", "frames" to framesFed)
                        } else {
                            pending = chunk
                            pendingOffset = 0
                        }
                    }
                    if (pendingOffset < pending.size) {
                        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val ib = encoder.getInputBuffer(inIndex)!!
                            ib.clear()
                            val take = minOf(ib.remaining(), pending.size - pendingOffset) / frameBytes * frameBytes
                            if (take > 0) {
                                ib.put(pending, pendingOffset, take)
                                val ptsUs = framesFed * 1_000_000L / sampleRate
                                framesFed += take / frameBytes
                                pendingOffset += take
                                encoder.queueInputBuffer(inIndex, 0, take, ptsUs, 0)
                            } else {
                                encoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                            }
                        }
                    }
                }
                if (inputDone && !eosQueued) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        encoder.queueInputBuffer(
                            inIndex, 0, 0, framesFed * 1_000_000L / sampleRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosQueued = true
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(info, if (eosQueued) TIMEOUT_US else 0)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = encoder.outputFormat
                    outIndex >= 0 -> {
                        val ob = encoder.getOutputBuffer(outIndex)
                        if (ob != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.get(bytes)
                            samples.add(
                                sampleInfo(info.size, info.presentationTimeUs, info.flags) to bytes
                            )
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        val fmt = outFormat ?: return null
        DoukLog.i("VideoLut", "AUDIO_ENC", "frames" to framesFed, "bytes" to samples.size)
        if (samples.isEmpty()) return null
        return PreparedAudio(fmt, samples)
    }

    /** 把 16/24/32-bit 或 float 的 PCM 统一削成 16-bit 小端，交给 AAC 编码器。 */
    private fun pcmTo16(raw: ByteBuffer, size: Int, encoding: Int, channels: Int): ByteArray {
        when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_16BIT -> {
                val out = ByteArray(size)
                raw.position(0)
                raw.get(out, 0, size)
                return out
            }
            android.media.AudioFormat.ENCODING_PCM_32BIT -> {
                val out = ByteArray(size / 4 * 2)
                var i = 0
                var o = 0
                while (i + 3 < size) {
                    out[o] = raw.get(i + 2)
                    out[o + 1] = raw.get(i + 3)
                    i += 4; o += 2
                }
                return out
            }
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val frames = size / 4
                val out = ByteArray(frames * 2)
                val view = raw.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                view.position(0)
                for (f in 0 until frames) {
                    val s = (view.get(f).coerceIn(-1f, 1f) * 32767f).toInt()
                    out[f * 2] = (s and 0xFF).toByte()
                    out[f * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                return out
            }
            else -> { // 24-bit packed（尼康 LPCM 的默认形态）以及其它按 24-bit 处理
                val frames = size / 3
                val out = ByteArray(frames * 2)
                var o = 0
                for (f in 0 until frames) {
                    val base = f * 3
                    out[o] = raw.get(base + 1)
                    out[o + 1] = raw.get(base + 2)
                    o += 2
                }
                return out
            }
        }
    }

    /** 导出参数：分辨率上限 / 编码 / 码率档位。0 表示跟原片一致。 */
    data class ExportOptions(
        val maxLongSide: Int = 0,
        val codec: String = "auto",   // auto / avc / hevc
        val quality: String = "high", // high / standard
        /** 码率倍数：0.8 省体积 / 1.0 默认 / 1.5 高码率（灰片大面积渐变时更干净） */
        val bitrateScale: Float = 1.0f,
        /** 只导出一段：起点毫秒。默认 0（整段） */
        val startMs: Long = 0L,
        /** 只导出一段：终点毫秒。默认 0（到结尾） */
        val endMs: Long = 0L
    )

    @Synchronized
    fun export(
        input: File,
        output: File,
        lut: Lut3D?,
        intensity: Float = 1f,
        options: ExportOptions = ExportOptions(),
        onProgress: (Int) -> Unit = {}
    ): Result<File> = runCatching {
        require(input.exists()) { "视频文件不存在" }
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var videoTrack = -1
        var audioTrack = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (videoTrack < 0 && mime.startsWith("video/")) videoTrack = i
            // MOV 里的 LPCM 音轨，部分机型（小米/MediaParser）只给 application/octet-stream，
            // 采样率声道全没有，所以只要不是视频轨就当音轨试一把
            val looksAudio = mime.startsWith("audio/") || !mime.startsWith("video/")
            if (audioTrack < 0 && looksAudio) audioTrack = i
            DoukLog.i("VideoLut", "TRACK", "index" to i, "mime" to mime)
        }
        require(videoTrack >= 0) { "这个视频里没有可解码的画面轨道" }
        val videoFormat = extractor.getTrackFormat(videoTrack)
        val mime = videoFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("读不到视频编码格式")
        val srcW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        // 分辨率上限：长边超了就等比缩，缩完补到偶数（编码器要求）
        var scaledW = srcW
        var scaledH = srcH
        val cap = options.maxLongSide
        if (cap > 0 && maxOf(srcW, srcH) > cap) {
            val scale = cap.toFloat() / maxOf(srcW, srcH)
            scaledW = (srcW * scale).toInt().coerceAtLeast(2)
            scaledH = (srcH * scale).toInt().coerceAtLeast(2)
        }
        // 编码器用 16 对齐的尺寸，避免部分机型 H.264 编码要求
        val outW = (scaledW + 15) / 16 * 16
        val outH = (scaledH + 15) / 16 * 16
        val fps = runCatching { videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30)
            .let { if (it in 1..120) it else 30 }
        val durationUs = runCatching { videoFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        val rotation = runCatching { videoFormat.getInteger("rotation-degrees") }.getOrDefault(0)
        // 「选一段转 Live 图」：只解这一段，时间戳全部从 0 开始，避免生成的 MP4 前几秒黑屏。
        val segmentStartUs = (options.startMs.coerceAtLeast(0L) * 1_000L)
            .coerceAtMost(if (durationUs > 0) durationUs else Long.MAX_VALUE)
        val requestedEndUs = if (options.endMs > options.startMs) options.endMs * 1_000L else Long.MAX_VALUE
        val segmentEndUs = if (durationUs > 0) {
            requestedEndUs.coerceIn(segmentStartUs, durationUs)
        } else requestedEndUs
        val segmentDurationUs = if (segmentEndUs > segmentStartUs && segmentEndUs != Long.MAX_VALUE) {
            segmentEndUs - segmentStartUs
        } else if (durationUs > segmentStartUs) durationUs - segmentStartUs else 0L

        // 原片是 H.265 就默认继续用 H.265：同样码率下比 H.264 明显干净，
        // 灰片这种大面积渐变更不容易糊成块。
        val sourceIsHevc = mime.contains("hevc", true) || mime.contains("h265", true)
        val encoderMime = when (options.codec) {
            "hevc" -> "video/hevc"
            "avc" -> "video/avc"
            else -> if (sourceIsHevc) "video/hevc" else "video/avc"
        }

        val decoder = MediaCodec.createDecoderByType(mime)
        val encoder = MediaCodec.createEncoderByType(encoderMime)
        var muxer: MediaMuxer? = null
        var eglDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext = EGL14.EGL_NO_CONTEXT
        var eglSurface = EGL14.EGL_NO_SURFACE
        var core: VideoLutRenderCore? = null
        var decoderSurface: Surface? = null

        try {
            // ---- EGL：只为编码器的输入 Surface 建上下文 ----
            // 码率按「每像素每帧」算，H.265 只要 H.264 的六成左右就能达到同样观感。
            // 之前是 W×H×fps/1000 一刀切，4K 直接顶到 60Mbps 上限、1080p 又只有 6Mbps，
            // 两头都不对：4K 勉强够，1080p 一放大就糊。
            val bitsPerPixel = when {
                encoderMime == "video/hevc" && options.quality == "high" -> 0.15f
                encoderMime == "video/hevc" -> 0.10f
                options.quality == "high" -> 0.24f
                else -> 0.16f
            }
            val targetBitrate = (outW.toLong() * outH * fps * bitsPerPixel)
                .toLong()
                .let { (it * options.bitrateScale).toLong() }
                .coerceIn(8_000_000L, 160_000_000L)
            val encodeFormat = MediaFormat.createVideoFormat(encoderMime, outW, outH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate.toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (encoderMime == "video/hevc") {
                    // Main Profile 8bit：Surface 输入实际就是 8bit，标 Main10 会让部分机型
                    // 直接把编码器配成 P010 然后拒绝 GL 画面上屏。
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    )
                } else {
                    // 以前不写 profile，编码器默认给 Baseline：没有 CABAC、没有 B 帧，
                    // 同样码率下细节明显更糊。这里显式要 High Profile，4K 再标 Level 5.1。
                    val caps = runCatching {
                        encoder.codecInfo.getCapabilitiesForType("video/avc")
                    }.getOrNull()
                    val highSupported = caps?.profileLevels?.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    } == true
                    if (highSupported) {
                        runCatching {
                            setInteger(
                                MediaFormat.KEY_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                            )
                            setInteger(
                                MediaFormat.KEY_LEVEL,
                                if (outW * outH >= 3840 * 2160)
                                    MediaCodecInfo.CodecProfileLevel.AVCLevel51
                                else MediaCodecInfo.CodecProfileLevel.AVCLevel41
                            )
                        }
                    }
                }
            }
            encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            DoukLog.i(
                "VideoLut", "ENC",
                "name" to (runCatching { encoder.name }.getOrDefault("?")),
                "mime" to encoderMime, "w" to outW, "h" to outH, "fps" to fps,
                "bitrate" to targetBitrate
            )
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "无法初始化 EGL" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "EGL 初始化失败" }
            // 编码器的输入 Surface 必须按「可录制」配置申请（EGL_RECORDABLE_ANDROID），
            // 少了这个标记，部分高通机型上图能画进去，但编码器会把缓冲区按错误格式解释，
            // 出来的就是一整片彩色噪点。这里先带标记申请，个别驱动不支持再退回不带标记。
            val recordable = 0x3142 // EGL_RECORDABLE_ANDROID
            fun configAttrs(withRecordable: Boolean): IntArray = buildList {
                add(EGL14.EGL_RED_SIZE); add(8)
                add(EGL14.EGL_GREEN_SIZE); add(8)
                add(EGL14.EGL_BLUE_SIZE); add(8)
                add(EGL14.EGL_ALPHA_SIZE); add(8)
                add(EGL14.EGL_RENDERABLE_TYPE); add(0x40) // EGL_OPENGL_ES3_BIT
                if (withRecordable) { add(recordable); add(1) }
                add(EGL14.EGL_NONE)
            }.toIntArray()
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            var chosen = EGL14.eglChooseConfig(
                eglDisplay, configAttrs(true), 0, configs, 0, 1, numConfigs, 0
            ) && numConfigs[0] > 0
            if (!chosen) {
                chosen = EGL14.eglChooseConfig(
                    eglDisplay, configAttrs(false), 0, configs, 0, 1, numConfigs, 0
                ) && numConfigs[0] > 0
            }
            check(chosen) { "找不到可用的 EGL 配置" }
            val config = configs[0] ?: throw IllegalStateException("EGL 配置为空")
            val cfgCheck = IntArray(1)
            if (EGL14.eglGetConfigAttrib(eglDisplay, config, recordable, cfgCheck, 0)) {
                DoukLog.i("VideoLut", "EGL_RECORDABLE", "value" to cfgCheck[0])
            }
            val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(
                eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0
            )
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "无法创建 GL 上下文" }
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, config, inputSurface, intArrayOf(EGL14.EGL_NONE), 0
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "无法绑定编码器 Surface" }
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "GL 上下文绑定失败"
            }

            // ---- 解码器输出到 SurfaceTexture，GL 套 LUT 后直接进编码器 ----
            // 解码帧按原始尺寸进纹理，再画满 16 对齐后的编码尺寸，避免取样错位
            val renderCore = VideoLutRenderCore(srcW, srcH)
            renderCore.setSurfaceSize(outW, outH)
            core = renderCore
            val st = renderCore.init()
            renderCore.setLut(lut)
            decoderSurface = Surface(st)
            decoder.configure(videoFormat, decoderSurface, null, 0)
            decoder.start()

            // 音轨必须先处理好：muxer.start() 之后再 addTrack 会直接抛异常
            val preparedAudio = if (audioTrack >= 0) {
                runCatching {
                    prepareAudio(
                        extractor, audioTrack, input,
                        startUs = segmentStartUs,
                        endUs = segmentEndUs
                    )
                }
                    .onFailure { DoukLog.w("VideoLut", "AUDIO_FAIL", "err" to it.message) }
                    .getOrNull()
            } else null
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) muxer.setOrientationHint(rotation)
            val audioMuxTrack = if (preparedAudio != null) {
                runCatching { muxer!!.addTrack(preparedAudio.format) }.getOrDefault(-1)
            } else -1
            var videoMuxTrack = -1
            var muxStarted = false

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var encoderDone = false
            var renderedFrames = 0L
            val totalFrames = if (segmentDurationUs > 0) {
                (segmentDurationUs / 1_000_000.0 * fps).toLong()
            } else if (durationUs > 0) {
                (durationUs / 1_000_000.0 * fps).toLong()
            } else 0L
            var lastPercent = -1

            fun drainEncoder(endOfStream: Boolean) {
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxStarted) {
                                videoMuxTrack = muxer!!.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val buf = encoder.getOutputBuffer(outIndex) ?: run {
                                encoder.releaseOutputBuffer(outIndex, false)
                                return
                            }
                            if (info.size > 0 && muxStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer!!.writeSampleData(videoMuxTrack, buf, info)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                                return
                            }
                        }
                    }
                }
            }

            extractor.selectTrack(videoTrack)
            if (segmentStartUs > 0) {
                extractor.seekTo(segmentStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0 || sampleTime > segmentEndUs) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIndex, 0, size, sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoder.releaseOutputBuffer(outIndex, false)
                        outputDone = true
                    } else {
                        val frameUs = info.presentationTimeUs
                        // seek 到前一个关键帧后，关键帧到选区起点之间的帧直接丢弃；
                        // 选区终点之后的帧也丢弃，这样生成的 MP4 长度和用户选的一致。
                        if (frameUs < segmentStartUs || (segmentEndUs != Long.MAX_VALUE && frameUs >= segmentEndUs)) {
                            decoder.releaseOutputBuffer(outIndex, false)
                        } else {
                            decoder.releaseOutputBuffer(outIndex, true)
                            if (renderCore.awaitFrame(60)) {
                                val relUs = (frameUs - segmentStartUs).coerceAtLeast(0L)
                                renderCore.draw(relUs * 1000, intensity)
                                EGLExt.eglPresentationTimeANDROID(
                                    eglDisplay, eglSurface, relUs * 1000
                                )
                                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                                    throw IllegalStateException("视频帧写回编码器失败")
                                }
                                renderedFrames++
                                if (totalFrames > 0) {
                                    val percent = ((renderedFrames * 100) / totalFrames).toInt().coerceIn(0, 99)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                }
                            }
                        }
                    }
                }

                drainEncoder(false)
                if (encoderDone) outputDone = true
            }

            // 收尾：告诉编码器没有更多帧，再把剩余的编码数据全部写进 muxer
            runCatching { encoder.signalEndOfInputStream() }
            drainEncoder(true)
            onProgress(99)

            // ---- 音轨写回（已预处理：AAC 直搬或 PCM 转 AAC）----
            if (preparedAudio != null && audioMuxTrack >= 0 && muxStarted) {
                val audioBuffer = ByteBuffer.allocateDirect(
                    preparedAudio.samples.maxOf { it.second.size }
                ).order(ByteOrder.nativeOrder())
                for ((sampleInfo, bytes) in preparedAudio.samples) {
                    audioBuffer.clear()
                    audioBuffer.put(bytes)
                    audioBuffer.position(0)
                    audioBuffer.limit(bytes.size)
                    runCatching { muxer!!.writeSampleData(audioMuxTrack, audioBuffer, sampleInfo) }
                }
                DoukLog.i("VideoLut", "AUDIO_MUXED", "samples" to preparedAudio.samples.size)
            }
            onProgress(100)
            DoukLog.i(
                "VideoLut", "EXPORT_OK",
                "frames" to renderedFrames, "size" to output.length(), "lut" to (lut?.size ?: 0)
            )
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { decoderSurface?.release() }
            runCatching { core?.release() }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                runCatching { EGL14.eglTerminate(eglDisplay) }
            }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
        // muxer 已在 finally 里 stop，文件此时才是完整的；解析失败就删掉半成品，不把假成功交给上层。
        ExportOutputVerifier.verifyVideo(output).onFailure {
            output.delete()
            throw it
        }.getOrThrow()
        output
    }
}
