package com.studiomogli.idgafcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.abs

enum class AspectGuide(val label: String, val ratio: Float) {
    OFF("OFF", 0f),
    SCOPE_239("2.39:1", 2.39f),
    FLAT_185("1.85:1", 1.85f),
    WIDE_169("16:9", 16f / 9f),
    CLASSIC_43("4:3", 4f / 3f),
    SQUARE_11("1:1", 1.0f),
    VERT_916("9:16", 9f / 16f)
}

enum class GridMode(val label: String) {
    OFF("GRID: OFF"),
    THIRDS("GRID: 3x3"),
    GOLDEN("GRID: φ")
}

enum class PeakingColor(val label: String, val argb: Int) {
    GREEN("GREEN", Color.argb(235, 57, 255, 20)),
    RED("RED", Color.argb(235, 255, 59, 48)),
    WHITE("WHITE", Color.argb(235, 255, 255, 255)),
    CYAN("CYAN", Color.argb(235, 0, 229, 255))
}

enum class PeakingSensitivity(val label: String, val shortLabel: String, val threshold: Int) {
    LOW("LOW", "L", 68),
    MED("MED", "M", 44),
    HIGH("HIGH", "H", 24)
}

enum class ZebraMode(val label: String, val shortLabel: String) {
    IRE_70("70% (SKIN)", "70%"),
    IRE_92("92% (HIGH)", "92%"),
    IRE_100("100% (CLIP)", "100%")
}

class CinemaOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val renderLock = Any()
    private val previewTransform = Matrix()

    fun setPreviewTransform(matrix: Matrix) {
        synchronized(renderLock) {
            previewTransform.set(matrix)
        }
        postInvalidate()
    }

    @Volatile var gridMode: GridMode = GridMode.OFF
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile var aspectGuide: AspectGuide = AspectGuide.OFF
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile var showPeaking: Boolean = false
        set(value) {
            field = value
            if (!value) {
                synchronized(renderLock) { peakingFront = null }
            }
            postInvalidate()
        }

    @Volatile var peakingColor: PeakingColor = PeakingColor.GREEN
    @Volatile var peakingSensitivity: PeakingSensitivity = PeakingSensitivity.MED

    @Volatile var showZebras: Boolean = false
        set(value) {
            field = value
            if (!value) {
                synchronized(renderLock) { zebraFront = null }
            }
            postInvalidate()
        }

    @Volatile var zebraMode: ZebraMode = ZebraMode.IRE_92

    @Volatile var showFalseColor: Boolean = false
        set(value) {
            field = value
            if (!value) {
                synchronized(renderLock) { falseColorFront = null }
            }
            postInvalidate()
        }

    @Volatile var showHistogram: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile var showHorizon: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile var horizonRollDegrees: Float = 0f
        set(value) {
            field = value
            if (showHorizon) postInvalidate()
        }

    // Double-buffered overlay bitmaps (background worker writes Back, swaps atomically to Front)
    private var peakingFront: Bitmap? = null
    private var peakingBack: Bitmap? = null
    private var zebraFront: Bitmap? = null
    private var zebraBack: Bitmap? = null
    private var falseColorFront: Bitmap? = null
    private var falseColorBack: Bitmap? = null

    // Reusable analysis buffers (zero per-frame allocations)
    private var bufLen: Int = 0
    private var samplePixels = IntArray(0)
    private var lumaBuf = IntArray(0)
    private var peakPixels = IntArray(0)
    private var zebraPixels = IntArray(0)
    private var falseColorPixels = IntArray(0)

    // 3-Channel RGB + Luma Histogram buffers
    private val rawBinsR = IntArray(64)
    private val rawBinsG = IntArray(64)
    private val rawBinsB = IntArray(64)
    private val rawBinsY = IntArray(64)

    private val workBinsR = FloatArray(64)
    private val workBinsG = FloatArray(64)
    private val workBinsB = FloatArray(64)
    private val workBinsY = FloatArray(64)

    private val frontBinsR = FloatArray(64)
    private val frontBinsG = FloatArray(64)
    private val frontBinsB = FloatArray(64)
    private val frontBinsY = FloatArray(64)
    private var hasHighlightClip = false

    // Precomputed 256-level ARRI/SmallHD-inspired Rec.709 IRE False Color LUT
    private val falseColorLut = IntArray(256).apply {
        for (y in 0..255) {
            this[y] = when {
                y <= 13 -> Color.argb(230, 139, 92, 246)   // 0-5% IRE: Purple (Crushed Black)
                y <= 38 -> Color.argb(225, 37, 99, 235)    // 5-15% IRE: Blue (Deep Shadows)
                y in 97..112 -> Color.argb(225, 34, 197, 94) // 38-44% IRE: Green (Middle Grey ~41%)
                y in 143..174 -> Color.argb(225, 244, 114, 182) // 56-68% IRE: Pink (Key Skin Tone)
                y in 217..242 -> Color.argb(230, 250, 204, 21) // 85-95% IRE: Yellow (Bright Highlights)
                y >= 243 -> Color.argb(240, 239, 68, 68)   // 95-100% IRE: Red (Clipped)
                else -> Color.argb(215, y, y, y)           // Neutral Monochrome Luma
            }
        }
    }

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

    private val horizonLevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34C759")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val horizonTiltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5F1F")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val horizonRefPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val horizonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
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
        color = Color.argb(195, 11, 13, 16)
        style = Paint.Style.FILL
    }

    private val histBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val histGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val histRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 59, 48)
        style = Paint.Style.FILL
    }

    private val histGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 52, 199, 89)
        style = Paint.Style.FILL
    }

    private val histBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 59, 130, 246)
        style = Paint.Style.FILL
    }

    private val histLumaStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 255, 255, 255)
        strokeWidth = 1.3f
        style = Paint.Style.STROKE
    }

    private val clipDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B30")
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val legendSwatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val histPathR = Path()
    private val histPathG = Path()
    private val histPathB = Path()
    private val histPathY = Path()
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destRect = RectF()
    private val histRect = RectF()
    private val pillRect = RectF()

    fun needsFrameAnalysis(): Boolean = showPeaking || showZebras || showHistogram || showFalseColor

    /**
     * Thread-safe background frame analysis (call from a dedicated worker thread).
     * Reuses pre-allocated buffers & double-buffered Bitmaps for zero UI-thread lag.
     */
    fun analyzeFrame(sample: Bitmap) {
        val w = sample.width
        val h = sample.height
        val total = w * h
        if (w < 4 || h < 4) return

        val doHist = showHistogram
        val doPeak = showPeaking
        val doZebra = showZebras
        val doFalse = showFalseColor
        if (!doHist && !doPeak && !doZebra && !doFalse) return

        if (bufLen != total) {
            bufLen = total
            samplePixels = IntArray(total)
            lumaBuf = IntArray(total)
            peakPixels = IntArray(total)
            zebraPixels = IntArray(total)
            falseColorPixels = IntArray(total)
        }

        sample.getPixels(samplePixels, 0, w, 0, 0, w, h)

        if (doHist) {
            rawBinsR.fill(0)
            rawBinsG.fill(0)
            rawBinsB.fill(0)
            rawBinsY.fill(0)
        }
        var clippedCount = 0

        for (i in 0 until total) {
            val c = samplePixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            lumaBuf[i] = y
            if (doHist) {
                rawBinsR[r shr 2]++
                rawBinsG[g shr 2]++
                rawBinsB[b shr 2]++
                rawBinsY[y shr 2]++
                if (r >= 252 || g >= 252 || b >= 252) clippedCount++
            }
            if (doFalse) {
                falseColorPixels[i] = falseColorLut[y]
            }
        }

        if (doHist) {
            var maxBin = 1
            for (i in 0 until 64) {
                if (rawBinsR[i] > maxBin) maxBin = rawBinsR[i]
                if (rawBinsG[i] > maxBin) maxBin = rawBinsG[i]
                if (rawBinsB[i] > maxBin) maxBin = rawBinsB[i]
                if (rawBinsY[i] > maxBin) maxBin = rawBinsY[i]
            }
            val invMax = 1f / maxBin.toFloat()
            for (i in 0 until 64) {
                workBinsR[i] = rawBinsR[i] * invMax
                workBinsG[i] = rawBinsG[i] * invMax
                workBinsB[i] = rawBinsB[i] * invMax
                workBinsY[i] = rawBinsY[i] * invMax
            }
            val clipAlert = clippedCount > (total / 40) // > 2.5% channel highlight clipping
            synchronized(renderLock) {
                System.arraycopy(workBinsR, 0, frontBinsR, 0, 64)
                System.arraycopy(workBinsG, 0, frontBinsG, 0, 64)
                System.arraycopy(workBinsB, 0, frontBinsB, 0, 64)
                System.arraycopy(workBinsY, 0, frontBinsY, 0, 64)
                hasHighlightClip = clipAlert
            }
        }

        if (doFalse) {
            val back = falseColorBack?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            back.setPixels(falseColorPixels, 0, w, 0, 0, w, h)
            synchronized(renderLock) {
                val prevFront = falseColorFront
                falseColorFront = back
                falseColorBack = prevFront
            }
        }

        if (doPeak) {
            peakPixels.fill(0)
            val threshold = peakingSensitivity.threshold
            val peakColor = peakingColor.argb
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val gx = abs(lumaBuf[row + x + 1] - lumaBuf[row + x - 1])
                    val gy = abs(lumaBuf[row + w + x] - lumaBuf[row - w + x])
                    if (gx + gy > threshold) {
                        peakPixels[row + x] = peakColor
                    }
                }
            }
            val back = peakingBack?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            back.setPixels(peakPixels, 0, w, 0, 0, w, h)
            synchronized(renderLock) {
                val prevFront = peakingFront
                peakingFront = back
                peakingBack = prevFront
            }
        }

        if (doZebra) {
            zebraPixels.fill(0)
            val mode = zebraMode
            val zebraColor = when (mode) {
                ZebraMode.IRE_70 -> Color.argb(215, 255, 204, 0)  // Warm Yellow for 70% Skin Band
                ZebraMode.IRE_92 -> Color.argb(215, 255, 59, 48)  // Warning Red for 92% IRE
                ZebraMode.IRE_100 -> Color.argb(230, 255, 23, 68) // Hot Clip Red for 100% IRE
            }
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (((x + y) and 3) < 2) {
                        val lv = lumaBuf[row + x]
                        val hit = when (mode) {
                            ZebraMode.IRE_70 -> lv in 170..192
                            ZebraMode.IRE_92 -> lv >= 235
                            ZebraMode.IRE_100 -> lv >= 250
                        }
                        if (hit) {
                            zebraPixels[row + x] = zebraColor
                        }
                    }
                }
            }
            val back = zebraBack?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            back.setPixels(zebraPixels, 0, w, 0, 0, w, h)
            synchronized(renderLock) {
                val prevFront = zebraFront
                zebraFront = back
                zebraBack = prevFront
            }
        }

        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val dp = resources.displayMetrics.density
        destRect.set(0f, 0f, w, h)

        // Draw frame-derived scopes using TextureView's EXACT transform matrix so they lock pixel-for-pixel!
        synchronized(renderLock) {
            if (showFalseColor || showPeaking || showZebras) {
                canvas.save()
                canvas.concat(previewTransform)
                if (showFalseColor) {
                    falseColorFront?.let { canvas.drawBitmap(it, null, destRect, overlayPaint) }
                }
                if (showPeaking) {
                    peakingFront?.let { canvas.drawBitmap(it, null, destRect, overlayPaint) }
                }
                if (showZebras) {
                    zebraFront?.let { canvas.drawBitmap(it, null, destRect, overlayPaint) }
                }
                canvas.restore()
            }
        }

        // 4. Aspect Ratio Frame Guides
        if (aspectGuide != AspectGuide.OFF && aspectGuide.ratio > 0f) {
            val viewRatio = w / h
            val targetRatio = aspectGuide.ratio
            if (targetRatio > viewRatio) {
                val guideH = w / targetRatio
                val barH = (h - guideH) / 2f
                canvas.drawRect(0f, 0f, w, barH, mattePaint)
                canvas.drawRect(0f, h - barH, w, h, mattePaint)
                canvas.drawLine(0f, barH, w, barH, frameBorderPaint)
                canvas.drawLine(0f, h - barH, w, h - barH, frameBorderPaint)
            } else {
                val guideW = h * targetRatio
                val barW = (w - guideW) / 2f
                canvas.drawRect(0f, 0f, barW, h, mattePaint)
                canvas.drawRect(w - barW, 0f, w, h, mattePaint)
                canvas.drawLine(barW, 0f, barW, h, frameBorderPaint)
                canvas.drawLine(w - barW, 0f, w - barW, h, frameBorderPaint)
            }
        }

        // 5. Rule of Thirds OR Golden Ratio (Phi 0.382 / 0.618) Grid + Center Crosshair
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

        // 6. Live Horizon Level Indicator + Live Degree Readout
        if (showHorizon) {
            val cx = w / 2f
            val cy = h / 2f
            val halfSpan = 64f * dp
            val gap = 14f * dp

            // Static white reference ticks on left and right
            canvas.drawLine(cx - halfSpan - 12f * dp, cy, cx - halfSpan - 3f * dp, cy, horizonRefPaint)
            canvas.drawLine(cx + halfSpan + 3f * dp, cy, cx + halfSpan + 12f * dp, cy, horizonRefPaint)

            val roll = horizonRollDegrees
            val isLevel = abs(roll) <= 1.5f
            val activePaint = if (isLevel) horizonLevelPaint else horizonTiltPaint
            canvas.save()
            canvas.rotate(-roll, cx, cy)
            canvas.drawLine(cx - halfSpan, cy, cx - gap, cy, activePaint)
            canvas.drawLine(cx + gap, cy, cx + halfSpan, cy, activePaint)
            canvas.restore()

            val degLabel = if (abs(roll) < 0.15f) "0.0°" else String.format(Locale.US, "%+.1f°", roll)
            horizonTextPaint.textSize = 10f * dp
            horizonTextPaint.color = if (isLevel) Color.parseColor("#34C759") else Color.parseColor("#FF5F1F")
            val pillW = 46f * dp
            val pillH = 17f * dp
            val pillTop = cy + 12f * dp
            pillRect.set(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, histBgPaint)
            canvas.drawText(degLabel, cx, pillTop + 12f * dp, horizonTextPaint)
        }

        // 7. Compact False Color IRE Legend Strip (Bottom-Center above dock)
        if (showFalseColor) {
            drawFalseColorLegend(canvas, w, h, dp)
        }

        // 8. 3-Channel RGB + Luma Overlay Histogram (Top-Right clear of all drawers)
        if (showHistogram) {
            val boxW = 132f * dp
            val boxH = 56f * dp
            val left = w - boxW - 18f * dp
            val top = 58f * dp
            histRect.set(left, top, left + boxW, top + boxH)

            canvas.drawRoundRect(histRect, 8f * dp, 8f * dp, histBgPaint)
            canvas.drawRoundRect(histRect, 8f * dp, 8f * dp, histBorderPaint)

            val pad = 4f * dp
            val innerW = boxW - pad * 2f
            val innerH = boxH - pad * 2f - 9f * dp
            val baseY = top + boxH - pad

            // Quarter-stop vertical guide lines (25% / 50% / 75%)
            for (q in 1..3) {
                val gx = left + pad + (q * 0.25f) * innerW
                canvas.drawLine(gx, top + 11f * dp, gx, baseY, histGuidePaint)
            }

            var clipAlert = false
            synchronized(renderLock) {
                clipAlert = hasHighlightClip
                buildFilledHistPath(histPathR, frontBinsR, left + pad, baseY, innerW, innerH)
                buildFilledHistPath(histPathG, frontBinsG, left + pad, baseY, innerW, innerH)
                buildFilledHistPath(histPathB, frontBinsB, left + pad, baseY, innerW, innerH)

                histPathY.reset()
                for (i in 0 until 64) {
                    val px = left + pad + (i / 63f) * innerW
                    val py = baseY - (frontBinsY[i] * innerH)
                    if (i == 0) histPathY.moveTo(px, py) else histPathY.lineTo(px, py)
                }
            }
            canvas.drawPath(histPathR, histRedPaint)
            canvas.drawPath(histPathG, histGreenPaint)
            canvas.drawPath(histPathB, histBluePaint)
            canvas.drawPath(histPathY, histLumaStrokePaint)

            badgeTextPaint.textSize = 7.5f * dp
            badgeTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("RGB", left + 6f * dp, top + 10f * dp, badgeTextPaint)
            if (clipAlert) {
                canvas.drawCircle(left + boxW - 8f * dp, top + 7.5f * dp, 3f * dp, clipDotPaint)
            }
        }
    }

    private fun buildFilledHistPath(path: Path, bins: FloatArray, startX: Float, baseY: Float, innerW: Float, innerH: Float) {
        path.reset()
        path.moveTo(startX, baseY)
        for (i in 0 until 64) {
            val px = startX + (i / 63f) * innerW
            val py = baseY - (bins[i] * innerH)
            path.lineTo(px, py)
        }
        path.lineTo(startX + innerW, baseY)
        path.close()
    }

    private fun drawFalseColorLegend(canvas: Canvas, w: Float, h: Float, dp: Float) {
        val items = arrayOf(
            Color.rgb(139, 92, 246) to "BLK",
            Color.rgb(37, 99, 235) to "SHD",
            Color.rgb(34, 197, 94) to "41%",
            Color.rgb(244, 114, 182) to "SKIN",
            Color.rgb(250, 204, 21) to "HI",
            Color.rgb(239, 68, 68) to "CLIP"
        )
        val segW = 36f * dp
        val totalW = segW * items.size + 12f * dp
        val barH = 18f * dp
        val left = (w - totalW) / 2f
        val top = h - 76f * dp

        pillRect.set(left, top, left + totalW, top + barH)
        canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, histBgPaint)
        canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, histBorderPaint)

        badgeTextPaint.textSize = 7.5f * dp
        badgeTextPaint.textAlign = Paint.Align.LEFT
        var cx = left + 6f * dp
        for ((color, label) in items) {
            legendSwatchPaint.color = color
            canvas.drawCircle(cx + 4f * dp, top + barH / 2f, 3.2f * dp, legendSwatchPaint)
            canvas.drawText(label, cx + 10f * dp, top + barH - 5.5f * dp, badgeTextPaint)
            cx += segW
        }
    }
}
