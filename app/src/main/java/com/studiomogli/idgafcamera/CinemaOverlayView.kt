package com.studiomogli.idgafcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

enum class AspectGuide(val label: String, val ratio: Float) {
    OFF("OFF", 0f),
    SCOPE_239("2.39:1", 2.39f),
    WIDE_169("16:9", 16f / 9f),
    VERT_916("9:16", 9f / 16f)
}

enum class GridMode(val label: String) {
    OFF("GRID: OFF"),
    THIRDS("GRID: 3x3"),
    GOLDEN("GRID: φ")
}

class CinemaOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var gridMode: GridMode = GridMode.OFF
        set(value) {
            field = value
            invalidate()
        }

    var aspectGuide: AspectGuide = AspectGuide.OFF
        set(value) {
            field = value
            invalidate()
        }

    var showPeaking: Boolean = false
        set(value) {
            field = value
            if (!value) peakingBitmap = null
            invalidate()
        }

    var showZebras: Boolean = false
        set(value) {
            field = value
            if (!value) zebraBitmap = null
            invalidate()
        }

    var showHistogram: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var peakingBitmap: Bitmap? = null
    private var zebraBitmap: Bitmap? = null
    private val histogramBins = FloatArray(64)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 95, 31)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val mattePaint = Paint().apply {
        color = Color.argb(155, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 95, 31)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val histBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 11, 13, 16)
        style = Paint.Style.FILL
    }

    private val histBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(85, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val histFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 95, 31)
        style = Paint.Style.FILL
    }

    private val histPath = Path()
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destRect = RectF()
    private val histRect = RectF()

    fun needsFrameAnalysis(): Boolean = showPeaking || showZebras || showHistogram

    /**
     * Analyzes a lightweight downscaled frame (e.g. 160x90) for Focus Peaking,
     * Overexposure Zebras, and Live Luma Histogram.
     */
    fun analyzeFrame(sample: Bitmap) {
        val w = sample.width
        val h = sample.height
        if (w < 4 || h < 4) return

        val pixels = IntArray(w * h)
        sample.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = IntArray(w * h)
        val rawBins = IntArray(64)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            luma[i] = y
            if (showHistogram) {
                rawBins[y shr 2]++
            }
        }

        if (showHistogram) {
            var maxBin = 1
            for (b in rawBins) if (b > maxBin) maxBin = b
            for (i in 0 until 64) {
                histogramBins[i] = rawBins[i].toFloat() / maxBin.toFloat()
            }
        }

        if (showPeaking) {
            val peakPixels = IntArray(w * h)
            val threshold = 44
            val peakColor = Color.argb(230, 57, 255, 20) // Cinema Neon Green
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val gx = abs(luma[row + x + 1] - luma[row + x - 1])
                    val gy = abs(luma[row + w + x] - luma[row - w + x])
                    if (gx + gy > threshold) {
                        peakPixels[row + x] = peakColor
                    }
                }
            }
            val bmp = peakingBitmap?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(peakPixels, 0, w, 0, 0, w, h)
            peakingBitmap = bmp
        }

        if (showZebras) {
            val zebraPixels = IntArray(w * h)
            val zebraColor = Color.argb(215, 255, 59, 48) // Cinema Warning Red
            val zebraThreshold = 235 // ~92% IRE
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (luma[row + x] >= zebraThreshold && ((x + y) and 3) < 2) {
                        zebraPixels[row + x] = zebraColor
                    }
                }
            }
            val bmp = zebraBitmap?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(zebraPixels, 0, w, 0, 0, w, h)
            zebraBitmap = bmp
        }

        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        destRect.set(0f, 0f, w, h)

        // 1. Focus Peaking overlay
        if (showPeaking) {
            peakingBitmap?.let { canvas.drawBitmap(it, null, destRect, overlayPaint) }
        }

        // 2. Overexposure Zebras overlay
        if (showZebras) {
            zebraBitmap?.let { canvas.drawBitmap(it, null, destRect, overlayPaint) }
        }

        // 3. Aspect Ratio Frame Guides
        if (aspectGuide != AspectGuide.OFF && aspectGuide.ratio > 0f) {
            val viewRatio = w / h
            val targetRatio = aspectGuide.ratio
            if (targetRatio > viewRatio) {
                // Letterbox top & bottom
                val guideH = w / targetRatio
                val barH = (h - guideH) / 2f
                canvas.drawRect(0f, 0f, w, barH, mattePaint)
                canvas.drawRect(0f, h - barH, w, h, mattePaint)
                canvas.drawLine(0f, barH, w, barH, frameBorderPaint)
                canvas.drawLine(0f, h - barH, w, h - barH, frameBorderPaint)
            } else {
                // Pillarbox left & right (e.g. 9:16 vertical guide on landscape screen)
                val guideW = h * targetRatio
                val barW = (w - guideW) / 2f
                canvas.drawRect(0f, 0f, barW, h, mattePaint)
                canvas.drawRect(w - barW, 0f, w, h, mattePaint)
                canvas.drawLine(barW, 0f, barW, h, frameBorderPaint)
                canvas.drawLine(w - barW, 0f, w - barW, h, frameBorderPaint)
            }
        }

        // 4. Rule of Thirds OR Golden Ratio (Phi 0.382 / 0.618) Grid + Center Crosshair
        if (gridMode != GridMode.OFF) {
            val f1 = if (gridMode == GridMode.GOLDEN) 0.382f else (1f / 3f)
            val f2 = if (gridMode == GridMode.GOLDEN) 0.618f else (2f / 3f)
            val x1 = w * f1
            val x2 = w * f2
            val y1 = h * f1
            val y2 = h * f2
            canvas.drawLine(x1, 0f, x1, h, gridPaint)
            canvas.drawLine(x2, 0f, x2, h, gridPaint)
            canvas.drawLine(0f, y1, w, y1, gridPaint)
            canvas.drawLine(0f, y2, w, y2, gridPaint)

            val cx = w / 2f
            val cy = h / 2f
            val arm = 18f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshairPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshairPaint)
        }

        // 5. Live Luma Histogram (Compact scope in top-left below HUD bar)
        if (showHistogram) {
            val dp = resources.displayMetrics.density
            val boxW = 136f * dp
            val boxH = 56f * dp
            val left = 80f * dp
            val top = 62f * dp
            histRect.set(left, top, left + boxW, top + boxH)

            canvas.drawRoundRect(histRect, 8f * dp, 8f * dp, histBgPaint)
            canvas.drawRoundRect(histRect, 8f * dp, 8f * dp, histBorderPaint)

            val pad = 4f * dp
            val innerW = boxW - pad * 2f
            val innerH = boxH - pad * 2f
            val baseY = top + boxH - pad

            histPath.reset()
            histPath.moveTo(left + pad, baseY)
            for (i in 0 until 64) {
                val px = left + pad + (i / 63f) * innerW
                val py = baseY - (histogramBins[i] * innerH)
                histPath.lineTo(px, py)
            }
            histPath.lineTo(left + pad + innerW, baseY)
            histPath.close()
            canvas.drawPath(histPath, histFillPaint)
        }
    }
}
