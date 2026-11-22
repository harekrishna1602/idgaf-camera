package com.studiomogli.idgafcamera

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlin.math.roundToInt

enum class ProControlPanel { NONE, ISO, SHUTTER, FOCUS, WB, ZOOM }

class MainActivity : AppCompatActivity(), CinemaEngineListener {

    companion object {
        private const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/harekrishna1602/idgaf-camera/releases/latest"
    }

    private lateinit var engine: CinemaCameraEngine
    private lateinit var textureView: TextureView
    private lateinit var focusReticle: View
    private lateinit var overlayView: CinemaOverlayView
    private lateinit var audioMeterView: AudioMeterView

    // Phase 4 Pro Audio / Codec / Bitrate Bar
    private lateinit var btnProSettingsToggle: TextView
    private lateinit var proSettingsBar: LinearLayout
    private lateinit var btnProCodec: TextView
    private lateinit var btnProBitrate: TextView
    private lateinit var btnProMic: TextView
    private lateinit var btnProTone: TextView
    private lateinit var btnProPresets: TextView
    private lateinit var btnUpdateApp: TextView
    private lateinit var presetStore: PresetStore
    private var updateDownloadUrl: String? = null
    private var toneIdx = 0
    private var gridModeIdx = 0
    private val bitrateOptions = listOf(24, 35, 50, 75, 100, 120)
    private var bitrateIdx = 1
    private var micPrefIdx = 0

    // Live Audio VU preview monitor (runs when idle; switches to MediaRecorder amplitude while recording)
    @Volatile private var audioMonitorRunning = false
    private var audioMonitorThread: Thread? = null

    // Phase 3 Monitoring Toolbar
    private lateinit var btnMonitorToggle: TextView
    private lateinit var monitorToolsBar: LinearLayout
    private lateinit var btnToolGrid: TextView
    private lateinit var btnToolFrame: TextView
    private lateinit var btnToolPeak: TextView
    private lateinit var btnToolZebra: TextView
    private lateinit var btnToolHist: TextView
    private var frameGuideIdx = 0

    private lateinit var txtTimecode: TextView
    private lateinit var viewRecDot: View
    private lateinit var btnResolution: TextView
    private lateinit var btnFps: TextView
    private lateinit var lensRail: LinearLayout
    private lateinit var btnShutterInner: View
    private lateinit var btnLastClip: TextView

    // Phase 2 Pro Dock Pills
    private lateinit var pillIso: LinearLayout
    private lateinit var txtIsoVal: TextView
    private lateinit var pillShutter: LinearLayout
    private lateinit var txtShutterVal: TextView
    private lateinit var pillWb: LinearLayout
    private lateinit var txtWbVal: TextView
    private lateinit var pillFocus: LinearLayout
    private lateinit var txtFocusVal: TextView
    private lateinit var pillZoom: LinearLayout
    private lateinit var txtZoomVal: TextView
    private lateinit var zoomRockerRow: LinearLayout
    private lateinit var btnZoomSpeed: TextView
    private lateinit var btnZoomWideHold: TextView
    private lateinit var btnZoomTeleHold: TextView
    private lateinit var btnZoomPreset1x: TextView
    private lateinit var btnZoomPreset2x: TextView
    private lateinit var btnZoomPreset5x: TextView
    private lateinit var scaleDetector: ScaleGestureDetector

    private val zoomSpeedSteps = listOf("SLOW" to 0.018f, "MED" to 0.045f, "FAST" to 0.11f)
    private var zoomSpeedIdx = 1
    private var zoomRockerDirection = 0 // -1 = Wide, +1 = Tele, 0 = stopped
    private var lastPreviewSize: Size = Size(1920, 1080)
    private lateinit var btnAeAfLock: TextView

    // Floating Pro Slider Drawer
    private lateinit var sliderDrawer: LinearLayout
    private lateinit var txtSliderTitle: TextView
    private lateinit var txtSliderReadout: TextView
    private lateinit var btnSliderAuto: TextView
    private lateinit var btnShutterUnit: TextView
    private lateinit var btnSlider180: TextView
    private var shutterInAngleMode = false
    private val shutterAngles = listOf(11.25f, 22.5f, 45f, 90f, 120f, 144f, 172.8f, 180f, 270f, 360f)
    private lateinit var rackFocusRow: LinearLayout
    private lateinit var btnSetFocusA: TextView
    private lateinit var btnSetFocusB: TextView
    private lateinit var btnPullTime: TextView
    private lateinit var btnPullFocusAB: TextView
    private var focusPullDurationMs: Long = 1200L
    private lateinit var proSeekBar: SeekBar

    private var activePanel: ProControlPanel = ProControlPanel.NONE
    private var suppressSeekCallback = false

    // Standard cinema shutter speeds (denominators)
    private val shutterDenoms = listOf(24, 30, 48, 50, 60, 100, 120, 180, 250, 500, 1000, 2000, 4000)

    // A/B Rack Focus points
    private var focusPointA: Float? = null
    private var focusPointB: Float? = null
    private var rackGoingToB = true
    private var rackAnimator: ValueAnimator? = null

    private val fpsOptions = listOf(24, 30, 60)
    private var currentFpsIdx = 1
    private var currentResolution = CinemaResolution.FHD_1080P
    private var lastSavedUri: Uri? = null

    private var recStartMs: Long = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val analysisRunnable = object : Runnable {
        override fun run() {
            if (overlayView.needsFrameAnalysis() && textureView.isAvailable) {
                try {
                    val bmp = textureView.getBitmap(160, 90)
                    if (bmp != null) {
                        overlayView.analyzeFrame(bmp)
                        bmp.recycle()
                    }
                } catch (_: Exception) {
                }
                uiHandler.postDelayed(this, 120L)
            }
        }
    }

    private fun syncAnalysisLoop() {
        uiHandler.removeCallbacks(analysisRunnable)
        if (overlayView.needsFrameAnalysis()) {
            uiHandler.post(analysisRunnable)
        }
    }

    private val zoomRockerRunnable = object : Runnable {
        override fun run() {
            if (zoomRockerDirection != 0) {
                val step = zoomSpeedSteps[zoomSpeedIdx].second
                val nextZoom = engine.currentZoomRatio + (zoomRockerDirection * step * engine.currentZoomRatio.coerceAtLeast(1f))
                engine.setZoomRatio(nextZoom)
                updateZoomReadoutUi()
                uiHandler.postDelayed(this, 24L)
            }
        }
    }

    private val timecodeRunnable = object : Runnable {
        override fun run() {
            if (engine.isRecording) {
                val elapsedMs = System.currentTimeMillis() - recStartMs
                val totalSec = elapsedMs / 1000L
                val hrs = totalSec / 3600L
                val mins = (totalSec % 3600L) / 60L
                val secs = totalSec % 60L
                txtTimecode.text = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
                uiHandler.postDelayed(this, 250L)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val camOk = perms[Manifest.permission.CAMERA] == true || hasPerm(Manifest.permission.CAMERA)
        val micOk = perms[Manifest.permission.RECORD_AUDIO] == true || hasPerm(Manifest.permission.RECORD_AUDIO)
        if (camOk && micOk) {
            openCameraIfReady()
        } else {
            Toast.makeText(this, "Camera & Microphone permissions are required.", Toast.LENGTH_LONG).show()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCameraIfReady()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveFullscreen()
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.cameraTextureView)
        focusReticle = findViewById(R.id.focusReticle)
        overlayView = findViewById(R.id.cinemaOverlayView)
        audioMeterView = findViewById(R.id.audioMeterView)
        btnProSettingsToggle = findViewById(R.id.btnProSettingsToggle)
        proSettingsBar = findViewById(R.id.proSettingsBar)
        btnProCodec = findViewById(R.id.btnProCodec)
        btnProBitrate = findViewById(R.id.btnProBitrate)
        btnProMic = findViewById(R.id.btnProMic)
        btnProTone = findViewById(R.id.btnProTone)
        btnProPresets = findViewById(R.id.btnProPresets)
        btnUpdateApp = findViewById(R.id.btnUpdateApp)
        presetStore = PresetStore(this)
        btnMonitorToggle = findViewById(R.id.btnMonitorToggle)
        monitorToolsBar = findViewById(R.id.monitorToolsBar)
        btnToolGrid = findViewById(R.id.btnToolGrid)
        btnToolFrame = findViewById(R.id.btnToolFrame)
        btnToolPeak = findViewById(R.id.btnToolPeak)
        btnToolZebra = findViewById(R.id.btnToolZebra)
        btnToolHist = findViewById(R.id.btnToolHist)
        txtTimecode = findViewById(R.id.txtTimecode)
        viewRecDot = findViewById(R.id.viewRecDot)
        btnResolution = findViewById(R.id.btnResolution)
        btnFps = findViewById(R.id.btnFps)
        lensRail = findViewById(R.id.lensRail)
        btnShutterInner = findViewById(R.id.btnShutterInner)
        btnLastClip = findViewById(R.id.btnLastClip)

        // Pro Dock bindings
        pillIso = findViewById(R.id.pillIso)
        txtIsoVal = findViewById(R.id.txtIsoVal)
        pillShutter = findViewById(R.id.pillShutter)
        txtShutterVal = findViewById(R.id.txtShutterVal)
        pillWb = findViewById(R.id.pillWb)
        txtWbVal = findViewById(R.id.txtWbVal)
        pillFocus = findViewById(R.id.pillFocus)
        txtFocusVal = findViewById(R.id.txtFocusVal)
        pillZoom = findViewById(R.id.pillZoom)
        txtZoomVal = findViewById(R.id.txtZoomVal)
        zoomRockerRow = findViewById(R.id.zoomRockerRow)
        btnZoomSpeed = findViewById(R.id.btnZoomSpeed)
        btnZoomWideHold = findViewById(R.id.btnZoomWideHold)
        btnZoomTeleHold = findViewById(R.id.btnZoomTeleHold)
        btnZoomPreset1x = findViewById(R.id.btnZoomPreset1x)
        btnZoomPreset2x = findViewById(R.id.btnZoomPreset2x)
        btnZoomPreset5x = findViewById(R.id.btnZoomPreset5x)
        btnAeAfLock = findViewById(R.id.btnAeAfLock)

        // Floating Drawer bindings
        sliderDrawer = findViewById(R.id.sliderDrawer)
        txtSliderTitle = findViewById(R.id.txtSliderTitle)
        txtSliderReadout = findViewById(R.id.txtSliderReadout)
        btnSliderAuto = findViewById(R.id.btnSliderAuto)
        btnShutterUnit = findViewById(R.id.btnShutterUnit)
        btnSlider180 = findViewById(R.id.btnSlider180)
        rackFocusRow = findViewById(R.id.rackFocusRow)
        btnSetFocusA = findViewById(R.id.btnSetFocusA)
        btnSetFocusB = findViewById(R.id.btnSetFocusB)
        btnPullTime = findViewById(R.id.btnPullTime)
        btnPullFocusAB = findViewById(R.id.btnPullFocusAB)
        proSeekBar = findViewById(R.id.proSeekBar)

        engine = CinemaCameraEngine(this, this)

        setupTopAndRightControls()
        setupProDockAndDrawer()
        setupMonitoringTools()
        setupProAudioAndCodecBar()

        btnUpdateApp.setOnClickListener {
            val url = updateDownloadUrl
            if (!url.isNullOrBlank()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open update link", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (savedInstanceState == null) {
            checkForGitHubUpdateSilently()
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextZoom = engine.currentZoomRatio * detector.scaleFactor
                engine.setZoomRatio(nextZoom)
                updateZoomReadoutUi()
                return true
            }
        })

        textureView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (event.pointerCount > 1) return@setOnTouchListener true
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (activePanel != ProControlPanel.NONE) {
                    selectPanel(ProControlPanel.NONE)
                } else {
                    val normX = event.x / v.width.coerceAtLeast(1)
                    val normY = event.y / v.height.coerceAtLeast(1)
                    val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        display?.rotation ?: Surface.ROTATION_90
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.rotation
                    }
                    engine.triggerTapToFocus(normX, normY, rot)
                    showFocusReticle(event.x, event.y)
                }
            }
            true
        }
    }

    private fun setupTopAndRightControls() {
        findViewById<View>(R.id.btnShutterOuter).setOnClickListener {
            if (engine.isRecording) {
                engine.stopRecording()
            } else {
                val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    display?.rotation ?: Surface.ROTATION_90
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.rotation
                }
                engine.startRecording(rot)
            }
        }

        btnResolution.setOnClickListener {
            if (engine.isRecording) return@setOnClickListener
            currentResolution = if (currentResolution == CinemaResolution.FHD_1080P) {
                CinemaResolution.UHD_4K
            } else {
                CinemaResolution.FHD_1080P
            }
            btnResolution.text = currentResolution.label
            engine.updateSettings(currentResolution, fpsOptions[currentFpsIdx])
        }

        btnFps.setOnClickListener {
            if (engine.isRecording) return@setOnClickListener
            currentFpsIdx = (currentFpsIdx + 1) % fpsOptions.size
            val fps = fpsOptions[currentFpsIdx]
            btnFps.text = "$fps FPS"
            engine.updateSettings(currentResolution, fps)
        }

        btnLastClip.setOnClickListener {
            if (engine.isRecording) {
                Toast.makeText(this, "Stop recording before opening Clip Gallery.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, ClipGalleryActivity::class.java))
        }
    }

    private fun setupMonitoringTools() {
        btnMonitorToggle.setOnClickListener {
            val showing = monitorToolsBar.visibility == View.VISIBLE
            if (!showing && proSettingsBar.visibility == View.VISIBLE) {
                proSettingsBar.visibility = View.GONE
                styleToolPill(btnProSettingsToggle, false)
            }
            monitorToolsBar.visibility = if (showing) View.GONE else View.VISIBLE
            styleToolPill(btnMonitorToggle, !showing)
        }

        btnToolGrid.setOnClickListener {
            val modes = GridMode.values()
            gridModeIdx = (gridModeIdx + 1) % modes.size
            val m = modes[gridModeIdx]
            overlayView.gridMode = m
            btnToolGrid.text = m.label
            styleToolPill(btnToolGrid, m != GridMode.OFF)
        }

        btnToolFrame.setOnClickListener {
            val guides = AspectGuide.values()
            frameGuideIdx = (frameGuideIdx + 1) % guides.size
            val g = guides[frameGuideIdx]
            overlayView.aspectGuide = g
            btnToolFrame.text = "FRAME: ${g.label}"
            styleToolPill(btnToolFrame, g != AspectGuide.OFF)
        }

        btnToolPeak.setOnClickListener {
            overlayView.showPeaking = !overlayView.showPeaking
            styleToolPill(btnToolPeak, overlayView.showPeaking)
            syncAnalysisLoop()
        }

        btnToolZebra.setOnClickListener {
            overlayView.showZebras = !overlayView.showZebras
            styleToolPill(btnToolZebra, overlayView.showZebras)
            syncAnalysisLoop()
        }

        btnToolHist.setOnClickListener {
            overlayView.showHistogram = !overlayView.showHistogram
            styleToolPill(btnToolHist, overlayView.showHistogram)
            syncAnalysisLoop()
        }
    }

    private fun styleToolPill(view: TextView, active: Boolean) {
        view.setBackgroundResource(if (active) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
        view.setTextColor(if (active) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun setupProAudioAndCodecBar() {
        btnProSettingsToggle.setOnClickListener {
            val showing = proSettingsBar.visibility == View.VISIBLE
            if (!showing && monitorToolsBar.visibility == View.VISIBLE) {
                monitorToolsBar.visibility = View.GONE
                styleToolPill(btnMonitorToggle, false)
            }
            proSettingsBar.visibility = if (showing) View.GONE else View.VISIBLE
            styleToolPill(btnProSettingsToggle, !showing)
        }

        btnProCodec.setOnClickListener {
            if (engine.isRecording) return@setOnClickListener
            engine.targetCodec = if (engine.targetCodec == CinemaCodec.H264) CinemaCodec.HEVC else CinemaCodec.H264
            btnProCodec.text = engine.targetCodec.label
            styleToolPill(btnProCodec, engine.targetCodec == CinemaCodec.HEVC)
        }

        btnProBitrate.setOnClickListener {
            if (engine.isRecording) return@setOnClickListener
            bitrateIdx = (bitrateIdx + 1) % bitrateOptions.size
            val mbps = bitrateOptions[bitrateIdx]
            engine.targetBitrateMbps = mbps
            btnProBitrate.text = "$mbps Mbps"
        }

        btnProMic.setOnClickListener {
            if (engine.isRecording) return@setOnClickListener
            val prefs = MicPreference.values()
            micPrefIdx = (micPrefIdx + 1) % prefs.size
            val selected = prefs[micPrefIdx]
            engine.micPreference = selected
            val extFound = engine.findExternalMicDevice() != null
            btnProMic.text = if (selected == MicPreference.EXTERNAL && !extFound) {
                "MIC: NO EXT"
            } else {
                selected.label
            }
        }

        btnProTone.setOnClickListener {
            val profiles = ToneProfile.values()
            toneIdx = (toneIdx + 1) % profiles.size
            val p = profiles[toneIdx]
            engine.setToneProfile(p)
            btnProTone.text = p.label
            styleToolPill(btnProTone, p != ToneProfile.STANDARD)
        }

        btnProPresets.setOnClickListener {
            showPresetsDialog()
        }
    }

    private fun showPresetsDialog() {
        val saved = presetStore.getAll()
        val items = mutableListOf("+ Save Current Settings as Template")
        saved.forEach { items.add("Load: ${it.name} (${it.resolutionName.replace("FHD_", "").replace("UHD_", "")} ${it.fps}fps)") }

        AlertDialog.Builder(this)
            .setTitle("Shooting Templates / Presets")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    showSavePresetDialog()
                } else {
                    val preset = saved[which - 1]
                    AlertDialog.Builder(this)
                        .setTitle(preset.name)
                        .setPositiveButton("Load Template") { _, _ -> applyPreset(preset) }
                        .setNeutralButton("Delete") { _, _ ->
                            presetStore.delete(preset.name)
                            Toast.makeText(this, "Deleted '${preset.name}'", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply {
            hint = "Template name (e.g. Studio 4K, Night Cine)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(36, 28, 36, 28)
        }
        AlertDialog.Builder(this)
            .setTitle("Save Shooting Template")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Template ${presetStore.getAll().size + 1}" }
                val preset = CameraPreset(
                    name = name,
                    resolutionName = currentResolution.name,
                    fps = fpsOptions[currentFpsIdx],
                    codecName = engine.targetCodec.name,
                    bitrateMbps = engine.targetBitrateMbps,
                    micPrefName = engine.micPreference.name,
                    toneProfileName = engine.toneProfile.name,
                    isManualExposure = engine.isManualExposure,
                    manualIso = engine.manualIso,
                    manualShutterNs = engine.manualShutterNs,
                    isShutter180Locked = engine.isShutter180Locked,
                    isManualWb = engine.isManualWb,
                    manualKelvin = engine.manualKelvin,
                    isManualFocus = engine.isManualFocus,
                    manualFocusDistance = engine.manualFocusDistance,
                    focusPullMs = focusPullDurationMs,
                    gridModeName = overlayView.gridMode.name,
                    aspectGuideName = overlayView.aspectGuide.name
                )
                presetStore.save(preset)
                Toast.makeText(this, "Saved template: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPreset(p: CameraPreset) {
        if (engine.isRecording) {
            Toast.makeText(this, "Stop recording before loading a template.", Toast.LENGTH_SHORT).show()
            return
        }
        currentResolution = try { CinemaResolution.valueOf(p.resolutionName) } catch (_: Exception) { CinemaResolution.FHD_1080P }
        currentFpsIdx = fpsOptions.indexOf(p.fps).takeIf { it >= 0 } ?: 1
        engine.targetCodec = try { CinemaCodec.valueOf(p.codecName) } catch (_: Exception) { CinemaCodec.H264 }
        engine.targetBitrateMbps = p.bitrateMbps
        bitrateIdx = bitrateOptions.indexOf(p.bitrateMbps).takeIf { it >= 0 } ?: 1
        engine.micPreference = try { MicPreference.valueOf(p.micPrefName) } catch (_: Exception) { MicPreference.AUTO }
        micPrefIdx = MicPreference.values().indexOf(engine.micPreference).coerceAtLeast(0)
        val tone = try { ToneProfile.valueOf(p.toneProfileName) } catch (_: Exception) { ToneProfile.STANDARD }
        toneIdx = ToneProfile.values().indexOf(tone).coerceAtLeast(0)
        engine.setToneProfile(tone)

        if (p.isManualExposure) {
            engine.setManualIso(p.manualIso)
            engine.setManualShutterNs(p.manualShutterNs)
        } else {
            engine.setAutoExposure()
        }
        if (p.isManualWb) engine.setManualWhiteBalanceKelvin(p.manualKelvin) else engine.setAutoWhiteBalance()
        if (p.isManualFocus) engine.setManualFocusDistance(p.manualFocusDistance) else engine.setAutoFocus()

        focusPullDurationMs = p.focusPullMs.coerceIn(300L, 10000L)
        btnPullTime.text = String.format(Locale.US, "%.1fs", focusPullDurationMs / 1000f)

        overlayView.gridMode = try { GridMode.valueOf(p.gridModeName) } catch (_: Exception) { GridMode.OFF }
        gridModeIdx = GridMode.values().indexOf(overlayView.gridMode).coerceAtLeast(0)
        btnToolGrid.text = overlayView.gridMode.label
        styleToolPill(btnToolGrid, overlayView.gridMode != GridMode.OFF)

        overlayView.aspectGuide = try { AspectGuide.valueOf(p.aspectGuideName) } catch (_: Exception) { AspectGuide.OFF }
        frameGuideIdx = AspectGuide.values().indexOf(overlayView.aspectGuide).coerceAtLeast(0)
        btnToolFrame.text = "FRAME: ${overlayView.aspectGuide.label}"
        styleToolPill(btnToolFrame, overlayView.aspectGuide != AspectGuide.OFF)

        btnResolution.text = currentResolution.label
        btnFps.text = "${p.fps} FPS"
        btnProCodec.text = engine.targetCodec.label
        styleToolPill(btnProCodec, engine.targetCodec == CinemaCodec.HEVC)
        btnProBitrate.text = "${engine.targetBitrateMbps} Mbps"
        btnProMic.text = engine.micPreference.label
        btnProTone.text = tone.label
        styleToolPill(btnProTone, tone != ToneProfile.STANDARD)

        engine.updateSettings(currentResolution, p.fps)
        Toast.makeText(this, "Loaded template: ${p.name}", Toast.LENGTH_SHORT).show()
    }

    private fun checkForGitHubUpdateSilently() {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "idgaf-camera-StudioMogli")
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@Thread

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val latestTag = json.optString("tag_name", "").trim()
                if (latestTag.isEmpty() || !isNewerVersion(latestTag, getCurrentVersionName())) return@Thread

                var downloadUrl = json.optString("html_url", "")
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "").lowercase()
                        val directUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk") && directUrl.isNotBlank()) {
                            downloadUrl = directUrl
                            break
                        }
                    }
                }
                if (downloadUrl.isNotBlank()) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            updateDownloadUrl = downloadUrl
                            btnUpdateApp.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun getCurrentVersionName(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: "0.5.0"
    } catch (_: Exception) { "0.5.0" }

    private fun isNewerVersion(latestRaw: String, currentRaw: String): Boolean {
        val lParts = latestRaw.removePrefix("v").removePrefix("V")
            .split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val cParts = currentRaw.removePrefix("v").removePrefix("V")
            .split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(lParts.size, cParts.size)) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun startAudioVuMonitor() {
        if (audioMonitorRunning || !hasPerm(Manifest.permission.RECORD_AUDIO)) return
        audioMonitorRunning = true
        audioMonitorThread = Thread {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)
            var ar: AudioRecord? = null
            val buffer = ShortArray(minBuf / 2)

            while (audioMonitorRunning) {
                if (engine.isRecording) {
                    // Release AudioRecord while MediaRecorder owns the mic, read MediaRecorder amplitude instead
                    if (ar != null) {
                        try { ar.stop(); ar.release() } catch (_: Exception) {}
                        ar = null
                    }
                    val amp = engine.getRecordingMaxAmplitude()
                    val norm = (amp / 22000f).coerceIn(0f, 1f)
                    audioMeterView.updateLevel(norm)
                    try { Thread.sleep(70L) } catch (_: Exception) {}
                } else {
                    if (ar == null) {
                        try {
                            @SuppressLint("MissingPermission")
                            val rec = AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                sampleRate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                minBuf
                            )
                            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                                rec.startRecording()
                                ar = rec
                            } else {
                                rec.release()
                            }
                        } catch (_: Exception) {
                            try { Thread.sleep(200L) } catch (_: Exception) {}
                        }
                    }
                    val activeAr = ar
                    if (activeAr != null) {
                        val read = activeAr.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            var peak = 0
                            for (i in 0 until read) {
                                val v = kotlin.math.abs(buffer[i].toInt())
                                if (v > peak) peak = v
                            }
                            val norm = (peak / 18000f).coerceIn(0f, 1f)
                            audioMeterView.updateLevel(norm)
                        }
                    }
                }
            }
            try { ar?.stop(); ar?.release() } catch (_: Exception) {}
        }.also { it.start() }
    }

    private fun stopAudioVuMonitor() {
        audioMonitorRunning = false
        audioMonitorThread?.interrupt()
        audioMonitorThread = null
    }

    private fun showTapToTypeValueDialog() {
        val panel = activePanel
        if (panel == ProControlPanel.NONE) return

        val (title, hint) = when (panel) {
            ProControlPanel.ISO -> "Enter Exact ISO" to "e.g. 100, 400, 800, 1600"
            ProControlPanel.SHUTTER -> if (shutterInAngleMode) {
                "Enter Shutter Angle (Degrees)" to "11 to 360 (e.g. 180, 90, 270)"
            } else {
                "Enter Shutter Speed (1/X sec)" to "e.g. 48, 50, 60, 120, 500"
            }
            ProControlPanel.WB -> "Enter White Balance (Kelvin)" to "2500 to 8500 (e.g. 5600)"
            ProControlPanel.FOCUS -> "Enter Focus Distance (%)" to "0 (Infinity) to 100 (Macro)"
            ProControlPanel.ZOOM -> "Enter Zoom Multiplier (x)" to "1 to 10 (e.g. 1.5, 2, 4)"
            ProControlPanel.NONE -> return
        }

        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(36, 28, 36, 28)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v <= 0 && panel != ProControlPanel.FOCUS) {
                    Toast.makeText(this, "Enter a valid number", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                when (panel) {
                    ProControlPanel.ISO -> engine.setManualIso(v)
                    ProControlPanel.SHUTTER -> {
                        if (shutterInAngleMode) {
                            engine.setManualShutterAngle(v.toFloat())
                        } else {
                            val denom = v.coerceIn(1, 8000)
                            engine.setManualShutterNs(1_000_000_000L / denom)
                        }
                    }
                    ProControlPanel.WB -> engine.setManualWhiteBalanceKelvin(v)
                    ProControlPanel.FOCUS -> {
                        val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
                        val d = (v.coerceIn(0, 100) / 100f) * maxF
                        engine.setManualFocusDistance(d)
                    }
                    ProControlPanel.ZOOM -> {
                        engine.setZoomRatio(v.toFloat())
                        updateZoomReadoutUi()
                    }
                    ProControlPanel.NONE -> {}
                }
                refreshSliderState()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupProDockAndDrawer() {
        pillIso.setOnClickListener {
            selectPanel(if (activePanel == ProControlPanel.ISO) ProControlPanel.NONE else ProControlPanel.ISO)
        }
        pillShutter.setOnClickListener {
            selectPanel(if (activePanel == ProControlPanel.SHUTTER) ProControlPanel.NONE else ProControlPanel.SHUTTER)
        }
        pillWb.setOnClickListener {
            selectPanel(if (activePanel == ProControlPanel.WB) ProControlPanel.NONE else ProControlPanel.WB)
        }
        pillFocus.setOnClickListener {
            if (!engine.capabilities.supportsManualFocus) {
                Toast.makeText(this, "This lens has fixed focus", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectPanel(if (activePanel == ProControlPanel.FOCUS) ProControlPanel.NONE else ProControlPanel.FOCUS)
        }
        pillZoom.setOnClickListener {
            selectPanel(if (activePanel == ProControlPanel.ZOOM) ProControlPanel.NONE else ProControlPanel.ZOOM)
        }

        btnZoomSpeed.setOnClickListener {
            zoomSpeedIdx = (zoomSpeedIdx + 1) % zoomSpeedSteps.size
            btnZoomSpeed.text = zoomSpeedSteps[zoomSpeedIdx].first
        }

        btnZoomWideHold.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomRockerDirection = -1
                    uiHandler.removeCallbacks(zoomRockerRunnable)
                    uiHandler.post(zoomRockerRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomRockerDirection = 0
                    uiHandler.removeCallbacks(zoomRockerRunnable)
                }
            }
            true
        }

        btnZoomTeleHold.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomRockerDirection = 1
                    uiHandler.removeCallbacks(zoomRockerRunnable)
                    uiHandler.post(zoomRockerRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomRockerDirection = 0
                    uiHandler.removeCallbacks(zoomRockerRunnable)
                }
            }
            true
        }

        btnZoomPreset1x.setOnClickListener { engine.setZoomRatio(1.0f); updateZoomReadoutUi() }
        btnZoomPreset2x.setOnClickListener { engine.setZoomRatio(2.0f); updateZoomReadoutUi() }
        btnZoomPreset5x.setOnClickListener { engine.setZoomRatio(5.0f); updateZoomReadoutUi() }
        btnAeAfLock.setOnClickListener {
            val locked = engine.toggleAeAfLock()
            updateLockUi(locked)
            Toast.makeText(this, if (locked) "AE / AF LOCKED" else "AE / AF UNLOCKED", Toast.LENGTH_SHORT).show()
        }

        txtSliderReadout.setOnClickListener {
            showTapToTypeValueDialog()
        }

        btnSliderAuto.setOnClickListener {
            when (activePanel) {
                ProControlPanel.ISO, ProControlPanel.SHUTTER -> engine.setAutoExposure()
                ProControlPanel.FOCUS -> {
                    rackAnimator?.cancel()
                    engine.setAutoFocus()
                }
                ProControlPanel.WB -> engine.setAutoWhiteBalance()
                ProControlPanel.ZOOM -> { engine.setZoomRatio(1.0f); updateZoomReadoutUi() }
                ProControlPanel.NONE -> {}
            }
            refreshSliderState()
        }

        btnShutterUnit.setOnClickListener {
            shutterInAngleMode = !shutterInAngleMode
            btnShutterUnit.text = if (shutterInAngleMode) "ANG°" else "SPD"
            refreshSliderState()
        }

        btnPullTime.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Seconds (e.g. 0.8, 1.5, 3.0)"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF888888.toInt())
                setPadding(36, 28, 36, 28)
            }
            AlertDialog.Builder(this)
                .setTitle("Focus Pull Duration (Seconds)")
                .setView(input)
                .setPositiveButton("Set") { _, _ ->
                    val secs = input.text.toString().trim().toFloatOrNull()
                    if (secs != null && secs in 0.2f..15.0f) {
                        focusPullDurationMs = (secs * 1000f).toLong()
                        btnPullTime.text = String.format(Locale.US, "%.1fs", secs)
                    } else {
                        Toast.makeText(this, "Enter 0.2 to 15.0 seconds", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnSlider180.setOnClickListener {
            val active = engine.toggleShutter180Rule()
            btnSlider180.setBackgroundResource(if (active) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
            btnSlider180.setTextColor(if (active) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
            refreshSliderState()
        }

        btnSetFocusA.setOnClickListener {
            focusPointA = engine.manualFocusDistance
            btnSetFocusA.setBackgroundResource(R.drawable.bg_lens_active)
            btnSetFocusA.setTextColor(0xFF0B0D10.toInt())
            Toast.makeText(this, "Focus Point A Saved", Toast.LENGTH_SHORT).show()
        }

        btnSetFocusB.setOnClickListener {
            focusPointB = engine.manualFocusDistance
            btnSetFocusB.setBackgroundResource(R.drawable.bg_lens_active)
            btnSetFocusB.setTextColor(0xFF0B0D10.toInt())
            Toast.makeText(this, "Focus Point B Saved", Toast.LENGTH_SHORT).show()
        }

        btnPullFocusAB.setOnClickListener {
            val a = focusPointA
            val b = focusPointB
            if (a == null || b == null) {
                Toast.makeText(this, "Set both Focus A and B first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rackAnimator?.cancel()
            val start = if (rackGoingToB) a else b
            val end = if (rackGoingToB) b else a
            rackGoingToB = !rackGoingToB
            btnPullFocusAB.text = if (rackGoingToB) "PULL B→A" else "PULL A→B"

            rackAnimator = ValueAnimator.ofFloat(start, end).apply {
                duration = focusPullDurationMs
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val d = anim.animatedValue as Float
                    engine.setManualFocusDistance(d)
                    if (activePanel == ProControlPanel.FOCUS) {
                        val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
                        suppressSeekCallback = true
                        proSeekBar.progress = ((d / maxF) * 100f).roundToInt().coerceIn(0, 100)
                        suppressSeekCallback = false
                        txtSliderReadout.text = formatFocus(d, maxF)
                    }
                }
                start()
            }
        }

        proSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressSeekCallback) return
                when (activePanel) {
                    ProControlPanel.ISO -> {
                        val minIso = engine.capabilities.isoRange.lower
                        val maxIso = engine.capabilities.isoRange.upper.coerceAtMost(6400)
                        val iso = minIso + ((maxIso - minIso) * (progress / 100f)).roundToInt()
                        engine.setManualIso(iso)
                        txtSliderReadout.text = "ISO $iso"
                        updateAutoButtonVisual(isManual = true)
                    }
                    ProControlPanel.SHUTTER -> {
                        if (shutterInAngleMode) {
                            val idx = progress.coerceIn(0, shutterAngles.lastIndex)
                            val ang = shutterAngles[idx]
                            engine.setManualShutterAngle(ang)
                            txtSliderReadout.text = String.format(Locale.US, "%.0f°", ang)
                            btnSlider180.setBackgroundResource(if (kotlin.math.abs(ang - 180f) < 1f) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                            btnSlider180.setTextColor(if (kotlin.math.abs(ang - 180f) < 1f) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                        } else {
                            val idx = progress.coerceIn(0, shutterDenoms.lastIndex)
                            val denom = shutterDenoms[idx]
                            val ns = 1_000_000_000L / denom
                            engine.setManualShutterNs(ns)
                            txtSliderReadout.text = "1/${denom}s"
                            btnSlider180.setBackgroundResource(R.drawable.bg_hud_pill)
                            btnSlider180.setTextColor(0xFFFFFFFF.toInt())
                        }
                        updateAutoButtonVisual(isManual = true)
                    }
                    ProControlPanel.FOCUS -> {
                        rackAnimator?.cancel()
                        val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
                        val diopters = (progress / 100f) * maxF
                        engine.setManualFocusDistance(diopters)
                        txtSliderReadout.text = formatFocus(diopters, maxF)
                        updateAutoButtonVisual(isManual = true)
                    }
                    ProControlPanel.WB -> {
                        val kelvin = 2500 + (progress * 60) // 2500K to 8500K
                        engine.setManualWhiteBalanceKelvin(kelvin)
                        txtSliderReadout.text = "${kelvin}K"
                        updateAutoButtonVisual(isManual = true)
                    }
                    ProControlPanel.ZOOM -> {
                        val minZ = engine.capabilities.minZoomRatio
                        val maxZ = engine.capabilities.maxZoomRatio.coerceAtMost(10.0f)
                        val ratio = minZ + ((maxZ - minZ) * (progress / 100f))
                        engine.setZoomRatio(ratio)
                        updateZoomReadoutUi()
                    }
                    ProControlPanel.NONE -> {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun selectPanel(panel: ProControlPanel) {
        activePanel = panel
        val pills = listOf(
            pillIso to ProControlPanel.ISO,
            pillShutter to ProControlPanel.SHUTTER,
            pillWb to ProControlPanel.WB,
            pillFocus to ProControlPanel.FOCUS,
            pillZoom to ProControlPanel.ZOOM
        )
        pills.forEach { (view, p) ->
            view.setBackgroundResource(if (p == panel) R.drawable.bg_dock_pill_active else R.drawable.bg_hud_pill)
        }

        if (panel == ProControlPanel.NONE) {
            sliderDrawer.visibility = View.GONE
            return
        }

        sliderDrawer.visibility = View.VISIBLE
        btnShutterUnit.visibility = if (panel == ProControlPanel.SHUTTER) View.VISIBLE else View.GONE
        btnSlider180.visibility = if (panel == ProControlPanel.SHUTTER) View.VISIBLE else View.GONE
        rackFocusRow.visibility = if (panel == ProControlPanel.FOCUS) View.VISIBLE else View.GONE
        zoomRockerRow.visibility = if (panel == ProControlPanel.ZOOM) View.VISIBLE else View.GONE
        refreshSliderState()
    }

    private fun refreshSliderState() {
        suppressSeekCallback = true
        when (activePanel) {
            ProControlPanel.ISO -> {
                txtSliderTitle.text = "ISO"
                proSeekBar.max = 100
                val minIso = engine.capabilities.isoRange.lower
                val maxIso = engine.capabilities.isoRange.upper.coerceAtMost(6400).coerceAtLeast(minIso + 1)
                val pct = (((engine.manualIso - minIso).toFloat() / (maxIso - minIso)) * 100f).roundToInt().coerceIn(0, 100)
                proSeekBar.progress = pct
                txtSliderReadout.text = if (engine.isManualExposure) "ISO ${engine.manualIso}" else "AUTO"
                updateAutoButtonVisual(engine.isManualExposure)
            }
            ProControlPanel.SHUTTER -> {
                if (shutterInAngleMode) {
                    txtSliderTitle.text = "ANGLE"
                    proSeekBar.max = shutterAngles.lastIndex
                    val curAng = engine.getCurrentShutterAngle()
                    val closestIdx = shutterAngles.indices.minByOrNull { kotlin.math.abs(shutterAngles[it] - curAng) } ?: 7
                    proSeekBar.progress = closestIdx
                    txtSliderReadout.text = if (engine.isManualExposure) String.format(Locale.US, "%.0f°", curAng) else "AUTO"
                } else {
                    txtSliderTitle.text = "SHUTTER"
                    proSeekBar.max = shutterDenoms.lastIndex
                    val curDenom = (1_000_000_000L / engine.manualShutterNs.coerceAtLeast(1L)).toInt()
                    val closestIdx = shutterDenoms.indices.minByOrNull { kotlin.math.abs(shutterDenoms[it] - curDenom) } ?: 4
                    proSeekBar.progress = closestIdx
                    txtSliderReadout.text = if (engine.isManualExposure) "1/${curDenom}s" else "AUTO"
                }
                btnSlider180.setBackgroundResource(if (engine.isShutter180Locked) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                btnSlider180.setTextColor(if (engine.isShutter180Locked) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                updateAutoButtonVisual(engine.isManualExposure)
            }
            ProControlPanel.FOCUS -> {
                txtSliderTitle.text = "FOCUS"
                proSeekBar.max = 100
                val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
                proSeekBar.progress = ((engine.manualFocusDistance / maxF) * 100f).roundToInt().coerceIn(0, 100)
                txtSliderReadout.text = if (engine.isManualFocus) formatFocus(engine.manualFocusDistance, maxF) else "AF-C"
                updateAutoButtonVisual(engine.isManualFocus)
            }
            ProControlPanel.WB -> {
                txtSliderTitle.text = "WHITE BAL"
                proSeekBar.max = 100
                proSeekBar.progress = ((engine.manualKelvin - 2500) / 60).coerceIn(0, 100)
                txtSliderReadout.text = if (engine.isManualWb) "${engine.manualKelvin}K" else "AWB"
                updateAutoButtonVisual(engine.isManualWb)
            }
            ProControlPanel.ZOOM -> {
                txtSliderTitle.text = "SERVO ZOOM"
                proSeekBar.max = 100
                updateZoomReadoutUi()
            }
            ProControlPanel.NONE -> {}
        }
        suppressSeekCallback = false
    }

    private fun updateZoomReadoutUi() {
        val z = engine.currentZoomRatio
        val label = String.format(Locale.US, "%.1fx", z)
        txtZoomVal.text = label
        txtZoomVal.setTextColor(if (z > 1.05f) 0xFFFF5F1F.toInt() else 0xFFFFFFFF.toInt())
        if (activePanel == ProControlPanel.ZOOM) {
            txtSliderReadout.text = label
            val minZ = engine.capabilities.minZoomRatio
            val maxZ = engine.capabilities.maxZoomRatio.coerceAtMost(10.0f).coerceAtLeast(minZ + 0.1f)
            suppressSeekCallback = true
            proSeekBar.progress = (((z - minZ) / (maxZ - minZ)) * 100f).roundToInt().coerceIn(0, 100)
            suppressSeekCallback = false
            updateAutoButtonVisual(z > 1.05f)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableImmersiveFullscreen()
        textureView.post { configureTransform(lastPreviewSize) }
    }

    private fun updateAutoButtonVisual(isManual: Boolean) {
        btnSliderAuto.setBackgroundResource(if (!isManual) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
        btnSliderAuto.setTextColor(if (!isManual) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun updateLockUi(locked: Boolean) {
        btnAeAfLock.text = if (locked) "LOCKED" else "LOCK"
        btnAeAfLock.setBackgroundResource(if (locked) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
        btnAeAfLock.setTextColor(if (locked) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun formatShutter(ns: Long): String {
        if (ns <= 0L) return "AUTO"
        val denom = (1_000_000_000.0 / ns.toDouble()).roundToInt().coerceAtLeast(1)
        return "1/$denom"
    }

    private fun formatFocus(diopters: Float, maxDiopters: Float): String {
        val pct = ((diopters / maxDiopters.coerceAtLeast(0.01f)) * 100f).roundToInt().coerceIn(0, 100)
        return when {
            pct <= 3 -> "INF ∞"
            pct >= 96 -> "MACRO"
            else -> "$pct%"
        }
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun openCameraIfReady() {
        if (!hasPerm(Manifest.permission.CAMERA) || !hasPerm(Manifest.permission.RECORD_AUDIO)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            return
        }
        val st = textureView.surfaceTexture ?: return
        engine.attachSurfaceAndOpen(st)
    }

    private fun showFocusReticle(x: Float, y: Float) {
        focusReticle.x = x - (focusReticle.width / 2f)
        focusReticle.y = y - (focusReticle.height / 2f)
        focusReticle.visibility = View.VISIBLE
        focusReticle.alpha = 1f
        focusReticle.animate().alpha(0f).setStartDelay(700).setDuration(300).withEndAction {
            focusReticle.visibility = View.GONE
        }.start()
    }

    private fun configureTransform(previewSize: Size) {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW, viewH)
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewH / previewSize.height.toFloat(),
                viewW / previewSize.width.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    // ==================== CinemaEngineListener ====================

    override fun onLensesReady(lenses: List<CinemaLens>, activeIndex: Int) {
        runOnUiThread { renderLensRail(lenses, activeIndex) }
    }

    private fun renderLensRail(lenses: List<CinemaLens>, activeIndex: Int) {
        lensRail.removeAllViews()
        lenses.forEachIndexed { idx, lens ->
            val pill = TextView(this).apply {
                text = lens.label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 12)
                val isActive = idx == activeIndex
                setBackgroundResource(if (isActive) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                setTextColor(if (isActive) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                val lp = LinearLayout.LayoutParams(dpToPx(48), dpToPx(38)).apply {
                    bottomMargin = dpToPx(8)
                }
                layoutParams = lp
                setOnClickListener {
                    if (!engine.isRecording) {
                        focusPointA = null
                        focusPointB = null
                        engine.selectLens(idx)
                        renderLensRail(lenses, idx)
                    }
                }
            }
            lensRail.addView(pill)
        }
    }

    override fun onCameraReady(
        previewSize: Size,
        activeRes: Size,
        activeFps: Int,
        lens: CinemaLens,
        caps: CameraCapabilities
    ) {
        runOnUiThread {
            lastPreviewSize = previewSize
            configureTransform(previewSize)
            updateZoomReadoutUi()
            val resTag = if (activeRes.width >= 3840) "4K" else "${activeRes.height}P"
            btnResolution.text = resTag
            btnFps.text = "$activeFps FPS"
            if (activePanel != ProControlPanel.NONE) refreshSliderState()
        }
    }

    override fun onTelemetryUpdated(telemetry: LiveTelemetry) {
        runOnUiThread {
            val orange = 0xFFFF5F1F.toInt()
            val white = 0xFFFFFFFF.toInt()

            txtIsoVal.text = telemetry.iso.toString()
            txtIsoVal.setTextColor(if (telemetry.isManualExposure) orange else white)

            txtShutterVal.text = if (shutterInAngleMode) {
                String.format(Locale.US, "%.0f°", engine.getCurrentShutterAngle())
            } else {
                formatShutter(telemetry.shutterNs)
            }
            txtShutterVal.setTextColor(if (telemetry.isManualExposure) orange else white)

            txtWbVal.text = if (telemetry.isManualWb) "${telemetry.kelvin}K" else "AWB"
            txtWbVal.setTextColor(if (telemetry.isManualWb) orange else white)

            val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
            txtFocusVal.text = if (telemetry.isManualFocus) formatFocus(telemetry.focusDistance, maxF) else "AF-C"
            txtFocusVal.setTextColor(if (telemetry.isManualFocus) orange else white)
        }
    }

    override fun onRecordingStarted() {
        runOnUiThread {
            recStartMs = System.currentTimeMillis()
            viewRecDot.visibility = View.VISIBLE
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_stop)
            btnShutterInner.animate().scaleX(0.54f).scaleY(0.54f).setDuration(140L).start()
            txtTimecode.setTextColor(0xFFFF3B30.toInt())
            uiHandler.removeCallbacks(timecodeRunnable)
            uiHandler.post(timecodeRunnable)
        }
    }

    override fun onRecordingStopped(savedUri: Uri?, filePathLabel: String) {
        runOnUiThread {
            uiHandler.removeCallbacks(timecodeRunnable)
            viewRecDot.visibility = View.GONE
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_rec)
            btnShutterInner.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140L).start()
            txtTimecode.setTextColor(0xFFFFFFFF.toInt())
            txtTimecode.text = "00:00:00"
            if (savedUri != null) {
                lastSavedUri = savedUri
                btnLastClip.setTextColor(0xFFFF5F1F.toInt())
                Toast.makeText(this, "Saved: $filePathLabel", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        enableImmersiveFullscreen()
        engine.startThread()
        syncAnalysisLoop()
        startAudioVuMonitor()
        if (textureView.isAvailable) {
            openCameraIfReady()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        rackAnimator?.cancel()
        uiHandler.removeCallbacks(timecodeRunnable)
        zoomRockerDirection = 0
        uiHandler.removeCallbacks(zoomRockerRunnable)
        uiHandler.removeCallbacks(analysisRunnable)
        stopAudioVuMonitor()
        engine.stopThread()
        super.onPause()
    }
}
