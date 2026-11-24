package com.lutcamera.lutcamerapro

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lutcamera.lutcamerapro.log.DoukLog
import com.lutcamera.lutcamerapro.ui.douk.RawDecode
import java.io.File
import java.util.concurrent.Executors

/**
 * 仅 debug 变体使用的 RAW 解码探针。
 *
 * 不参与 release 打包；通过 adb 启动，传入本地 RAW 路径后直接调用 [RawDecode]，
 * 把解码结果写到应用外部目录并显示在屏幕上，方便在无相机连接的手机上验证 LibRaw。
 */
class RawProbeActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var image: ImageView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
            text = "RAW 解码中…"
        }
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(image, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)

        val path = intent.getStringExtra("path")
            ?: File(getExternalFilesDir(null), "LYS_3229.NEF").absolutePath
        val maxSide = intent.getIntExtra("maxSide", 4096)
        val preview = intent.getBooleanExtra("preview", true)
        val file = File(path)
        status.text = "文件: ${file.name}  ${file.length()} B\n目标长边: $maxSide  preview=$preview\nLibRaw: ${RawDecode.libRawVersion}"

        executor.execute {
            val started = System.currentTimeMillis()
            val bitmap = RawDecode.decode(file, maxSide, preview)
            if (bitmap == null) {
                runOnUiThread { status.text = "解码失败，详见 DoukLog/RawDecode" }
                return@execute
            }
            val outDir = getExternalFilesDir(null) ?: filesDir
            val out = File(outDir, "raw_probe_${bitmap.width}x${bitmap.height}.jpg")
            runCatching {
                out.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
            }
            val shown = if (bitmap.width > 1600) {
                val ratio = 1600f / bitmap.width
                Bitmap.createScaledBitmap(
                    bitmap,
                    1600,
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            runOnUiThread {
                image.setImageBitmap(shown)
                status.text = buildString {
                    append("解码成功 ${bitmap.width}×${bitmap.height}\n")
                    append("耗时 ${System.currentTimeMillis() - started} ms\n")
                    append("写入 ${out.absolutePath} ${out.length()} B")
                }
                DoukLog.i(
                    "RawProbe", "DONE",
                    "file" to file.name,
                    "out" to "${bitmap.width}x${bitmap.height}",
                    "preview" to preview.toString(),
                    "ms" to (System.currentTimeMillis() - started),
                    "jpeg" to out.absolutePath
                )
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
