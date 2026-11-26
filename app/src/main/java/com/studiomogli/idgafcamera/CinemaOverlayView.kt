package com.studiomogli.idgafcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
    GOLDEN("GRID: φ"),
    DIAGONAL("GRID: DIAG"),
    SAFE("GRID: SAFE"),
    QUAD("GRID: 4x4")
}

enum class RecordAspectMode(val label: String, val shortBadge: String) {
    WIDE_16_9("16:9 WIDE", ""),
    VERT_9_16_PORTRAIT("9:16 PORT", "9:16P")
}

enum class PeakingColor(val label: String, val argb: Int) {
    GREEN("GREEN", Color.argb(235, 57, 255, 20)),
    RED("RED", Color.argb(235, 255, 59, 48)),
    WHITE("WHITE", Color.argb(235, 255, 255, 255)),
    CYAN("CYAN", Color.argb(235, 0, 229, 255))
}

enum class PeakingSensitivity(val label: String, val shortLabel: String, val gpuThreshold: Float) {
    LOW("LOW", "L", 0.24f),
    MED("MED", "M", 0.15f),
    HIGH("HIGH", "H", 0.095f)
}

enum class ZebraMode(val label: String, val shortLabel: String, val gpuCode: Int) {
    IRE_70("70% (SKIN)", "70%", 1),
    IRE_92("92% (HIGH)", "92%", 2),
    IRE_100("100% (CLIP)", "100%", 3)
}

class CinemaOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val renderLock = Any()

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

    @Volatile var frameCropRecEnabled: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile var recordAspectMode: RecordAspectMode = RecordAspectMode.WIDE_16_9
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile var displayRotation: Int = android.view.Surface.ROTATION_90
        set(value) {
            field = value
            if (recordAspectMode == RecordAspectMode.VERT_9_16_PORTRAIT) postInvalidate()
        }

    // Stored here for unified UI & Preset state; rendered on GPU at 60 FPS by CinemaGlRenderer!
    @Volatile var showPeaking: Boolean = false
    @Volatile var peakingColor: PeakingColor = PeakingColor.GREEN
    @Volatile var peakingSensitivity: PeakingSensitivity = PeakingSensitivity.MED
    @Volatile var showZebras: Boolean = false
    @Volatile var zebraMode: ZebraMode = ZebraMode.IRE_92

    @Volatile var showFalseColor: Boolean = false
        set(value) {
            field = value
            postInvalidate() // Toggles compact IRE Legend bar
        }

    @Volatile var showHistogram: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile var showWaveform: Boolean = false
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

    @Volatile var splitCompareFraction: Float = -1f
        set(value) {
            field = value
            postInvalidate()
        }

    @Volatile var splitRightLabel: String = "LOOK"
        set(value) {
            field = value
            if (splitCompareFraction >= 0f) postInvalidate()
        }

    fun toGpuScopeState(): GpuScopeState = GpuScopeState(
        showPeaking = showPeaking,
        peakingColorArgb = peakingColor.argb,
        peakingThreshold = peakingSensitivity.gpuThreshold,
        showZebras = showZebras,
        zebraModeCode = zebraMode.gpuCode,
        showFalseColor = showFalseColor
    )

    // Ultra-lightweight reusable buffer for RGB + Luma Histogram ONLY (64x36 = 2,304 px)
    private var bufLen: Int = 0
    private var samplePixels = IntArray(0)

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

    // Real-time Luma Waveform Monitor (80 horizontal columns x 32 IRE rows)
    private val wfmCols = 80
    private val wfmRows = 32
    private val rawWfmGrid = IntArray(80 * 32)
    private val workWfmAlpha = IntArray(80 * 32)
    private val frontWfmAlpha = IntArray(80 * 32)
    private var wfmHasClip = false
    private val wfmTracePaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val wfmSkinGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 244, 114, 182)
        strokeWidth = 1f
        style = Paint.Style.STROKE
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

    private val splitShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val splitLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5F1F")
        strokeWidth = 2.4f
        style = Paint.Style.STROKE
    }

    private val splitThumbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 18, 22, 29)
        style = Paint.Style.FILL
    }

    private val splitThumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5F1F")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val splitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val histPathR = Path()
    private val histPathG = Path()
    private val histPathB = Path()
    private val histPathY = Path()
    private val histRect = RectF()
    private val pillRect = RectF()

    // Only Histogram & Luma Waveform use lightweight CPU sampling; Peaking, Zebras & False Color run 100% on GPU!
    fun needsHistogramAnalysis(): Boolean = showHistogram || showWaveform

    fun analyzeHistogramFrame(sample: Bitmap) {
        val doHist = showHistogram
        val doWfm = showWaveform
        if (!doHist && !doWfm) return
        val w = sample.width
        val h = sample.height
        val total = w * h
        if (w < 4 || h < 4) return

        if (bufLen != total) {
            bufLen = total
            samplePixels = IntArray(total)
        }
        sample.getPixels(samplePixels, 0, w, 0, 0, w, h)

        if (doHist) {
            rawBinsR.fill(0)
            rawBinsG.fill(0)
            rawBinsB.fill(0)
            rawBinsY.fill(0)
        }
        if (doWfm) {
            rawWfmGrid.fill(0)
        }

        var clippedCount = 0
        var wfmClipCount = 0

        for (py in 0 until h) {
            val rowOff = py * w
            for (px in 0 until w) {
                val c = samplePixels[rowOff + px]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = (r * 77 + g * 150 + b * 29) shr 8

                if (doHist) {
                    rawBinsR[r shr 2]++
                    rawBinsG[g shr 2]++
                    rawBinsB[b shr 2]++
                    rawBinsY[y shr 2]++
                    if (r >= 252 || g >= 252 || b >= 252) clippedCount++
                }
                if (doWfm) {
                    val col = ((px * wfmCols) / w).coerceIn(0, wfmCols - 1)
                    val bin = (y shr 3).coerceIn(0, wfmRows - 1)
                    rawWfmGrid[col * wfmRows + bin]++
                    if (y >= 246) wfmClipCount++
                }
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
        }

        if (doWfm) {
            // Boost trace intensity cleanly so even a single bright pixel per column reads clearly on the phosphor trace
            val fullCount = (h / 4).coerceAtLeast(3).toFloat()
            for (i in 0 until (wfmCols * wfmRows)) {
                val cnt = rawWfmGrid[i]
                workWfmAlpha[i] = if (cnt <= 0) {
                    0
                } else {
                    val norm = (cnt / fullCount).coerceIn(0f, 1f)
                    (78 + (norm * 177f).toInt()).coerceIn(78, 255)
                }
            }
        }

        val clipAlert = clippedCount > (total / 40)
        val wfmClipAlert = wfmClipCount > (total / 50)
        synchronized(renderLock) {
            if (doHist) {
                System.arraycopy(workBinsR, 0, frontBinsR, 0, 64)
                System.arraycopy(workBinsG, 0, frontBinsG, 0, 64)
                System.arraycopy(workBinsB, 0, frontBinsB, 0, 64)
                System.arraycopy(workBinsY, 0, frontBinsY, 0, 64)
                hasHighlightClip = clipAlert
            }
            if (doWfm) {
                System.arraycopy(workWfmAlpha, 0, frontWfmAlpha, 0, wfmCols * wfmRows)
                wfmHasClip = wfmClipAlert
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

        // 1. Aspect Ratio Frame Guides (Landscape guides + Frame Crop Rec mode!)
        val isPortMode = (recordAspectMode == RecordAspectMode.VERT_9_16_PORTRAIT)
        val effRatio = if (!isPortMode && aspectGuide != AspectGuide.OFF && aspectGuide.ratio > 0f) {
            aspectGuide.ratio
        } else {
            0f
        }
        if (effRatio > 0f) {
            val viewRatio = w / h
            if (effRatio > viewRatio) {
                val guideH = w / effRatio
                val barH = (h - guideH) / 2f
                canvas.drawRect(0f, 0f, w, barH, mattePaint)
                canvas.drawRect(0f, h - barH, w, h, mattePaint)
                canvas.drawLine(0f, barH, w, barH, frameBorderPaint)
                canvas.drawLine(0f, h - barH, w, h - barH, frameBorderPaint)
            } else {
                val guideW = h * effRatio
                val barW = (w - guideW) / 2f
                canvas.drawRect(0f, 0f, barW, h, mattePaint)
                canvas.drawRect(w - barW, 0f, w, h, mattePaint)
                canvas.drawLine(barW, 0f, barW, h, frameBorderPaint)
                canvas.drawLine(w - barW, 0f, w - barW, h, frameBorderPaint)
            }
        }

        // Subtle live badge when True Frame Crop Rec OR Portrait 9:16 Mode is active
        if (!isPortMode && frameCropRecEnabled && aspectGuide != AspectGuide.OFF && aspectGuide != AspectGuide.WIDE_169) {
            val tag = "${aspectGuide.label} CROP REC"
            splitTextPaint.textSize = 8.5f * dp
            val tw = splitTextPaint.measureText(tag) + 16f * dp
            val th = 18f * dp
            val botY = if (showFalseColor) (h - 98f * dp) else (h - 76f * dp)
            pillRect.set((w - tw) / 2f, botY, (w + tw) / 2f, botY + th)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, histBgPaint)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, frameBorderPaint)
            splitTextPaint.color = Color.parseColor("#FF5F1F")
            canvas.drawText(tag, w / 2f, botY + 12.5f * dp, splitTextPaint)
        } else if (isPortMode) {
            // Rotated 90° so it sits upright at the TOP-CENTER when the user holds the phone vertically!
            val tag = "9:16 PORTRAIT • UPRIGHT"
            splitTextPaint.textSize = 8.5f * dp
            val tw = splitTextPaint.measureText(tag) + 16f * dp
            val th = 18f * dp
            val isRev = (displayRotation == android.view.Surface.ROTATION_270)
            val rotDeg = if (isRev) 90f else -90f
            val anchorX = if (isRev) (w - 168f * dp) else (84f * dp)
            val anchorY = h * 0.50f
            canvas.save()
            canvas.translate(anchorX, anchorY)
            canvas.rotate(rotDeg)
            pillRect.set(-tw / 2f, -th / 2f, tw / 2f, th / 2f)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, histBgPaint)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, frameBorderPaint)
            splitTextPaint.color = Color.parseColor("#FF5F1F")
            canvas.drawText(tag, 0f, 3.2f * dp, splitTextPaint)
            canvas.restore()
        }

        // 2. Cinema Composition Grids (3x3, Golden φ, Diagonals, Broadcast Safe 90%/80%, 4x4) + Center Crosshair
        if (gridMode != GridMode.OFF) {
            val cx = w / 2f
            val cy = h / 2f
            when (gridMode) {
                GridMode.THIRDS, GridMode.GOLDEN -> {
                    val f1 = if (gridMode == GridMode.GOLDEN) 0.382f else (1f / 3f)
                    val f2 = if (gridMode == GridMode.GOLDEN) 0.618f else (2f / 3f)
                    canvas.drawLine(w * f1, 0f, w * f1, h, gridPaint)
                    canvas.drawLine(w * f2, 0f, w * f2, h, gridPaint)
                    canvas.drawLine(0f, h * f1, w, h * f1, gridPaint)
                    canvas.drawLine(0f, h * f2, w, h * f2, gridPaint)
                }
                GridMode.DIAGONAL -> {
                    canvas.drawLine(0f, 0f, w, h, gridPaint)
                    canvas.drawLine(w, 0f, 0f, h, gridPaint)
                    canvas.drawLine(cx, 0f, cx, h, gridPaint)
                    canvas.drawLine(0f, cy, w, cy, gridPaint)
                }
                GridMode.SAFE -> {
                    // Action Safe (90%) + Title Safe (80%) cinema broadcast guides
                    canvas.drawRect(w * 0.05f, h * 0.05f, w * 0.95f, h * 0.95f, gridPaint)
                    canvas.drawRect(w * 0.10f, h * 0.10f, w * 0.90f, h * 0.90f, gridPaint)
                }
                GridMode.QUAD -> {
                    for (i in 1..3) {
                        val frac = i * 0.25f
                        canvas.drawLine(w * frac, 0f, w * frac, h, gridPaint)
                        canvas.drawLine(0f, h * frac, w, h * frac, gridPaint)
                    }
                }
                GridMode.OFF -> {}
            }
            val arm = 18f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshairPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshairPaint)
        }

        // 3. Live Horizon Level Indicator + Live Degree Readout
        if (showHorizon) {
            val cx = w / 2f
            val cy = h / 2f
            val halfSpan = 64f * dp
            val gap = 14f * dp

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

        // 3.5. Phase 7.2.5 Live RAW | LOOK Split-Screen Wipe Divider & Draggable Handle
        val splitFrac = splitCompareFraction
        if (splitFrac in 0f..1f) {
            drawSplitCompareDivider(canvas, w, h, dp, splitFrac)
        }

        // 4. Compact False Color IRE Legend Strip (Bottom-Center above dock)
        if (showFalseColor) {
            drawFalseColorLegend(canvas, w, h, dp)
        }

        // 5. 3-Channel RGB + Luma Overlay Histogram (Top-Left opposite Pilot button, clear of right rail)
        if (showHistogram) {
            val boxW = 132f * dp
            val boxH = 56f * dp
            val left = 18f * dp
            val top = 58f * dp
            histRect.set(left, top, left + boxW, top + boxH)

            canvas.drawRoundRect(histRect, 8f * dp, 8f * dp, histBgPaint)
            canvas.drawRoundRect(histRect, 8f * dp, 8f * dp, histBorderPaint)

            val pad = 4f * dp
            val innerW = boxW - pad * 2f
            val innerH = boxH - pad * 2f - 9f * dp
            val baseY = top + boxH - pad

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

        // 6. Real-Time Luma Waveform Monitor (Left-to-Right Frame Exposure Scope — 0..100 IRE)
        if (showWaveform) {
            val boxW = 132f * dp
            val boxH = 56f * dp
            val left = if (showHistogram) (156f * dp) else (18f * dp)
            val top = 58f * dp
            histRect.set(left, top, left + boxW, top + boxH)

            canvas.drawRoundRect(histRect, 8f * dp, 8f * dp, histBgPaint)
            canvas.drawRoundRect(histRect, 8f * dp, 8f * dp, histBorderPaint)

            val pad = 4f * dp
            val plotTop = top + 11f * dp
            val plotBot = top + boxH - pad
            val innerW = boxW - pad * 2f
            val innerH = (plotBot - plotTop).coerceAtLeast(1f)

            // Horizontal IRE Graticule: 50% Mid-Grey + 70% Skin Tone + 100% Ceiling
            val y50 = plotBot - innerH * 0.50f
            val y70 = plotBot - innerH * 0.70f
            canvas.drawLine(left + pad, plotTop, left + boxW - pad, plotTop, histGuidePaint)
            canvas.drawLine(left + pad, y70, left + boxW - pad, y70, wfmSkinGuidePaint)
            canvas.drawLine(left + pad, y50, left + boxW - pad, y50, histGuidePaint)
            canvas.drawLine(left + pad, plotBot, left + boxW - pad, plotBot, histGuidePaint)

            val cellW = innerW / wfmCols.toFloat()
            val cellH = innerH / wfmRows.toFloat()
            var clipAlert = false

            synchronized(renderLock) {
                clipAlert = wfmHasClip
                for (col in 0 until wfmCols) {
                    val cx0 = left + pad + col * cellW
                    val cx1 = cx0 + cellW + 0.6f
                    val baseIdx = col * wfmRows
                    for (bin in 0 until wfmRows) {
                        val a = frontWfmAlpha[baseIdx + bin]
                        if (a <= 0) continue
                        val cyBot = plotBot - bin * cellH
                        val cyTop = cyBot - cellH - 0.6f
                        wfmTracePaint.color = when {
                            bin >= 30 -> Color.argb(a, 255, 59, 48)   // 94-100% IRE Clip Red
                            bin >= 27 -> Color.argb(a, 255, 204, 0)   // 84-93% IRE Highlight Amber
                            else -> Color.argb(a, 52, 199, 89)        // 0-83% IRE Cinema Phosphor Green
                        }
                        canvas.drawRect(cx0, cyTop, cx1, cyBot, wfmTracePaint)
                    }
                }
            }

            badgeTextPaint.textSize = 7.5f * dp
            badgeTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("WFM • IRE", left + 6f * dp, top + 10f * dp, badgeTextPaint)
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

    private fun drawSplitCompareDivider(canvas: Canvas, w: Float, h: Float, dp: Float, frac: Float) {
        val x = (frac * w).coerceIn(0f, w)
        val topY = 50f * dp
        val botY = h

        // Vertical drop-shadow + crisp Safety Orange divider line
        splitShadowPaint.strokeWidth = 3.5f * dp
        splitLinePaint.strokeWidth = 1.8f * dp
        canvas.drawLine(x, topY, x, botY, splitShadowPaint)
        canvas.drawLine(x, topY, x, botY, splitLinePaint)

        // Tactile circular drag handle at vertical center (◄►)
        val cy = h * 0.50f
        val r = 15f * dp
        canvas.drawCircle(x, cy, r + 1.5f * dp, splitShadowPaint)
        canvas.drawCircle(x, cy, r, splitThumbFillPaint)
        canvas.drawCircle(x, cy, r, splitThumbStrokePaint)

        splitTextPaint.textSize = 10f * dp
        splitTextPaint.color = Color.WHITE
        canvas.drawText("◂▸", x, cy + 3.5f * dp, splitTextPaint)

        // Left "RAW" & Right "<LUT/LOOK>" compact pill tags beside the handle
        val tagH = 18f * dp
        val tagTop = cy - r - tagH - 8f * dp
        val tagGap = 8f * dp
        splitTextPaint.textSize = 8.5f * dp

        if (x > 64f * dp) {
            val rawW = 36f * dp
            pillRect.set(x - tagGap - rawW, tagTop, x - tagGap, tagTop + tagH)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, histBgPaint)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, histBorderPaint)
            splitTextPaint.color = Color.WHITE
            canvas.drawText("RAW", pillRect.centerX(), tagTop + 12.5f * dp, splitTextPaint)
        }

        val rightTxt = splitRightLabel.take(14)
        val rightW = (splitTextPaint.measureText(rightTxt) + 14f * dp).coerceAtLeast(42f * dp)
        if (w - x > rightW + tagGap + 14f * dp) {
            pillRect.set(x + tagGap, tagTop, x + tagGap + rightW, tagTop + tagH)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, histBgPaint)
            canvas.drawRoundRect(pillRect, 6f * dp, 6f * dp, splitThumbStrokePaint)
            splitTextPaint.color = Color.parseColor("#FF5F1F")
            canvas.drawText(rightTxt, pillRect.centerX(), tagTop + 12.5f * dp, splitTextPaint)
        }
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
