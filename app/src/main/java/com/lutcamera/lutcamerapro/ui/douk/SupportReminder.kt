package com.lutcamera.lutcamerapro.ui.douk

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lutcamera.lutcamerapro.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每月第一次打开 App 时弹一次「用得怎么样」+ 赞赏码。
 *
 * 只按自然月记录，同一个月内不再重复打扰。
 * 弹窗是自绘圆角卡片，底部保留「取消 / 保存图片」两个按钮；点空白处或返回键也是取消。
 */
object SupportReminder {
    private const val MONTH_PATTERN = "yyyy-MM"

    /**
     * 如果本月还没弹过，就展示赞赏提示。
     * @return true 表示弹窗已经展示；调用方应等 onDismiss 后再做下一件事，避免两个对话框叠在一起。
     */
    fun showIfDue(activity: Activity, onDismiss: () -> Unit): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val month = SimpleDateFormat(MONTH_PATTERN, Locale.US).format(Date())
        if (DoukPrefs.lastSupportPromptMonth == month) return false

        val qr = runCatching {
            BitmapFactory.decodeResource(activity.resources, R.drawable.douk_support_qr)
        }.getOrNull() ?: return false

        // 先记月份再弹：即使进程随后被杀，本月也不会重复弹。
        DoukPrefs.lastSupportPromptMonth = month

        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val qrSide = (248 * density).toInt()
        val corner = 26f * density

        val title = TextView(activity).apply {
            text = "这个月用得怎么样？"
            textSize = 20f
            setTextColor(0xFF16181D.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val message = TextView(activity).apply {
            text = "如果连镜帮到了你，可以请作者喝杯咖啡。赞赏完全自愿，不影响任何功能。"
            textSize = 13.5f
            setLineSpacing(0f, 1.35f)
            setTextColor(0xFF4A4F57.toInt())
        }
        val image = ImageView(activity).apply {
            setImageBitmap(qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
        }
        val imageHolder = FrameLayout(activity).apply {
            addView(
                image,
                FrameLayout.LayoutParams(qrSide, qrSide, Gravity.CENTER_HORIZONTAL)
            )
        }
        fun actionButton(label: String, background: GradientDrawable, textColor: Int): TextView =
            TextView(activity).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(textColor)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isClickable = true
                isFocusable = true
                this.background = background
            }

        val cancelButton = actionButton(
            "取消",
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(0xFFF0F2F5.toInt())
            },
            0xFF4A4F57.toInt()
        )
        val saveButton = actionButton(
            "保存图片",
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(0xFF2F6FED.toInt())
            },
            Color.WHITE
        )
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelButton, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f))
            addView(saveButton, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                marginStart = (10 * density).toInt()
            })
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
                setColor(Color.WHITE)
            }
            addView(title)
            addView(message, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() })
            addView(imageHolder, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * density).toInt() })
            addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * density).toInt() })
        }

        var dismissed = false
        val finish = {
            if (!dismissed) {
                dismissed = true
                onDismiss()
            }
        }

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            setOnDismissListener { finish() }
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            saveToGallery(activity, qr)
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val maxWidth = activity.resources.displayMetrics.widthPixels - (48 * density).toInt()
            setLayout(minOf((340 * density).toInt(), maxWidth), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.55f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        return true
    }

    private fun saveToGallery(activity: Activity, qr: Bitmap) {
        val owner = activity as? LifecycleOwner
        val save = {
            val ok = saveSupportQrToGallery(activity, qr)
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    if (ok) "赞赏码已保存到相册" else "保存失败，请稍后再试",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (owner != null) {
            owner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { save() }
            }
        } else {
            Thread({ save() }, "lianjing-support-save").start()
        }
    }
}
