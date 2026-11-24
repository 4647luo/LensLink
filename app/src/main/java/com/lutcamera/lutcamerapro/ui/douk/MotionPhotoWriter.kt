package com.lutcamera.lutcamerapro.ui.douk

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.lutcamera.lutcamerapro.log.DoukLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * 把一段 MP4 合成安卓动态照片（Google Motion Photo / 小米「实况图」）。
 *
 * 文件结构：封面 JPEG（含 XMP + 旧版 MicroVideo 兼容标记）在前，MP4 原样追加在后。
 * 新版 Android / Google Photos 读 Container XMP；小米相册、旧版编辑器读
 * GCamera:MicroVideo / MicroVideoOffset；两套都写，最大化兼容。
 *
 * 参考：
 * - Android 官方 Motion Photo 1.0 格式
 * - GitHub `hellodk34/make_motion_photo` 的 XMP 结构
 * - GitHub `ZhiQiu-Kinsey/AppleLivePhotoConvert` 的 GIF/JPEG+MP4 拼接思路
 */
internal object MotionPhotoWriter {

    /**
     * @param video 已裁好片段的 MP4（时长就是 Live 图循环的那一段）
     * @param output 目标 .jpg
     * @param coverAtUs 封面帧时间，通常取片段开头往后 0.2 秒
     */
    fun write(video: File, output: File, coverAtUs: Long = 200_000L): Result<File> = runCatching {
        require(video.exists() && video.length() > 0) { "Live 图视频片段不存在" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val bitmap = retriever.getFrameAtTime(
                coverAtUs.coerceAtLeast(0L),
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: retriever.frameAtTime ?: throw IllegalStateException("读不到视频封面帧")
            val cover = scaleCover(bitmap, 3840)
            val jpegBytes = ByteArrayOutputStream().use { baos ->
                cover.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                baos.toByteArray()
            }
            if (cover !== bitmap) cover.recycle()
            bitmap.recycle()
            require(jpegBytes.isNotEmpty()) { "封面帧编码失败" }

            val mp4Length = video.length()
            val xmp = buildXmp(mp4Length, coverAtUs)
            val jpegWithXmp = injectXmp(jpegBytes, xmp)
            FileOutputStream(output).use { out ->
                out.write(jpegWithXmp)
                FileInputStream(video).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            DoukLog.i(
                "MotionPhoto", "WRITE_OK",
                "file" to output.name,
                "cover" to "${cover.width}x${cover.height}",
                "mp4Bytes" to mp4Length,
                "total" to output.length()
            )
        } finally {
            runCatching { retriever.release() }
        }
        output
    }

    private fun scaleCover(src: Bitmap, maxSide: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxSide) return src
        val ratio = maxSide.toFloat() / long
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun buildXmp(mp4Length: Long, presentationUs: Long): String {
        // 数字固定用 Locale.US，避免某些系统区域把 123456 写成 123,456。
        val len = String.format(Locale.US, "%d", mp4Length)
        val ts = String.format(Locale.US, "%d", presentationUs.coerceAtLeast(0L))
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                  xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                  xmlns:Container="http://ns.google.com/photos/1.0/container/"
                  xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                  Camera:MotionPhoto="1"
                  Camera:MotionPhotoVersion="1"
                  Camera:MotionPhotoPresentationTimestampUs="$ts"
                  Camera:MicroVideo="1"
                  Camera:MicroVideoVersion="1"
                  Camera:MicroVideoOffset="$len"
                  Camera:MicroVideoPresentationTimestampUs="$ts">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$len"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }

    /** JPEG 以 FFD8 开头；XMP APP1 放在 SOI 之后、其它段之前，系统扫描头 128KB 就能读到。 */
    private fun injectXmp(jpeg: ByteArray, xmp: String): ByteArray {
        require(jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
            "封面不是合法 JPEG"
        }
        val identifier = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
        val packet = xmp.toByteArray(Charsets.UTF_8)
        val payloadLength = identifier.size + packet.size + 2
        require(payloadLength <= 0xFFFF) { "XMP 段过大" }
        val segment = ByteArray(payloadLength + 2)
        segment[0] = 0xFF.toByte()
        segment[1] = 0xE1.toByte()
        segment[2] = ((payloadLength shr 8) and 0xFF).toByte()
        segment[3] = (payloadLength and 0xFF).toByte()
        System.arraycopy(identifier, 0, segment, 4, identifier.size)
        System.arraycopy(packet, 0, segment, 4 + identifier.size, packet.size)
        return ByteArray(2 + segment.size + jpeg.size - 2).also { out ->
            out[0] = jpeg[0]
            out[1] = jpeg[1]
            System.arraycopy(segment, 0, out, 2, segment.size)
            System.arraycopy(jpeg, 2, out, 2 + segment.size, jpeg.size - 2)
        }
    }
}
