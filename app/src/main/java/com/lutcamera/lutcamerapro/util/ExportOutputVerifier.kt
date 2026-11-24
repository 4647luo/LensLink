package com.lutcamera.lutcamerapro.util

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 导出后的最小闭环校验：只看文件是否真的能解析出视频轨和正时长。
 * 以前 MediaMuxer 在编码帧不足/音画不同步时会生成一个 0 字节或只有 ftyp 的假 MP4，
 * 界面却仍提示成功；这里在返回前把它拦下来并删掉半成品。
 */
object ExportOutputVerifier {
    data class VideoInfo(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val hasAudio: Boolean
    )

    fun verifyVideo(file: File, minDurationMs: Long = 100L): Result<VideoInfo> = runCatching {
        require(file.isFile) { "导出文件不存在" }
        require(file.length() > 4096L) { "导出文件过小（${file.length()} B）" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            require(duration >= minDurationMs) { "导出视频时长为 $duration ms" }
            require(width > 0 && height > 0) { "导出文件没有可解析的视频轨" }
            VideoInfo(
                durationMs = duration,
                width = width,
                height = height,
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            )
        } finally {
            runCatching { retriever.release() }
        }
    }
}
