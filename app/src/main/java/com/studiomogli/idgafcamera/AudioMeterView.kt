package com.studiomogli.idgafcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class AudioMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var level: Float = 0f // 0.0f .. 1.0f
    private var peak: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 20, 24, 31)
        style = Paint.Style.FILL
    }
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34C759")
        style = Paint.Style.FILL
    }
    private val yellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCC00")
        style = Paint.Style.FILL
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B30")
        style = Paint.Style.FILL
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val segRect = RectF()

    fun updateLevel(normalized: Float) {
        val clamped = normalized.coerceIn(0f, 1f)
        level = level * 0.45f + clamped * 0.55f
        peak = if (clamped >= peak) clamped else (peak - 0.03f).coerceAtLeast(level)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val segments = 14
        val gap = 2f * resources.displayMetrics.density
        val segW = (w - gap * (segments - 1)) / segments

        for (i in 0 until segments) {
            val frac = (i + 1).toFloat() / segments.toFloat()
            val left = i * (segW + gap)
            segRect.set(left, 0f, left + segW, h)
            val paint = when {
                frac > level -> bgPaint
                frac > 0.85f -> redPaint
                frac > 0.65f -> yellowPaint
                else -> greenPaint
            }
            canvas.drawRoundRect(segRect, 2f, 2f, paint)
        }

        if (peak > 0.05f) {
            val peakIdx = ((peak * segments).toInt()).coerceIn(0, segments - 1)
            val left = peakIdx * (segW + gap)
            segRect.set(left, 0f, left + segW, h)
            canvas.drawRoundRect(segRect, 2f, 2f, peakPaint)
        }
    }
}
