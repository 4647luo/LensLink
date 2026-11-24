package com.lutcamera.lutcamerapro.ui.douk

import android.graphics.Bitmap
import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.File

/**
 * 相机 RAW 解码，走随 APK 编译的最新 LibRaw（0.23.0 Snapshot202611）。
 *
 * 为什么不用 AndroidLibRaw 2.0.7：它内置的是 LibRaw 0.21.2，遇到尼康 Z50II 的
 * “高效率★”(High Efficiency*) NEF 会在 open_file 阶段报数据损坏；最新 master
 * 的 src/decoders/nikonhe.cpp 才包含 Z50II HE/HE★ 解码器。
 *
 * JNI 直接用 AndroidBitmap_lockPixels 按 stride 写 ARGB_8888 软件位图，
 * 不再经过 HardwareBuffer，也不依赖 AndroidLibRaw 那个会花屏的 getMutableBitmap。
 */
object RawDecode {

    private const val TAG = "RawDecode"

    /** RAW 传感器直出的 HDR 增益层：Alpha_8，0=不增益，255=ratioMax。 */
    data class HdrGain(
        val gain: Bitmap,
        val ratioMax: Float,
        val highlightStart: Float
    )

    /** JNI 里创建 Bitmap 需要 jnigraphics；加载失败时所有 RAW 解码回退内嵌预览。 */
    private val nativeReady: Boolean by lazy {
        runCatching { System.loadLibrary("lianjingraw") }
            .onFailure { DoukLog.e(TAG, "NATIVE_LOAD_FAIL", "err" to it.message) }
            .isSuccess
    }

    /**
     * LibRaw 的 `raw` 静态库编译时带了 LIBRAW_NOTHREADS，不能并发解码。
     * 图库、编辑器、导出可能同时请求，这里用一把锁串行化。
     */
    private val nativeLock = Any()

    private external fun nativeDecode(path: String, maxSide: Int, preview: Boolean): Bitmap?

    private external fun nativeHdrGain(
        path: String,
        maxSide: Int,
        maxBoost: Float,
        highlightStart: Float
    ): Bitmap?

    private external fun nativeProbe(path: String): IntArray?

    private external fun nativeVersion(): String

    /** 当前实际编进 APK 的 LibRaw 版本；日志里能直接看出来是不是带 Z50II HE 的版本。 */
    val libRawVersion: String by lazy {
        if (!nativeReady) "unavailable" else runCatching { nativeVersion() }.getOrDefault("unknown")
    }

    /**
     * 解码一张 RAW。
     *
     * @param maxSide 输出长边上限；传 [Int.MAX_VALUE] 表示保留 LibRaw 原始输出尺寸
     * @param preview 预览场景允许 LibRaw 直接半尺寸输出，导出场景必须保持全尺寸
     */
    fun decode(file: File, maxSide: Int, preview: Boolean = true): Bitmap? {
        if (!file.exists() || file.length() <= 0L) return null
        if (!nativeReady) {
            DoukLog.w(TAG, "NATIVE_UNAVAILABLE", "file" to file.name)
            return null
        }
        val started = System.currentTimeMillis()
        return try {
            val bitmap = synchronized(nativeLock) {
                nativeDecode(file.absolutePath, maxSide, preview)
            }
            if (bitmap == null) {
                DoukLog.w(
                    TAG, "DECODE_NULL",
                    "file" to file.name,
                    "libraw" to libRawVersion,
                    "ms" to (System.currentTimeMillis() - started)
                )
                null
            } else {
                DoukLog.i(
                    TAG, "DECODE_OK",
                    "file" to file.name,
                    "out" to "${bitmap.width}x${bitmap.height}",
                    "preview" to preview.toString(),
                    "libraw" to libRawVersion,
                    "ms" to (System.currentTimeMillis() - started)
                )
                bitmap
            }
        } catch (t: Throwable) {
            // 不吞掉具体原因：RAW 格式千奇百怪，日志里要能看出是打开、处理还是建位图失败。
            DoukLog.e(
                TAG, "DECODE_FAIL",
                "file" to file.name,
                "libraw" to libRawVersion,
                "err" to t.message
            )
            null
        }
    }

    /**
     * 只要尺寸信息，不真正做去马赛克。
     * 图库先拿它判断原图是否超过 40MP，再决定是否降采样。
     */
    fun probeSize(file: File): Pair<Int, Int>? {
        if (!file.exists() || file.length() <= 0L || !nativeReady) return null
        return try {
            val size = synchronized(nativeLock) { nativeProbe(file.absolutePath) }
            if (size == null || size.size < 2 || size[0] <= 0 || size[1] <= 0) null
            else size[0] to size[1]
        } catch (_: Throwable) {
            null
        }
    }

    /** 供调试探针使用的最小解码，不带任何 UI 依赖。 */
    fun decodeProbe(file: File): Bitmap? =
        decode(file, Int.MAX_VALUE, preview = false)

    /**
     * 只从 RAW 传感器原始像素生成 HDR 增益图，不重复输出 SDR 底图。
     *
     * 预览传 1024/2048 长边即可；导出用 2048 长边，GainMap 本身就是低频亮度层，
     * 不需要跟底图同尺寸。highlightStart 越小，越高亮以下的中高光也会参与扩展。
     */
    fun hdrGain(
        file: File,
        maxSide: Int,
        maxBoost: Float,
        highlightStart: Float
    ): HdrGain? {
        if (!file.exists() || file.length() <= 0L || !nativeReady) return null
        val boost = maxBoost.coerceIn(1.02f, 5f)
        val start = highlightStart.coerceIn(0f, 0.98f)
        val started = System.currentTimeMillis()
        return try {
            val gain = synchronized(nativeLock) {
                nativeHdrGain(file.absolutePath, maxSide, boost, start)
            } ?: return null
            DoukLog.i(
                TAG, "HDR_GAIN_OK",
                "file" to file.name,
                "out" to "${gain.width}x${gain.height}",
                "boost" to boost.toString(),
                "start" to start.toString(),
                "ms" to (System.currentTimeMillis() - started)
            )
            HdrGain(gain, boost, start)
        } catch (t: Throwable) {
            DoukLog.e(TAG, "HDR_GAIN_FAIL", "file" to file.name, "err" to t.message)
            null
        }
    }
}
