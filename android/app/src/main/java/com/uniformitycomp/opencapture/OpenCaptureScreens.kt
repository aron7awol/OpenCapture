// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.app.Activity
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.*

/** The preview never lives in a scroll view; both orientations use the same surface. */
class OpenCaptureScreens(activity: Activity, toggle: () -> Unit, about: () -> Unit) {
    val texture = TextureView(activity)
    private val border = object : View(activity) {
        val frame = RectF()
        val pen = Paint().apply { color = Color.rgb(128, 128, 128); style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = false }
        override fun onDraw(canvas: Canvas) {
            if (!frame.isEmpty) {
                canvas.drawRect(frame, pen)
                val x = kotlin.math.floor(frame.centerX()) + .5f
                val y = kotlin.math.floor(frame.centerY()) + .5f
                canvas.drawLine(x, frame.top, x, frame.bottom, pen)
                canvas.drawLine(frame.left, y, frame.right, y, pen)
            }
        }
    }
    fun outline(frame: RectF) {
        border.frame.set(kotlin.math.round(frame.left) + .5f, kotlin.math.round(frame.top) + .5f,
            kotlin.math.round(frame.right) - .5f, kotlin.math.round(frame.bottom) - .5f)
        border.invalidate()
    }
    val status = TextView(activity).apply {
        text = "Opening camera"; textSize = 14f; setTextColor(Color.WHITE)
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val cameraInfo = TextView(activity).apply {
        text = "OpenCapture"; textSize = 18f; setTextColor(Color.WHITE)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    val toggleButton = ImageButton(activity).apply {
        setImageResource(android.R.drawable.ic_media_pause); contentDescription = "Stop camera server"
        setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { toggle() }; tooltipText = contentDescription
    }
    val aboutButton = ImageButton(activity).apply {
        setImageResource(android.R.drawable.ic_menu_info_details); contentDescription = "About OpenCapture"
        setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { about() }; tooltipText = contentDescription
    }
    init {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val bar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(22, 24, 26))
        }
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; addView(cameraInfo); addView(status)
        }
        bar.addView(labels, LinearLayout.LayoutParams(0, dp(56), 1f))
        bar.addView(toggleButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(aboutButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        val preview = FrameLayout(activity).apply {
            addView(texture, FrameLayout.LayoutParams(-1, -1))
            addView(border, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        var landscape = false
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val wide = right - left > bottom - top && right - left >= dp(600)
            if (wide != landscape) {
                landscape = wide
                root.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                preview.layoutParams = if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f)
                bar.orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                bar.layoutParams = if (wide) LinearLayout.LayoutParams(dp(168), -1) else LinearLayout.LayoutParams(-1, -2)
                bar.gravity = if (wide) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                labels.layoutParams = if (wide) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(0, dp(56), 1f)
                cameraInfo.maxLines = if (wide) 3 else 1
                status.maxLines = if (wide) 4 else 2
            }
        }
        activity.setContentView(root)
    }
    fun serving(enabled: Boolean) {
        toggleButton.setImageResource(if (enabled) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        toggleButton.contentDescription = if (enabled) "Stop camera server" else "Resume camera server"
        toggleButton.tooltipText = toggleButton.contentDescription
    }
}
