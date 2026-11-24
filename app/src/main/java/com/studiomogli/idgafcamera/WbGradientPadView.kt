package com.studiomogli.idgafcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * FiLMiC Pro-style 2D White Balance (2500K..8500K) × Green/Magenta Tint (-50..+50) Gradient Map.
 * X-axis: Cool Blue (2500K) -> Neutral Daylight (5600K) -> Warm Amber (8500K).
 * Y-axis: Magenta (+50, top) -> Neutral (0, center) -> Green (-50, bottom).
 */
class WbGradientPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MIN_KELVIN = 2500
        const val MAX_KELVIN = 8500
        const val MIN_TINT = -50
        const val MAX_TINT = 50
    }

    var onWbChanged: ((kelvin: Int, tint: Int) -> Unit)? = null
    var onDragStateChanged: ((dragging: Boolean) -> Unit)? = null

    private var currentKelvin: Int = 5600
    private var currentTint: Int = 0
    private var isManualMode: Boolean = false

    private val padRect = RectF()
    private val clipPath = Path()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 11, 13, 16)
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }
    private val puckShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 11, 13, 16)
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
    }
    private val puckRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val puckCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5F1F")
        style = Paint.Style.FILL
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 11, 13, 16)
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    // Pre-rendered 2D bilinear color gradient (120x48 — fast, zero per-frame allocations)
    private val gradientBitmap: Bitmap = buildGradientBitmap(120, 48)

    fun setState(kelvin: Int, tint: Int, manual: Boolean) {
        currentKelvin = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN)
        currentTint = tint.coerceIn(MIN_TINT, MAX_TINT)
        isManualMode = manual
        invalidate()
    }

    private fun buildGradientBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)

        val neutralX = (5600f - MIN_KELVIN) / (MAX_KELVIN - MIN_KELVIN).toFloat()

        for (y in 0 until h) {
            val ty = y / (h - 1).toFloat() // 0 = top (+50 Magenta), 0.5 = neutral, 1 = bottom (-50 Green)
            val row = y * w
            for (x in 0 until w) {
                val tx = x / (w - 1).toFloat() // 0 = 2500K Cool Blue, neutralX = 5600K White, 1 = 8500K Warm Amber

                // Horizontal Kelvin base color (Cool Blue -> Crisp White -> Warm Amber)
                var r: Float
                var g: Float
                var b: Float
                if (tx <= neutralX) {
                    val f = (tx / neutralX).coerceIn(0f, 1f)
                    r = lerp(74f, 246f, f)
                    g = lerp(148f, 248f, f)
                    b = lerp(255f, 250f, f)
                } else {
                    val f = ((tx - neutralX) / (1f - neutralX)).coerceIn(0f, 1f)
                    r = lerp(246f, 255f, f)
                    g = lerp(248f, 168f, f)
                    b = lerp(250f, 64f, f)
                }

                // Vertical Tint blend (Top = Magenta, Bottom = Green)
                if (ty < 0.5f) {
                    val m = ((0.5f - ty) * 2f).coerceIn(0f, 1f) * 0.58f
                    r = lerp(r, 232f, m)
                    g = lerp(g, 88f, m)
                    b = lerp(b, 238f, m)
                } else {
                    val gr = ((ty - 0.5f) * 2f).coerceIn(0f, 1f) * 0.56f
                    r = lerp(r, 64f, gr)
                    g = lerp(g, 214f, gr)
                    b = lerp(b, 118f, gr)
                }

                pixels[row + x] = Color.argb(
                    238,
                    r.roundToInt().coerceIn(0, 255),
                    g.roundToInt().coerceIn(0, 255),
                    b.roundToInt().coerceIn(0, 255)
                )
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val dp = resources.displayMetrics.density
        val radius = 10f * dp
        padRect.set(1f, 1f, w - 1f, h - 1f)

        // Draw rounded 2D gradient map
        clipPath.reset()
        clipPath.addRoundRect(padRect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(gradientBitmap, null, padRect, bitmapPaint)

        // Neutral reference axes (5600K vertical & Tint=0 horizontal)
        val neutralX = padRect.left + ((5600f - MIN_KELVIN) / (MAX_KELVIN - MIN_KELVIN).toFloat()) * padRect.width()
        val neutralY = padRect.centerY()
        canvas.drawLine(neutralX, padRect.top, neutralX, padRect.bottom, guidePaint)
        canvas.drawLine(padRect.left, neutralY, padRect.right, neutralY, guidePaint)

        // Subtle axis corner labels
        axisTextPaint.textSize = 8f * dp
        axisTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("2500K", padRect.left + 6f * dp, padRect.bottom - 5f * dp, axisTextPaint)
        axisTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("8500K", padRect.right - 6f * dp, padRect.bottom - 5f * dp, axisTextPaint)
        axisTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("+M", neutralX, padRect.top + 10f * dp, axisTextPaint)
        canvas.drawText("-G", neutralX, padRect.bottom - 5f * dp, axisTextPaint)

        canvas.restore()
        canvas.drawRoundRect(padRect, radius, radius, borderPaint)

        // Draw tactile puck at current (Kelvin, Tint)
        val nx = ((currentKelvin - MIN_KELVIN).toFloat() / (MAX_KELVIN - MIN_KELVIN).toFloat()).coerceIn(0f, 1f)
        val ny = ((MAX_TINT - currentTint).toFloat() / (MAX_TINT - MIN_TINT).toFloat()).coerceIn(0f, 1f)
        val px = (padRect.left + nx * padRect.width()).coerceIn(padRect.left + 9f * dp, padRect.right - 9f * dp)
        val py = (padRect.top + ny * padRect.height()).coerceIn(padRect.top + 9f * dp, padRect.bottom - 9f * dp)

        val outerR = 8.5f * dp
        canvas.drawCircle(px, py, outerR, puckShadowPaint)
        canvas.drawCircle(px, py, outerR, puckRingPaint)
        if (isManualMode) {
            canvas.drawCircle(px, py, 3.6f * dp, puckCorePaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onDragStateChanged?.invoke(true)
                handleTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onDragStateChanged?.invoke(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouch(x: Float, y: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val nx = (x / w).coerceIn(0f, 1f)
        val ny = (y / h).coerceIn(0f, 1f)

        val rawK = MIN_KELVIN + nx * (MAX_KELVIN - MIN_KELVIN)
        val snappedK = ((rawK / 50f).roundToInt() * 50).coerceIn(MIN_KELVIN, MAX_KELVIN)
        val tint = (MAX_TINT - ny * (MAX_TINT - MIN_TINT)).roundToInt().coerceIn(MIN_TINT, MAX_TINT)

        if (snappedK != currentKelvin || tint != currentTint || !isManualMode) {
            currentKelvin = snappedK
            currentTint = tint
            isManualMode = true
            invalidate()
            onWbChanged?.invoke(snappedK, tint)
        }
    }
}
