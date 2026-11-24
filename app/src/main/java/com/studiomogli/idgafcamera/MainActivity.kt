package com.studiomogli.idgafcamera

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.os.Bundle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class ProControlPanel { NONE, EV, ISO, SHUTTER, FOCUS, WB }
enum class ZoomCurve(val label: String) { LINEAR("LIN"), EXPONENTIAL("EXP"), CRASH("CRASH") }

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
    private lateinit var proSettingsBar: HorizontalScrollView
    private lateinit var btnProCodec: TextView
    private lateinit var btnProBitrate: TextView
    private lateinit var btnProMic: TextView
    private lateinit var btnProTone: TextView
    private lateinit var btnProStab: TextView
    private lateinit var btnProTorch: TextView
    private lateinit var btnProTimer: TextView
    private lateinit var btnProPresets: TextView
    private lateinit var txtTimerCountdown: TextView
    private val timerOptions = listOf(0, 3, 5)
    private var timerIdx = 0
    private var countdownRemaining = 0
    private var pendingRecRotation = Surface.ROTATION_90
    private lateinit var txtSysTelemetry: TextView
    private lateinit var btnUpdateApp: TextView
    private lateinit var presetStore: PresetStore
    private var updateDownloadUrl: String? = null
    private var toneIdx = 0
    private var gridModeIdx = 0
    private val bitrateOptions = listOf(4, 8, 12, 24, 35, 50, 75, 100, 120)
    private var bitrateIdx = 4
    private var activeToast: Toast? = null
    private var micPrefIdx = 0

    // Phase 6.8 Pro Audio Controller Drawer
    private lateinit var btnAudioPanelToggle: LinearLayout
    private lateinit var txtAudioDb: TextView
    private lateinit var audioControlDrawer: LinearLayout
    private lateinit var btnAudioMute: TextView
    private lateinit var btnPreampCam: TextView
    private lateinit var btnPreampRaw: TextView
    private lateinit var btnPreampBoost: TextView
    private lateinit var audioGainSeekBar: SeekBar
    private lateinit var txtAudioTrimVal: TextView

    // Live Audio VU preview monitor (runs when idle; releases hardware mic cleanly before MediaRecorder.start())
    @Volatile private var audioMonitorRunning = false
    @Volatile private var audioMonitorRestartNeeded = false
    @Volatile private var audioPreviewPausedForRec = false
    @Volatile private var isPreviewMicHeld = false
    private var audioMonitorThread: Thread? = null

    // Phase 3 / 6.10 Monitoring Toolbar
    private lateinit var btnMonitorToggle: TextView
    private lateinit var monitorToolsBar: HorizontalScrollView
    private lateinit var btnToolGrid: TextView
    private lateinit var btnToolFrame: TextView
    private lateinit var btnToolPeak: TextView
    private lateinit var btnToolZebra: TextView
    private lateinit var btnToolFalseColor: TextView
    private lateinit var btnToolHist: TextView
    private lateinit var btnToolHorizon: TextView
    private lateinit var btnToolMonConfig: TextView
    private var sensorManager: SensorManager? = null
    private var smoothedRoll = 0f
    private var frameGuideIdx = 0

    private lateinit var txtTimecode: TextView
    private lateinit var viewRecDot: View
    private lateinit var btnResolution: TextView
    private lateinit var btnFps: TextView
    private lateinit var lensRail: LinearLayout
    private lateinit var btnAutoPilot: TextView
    private lateinit var btnShutterInner: View
    private lateinit var btnLastClip: TextView

    // Phase 6 Pro Cinema Zoom Rocker + Pinch-to-Zoom
    private lateinit var btnZoomTele: TextView
    private lateinit var btnZoomWide: TextView
    private lateinit var txtZoomReadout: TextView
    private lateinit var btnZoomSpeed: TextView
    private lateinit var zoomPopoutDrawer: LinearLayout
    private lateinit var btnZoomPreset1x: TextView
    private lateinit var btnZoomPreset2x: TextView
    private lateinit var btnZoomPreset5x: TextView
    private lateinit var btnZoomPresetMax: TextView
    private lateinit var btnZoomCurve: TextView
    private lateinit var btnZoomTime: TextView
    private lateinit var btnSetZoomA: TextView
    private lateinit var btnSetZoomB: TextView
    private lateinit var btnPullZoomAB: TextView
    private lateinit var zoomSeekBar: SeekBar
    private var suppressZoomSeek = false
    private lateinit var scaleDetector: ScaleGestureDetector
    private var zoomRockerDir: Int = 0 // +1 = Tele, -1 = Wide, 0 = Idle
    private var zoomHoldStartMs: Long = 0L
    // Faster, cinema-grade zoom speeds (base step / multiplier per 16ms frame)
    private val zoomSpeedSteps = listOf("SLOW" to 0.032f, "MED" to 0.095f, "FAST" to 0.24f)
    private var zoomSpeedIdx = 1
    private var zoomCurve: ZoomCurve = ZoomCurve.EXPONENTIAL
    private var zoomPullDurationMs: Long = 1500L
    private var zoomPointA: Float? = null
    private var zoomPointB: Float? = null
    private var zoomGoingToB = true
    private var zoomAnimator: ValueAnimator? = null

    // Phase 2 Pro Dock Pills
    private lateinit var pillEv: LinearLayout
    private lateinit var txtEvVal: TextView
    private lateinit var pillIso: LinearLayout
    private lateinit var txtIsoVal: TextView
    private lateinit var pillShutter: LinearLayout
    private lateinit var txtShutterVal: TextView
    private lateinit var pillWb: LinearLayout
    private lateinit var txtWbVal: TextView
    private lateinit var pillFocus: LinearLayout
    private lateinit var txtFocusVal: TextView
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
    private lateinit var wbGradientPanel: LinearLayout
    private lateinit var wbGradientPad: WbGradientPadView
    private lateinit var btnWbPreset3200: TextView
    private lateinit var btnWbPreset4300: TextView
    private lateinit var btnWbPreset5600: TextView
    private lateinit var btnWbPreset6500: TextView
    private lateinit var txtWbTintVal: TextView

    private var activePanel: ProControlPanel = ProControlPanel.NONE
    private var suppressSeekCallback = false
    private var isUserDraggingProSeek = false
    private var lastTelemetry: LiveTelemetry? = null

    // Standard cinema shutter speeds (denominators)
    private val shutterDenoms = listOf(24, 30, 48, 50, 60, 100, 120, 180, 250, 500, 1000, 2000, 4000)

    // A/B Rack Focus points
    private var focusPointA: Float? = null
    private var focusPointB: Float? = null
    private var rackGoingToB = true
    private var rackAnimator: ValueAnimator? = null

    private val fpsOptions = listOf(20, 24, 30, 60)
    private var currentFps: Int = 30
    private var currentResolution = CinemaResolution.FHD_1080P
    private var lastSavedUri: Uri? = null
    private var lastPreviewSize: Size? = null
    private var lastDisplayRotation: Int = Surface.ROTATION_90
    private var displayManager: DisplayManager? = null

    private var recStartMs: Long = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    private val isAnalyzingFrame = AtomicBoolean(false)
    private var captureSampleBitmap: Bitmap? = null

    private fun startAnalysisThread() {
        if (analysisThread == null) {
            analysisThread = HandlerThread("IdgafMonitorWorker").also { it.start() }
            analysisHandler = Handler(analysisThread!!.looper)
        }
    }

    private val displayRotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val curRot = getCurrentDisplayRotation()
            if (curRot != lastDisplayRotation) {
                lastDisplayRotation = curRot
                engine.setDisplayRotation(curRot)
            }
        }
    }

    private fun getCurrentDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun stopAnalysisThread() {
        uiHandler.removeCallbacks(analysisRunnable)
        analysisHandler?.removeCallbacksAndMessages(null)
        analysisThread?.quitSafely()
        try {
            analysisThread?.join(300)
        } catch (_: Exception) {
        }
        analysisThread = null
        analysisHandler = null
        isAnalyzingFrame.set(false)
        captureSampleBitmap = null
    }

    private val analysisRunnable = object : Runnable {
        override fun run() {
            if (!overlayView.needsHistogramAnalysis()) return
            if (textureView.isAvailable && isAnalyzingFrame.compareAndSet(false, true)) {
                val worker = analysisHandler
                if (worker == null) {
                    isAnalyzingFrame.set(false)
                } else {
                    try {
                        // Ultra-lightweight 64x36 sample ONLY for RGB+Luma Histogram (Peaking, Zebras & False Color run 100% on GPU at 60 FPS!)
                        val reusable = captureSampleBitmap?.takeIf { !it.isRecycled && it.width == 64 && it.height == 36 }
                            ?: Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).also { captureSampleBitmap = it }
                        textureView.getBitmap(reusable)
                        worker.post {
                            try {
                                if (!reusable.isRecycled) {
                                    overlayView.analyzeHistogramFrame(reusable)
                                }
                            } catch (_: Exception) {
                            } finally {
                                isAnalyzingFrame.set(false)
                            }
                        }
                    } catch (_: Exception) {
                        isAnalyzingFrame.set(false)
                    }
                }
            }
            uiHandler.postDelayed(this, 110L)
        }
    }

    private fun syncAnalysisLoop() {
        engine.updateGpuScopes(overlayView.toGpuScopeState())
        uiHandler.removeCallbacks(analysisRunnable)
        if (overlayView.needsHistogramAnalysis()) {
            startAnalysisThread()
            uiHandler.post(analysisRunnable)
        }
    }

    private val zoomRockerRunnable = object : Runnable {
        override fun run() {
            if (zoomRockerDir != 0) {
                val baseSpeed = zoomSpeedSteps[zoomSpeedIdx].second
                val cur = engine.currentZoomRatio
                val nextZoom = when (zoomCurve) {
                    ZoomCurve.LINEAR -> {
                        cur + (baseSpeed * zoomRockerDir)
                    }
                    ZoomCurve.EXPONENTIAL -> {
                        // Multiplicative zoom feels constant-speed across 1x -> 10x
                        val factor = 1.0f + (baseSpeed * 0.42f)
                        if (zoomRockerDir > 0) cur * factor else cur / factor
                    }
                    ZoomCurve.CRASH -> {
                        // Accelerates sharply the longer you hold for dramatic crash zooms
                        val heldSec = ((System.currentTimeMillis() - zoomHoldStartMs) / 1000f).coerceIn(0f, 1.5f)
                        val boost = 1.6f + (heldSec * 2.8f)
                        val factor = 1.0f + (baseSpeed * 0.45f * boost)
                        if (zoomRockerDir > 0) cur * factor else cur / factor
                    }
                }.coerceIn(engine.capabilities.minZoom, engine.capabilities.maxZoom)

                engine.setZoomRatio(nextZoom)
                updateZoomReadout()
                uiHandler.postDelayed(this, 16L)
            }
        }
    }

    private val horizonSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!overlayView.showHorizon) return
            val x = event.values[0]
            val y = event.values[1]
            val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: Surface.ROTATION_90
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            // Account for standard landscape (ROTATION_90) vs reverse landscape (ROTATION_270)
            val rawAngle = if (rot == Surface.ROTATION_270) {
                Math.toDegrees(kotlin.math.atan2((-y).toDouble(), (-x).toDouble())).toFloat()
            } else {
                Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat()
            }
            val clamped = rawAngle.coerceIn(-45f, 45f)
            smoothedRoll = smoothedRoll * 0.78f + clamped * 0.22f
            overlayView.horizonRollDegrees = smoothedRoll
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdownRemaining > 0) {
                txtTimerCountdown.text = countdownRemaining.toString()
                txtTimerCountdown.visibility = View.VISIBLE
                countdownRemaining--
                uiHandler.postDelayed(this, 1000L)
            } else {
                txtTimerCountdown.visibility = View.GONE
                beginRecordingCleanly(pendingRecRotation)
            }
        }
    }

    private val sysTelemetryRunnable = object : Runnable {
        override fun run() {
            updateSystemTelemetry()
            uiHandler.postDelayed(this, 10000L)
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
            openCameraIfReady(width, height)
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            engine.onPreviewSizeChanged(width, height)
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            engine.onPreviewSurfaceDestroyed()
            return true
        }
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
        btnAudioPanelToggle = findViewById(R.id.btnAudioPanelToggle)
        txtAudioDb = findViewById(R.id.txtAudioDb)
        audioControlDrawer = findViewById(R.id.audioControlDrawer)
        btnAudioMute = findViewById(R.id.btnAudioMute)
        btnPreampCam = findViewById(R.id.btnPreampCam)
        btnPreampRaw = findViewById(R.id.btnPreampRaw)
        btnPreampBoost = findViewById(R.id.btnPreampBoost)
        audioGainSeekBar = findViewById(R.id.audioGainSeekBar)
        txtAudioTrimVal = findViewById(R.id.txtAudioTrimVal)
        btnProSettingsToggle = findViewById(R.id.btnProSettingsToggle)
        proSettingsBar = findViewById(R.id.proSettingsBar)
        btnProCodec = findViewById(R.id.btnProCodec)
        btnProBitrate = findViewById(R.id.btnProBitrate)
        btnProMic = findViewById(R.id.btnProMic)
        btnProTone = findViewById(R.id.btnProTone)
        btnProStab = findViewById(R.id.btnProStab)
        btnProTorch = findViewById(R.id.btnProTorch)
        btnProTimer = findViewById(R.id.btnProTimer)
        btnProPresets = findViewById(R.id.btnProPresets)
        txtTimerCountdown = findViewById(R.id.txtTimerCountdown)
        txtSysTelemetry = findViewById(R.id.txtSysTelemetry)
        btnUpdateApp = findViewById(R.id.btnUpdateApp)
        presetStore = PresetStore(this)
        btnMonitorToggle = findViewById(R.id.btnMonitorToggle)
        monitorToolsBar = findViewById(R.id.monitorToolsBar)
        btnToolGrid = findViewById(R.id.btnToolGrid)
        btnToolFrame = findViewById(R.id.btnToolFrame)
        btnToolPeak = findViewById(R.id.btnToolPeak)
        btnToolZebra = findViewById(R.id.btnToolZebra)
        btnToolFalseColor = findViewById(R.id.btnToolFalseColor)
        btnToolHist = findViewById(R.id.btnToolHist)
        btnToolHorizon = findViewById(R.id.btnToolHorizon)
        btnToolMonConfig = findViewById(R.id.btnToolMonConfig)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        lastDisplayRotation = getCurrentDisplayRotation()
        txtTimecode = findViewById(R.id.txtTimecode)
        viewRecDot = findViewById(R.id.viewRecDot)
        btnResolution = findViewById(R.id.btnResolution)
        btnFps = findViewById(R.id.btnFps)
        lensRail = findViewById(R.id.lensRail)
        btnAutoPilot = findViewById(R.id.btnAutoPilot)
        btnShutterInner = findViewById(R.id.btnShutterInner)
        btnLastClip = findViewById(R.id.btnLastClip)
        btnZoomTele = findViewById(R.id.btnZoomTele)
        btnZoomWide = findViewById(R.id.btnZoomWide)
        txtZoomReadout = findViewById(R.id.txtZoomReadout)
        btnZoomSpeed = findViewById(R.id.btnZoomSpeed)
        zoomPopoutDrawer = findViewById(R.id.zoomPopoutDrawer)
        btnZoomPreset1x = findViewById(R.id.btnZoomPreset1x)
        btnZoomPreset2x = findViewById(R.id.btnZoomPreset2x)
        btnZoomPreset5x = findViewById(R.id.btnZoomPreset5x)
        btnZoomPresetMax = findViewById(R.id.btnZoomPresetMax)
        btnZoomCurve = findViewById(R.id.btnZoomCurve)
        btnZoomTime = findViewById(R.id.btnZoomTime)
        btnSetZoomA = findViewById(R.id.btnSetZoomA)
        btnSetZoomB = findViewById(R.id.btnSetZoomB)
        btnPullZoomAB = findViewById(R.id.btnPullZoomAB)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)

        // Pro Dock bindings
        pillEv = findViewById(R.id.pillEv)
        txtEvVal = findViewById(R.id.txtEvVal)
        pillIso = findViewById(R.id.pillIso)
        txtIsoVal = findViewById(R.id.txtIsoVal)
        pillShutter = findViewById(R.id.pillShutter)
        txtShutterVal = findViewById(R.id.txtShutterVal)
        pillWb = findViewById(R.id.pillWb)
        txtWbVal = findViewById(R.id.txtWbVal)
        pillFocus = findViewById(R.id.pillFocus)
        txtFocusVal = findViewById(R.id.txtFocusVal)
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
        wbGradientPanel = findViewById(R.id.wbGradientPanel)
        wbGradientPad = findViewById(R.id.wbGradientPad)
        btnWbPreset3200 = findViewById(R.id.btnWbPreset3200)
        btnWbPreset4300 = findViewById(R.id.btnWbPreset4300)
        btnWbPreset5600 = findViewById(R.id.btnWbPreset5600)
        btnWbPreset6500 = findViewById(R.id.btnWbPreset6500)
        txtWbTintVal = findViewById(R.id.txtWbTintVal)

        engine = CinemaCameraEngine(this, this)

        setupTopAndRightControls()
        setupProDockAndDrawer()
        setupMonitoringTools()
        setupProAudioAndCodecBar()
        setupCinemaZoomRocker()
        setupAudioControllerDrawer()

        findViewById<TextView>(R.id.btnBrandCredits).setOnClickListener {
            showStudioMogliCreditsDialog()
        }

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
                zoomAnimator?.cancel()
                val next = (engine.currentZoomRatio * detector.scaleFactor).coerceIn(
                    engine.capabilities.minZoom,
                    engine.capabilities.maxZoom
                )
                engine.setZoomRatio(next)
                updateZoomReadout()
                return true
            }
        })

        textureView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_DOWN && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                if (hasAnyOpenDrawer()) {
                    closeAllDrawers()
                } else {
                    val normX = event.x / v.width.coerceAtLeast(1)
                    val normY = event.y / v.height.coerceAtLeast(1)
                    val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        display?.rotation ?: Surface.ROTATION_90
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.rotation
                    }
                    if (engine.isAeAfLocked) {
                        showSingleToast("AE/AF Lock is ON — turn off LOCK to tap-focus")
                    } else {
                        engine.triggerTapToFocus(normX, normY, rot)
                        showFocusReticle(event.x, event.y)
                    }
                }
            }
            true
        }
    }

    private fun setupTopAndRightControls() {
        btnAutoPilot.setOnClickListener {
            if (engine.isRecording) {
                showSingleToast("Stop recording to toggle Auto Pilot")
                return@setOnClickListener
            }
            when (engine.autoPilotState) {
                AutoPilotState.OFF -> triggerAutoPilotRescue()
                AutoPilotState.METERING -> {
                    engine.disengageAutoPilot()
                    showSingleToast("Auto Pilot cancelled")
                }
                AutoPilotState.LOCKED -> {
                    engine.disengageAutoPilot()
                    showSingleToast("Auto Pilot OFF (Standard Auto)")
                }
            }
        }

        btnAutoPilot.setOnLongClickListener {
            if (engine.isRecording) {
                showSingleToast("Stop recording to re-meter Auto Pilot")
                return@setOnLongClickListener true
            }
            triggerAutoPilotRescue()
            true
        }

        findViewById<View>(R.id.btnShutterOuter).setOnClickListener {
            if (txtTimerCountdown.visibility == View.VISIBLE) {
                uiHandler.removeCallbacks(countdownRunnable)
                txtTimerCountdown.visibility = View.GONE
                Toast.makeText(this, "Timer cancelled", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (engine.isRecording) {
                engine.stopRecording()
            } else {
                val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    display?.rotation ?: Surface.ROTATION_90
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.rotation
                }
                val delaySec = timerOptions[timerIdx]
                if (delaySec > 0) {
                    pendingRecRotation = rot
                    countdownRemaining = delaySec
                    uiHandler.removeCallbacks(countdownRunnable)
                    uiHandler.post(countdownRunnable)
                } else {
                    beginRecordingCleanly(rot)
                }
            }
        }

        btnResolution.setOnClickListener {
            if (engine.isRecording) return@setOnClickListener
            showResolutionPickerDialog()
        }

        btnFps.setOnClickListener {
            if (engine.isRecording) return@setOnClickListener
            showFpsPickerDialog()
        }

        btnFps.setOnLongClickListener {
            if (!engine.isRecording) showCustomFpsInputDialog()
            true
        }

        btnLastClip.setOnClickListener {
            if (engine.isRecording) {
                Toast.makeText(this, "Stop recording before opening Clip Vault.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, ClipGalleryActivity::class.java))
        }
    }

    private fun setupMonitoringTools() {
        btnMonitorToggle.setOnClickListener {
            val showing = monitorToolsBar.visibility == View.VISIBLE
            if (!showing) closeTopDrawers(except = "MON")
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
            updateMonitorPillLabels()
            syncAnalysisLoop()
        }

        btnToolPeak.setOnLongClickListener {
            showPeakingConfigDialog()
            true
        }

        btnToolZebra.setOnClickListener {
            overlayView.showZebras = !overlayView.showZebras
            updateMonitorPillLabels()
            syncAnalysisLoop()
        }

        btnToolZebra.setOnLongClickListener {
            showZebraConfigDialog()
            true
        }

        btnToolFalseColor.setOnClickListener {
            overlayView.showFalseColor = !overlayView.showFalseColor
            styleToolPill(btnToolFalseColor, overlayView.showFalseColor)
            syncAnalysisLoop()
        }

        btnToolHist.setOnClickListener {
            overlayView.showHistogram = !overlayView.showHistogram
            styleToolPill(btnToolHist, overlayView.showHistogram)
            syncAnalysisLoop()
        }

        btnToolHorizon.setOnClickListener {
            overlayView.showHorizon = !overlayView.showHorizon
            styleToolPill(btnToolHorizon, overlayView.showHorizon)
            syncHorizonSensor()
        }

        btnToolMonConfig.setOnClickListener {
            showMonitorConfigDialog()
        }
    }

    private fun updateMonitorPillLabels() {
        btnToolPeak.text = if (overlayView.showPeaking) {
            "PEAK:${overlayView.peakingSensitivity.shortLabel}"
        } else {
            "PEAK"
        }
        styleToolPill(btnToolPeak, overlayView.showPeaking)

        btnToolZebra.text = if (overlayView.showZebras) {
            "ZEB:${overlayView.zebraMode.shortLabel}"
        } else {
            "ZEBRA"
        }
        styleToolPill(btnToolZebra, overlayView.showZebras)
    }

    private fun showMonitorConfigDialog() {
        val items = arrayOf(
            "Peaking Color: ${overlayView.peakingColor.label}",
            "Peaking Sensitivity: ${overlayView.peakingSensitivity.label}",
            "Zebra Level: ${overlayView.zebraMode.label}"
        )
        AlertDialog.Builder(this)
            .setTitle("Monitor Tools Configuration")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showPeakingColorDialog()
                    1 -> showPeakingSensitivityDialog()
                    2 -> showZebraConfigDialog()
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showPeakingConfigDialog() {
        val items = arrayOf(
            "Peaking Color (${overlayView.peakingColor.label})",
            "Peaking Sensitivity (${overlayView.peakingSensitivity.label})"
        )
        AlertDialog.Builder(this)
            .setTitle("Focus Peaking Settings")
            .setItems(items) { _, which ->
                if (which == 0) showPeakingColorDialog() else showPeakingSensitivityDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPeakingColorDialog() {
        val colors = PeakingColor.values()
        val labels = colors.map { c ->
            if (c == overlayView.peakingColor) "✓ ${c.label}" else c.label
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Focus Peaking Color")
            .setItems(labels) { _, which ->
                overlayView.peakingColor = colors[which]
                if (!overlayView.showPeaking) overlayView.showPeaking = true
                updateMonitorPillLabels()
                syncAnalysisLoop()
                showSingleToast("Peaking Color: ${colors[which].label}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPeakingSensitivityDialog() {
        val levels = PeakingSensitivity.values()
        val labels = levels.map { s ->
            if (s == overlayView.peakingSensitivity) "✓ ${s.label}" else s.label
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Focus Peaking Sensitivity")
            .setItems(labels) { _, which ->
                overlayView.peakingSensitivity = levels[which]
                if (!overlayView.showPeaking) overlayView.showPeaking = true
                updateMonitorPillLabels()
                syncAnalysisLoop()
                showSingleToast("Peaking Sensitivity: ${levels[which].label}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showZebraConfigDialog() {
        val modes = ZebraMode.values()
        val labels = modes.map { m ->
            if (m == overlayView.zebraMode) "✓ ${m.label}" else m.label
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Zebra IRE Threshold")
            .setItems(labels) { _, which ->
                overlayView.zebraMode = modes[which]
                if (!overlayView.showZebras) overlayView.showZebras = true
                updateMonitorPillLabels()
                syncAnalysisLoop()
                showSingleToast("Zebra Level: ${modes[which].label}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncHorizonSensor() {
        val sm = sensorManager ?: return
        sm.unregisterListener(horizonSensorListener)
        if (overlayView.showHorizon) {
            val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor != null) {
                sm.registerListener(horizonSensorListener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun hasAnyOpenDrawer(): Boolean {
        return activePanel != ProControlPanel.NONE ||
            zoomPopoutDrawer.visibility == View.VISIBLE ||
            audioControlDrawer.visibility == View.VISIBLE ||
            monitorToolsBar.visibility == View.VISIBLE ||
            proSettingsBar.visibility == View.VISIBLE
    }

    private fun closeTopDrawers(except: String? = null) {
        if (except != "MON" && monitorToolsBar.visibility == View.VISIBLE) {
            monitorToolsBar.visibility = View.GONE
            styleToolPill(btnMonitorToggle, false)
        }
        if (except != "PRO" && proSettingsBar.visibility == View.VISIBLE) {
            proSettingsBar.visibility = View.GONE
            styleToolPill(btnProSettingsToggle, false)
        }
        if (except != "AUDIO" && audioControlDrawer.visibility == View.VISIBLE) {
            audioControlDrawer.visibility = View.GONE
            btnAudioPanelToggle.setBackgroundResource(R.drawable.bg_hud_pill)
        }
    }

    private fun closeAllDrawers() {
        closeTopDrawers(except = null)
        selectPanel(ProControlPanel.NONE)
        zoomPopoutDrawer.visibility = View.GONE
    }

    private fun resetFocusAndZoomABPoints() {
        rackAnimator?.cancel()
        zoomAnimator?.cancel()
        focusPointA = null
        focusPointB = null
        rackGoingToB = true
        btnSetFocusA.setBackgroundResource(R.drawable.bg_hud_pill)
        btnSetFocusA.setTextColor(0xFFFFFFFF.toInt())
        btnSetFocusB.setBackgroundResource(R.drawable.bg_hud_pill)
        btnSetFocusB.setTextColor(0xFFFFFFFF.toInt())
        btnPullFocusAB.text = "PULL A→B"

        zoomPointA = null
        zoomPointB = null
        zoomGoingToB = true
        btnSetZoomA.setBackgroundResource(R.drawable.bg_hud_pill)
        btnSetZoomA.setTextColor(0xFFFFFFFF.toInt())
        btnSetZoomB.setBackgroundResource(R.drawable.bg_hud_pill)
        btnSetZoomB.setTextColor(0xFFFFFFFF.toInt())
        btnPullZoomAB.text = "A→B"
    }

    private fun styleToolPill(view: TextView, active: Boolean) {
        view.setBackgroundResource(if (active) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
        view.setTextColor(if (active) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun setupProAudioAndCodecBar() {
        btnProSettingsToggle.setOnClickListener {
            val showing = proSettingsBar.visibility == View.VISIBLE
            if (!showing) closeTopDrawers(except = "PRO")
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
            audioMonitorRestartNeeded = true
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

        btnProStab.setOnClickListener {
            val enabled = engine.toggleStabilization()
            styleToolPill(btnProStab, enabled)
            Toast.makeText(this, if (enabled) "Stabilization ON" else "Stabilization OFF", Toast.LENGTH_SHORT).show()
        }

        btnProTorch.setOnClickListener {
            if (!engine.capabilities.hasFlash) {
                Toast.makeText(this, "No LED flash on this lens", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val on = engine.toggleTorch()
            styleToolPill(btnProTorch, on)
        }

        btnProTimer.setOnClickListener {
            timerIdx = (timerIdx + 1) % timerOptions.size
            val sec = timerOptions[timerIdx]
            btnProTimer.text = if (sec == 0) "TMR: OFF" else "TMR: ${sec}s"
            styleToolPill(btnProTimer, sec > 0)
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
                    fps = currentFps,
                    codecName = engine.targetCodec.name,
                    bitrateMbps = engine.targetBitrateMbps,
                    micPrefName = engine.micPreference.name,
                    audioPreampModeName = engine.audioPreampMode.name,
                    isAudioMuted = engine.isAudioMuted,
                    isStabilizationEnabled = engine.isStabilizationEnabled,
                    toneProfileName = engine.toneProfile.name,
                    evCompensationIndex = engine.evCompensationIndex,
                    isManualExposure = engine.isManualExposure,
                    manualIso = engine.manualIso,
                    manualShutterNs = engine.manualShutterNs,
                    isShutter180Locked = engine.isShutter180Locked,
                    isManualWb = engine.isManualWb,
                    manualKelvin = engine.manualKelvin,
                    manualTint = engine.manualTint,
                    isManualFocus = engine.isManualFocus,
                    manualFocusDistance = engine.manualFocusDistance,
                    focusPullMs = focusPullDurationMs,
                    zoomCurveName = zoomCurve.name,
                    zoomPullDurationMs = zoomPullDurationMs,
                    gridModeName = overlayView.gridMode.name,
                    aspectGuideName = overlayView.aspectGuide.name,
                    peakingColorName = overlayView.peakingColor.name,
                    peakingSensitivityName = overlayView.peakingSensitivity.name,
                    zebraModeName = overlayView.zebraMode.name
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
        currentFps = p.fps.coerceIn(1, 60)
        engine.targetCodec = try { CinemaCodec.valueOf(p.codecName) } catch (_: Exception) { CinemaCodec.H264 }
        engine.targetBitrateMbps = p.bitrateMbps
        bitrateIdx = bitrateOptions.indexOf(p.bitrateMbps).takeIf { it >= 0 } ?: 1
        engine.micPreference = try { MicPreference.valueOf(p.micPrefName) } catch (_: Exception) { MicPreference.AUTO }
        micPrefIdx = MicPreference.values().indexOf(engine.micPreference).coerceAtLeast(0)
        val tone = try { ToneProfile.valueOf(p.toneProfileName) } catch (_: Exception) { ToneProfile.STANDARD }
        toneIdx = ToneProfile.values().indexOf(tone).coerceAtLeast(0)
        engine.setToneProfile(tone)

        engine.audioPreampMode = try { AudioPreampMode.valueOf(p.audioPreampModeName) } catch (_: Exception) { AudioPreampMode.CAMCORDER }
        engine.isAudioMuted = p.isAudioMuted
        audioMonitorRestartNeeded = true
        updateAudioDrawerUi()

        engine.setStabilizationEnabled(p.isStabilizationEnabled)
        styleToolPill(btnProStab, p.isStabilizationEnabled)

        if (p.isManualExposure) {
            engine.setManualIso(p.manualIso)
            engine.setManualShutterNs(p.manualShutterNs)
        } else {
            engine.setAutoExposure()
            engine.setEvCompensation(p.evCompensationIndex)
            updateEvDisplay()
        }
        if (p.isManualWb) {
            engine.setManualWhiteBalanceKelvin(p.manualKelvin)
            engine.setManualWhiteBalanceTint(p.manualTint)
        } else {
            engine.setAutoWhiteBalance()
        }
        if (p.isManualFocus) engine.setManualFocusDistance(p.manualFocusDistance) else engine.setAutoFocus()

        focusPullDurationMs = p.focusPullMs.coerceIn(300L, 10000L)
        btnPullTime.text = String.format(Locale.US, "%.1fs", focusPullDurationMs / 1000f)

        zoomCurve = try { ZoomCurve.valueOf(p.zoomCurveName) } catch (_: Exception) { ZoomCurve.EXPONENTIAL }
        btnZoomCurve.text = zoomCurve.label
        zoomPullDurationMs = p.zoomPullDurationMs.coerceIn(200L, 15000L)
        btnZoomTime.text = String.format(Locale.US, "%.1fs", zoomPullDurationMs / 1000f)

        overlayView.gridMode = try { GridMode.valueOf(p.gridModeName) } catch (_: Exception) { GridMode.OFF }
        gridModeIdx = GridMode.values().indexOf(overlayView.gridMode).coerceAtLeast(0)
        btnToolGrid.text = overlayView.gridMode.label
        styleToolPill(btnToolGrid, overlayView.gridMode != GridMode.OFF)

        overlayView.aspectGuide = try { AspectGuide.valueOf(p.aspectGuideName) } catch (_: Exception) { AspectGuide.OFF }
        frameGuideIdx = AspectGuide.values().indexOf(overlayView.aspectGuide).coerceAtLeast(0)
        btnToolFrame.text = "FRAME: ${overlayView.aspectGuide.label}"
        styleToolPill(btnToolFrame, overlayView.aspectGuide != AspectGuide.OFF)

        overlayView.peakingColor = try { PeakingColor.valueOf(p.peakingColorName) } catch (_: Exception) { PeakingColor.GREEN }
        overlayView.peakingSensitivity = try { PeakingSensitivity.valueOf(p.peakingSensitivityName) } catch (_: Exception) { PeakingSensitivity.MED }
        overlayView.zebraMode = try { ZebraMode.valueOf(p.zebraModeName) } catch (_: Exception) { ZebraMode.IRE_92 }
        updateMonitorPillLabels()
        syncAnalysisLoop()

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

    private fun updateSystemTelemetry() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
            txtSysTelemetry.text = String.format(Locale.US, "%d%% • %.1fGB", pct, freeGb)
            txtSysTelemetry.setTextColor(
                when {
                    pct <= 15 || freeGb < 1.0 -> 0xFFFF3B30.toInt()
                    pct <= 30 || freeGb < 3.0 -> 0xFFFFCC00.toInt()
                    else -> 0xFFB0B7C3.toInt()
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun showStudioMogliCreditsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_credits, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.txtCreditsVersion).text =
            "v${getCurrentVersionName()} • Community Cinema Engine"

        view.findViewById<TextView>(R.id.btnCreditsWebsite).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.studiomogli.com")))
            } catch (_: Exception) {
                Toast.makeText(this, "Visit www.studiomogli.com", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<TextView>(R.id.btnCreditsCheckUpdate).setOnClickListener {
            Toast.makeText(this, "Checking GitHub Releases...", Toast.LENGTH_SHORT).show()
            checkForGitHubUpdateSilently(manualCheck = true)
        }

        view.findViewById<TextView>(R.id.btnCreditsClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun checkForGitHubUpdateSilently(manualCheck: Boolean = false) {
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
                if (latestTag.isEmpty() || !isNewerVersion(latestTag, getCurrentVersionName())) {
                    if (manualCheck) {
                        runOnUiThread {
                            Toast.makeText(this, "You're on the latest version (v${getCurrentVersionName()})!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@Thread
                }

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
                            if (manualCheck) {
                                Toast.makeText(this, "Update $latestTag available! Tap UPDATE in top bar.", Toast.LENGTH_LONG).show()
                            }
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
        info.versionName ?: "0.7.0.2"
    } catch (_: Exception) { "0.7.0.2" }

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

    private fun setupAudioControllerDrawer() {
        btnAudioPanelToggle.setOnClickListener {
            val showing = audioControlDrawer.visibility == View.VISIBLE
            if (!showing) closeTopDrawers(except = "AUDIO")
            audioControlDrawer.visibility = if (showing) View.GONE else View.VISIBLE
            btnAudioPanelToggle.setBackgroundResource(if (!showing) R.drawable.bg_dock_pill_active else R.drawable.bg_hud_pill)
        }

        btnAudioMute.setOnClickListener {
            if (engine.isRecording) {
                showSingleToast("Stop recording to toggle Mute")
                return@setOnClickListener
            }
            engine.isAudioMuted = !engine.isAudioMuted
            updateAudioDrawerUi()
            showSingleToast(if (engine.isAudioMuted) "Audio MUTED (Video-Only Recording)" else "Audio LIVE")
        }

        btnPreampCam.setOnClickListener { selectAudioPreamp(AudioPreampMode.CAMCORDER) }
        btnPreampRaw.setOnClickListener { selectAudioPreamp(AudioPreampMode.RAW_UNPROCESSED) }
        btnPreampBoost.setOnClickListener { selectAudioPreamp(AudioPreampMode.VOICE_BOOST) }

        audioGainSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val db = progress - 12 // -12 dB .. +12 dB
                engine.audioMonitorTrimDb = db
                txtAudioTrimVal.text = if (db > 0) "+$db dB" else "$db dB"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        txtAudioTrimVal.setOnClickListener {
            engine.audioMonitorTrimDb = 0
            audioGainSeekBar.progress = 12
            txtAudioTrimVal.text = "0 dB"
        }

        updateAudioDrawerUi()
    }

    private fun selectAudioPreamp(mode: AudioPreampMode) {
        if (engine.isRecording) {
            showSingleToast("Stop recording to switch preamp stage")
            return
        }
        engine.audioPreampMode = mode
        audioMonitorRestartNeeded = true
        updateAudioDrawerUi()
        showSingleToast(
            when (mode) {
                AudioPreampMode.CAMCORDER -> "Preamp: CAMCORDER (Balanced)"
                AudioPreampMode.RAW_UNPROCESSED -> "Preamp: RAW UNPROCESSED (Zero AGC)"
                AudioPreampMode.VOICE_BOOST -> "Preamp: VOICE BOOST (High Gain)"
            }
        )
    }

    private fun updateAudioDrawerUi() {
        val muted = engine.isAudioMuted
        btnAudioMute.text = if (muted) "MUTED" else "LIVE"
        btnAudioMute.setBackgroundResource(if (muted) R.drawable.bg_shutter_stop else R.drawable.bg_hud_pill)
        btnAudioMute.setTextColor(if (muted) 0xFFFFFFFF.toInt() else 0xFF34C759.toInt())

        styleToolPill(btnPreampCam, engine.audioPreampMode == AudioPreampMode.CAMCORDER)
        styleToolPill(btnPreampRaw, engine.audioPreampMode == AudioPreampMode.RAW_UNPROCESSED)
        styleToolPill(btnPreampBoost, engine.audioPreampMode == AudioPreampMode.VOICE_BOOST)

        if (muted) {
            txtAudioDb.text = "MUTE"
            txtAudioDb.setTextColor(0xFFFF3B30.toInt())
            audioMeterView.updateLevel(0f)
        }
    }

    private fun dispatchAudioMeterUi(rawPeak: Int) {
        if (engine.isAudioMuted) {
            audioMeterView.updateLevel(0f)
            runOnUiThread {
                txtAudioDb.text = "MUTE"
                txtAudioDb.setTextColor(0xFFFF3B30.toInt())
            }
            return
        }
        val gainMul = Math.pow(10.0, engine.audioMonitorTrimDb / 20.0).toFloat()
        val adjusted = (rawPeak * gainMul).coerceAtLeast(0f)
        val norm = (adjusted / 24000f).coerceIn(0f, 1f)
        audioMeterView.updateLevel(norm)

        val dbfs = if (adjusted < 4f) -60f else (20.0 * kotlin.math.log10((adjusted / 32767.0).coerceIn(0.0001, 1.0))).toFloat()
        val label = when {
            dbfs >= -1.0f -> "CLIP"
            dbfs <= -55f -> "-∞dB"
            else -> String.format(Locale.US, "%ddB", dbfs.roundToInt())
        }
        val color = when {
            dbfs >= -1.0f -> 0xFFFF3B30.toInt()
            dbfs >= -9.0f -> 0xFFFFCC00.toInt()
            else -> 0xFF34C759.toInt()
        }
        runOnUiThread {
            txtAudioDb.text = label
            txtAudioDb.setTextColor(color)
        }
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
                if (engine.isRecording || engine.isAudioMuted || audioPreviewPausedForRec) {
                    if (ar != null) {
                        try { ar.stop(); ar.release() } catch (_: Exception) {}
                        ar = null
                    }
                    isPreviewMicHeld = false
                    val amp = if (engine.isRecording && !engine.isAudioMuted) engine.getRecordingMaxAmplitude() else 0
                    dispatchAudioMeterUi(amp)
                    try { Thread.sleep(60L) } catch (_: Exception) {}
                } else {
                    if (audioMonitorRestartNeeded && ar != null) {
                        try { ar.stop(); ar.release() } catch (_: Exception) {}
                        ar = null
                        isPreviewMicHeld = false
                        audioMonitorRestartNeeded = false
                    }
                    if (ar == null) {
                        try {
                            @SuppressLint("MissingPermission")
                            val rec = AudioRecord(
                                engine.audioPreampMode.audioSource,
                                sampleRate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                minBuf
                            )
                            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && engine.micPreference != MicPreference.INTERNAL) {
                                    engine.findExternalMicDevice()?.let { extMic ->
                                        try { rec.setPreferredDevice(extMic) } catch (_: Exception) {}
                                    }
                                }
                                rec.startRecording()
                                ar = rec
                                isPreviewMicHeld = true
                            } else {
                                rec.release()
                            }
                        } catch (_: Exception) {
                            isPreviewMicHeld = false
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
                            dispatchAudioMeterUi(peak)
                        }
                    }
                }
            }
            try { ar?.stop(); ar?.release() } catch (_: Exception) {}
            isPreviewMicHeld = false
        }.also { it.start() }
    }

    private fun beginRecordingCleanly(displayRotation: Int) {
        if (engine.isRecording) return
        // Release preview AudioRecord cleanly BEFORE MediaRecorder.prepare()/start() to avoid mic contention or audio delay!
        audioPreviewPausedForRec = true
        val waitStart = System.currentTimeMillis()
        while (isPreviewMicHeld && (System.currentTimeMillis() - waitStart) < 160L) {
            try { Thread.sleep(10L) } catch (_: Exception) { break }
        }
        engine.startRecording(displayRotation)
        if (!engine.isRecording) {
            audioPreviewPausedForRec = false
        }
    }

    private fun stopAudioVuMonitor() {
        audioMonitorRunning = false
        audioPreviewPausedForRec = false
        audioMonitorThread?.interrupt()
        audioMonitorThread = null
    }

    private fun showTapToTypeValueDialog() {
        val panel = activePanel
        if (panel == ProControlPanel.NONE) return

        val minEvStops = engine.capabilities.evRange.lower * engine.capabilities.evStep
        val maxEvStops = engine.capabilities.evRange.upper * engine.capabilities.evStep

        val (title, hint) = when (panel) {
            ProControlPanel.EV -> "Enter Exposure Comp (EV Stops)" to String.format(
                Locale.US,
                "e.g. %.1f to +%.1f (0 = Reset)",
                minEvStops,
                maxEvStops
            )
            ProControlPanel.ISO -> "Enter Exact ISO" to "e.g. 100, 400, 800, 3200, 16000"
            ProControlPanel.SHUTTER -> if (shutterInAngleMode) {
                "Enter Shutter Angle (Degrees)" to "11 to 360 (e.g. 180, 90, 270)"
            } else {
                "Enter Shutter Speed (1/X sec)" to "e.g. 48, 50, 60, 120, 500"
            }
            ProControlPanel.WB -> "Enter White Balance (Kelvin)" to "2500 to 8500 (e.g. 5600)"
            ProControlPanel.FOCUS -> "Enter Focus Distance (%)" to "0 (Infinity) to 100 (Macro)"
            ProControlPanel.NONE -> return
        }

        val input = EditText(this).apply {
            this.hint = hint
            inputType = if (panel == ProControlPanel.EV) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(36, 28, 36, 28)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                if (engine.isAeAfLocked && (panel == ProControlPanel.EV || panel == ProControlPanel.ISO || panel == ProControlPanel.SHUTTER)) {
                    showSingleToast("AE/AF Lock is ON — turn off LOCK first")
                    return@setPositiveButton
                }
                val rawText = input.text.toString().trim()
                if (panel == ProControlPanel.EV) {
                    val stops = rawText.toFloatOrNull()
                    if (stops == null) {
                        showSingleToast("Enter a valid EV stop (e.g. -1.5, 0, +1.0)")
                        return@setPositiveButton
                    }
                    val step = engine.capabilities.evStep.coerceAtLeast(0.01f)
                    val evIdx = (stops / step).roundToInt()
                    if (engine.setEvCompensation(evIdx)) {
                        updateEvDisplay()
                    } else {
                        showSingleToast("AE/AF Lock is ON — turn off LOCK first")
                    }
                    refreshSliderState()
                    return@setPositiveButton
                }

                val v = rawText.toIntOrNull()
                if (v == null || (v <= 0 && panel != ProControlPanel.FOCUS)) {
                    showSingleToast("Enter a valid number")
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
                    ProControlPanel.EV, ProControlPanel.NONE -> {}
                }
                refreshSliderState()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCinemaZoomRocker() {
        btnZoomTele.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomAnimator?.cancel()
                    zoomHoldStartMs = System.currentTimeMillis()
                    zoomRockerDir = 1
                    v.setBackgroundResource(R.drawable.bg_lens_active)
                    btnZoomTele.setTextColor(0xFF0B0D10.toInt())
                    uiHandler.removeCallbacks(zoomRockerRunnable)
                    uiHandler.post(zoomRockerRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomRockerDir = 0
                    v.setBackgroundResource(R.drawable.bg_hud_pill)
                    btnZoomTele.setTextColor(0xFFFFFFFF.toInt())
                    uiHandler.removeCallbacks(zoomRockerRunnable)
                }
            }
            true
        }

        btnZoomWide.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomAnimator?.cancel()
                    zoomHoldStartMs = System.currentTimeMillis()
                    zoomRockerDir = -1
                    v.setBackgroundResource(R.drawable.bg_lens_active)
                    btnZoomWide.setTextColor(0xFF0B0D10.toInt())
                    uiHandler.removeCallbacks(zoomRockerRunnable)
                    uiHandler.post(zoomRockerRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomRockerDir = 0
                    v.setBackgroundResource(R.drawable.bg_hud_pill)
                    btnZoomWide.setTextColor(0xFFFFFFFF.toInt())
                    uiHandler.removeCallbacks(zoomRockerRunnable)
                }
            }
            true
        }

        txtZoomReadout.setOnClickListener {
            val showing = zoomPopoutDrawer.visibility == View.VISIBLE
            zoomPopoutDrawer.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) updateZoomReadout()
        }

        btnZoomPreset1x.setOnClickListener { animateZoomTo(1.0f) }
        btnZoomPreset2x.setOnClickListener { animateZoomTo(2.0f) }
        btnZoomPreset5x.setOnClickListener { animateZoomTo(5.0f) }
        btnZoomPresetMax.setOnClickListener { animateZoomTo(engine.capabilities.maxZoom) }

        btnZoomCurve.setOnClickListener {
            val curves = ZoomCurve.values()
            val nextIdx = (curves.indexOf(zoomCurve) + 1) % curves.size
            zoomCurve = curves[nextIdx]
            btnZoomCurve.text = zoomCurve.label
            showSingleToast(
                when (zoomCurve) {
                    ZoomCurve.LINEAR -> "Zoom Curve: Linear"
                    ZoomCurve.EXPONENTIAL -> "Zoom Curve: Smooth Ease (Exp)"
                    ZoomCurve.CRASH -> "Zoom Curve: Crash Zoom"
                }
            )
        }

        btnZoomTime.setOnClickListener {
            val input = EditText(this).apply {
                hint = "Seconds (e.g. 0.4, 1.5, 4.0)"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format(Locale.US, "%.1f", zoomPullDurationMs / 1000f))
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF888888.toInt())
                setPadding(36, 28, 36, 28)
            }
            AlertDialog.Builder(this)
                .setTitle("Smooth Zoom Pull Duration (Seconds)")
                .setView(input)
                .setPositiveButton("Set") { _, _ ->
                    val secs = input.text.toString().trim().toFloatOrNull()
                    if (secs != null && secs in 0.2f..15.0f) {
                        zoomPullDurationMs = (secs * 1000f).toLong()
                        btnZoomTime.text = String.format(Locale.US, "%.1fs", secs)
                    } else {
                        showSingleToast("Enter 0.2 to 15.0 seconds")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnSetZoomA.setOnClickListener {
            zoomPointA = engine.currentZoomRatio
            btnSetZoomA.setBackgroundResource(R.drawable.bg_lens_active)
            btnSetZoomA.setTextColor(0xFF0B0D10.toInt())
            showSingleToast(String.format(Locale.US, "Zoom Point A Saved (%.1fx)", zoomPointA))
        }

        btnSetZoomB.setOnClickListener {
            zoomPointB = engine.currentZoomRatio
            btnSetZoomB.setBackgroundResource(R.drawable.bg_lens_active)
            btnSetZoomB.setTextColor(0xFF0B0D10.toInt())
            showSingleToast(String.format(Locale.US, "Zoom Point B Saved (%.1fx)", zoomPointB))
        }

        btnPullZoomAB.setOnClickListener {
            val a = zoomPointA
            val b = zoomPointB
            if (a == null || b == null) {
                showSingleToast("Set Zoom A and B first!")
                return@setOnClickListener
            }
            val target = if (zoomGoingToB) b else a
            zoomGoingToB = !zoomGoingToB
            btnPullZoomAB.text = if (zoomGoingToB) "B→A" else "A→B"
            animateZoomTo(target)
        }

        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressZoomSeek) return
                zoomAnimator?.cancel()
                val minZ = engine.capabilities.minZoom
                val maxZ = engine.capabilities.maxZoom.coerceAtLeast(minZ + 0.1f)
                val target = minZ + (maxZ - minZ) * (progress / 100f)
                engine.setZoomRatio(target)
                updateZoomReadout()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                zoomAnimator?.cancel()
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnZoomSpeed.setOnClickListener {
            zoomSpeedIdx = (zoomSpeedIdx + 1) % zoomSpeedSteps.size
            btnZoomSpeed.text = zoomSpeedSteps[zoomSpeedIdx].first
        }
    }

    private fun animateZoomTo(targetZoom: Float) {
        val minZ = engine.capabilities.minZoom
        val maxZ = engine.capabilities.maxZoom
        val start = engine.currentZoomRatio.coerceIn(minZ, maxZ)
        val end = targetZoom.coerceIn(minZ, maxZ)
        zoomAnimator?.cancel()

        if (kotlin.math.abs(end - start) < 0.02f) {
            engine.setZoomRatio(end)
            updateZoomReadout()
            return
        }

        val durMs = when (zoomCurve) {
            ZoomCurve.CRASH -> 280L
            else -> zoomPullDurationMs
        }

        // For EXPONENTIAL curve, interpolate in log-zoom space so optical speed feels silky smooth
        val useLogSpace = (zoomCurve == ZoomCurve.EXPONENTIAL || zoomCurve == ZoomCurve.CRASH) && start > 0.05f && end > 0.05f
        val animStart = if (useLogSpace) kotlin.math.ln(start.toDouble()).toFloat() else start
        val animEnd = if (useLogSpace) kotlin.math.ln(end.toDouble()).toFloat() else end

        zoomAnimator = ValueAnimator.ofFloat(animStart, animEnd).apply {
            duration = durMs
            interpolator = when (zoomCurve) {
                ZoomCurve.LINEAR -> LinearInterpolator()
                ZoomCurve.EXPONENTIAL -> AccelerateDecelerateInterpolator()
                ZoomCurve.CRASH -> DecelerateInterpolator(2.2f)
            }
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                val z = if (useLogSpace) kotlin.math.exp(v.toDouble()).toFloat() else v
                engine.setZoomRatio(z)
                updateZoomReadout()
            }
            start()
        }
    }

    private fun updateZoomReadout() {
        val z = engine.currentZoomRatio
        txtZoomReadout.text = String.format(Locale.US, "%.1fx", z)

        val minZ = engine.capabilities.minZoom
        val maxZ = engine.capabilities.maxZoom.coerceAtLeast(minZ + 0.1f)
        btnZoomPresetMax.text = String.format(Locale.US, "%.0fx", maxZ)
        btnZoomPreset5x.visibility = if (maxZ >= 4.5f) View.VISIBLE else View.GONE
        btnZoomPreset2x.visibility = if (maxZ >= 1.8f) View.VISIBLE else View.GONE

        suppressZoomSeek = true
        zoomSeekBar.progress = (((z - minZ) / (maxZ - minZ)) * 100f).roundToInt().coerceIn(0, 100)
        suppressZoomSeek = false

        styleToolPill(btnZoomPreset1x, kotlin.math.abs(z - 1.0f) < 0.15f)
        styleToolPill(btnZoomPreset2x, kotlin.math.abs(z - 2.0f) < 0.25f)
        styleToolPill(btnZoomPreset5x, kotlin.math.abs(z - 5.0f) < 0.35f)
        styleToolPill(btnZoomPresetMax, kotlin.math.abs(z - maxZ) < 0.35f && maxZ > 5.2f)
    }

    private fun showResolutionPickerDialog() {
        val allRes = CinemaResolution.values()
        val supported = engine.capabilities.supportedResolutions
        val labels = allRes.map { res ->
            val ok = res in supported
            val suffix = if (ok) " (${res.width}x${res.height})" else " — Not Supported"
            "${res.label}$suffix"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Video Resolution")
            .setItems(labels) { _, which ->
                val chosen = allRes[which]
                if (chosen !in supported) {
                    Toast.makeText(
                        this,
                        "${chosen.label} is not supported by this camera lens hardware.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setItems
                }
                currentResolution = chosen
                btnResolution.text = chosen.label
                val smartMbps = when (chosen) {
                    CinemaResolution.SD_480P -> 4
                    CinemaResolution.HD_720P -> 12
                    CinemaResolution.FHD_1080P -> 24
                    CinemaResolution.QHD_2K -> 50
                    CinemaResolution.UHD_4K -> 75
                }
                engine.targetBitrateMbps = smartMbps
                bitrateIdx = bitrateOptions.indexOf(smartMbps).takeIf { it >= 0 } ?: 3
                btnProBitrate.text = "$smartMbps Mbps"
                engine.updateSettings(currentResolution, currentFps)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFpsPickerDialog() {
        val options = arrayOf(
            "20 FPS",
            "24 FPS (Cinema)",
            "30 FPS (Standard)",
            "60 FPS (Smooth)",
            "Custom FPS (Type 1–60)..."
        )
        AlertDialog.Builder(this)
            .setTitle("Select Frame Rate (Current: $currentFps FPS)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applySelectedFps(20)
                    1 -> applySelectedFps(24)
                    2 -> applySelectedFps(30)
                    3 -> applySelectedFps(60)
                    4 -> showCustomFpsInputDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomFpsInputDialog() {
        val input = EditText(this).apply {
            hint = "Enter FPS (1 to 60, e.g. 1, 12, 24, 30, 60)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentFps.toString())
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(36, 28, 36, 28)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom Frame Rate (1–60 FPS)")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val typed = input.text.toString().trim().toIntOrNull()
                if (typed == null || typed !in 1..60) {
                    Toast.makeText(this, "Enter a value between 1 and 60 FPS", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applySelectedFps(typed)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applySelectedFps(fps: Int) {
        currentFps = fps.coerceIn(1, 60)
        btnFps.text = "$currentFps FPS"
        engine.updateSettings(currentResolution, currentFps)
    }

    private fun setupProDockAndDrawer() {
        pillEv.setOnClickListener {
            selectPanel(if (activePanel == ProControlPanel.EV) ProControlPanel.NONE else ProControlPanel.EV)
        }

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
                showSingleToast("This lens has fixed focus")
                return@setOnClickListener
            }
            selectPanel(if (activePanel == ProControlPanel.FOCUS) ProControlPanel.NONE else ProControlPanel.FOCUS)
        }
        btnAeAfLock.setOnClickListener {
            val locked = engine.toggleAeAfLock()
            updateLockUi(locked)
            Toast.makeText(this, if (locked) "AE / AF LOCKED" else "AE / AF UNLOCKED", Toast.LENGTH_SHORT).show()
        }

        txtSliderReadout.setOnClickListener {
            showTapToTypeValueDialog()
        }

        btnSliderAuto.setOnClickListener {
            if (engine.isAeAfLocked) {
                showSingleToast("AE/AF Lock is ON — turn off LOCK first")
                return@setOnClickListener
            }
            when (activePanel) {
                ProControlPanel.EV -> {
                    engine.setEvCompensation(0)
                    updateEvDisplay()
                }
                ProControlPanel.ISO, ProControlPanel.SHUTTER -> engine.setAutoExposure()
                ProControlPanel.FOCUS -> {
                    rackAnimator?.cancel()
                    engine.setAutoFocus()
                }
                ProControlPanel.WB -> engine.setAutoWhiteBalance()
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

        wbGradientPad.onDragStateChanged = { dragging ->
            isUserDraggingProSeek = dragging
        }
        wbGradientPad.onWbChanged = { kelvin, tint ->
            engine.setManualWhiteBalance(kelvin, tint)
            txtSliderReadout.text = "${kelvin}K"
            updateTintDisplay(tint)
            updateWbPresetHighlights(kelvin, isManual = true)
            updateAutoButtonVisual(isManual = true)
        }

        val applyWbPreset = { kelvin: Int ->
            engine.setManualWhiteBalance(kelvin, engine.manualTint)
            refreshSliderState()
        }
        btnWbPreset3200.setOnClickListener { applyWbPreset(3200) }
        btnWbPreset4300.setOnClickListener { applyWbPreset(4300) }
        btnWbPreset5600.setOnClickListener { applyWbPreset(5600) }
        btnWbPreset6500.setOnClickListener { applyWbPreset(6500) }

        txtWbTintVal.setOnClickListener {
            engine.setManualWhiteBalanceTint(0)
            refreshSliderState()
            showSingleToast("Tint reset to 0")
        }

        proSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressSeekCallback) return
                if (engine.isAeAfLocked && (activePanel == ProControlPanel.EV || activePanel == ProControlPanel.ISO || activePanel == ProControlPanel.SHUTTER)) {
                    showSingleToast("AE/AF Lock is ON — turn off LOCK first")
                    refreshSliderState()
                    return
                }
                when (activePanel) {
                    ProControlPanel.EV -> {
                        val minEv = engine.capabilities.evRange.lower
                        val maxEv = engine.capabilities.evRange.upper
                        val idx = minEv + progress
                        if (engine.setEvCompensation(idx.coerceIn(minEv, maxEv))) {
                            updateEvDisplay()
                            txtSliderReadout.text = formatEv(engine.getCurrentEvStops())
                            updateAutoButtonVisual(isManual = engine.evCompensationIndex != 0)
                        } else {
                            showSingleToast("AE/AF Lock is ON — turn off LOCK first")
                            refreshSliderState()
                        }
                    }
                    ProControlPanel.ISO -> {
                        val iso = progressToLogIso(progress)
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
                    ProControlPanel.NONE -> {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserDraggingProSeek = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserDraggingProSeek = false
            }
        })
    }

    private fun selectPanel(panel: ProControlPanel) {
        activePanel = panel
        val pills = listOf(
            pillEv to ProControlPanel.EV,
            pillIso to ProControlPanel.ISO,
            pillShutter to ProControlPanel.SHUTTER,
            pillWb to ProControlPanel.WB,
            pillFocus to ProControlPanel.FOCUS
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
        proSeekBar.visibility = if (panel == ProControlPanel.WB) View.GONE else View.VISIBLE
        wbGradientPanel.visibility = if (panel == ProControlPanel.WB) View.VISIBLE else View.GONE
        refreshSliderState()
    }

    private fun refreshSliderState() {
        suppressSeekCallback = true
        val live = lastTelemetry
        when (activePanel) {
            ProControlPanel.EV -> {
                txtSliderTitle.text = "EV COMP"
                val minEv = engine.capabilities.evRange.lower
                val maxEv = engine.capabilities.evRange.upper
                proSeekBar.max = (maxEv - minEv).coerceAtLeast(1)
                proSeekBar.progress = (engine.evCompensationIndex - minEv).coerceIn(0, proSeekBar.max)
                txtSliderReadout.text = formatEv(engine.getCurrentEvStops())
                updateAutoButtonVisual(isManual = engine.evCompensationIndex != 0)
            }
            ProControlPanel.ISO -> {
                txtSliderTitle.text = "ISO"
                proSeekBar.max = 100
                val shownIso = if (engine.isManualExposure) engine.manualIso else (live?.iso ?: engine.manualIso)
                proSeekBar.progress = logIsoToProgress(shownIso)
                txtSliderReadout.text = if (engine.isManualExposure) "ISO $shownIso" else "AUTO • $shownIso"
                updateAutoButtonVisual(engine.isManualExposure)
            }
            ProControlPanel.SHUTTER -> {
                val shownNs = if (engine.isManualExposure) engine.manualShutterNs else (live?.shutterNs ?: engine.manualShutterNs)
                if (shutterInAngleMode) {
                    txtSliderTitle.text = "ANGLE"
                    proSeekBar.max = shutterAngles.lastIndex
                    val curAng = engine.getCurrentShutterAngle()
                    val closestIdx = shutterAngles.indices.minByOrNull { kotlin.math.abs(shutterAngles[it] - curAng) } ?: 7
                    proSeekBar.progress = closestIdx
                    val angStr = String.format(Locale.US, "%.0f°", curAng)
                    txtSliderReadout.text = if (engine.isManualExposure) angStr else "AUTO • $angStr"
                } else {
                    txtSliderTitle.text = "SHUTTER"
                    proSeekBar.max = shutterDenoms.lastIndex
                    val curDenom = (1_000_000_000L / shownNs.coerceAtLeast(1L)).toInt()
                    val closestIdx = shutterDenoms.indices.minByOrNull { kotlin.math.abs(shutterDenoms[it] - curDenom) } ?: 4
                    proSeekBar.progress = closestIdx
                    txtSliderReadout.text = if (engine.isManualExposure) "1/${curDenom}s" else "AUTO • 1/${curDenom}s"
                }
                btnSlider180.setBackgroundResource(if (engine.isShutter180Locked) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                btnSlider180.setTextColor(if (engine.isShutter180Locked) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                updateAutoButtonVisual(engine.isManualExposure)
            }
            ProControlPanel.FOCUS -> {
                txtSliderTitle.text = "FOCUS"
                proSeekBar.max = 100
                val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
                if (engine.isManualFocus) {
                    proSeekBar.progress = ((engine.manualFocusDistance / maxF) * 100f).roundToInt().coerceIn(0, 100)
                    txtSliderReadout.text = formatFocus(engine.manualFocusDistance, maxF)
                } else {
                    // Keep AF-C slider parked calmly so micro-hunting never causes jittery jumps
                    proSeekBar.progress = ((engine.manualFocusDistance / maxF) * 100f).roundToInt().coerceIn(0, 100)
                    txtSliderReadout.text = "AF-C (AUTO)"
                }
                updateAutoButtonVisual(engine.isManualFocus)
            }
            ProControlPanel.WB -> {
                txtSliderTitle.text = "WHITE BAL"
                val shownK = if (engine.isManualWb) engine.manualKelvin else (live?.kelvin ?: engine.getLiveKelvin())
                val shownTint = if (engine.isManualWb) engine.manualTint else 0
                txtSliderReadout.text = if (engine.isManualWb) "${shownK}K" else "AWB • ${shownK}K"
                wbGradientPad.setState(shownK, shownTint, engine.isManualWb)
                updateTintDisplay(shownTint)
                updateWbPresetHighlights(shownK, engine.isManualWb)
                updateAutoButtonVisual(engine.isManualWb)
            }
            ProControlPanel.NONE -> {}
        }
        suppressSeekCallback = false
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

    private fun progressToLogIso(progress: Int): Int {
        val minIso = engine.capabilities.effectiveIsoRange.lower.coerceAtLeast(25).toDouble()
        val maxIso = engine.capabilities.effectiveIsoRange.upper.coerceAtLeast(minIso.toInt() + 1).toDouble()
        val raw = minIso * Math.pow(maxIso / minIso, progress.coerceIn(0, 100) / 100.0)
        // Round to clean steps for tactile feel
        return when {
            raw < 200.0 -> (raw / 5.0).roundToInt() * 5
            raw < 1000.0 -> (raw / 10.0).roundToInt() * 10
            else -> (raw / 25.0).roundToInt() * 25
        }.coerceIn(engine.capabilities.effectiveIsoRange.lower, engine.capabilities.effectiveIsoRange.upper)
    }

    private fun logIsoToProgress(iso: Int): Int {
        val minIso = engine.capabilities.effectiveIsoRange.lower.coerceAtLeast(25).toDouble()
        val maxIso = engine.capabilities.effectiveIsoRange.upper.coerceAtLeast(minIso.toInt() + 1).toDouble()
        val clamped = iso.toDouble().coerceIn(minIso, maxIso)
        val denom = kotlin.math.ln(maxIso / minIso).coerceAtLeast(0.001)
        return ((kotlin.math.ln(clamped / minIso) / denom) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun updateTintDisplay(tint: Int) {
        txtWbTintVal.text = when {
            tint > 0 -> "TINT +$tint"
            tint < 0 -> "TINT $tint"
            else -> "TINT 0"
        }
        txtWbTintVal.setTextColor(
            when {
                tint > 0 -> 0xFFE879F9.toInt() // Magenta
                tint < 0 -> 0xFF34C759.toInt() // Green
                else -> 0xFFFFFFFF.toInt()
            }
        )
    }

    private fun updateWbPresetHighlights(kelvin: Int, isManual: Boolean) {
        styleToolPill(btnWbPreset3200, isManual && kotlin.math.abs(kelvin - 3200) <= 100)
        styleToolPill(btnWbPreset4300, isManual && kotlin.math.abs(kelvin - 4300) <= 100)
        styleToolPill(btnWbPreset5600, isManual && kotlin.math.abs(kelvin - 5600) <= 100)
        styleToolPill(btnWbPreset6500, isManual && kotlin.math.abs(kelvin - 6500) <= 100)
    }

    private fun formatEv(stops: Float): String {
        return when {
            stops > 0.05f -> String.format(Locale.US, "+%.1f", stops)
            stops < -0.05f -> String.format(Locale.US, "%.1f", stops)
            else -> "0.0"
        }
    }

    private fun updateEvDisplay() {
        val stops = engine.getCurrentEvStops()
        txtEvVal.text = formatEv(stops)
        txtEvVal.setTextColor(if (engine.evCompensationIndex != 0 && !engine.isManualExposure) 0xFFFF5F1F.toInt() else 0xFFFFFFFF.toInt())
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

    private fun openCameraIfReady(surfaceW: Int = textureView.width, surfaceH: Int = textureView.height) {
        if (!hasPerm(Manifest.permission.CAMERA) || !hasPerm(Manifest.permission.RECORD_AUDIO)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            return
        }
        val st = textureView.surfaceTexture ?: return
        val w = if (surfaceW > 0) surfaceW else textureView.width.coerceAtLeast(1)
        val h = if (surfaceH > 0) surfaceH else textureView.height.coerceAtLeast(1)
        textureView.setTransform(Matrix())
        lastDisplayRotation = getCurrentDisplayRotation()
        engine.attachSurfaceAndOpen(
            texture = st,
            width = w,
            height = h,
            displayRotation = lastDisplayRotation
        )
        engine.updateGpuScopes(overlayView.toGpuScopeState())
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
        // CinemaGlRenderer handles exact aspect center-crop + rotation on the GPU; keep TextureView matrix identity
        textureView.setTransform(Matrix())
        engine.onPreviewSizeChanged(textureView.width, textureView.height)
        engine.setDisplayRotation(getCurrentDisplayRotation())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableImmersiveFullscreen()
        lastDisplayRotation = getCurrentDisplayRotation()
        textureView.post {
            engine.onPreviewSizeChanged(textureView.width, textureView.height)
            engine.setDisplayRotation(lastDisplayRotation)
        }
    }

    // ==================== CinemaEngineListener ====================

    override fun onLensesReady(lenses: List<CinemaLens>, activeIndex: Int) {
        runOnUiThread { renderLensRail(lenses, activeIndex) }
    }

    private fun renderLensRail(lenses: List<CinemaLens>, activeIndex: Int) {
        lensRail.removeAllViews()
        val active = lenses.getOrNull(activeIndex)

        // Clean Lens Switcher at top of left rail using focal length in mm
        val otherLens = if (active?.isFront == true) {
            lenses.firstOrNull { !it.isFront && it.eqFocalMm in 21..30 } ?: lenses.firstOrNull { !it.isFront }
        } else {
            lenses.firstOrNull { it.isFront }
        }
        val flipPill = TextView(this).apply {
            text = if (otherLens != null) "⇄ ${otherLens.label}" else "FLIP"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 10)
            setBackgroundResource(R.drawable.bg_hud_pill)
            setTextColor(0xFFFF5F1F.toInt())
            layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(36)).apply {
                bottomMargin = dpToPx(10)
            }
            setOnClickListener {
                if (!engine.isRecording) {
                    resetFocusAndZoomABPoints()
                    if (engine.toggleFrontBackCamera()) {
                        val newIdx = lenses.indexOf(engine.getActiveLens()).coerceAtLeast(0)
                        renderLensRail(lenses, newIdx)
                    }
                }
            }
        }
        lensRail.addView(flipPill)

        // Show physical back lenses (e.g. 0.5x / 1x / 2x) only if more than 1 back lens exists
        val backLenses = lenses.mapIndexedNotNull { idx, l -> if (!l.isFront) idx to l else null }
        if (backLenses.size > 1 && active?.isFront == false) {
            backLenses.forEach { (idx, lens) ->
                val pill = TextView(this).apply {
                    text = lens.label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(8, 10, 8, 10)
                    val isActive = idx == activeIndex
                    setBackgroundResource(if (isActive) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                    setTextColor(if (isActive) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(34)).apply {
                        bottomMargin = dpToPx(6)
                    }
                    setOnClickListener {
                        if (!engine.isRecording) {
                            resetFocusAndZoomABPoints()
                            engine.selectLens(idx)
                            renderLensRail(lenses, idx)
                        }
                    }
                }
                lensRail.addView(pill)
            }
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
            lastDisplayRotation = getCurrentDisplayRotation()
            configureTransform(previewSize)
            engine.updateGpuScopes(overlayView.toGpuScopeState())
            currentResolution = engine.targetResolution
            btnResolution.text = currentResolution.label
            btnFps.text = "$activeFps FPS"
            updateZoomReadout()
            updateEvDisplay()
            styleToolPill(btnProTorch, engine.isTorchEnabled)
            btnProTorch.alpha = if (caps.hasFlash) 1.0f else 0.4f
            styleToolPill(btnProStab, engine.isStabilizationEnabled)
            updateAutoPilotUi(engine.autoPilotState)
            pillFocus.alpha = if (caps.supportsManualFocus) 1.0f else 0.45f
            if (activePanel != ProControlPanel.NONE) refreshSliderState()
        }
    }

    override fun onTelemetryUpdated(telemetry: LiveTelemetry) {
        lastTelemetry = telemetry
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

            // Always show the live Kelvin number (white = AWB, Safety Orange = Manual WB)
            txtWbVal.text = "${telemetry.kelvin}K"
            txtWbVal.setTextColor(if (telemetry.isManualWb) orange else white)

            val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
            txtFocusVal.text = if (telemetry.isManualFocus) formatFocus(telemetry.focusDistance, maxF) else "AF-C"
            txtFocusVal.setTextColor(if (telemetry.isManualFocus) orange else white)

            // Live-sync the open Pro Slider thumb & readout when running in Auto mode
            if (activePanel != ProControlPanel.NONE && !isUserDraggingProSeek) {
                val shouldSync = when (activePanel) {
                    ProControlPanel.ISO, ProControlPanel.SHUTTER -> !telemetry.isManualExposure
                    ProControlPanel.WB -> !telemetry.isManualWb
                    ProControlPanel.FOCUS, ProControlPanel.EV, ProControlPanel.NONE -> false
                }
                if (shouldSync) {
                    refreshSliderState()
                }
            }
        }
    }

    private fun triggerAutoPilotRescue() {
        // 1. Clean UI & cancel competing animations or self-timer countdowns
        closeAllDrawers()
        rackAnimator?.cancel()
        zoomAnimator?.cancel()
        uiHandler.removeCallbacks(countdownRunnable)
        txtTimerCountdown.visibility = View.GONE
        timerIdx = 0
        btnProTimer.text = "TMR: OFF"
        styleToolPill(btnProTimer, false)

        // 2. Rescue Audio (unmute + balanced CAM preamp + 0 dB trim) when idle so sound is guaranteed live
        if (!engine.isRecording) {
            engine.isAudioMuted = false
            engine.audioPreampMode = AudioPreampMode.CAMCORDER
            engine.audioMonitorTrimDb = 0
            audioGainSeekBar.progress = 12
            txtAudioTrimVal.text = "0 dB"
            audioMonitorRestartNeeded = true
            updateAudioDrawerUi()
        }

        // 3. Rescue Tone & Stabilization pills + Lock & EV readouts
        toneIdx = 0
        btnProTone.text = ToneProfile.STANDARD.label
        styleToolPill(btnProTone, false)
        styleToolPill(btnProStab, true)
        updateLockUi(false)

        // 4. Launch two-stage scene meter & lock in Camera2 engine (does NOT start recording)
        engine.engageAutoPilot()
        updateEvDisplay()
        showSingleToast("Auto Pilot metering scene...")
    }

    private fun updateAutoPilotUi(state: AutoPilotState) {
        when (state) {
            AutoPilotState.OFF -> {
                btnAutoPilot.text = "PILOT"
                btnAutoPilot.setBackgroundResource(R.drawable.bg_hud_pill)
                btnAutoPilot.setTextColor(0xFF34C759.toInt())
            }
            AutoPilotState.METERING -> {
                btnAutoPilot.text = "SYNC…"
                btnAutoPilot.setBackgroundResource(R.drawable.bg_dock_pill_active)
                btnAutoPilot.setTextColor(0xFFFFCC00.toInt())
            }
            AutoPilotState.LOCKED -> {
                btnAutoPilot.text = "PILOT"
                btnAutoPilot.setBackgroundResource(R.drawable.bg_pilot_active)
                btnAutoPilot.setTextColor(0xFF0B0D10.toInt())
            }
        }
    }

    override fun onAutoPilotStateChanged(state: AutoPilotState, summary: String?) {
        runOnUiThread {
            updateAutoPilotUi(state)
            updateLockUi(engine.isAeAfLocked)
            updateEvDisplay()
            if (activePanel != ProControlPanel.NONE) refreshSliderState()
            if (state == AutoPilotState.LOCKED && !summary.isNullOrBlank()) {
                val audioTag = if (engine.isAudioMuted) "MUTED" else "Audio LIVE"
                showSingleToast("$summary • $audioTag", Toast.LENGTH_LONG)
            }
        }
    }

    override fun onRecordingStarted() {
        runOnUiThread {
            recStartMs = System.currentTimeMillis()
            viewRecDot.visibility = View.VISIBLE
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_stop)
            btnShutterInner.animate().scaleX(0.54f).scaleY(0.54f).setDuration(140L).start()
            txtTimecode.setTextColor(0xFFFF3B30.toInt())
            btnAutoPilot.alpha = 0.45f
            uiHandler.removeCallbacks(timecodeRunnable)
            uiHandler.post(timecodeRunnable)
        }
    }

    override fun onRecordingStopped(savedUri: Uri?, filePathLabel: String) {
        audioPreviewPausedForRec = false
        runOnUiThread {
            uiHandler.removeCallbacks(timecodeRunnable)
            viewRecDot.visibility = View.GONE
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_rec)
            btnShutterInner.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140L).start()
            txtTimecode.setTextColor(0xFFFFFFFF.toInt())
            btnAutoPilot.alpha = 1.0f
            txtTimecode.text = "00:00:00"
            if (savedUri != null) {
                lastSavedUri = savedUri
                btnLastClip.setTextColor(0xFFFF5F1F.toInt())
                Toast.makeText(this, "Saved: $filePathLabel", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onError(message: String) {
        if (!engine.isRecording) audioPreviewPausedForRec = false
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun showSingleToast(msg: String, length: Int = Toast.LENGTH_SHORT) {
        activeToast?.cancel()
        activeToast = Toast.makeText(this, msg, length)
        activeToast?.show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        enableImmersiveFullscreen()
        displayManager?.registerDisplayListener(displayRotationListener, uiHandler)
        engine.startThread()
        syncAnalysisLoop()
        syncHorizonSensor()
        startAudioVuMonitor()
        uiHandler.removeCallbacks(sysTelemetryRunnable)
        uiHandler.post(sysTelemetryRunnable)
        textureView.surfaceTextureListener = surfaceTextureListener
        if (textureView.isAvailable) {
            openCameraIfReady()
        }
    }

    override fun onPause() {
        displayManager?.unregisterDisplayListener(displayRotationListener)
        rackAnimator?.cancel()
        zoomAnimator?.cancel()
        uiHandler.removeCallbacks(timecodeRunnable)
        zoomRockerDir = 0
        uiHandler.removeCallbacks(zoomRockerRunnable)
        stopAnalysisThread()
        stopAudioVuMonitor()
        uiHandler.removeCallbacks(sysTelemetryRunnable)
        uiHandler.removeCallbacks(countdownRunnable)
        txtTimerCountdown.visibility = View.GONE
        sensorManager?.unregisterListener(horizonSensorListener)
        engine.stopThread()
        super.onPause()
    }
}
