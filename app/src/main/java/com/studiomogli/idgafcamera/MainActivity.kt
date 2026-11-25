package com.studiomogli.idgafcamera

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
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
import android.widget.ScrollView
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
enum class CameraWorkbench { SAVIOR, ERA }
enum class EraSliderPanel { NONE, IRIS, FOCUS }
enum class EraCameraId(val shaderCode: Int, val pillLabel: String, val fileTag: String) {
    SILENT_1920S(1, "1920s SILENT ▾", "1920s"),
    MONO_1950S(2, "1950s MONO 35mm ▾", "1950s")
}

data class SaviorWorkbenchSnapshot(
    val fps: Int,
    val timelapseIntervalMs: Long,
    val evCompensationIndex: Int,
    val isManualExposure: Boolean,
    val manualIso: Int,
    val manualShutterNs: Long,
    val isShutter180Locked: Boolean,
    val isManualWb: Boolean,
    val manualKelvin: Int,
    val manualTint: Int,
    val isManualFocus: Boolean,
    val manualFocusDistance: Float,
    val isAeAfLocked: Boolean,
    val zoomRatio: Float,
    val splitCompareActive: Boolean,
    val splitCompareFraction: Float
)

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
    private lateinit var btnProStab: TextView
    private lateinit var btnProTorch: TextView
    private lateinit var btnProTimer: TextView
    private lateinit var btnProTimelapse: TextView
    private lateinit var btnProMirrorFront: TextView
    private lateinit var btnProPresets: TextView

    // Phase 7.2A Live GPU Color Studio Drawer (+ v0.7.1.1 Peek-Through Grading Mode)
    private lateinit var btnColorPanelToggle: TextView
    private lateinit var colorControlDrawer: LinearLayout
    private lateinit var colorDrawerHeaderRow: LinearLayout
    private lateinit var txtColorDrawerTitle: TextView
    private lateinit var colorLooksPresetRow: LinearLayout
    private lateinit var colorLutBarRow: LinearLayout
    private lateinit var btnLutPrev: TextView
    private lateinit var btnSelectCubeLut: TextView
    private lateinit var btnLutNext: TextView
    private lateinit var btnImportCubeQuick: TextView
    private lateinit var scrollColorSliders: ScrollView
    private lateinit var rowColorLutMix: LinearLayout
    private lateinit var seekColorLutMix: SeekBar
    private lateinit var txtColorLutMixVal: TextView
    private lateinit var rowColorExp: LinearLayout
    private lateinit var rowColorCon: LinearLayout
    private lateinit var rowColorSat: LinearLayout
    private lateinit var rowColorShd: LinearLayout
    private lateinit var rowColorHil: LinearLayout
    private lateinit var rowColorVig: LinearLayout
    private lateinit var btnColorCompareAB: TextView
    private lateinit var btnColorSplitWipe: TextView
    private lateinit var btnExitSplitWipe: TextView
    private lateinit var btnColorReset: TextView
    private lateinit var btnColorClose: TextView
    private lateinit var btnLookNone: TextView
    private lateinit var btnLookLog: TextView
    private lateinit var btnLookCine: TextView
    private lateinit var btnLookPunch: TextView
    private lateinit var btnLookNoir: TextView
    private lateinit var seekColorExp: SeekBar
    private lateinit var txtColorExpVal: TextView
    private lateinit var seekColorCon: SeekBar
    private lateinit var txtColorConVal: TextView
    private lateinit var seekColorSat: SeekBar
    private lateinit var txtColorSatVal: TextView
    private lateinit var seekColorShd: SeekBar
    private lateinit var txtColorShdVal: TextView
    private lateinit var seekColorHil: SeekBar
    private lateinit var txtColorHilVal: TextView
    private lateinit var seekColorVig: SeekBar
    private lateinit var txtColorVigVal: TextView
    private var gpuColorState: GpuColorState = GpuColorState()
    private var activeLookTag: String = "NONE"
    private var activeLutSourceId: String = ""
    private var activeLutDisplayName: String = "NONE"
    private var suppressColorSeek: Boolean = false
    private var isSplitCompareActive: Boolean = false
    private var splitCompareFraction: Float = 0.50f
    private var isDraggingSplitLine: Boolean = false
    private var rawTouchDownRawX: Float = 0f
    private var rawSlideEngagedSplit: Boolean = false
    private lateinit var txtTimerCountdown: TextView
    private val timerOptions = listOf(0, 3, 5)
    private var timerIdx = 0
    private var countdownRemaining = 0
    private var pendingRecRotation = Surface.ROTATION_90
    private lateinit var btnBrandCredits: TextView
    private lateinit var txtSysTelemetry: TextView
    private lateinit var txtLowBatteryBanner: TextView
    private lateinit var txtEraBattery: TextView
    private var isBatteryCharging: Boolean = false
    private var lastBatteryPct: Int = 100
    private var lowBatteryWarned15: Boolean = false
    private var lowBatteryWarned7: Boolean = false
    private var lowBatteryBannerDismissed: Boolean = false
    private var batteryReceiverRegistered: Boolean = false
    private lateinit var presetStore: PresetStore
    private var updateDownloadUrl: String? = null
    private var updateLatestTag: String? = null
    private var gridModeIdx = 0
    private val bitrateOptions = listOf(4, 8, 12, 24, 35, 50, 75, 100, 120)
    private var bitrateIdx = 4
    private var activeToast: Toast? = null

    // Phase 6.8 / 7.1.1 Unified Pro Audio Controller Drawer (Mute + Mic Input + Stage + VU Trim)
    private lateinit var btnAudioPanelToggle: LinearLayout
    private lateinit var txtAudioDb: TextView
    private lateinit var audioControlDrawer: LinearLayout
    private lateinit var btnAudioMute: TextView
    private lateinit var btnAudioMicAuto: TextView
    private lateinit var btnAudioMicInt: TextView
    private lateinit var btnAudioMicExt: TextView
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

    // Phase 7.3 Workbench Switcher (SAVIOR / ERA) & Era 1 (1920s Silent 35mm) HUD
    private lateinit var saviorTopBar: LinearLayout
    private lateinit var saviorBottomDock: LinearLayout
    private lateinit var btnWorkbenchToEra: TextView
    private lateinit var zoomRockerContainer: LinearLayout
    private lateinit var eraTopBar: LinearLayout
    private lateinit var eraBottomDock: LinearLayout
    private lateinit var btnEraWorkbenchSwitch: TextView
    private lateinit var btnEraCameraPicker: TextView
    private lateinit var viewEraRecDot: View
    private lateinit var txtEraFootage: TextView
    private lateinit var txtEraAudioBadge: TextView
    private lateinit var btnEraTorch: TextView
    private lateinit var btnEraFlipLens: TextView
    private lateinit var eraSliderDrawer: LinearLayout
    private lateinit var txtEraSliderTitle: TextView
    private lateinit var txtEraSliderReadout: TextView
    private lateinit var btnEraLockExp: TextView
    private lateinit var btnEraSliderAuto: TextView
    private lateinit var eraAsaStockRow: LinearLayout
    private lateinit var eraSeekBar: SeekBar
    private lateinit var pillEraSpeed: LinearLayout
    private lateinit var txtEraSlot1Label: TextView
    private lateinit var txtEraSpeedVal: TextView
    private lateinit var pillEraTurret: LinearLayout
    private lateinit var txtEraTurretVal: TextView
    private lateinit var pillEraIris: LinearLayout
    private lateinit var txtEraIrisLabel: TextView
    private lateinit var txtEraIrisVal: TextView
    private lateinit var pillEraFocus: LinearLayout
    private lateinit var txtEraFocusVal: TextView
    private lateinit var pillEraPrint: LinearLayout
    private lateinit var txtEraPrintVal: TextView
    private var currentWorkbench: CameraWorkbench = CameraWorkbench.SAVIOR
    private var activeEraCamera: EraCameraId = EraCameraId.MONO_1950S
    private var activeEraPanel: EraSliderPanel = EraSliderPanel.NONE
    private var eraSilentFps: Int = 16
    private var era1950sFps: Int = 24
    private val era1950sTurretPrimes = listOf("28mm" to 1.0f, "35mm" to 1.35f, "50mm" to 1.95f)
    private var era1950sTurretIdx: Int = 1 // Starts on Satyajit Ray's iconic 35mm prime!
    private var eraArchiveWear: Boolean = false
    private var era1920sTintCode: Int = 0 // 0=SILVER, 1=AMBER, 2=COBALT, 3=SEPIA
    private var era1950sFilterCode: Int = 0 // 0=YEL K2, 1=CLEAR, 2=RED 25A, 3=GRN X1
    private var eraManualExposureActive: Boolean = false
    private var eraSelectedAsa: Int = 250
    private val eraManualShutterDenoms = listOf(16, 18, 24, 30, 32, 36, 48, 50, 60, 100, 120, 180, 250, 500, 1000, 2000)
    @Volatile private var eraMicMonitorPaused: Boolean = false
    private var suppressEraSeek: Boolean = false
    private var saviorSnapshot: SaviorWorkbenchSnapshot? = null

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

    private val batteryBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            updateSystemTelemetry(intent)
        }
    }

    private val sysTelemetryRunnable = object : Runnable {
        override fun run() {
            updateSystemTelemetry(null)
            uiHandler.postDelayed(this, 10000L)
        }
    }

    private val timecodeRunnable = object : Runnable {
        override fun run() {
            if (engine.isRecording) {
                val elapsedMs = System.currentTimeMillis() - recStartMs
                val totalSec = elapsedMs / 1000L
                if (currentWorkbench == CameraWorkbench.ERA) {
                    // Authentic 35mm 4-Perf Film Footage (16 frames/ft): 24 FPS = 1.5 ft/s (90 ft/min), 16 FPS = 1.0 ft/s
                    val activeEraFpsVal = if (activeEraCamera == EraCameraId.MONO_1950S) era1950sFps else eraSilentFps
                    val feet = ((elapsedMs / 1000.0) * (activeEraFpsVal / 16.0)).toInt().coerceAtLeast(0)
                    val mins = totalSec / 60L
                    val secs = totalSec % 60L
                    txtEraFootage.text = String.format(Locale.US, "%03d FT • %02d:%02d", feet, mins, secs)
                } else {
                    val hrs = totalSec / 3600L
                    val mins = (totalSec % 3600L) / 60L
                    val secs = totalSec % 60L
                    txtTimecode.text = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
                }
                uiHandler.postDelayed(this, 250L)
            }
        }
    }

    private val importCubeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importCubeLutFromUri(uri)
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
        btnAudioMicAuto = findViewById(R.id.btnAudioMicAuto)
        btnAudioMicInt = findViewById(R.id.btnAudioMicInt)
        btnAudioMicExt = findViewById(R.id.btnAudioMicExt)
        btnPreampCam = findViewById(R.id.btnPreampCam)
        btnPreampRaw = findViewById(R.id.btnPreampRaw)
        btnPreampBoost = findViewById(R.id.btnPreampBoost)
        audioGainSeekBar = findViewById(R.id.audioGainSeekBar)
        txtAudioTrimVal = findViewById(R.id.txtAudioTrimVal)
        btnProSettingsToggle = findViewById(R.id.btnProSettingsToggle)
        proSettingsBar = findViewById(R.id.proSettingsBar)
        btnProCodec = findViewById(R.id.btnProCodec)
        btnProBitrate = findViewById(R.id.btnProBitrate)
        btnProStab = findViewById(R.id.btnProStab)
        btnProTorch = findViewById(R.id.btnProTorch)
        btnProTimer = findViewById(R.id.btnProTimer)
        btnProTimelapse = findViewById(R.id.btnProTimelapse)
        btnProMirrorFront = findViewById(R.id.btnProMirrorFront)
        btnProPresets = findViewById(R.id.btnProPresets)
        btnColorPanelToggle = findViewById(R.id.btnColorPanelToggle)
        colorControlDrawer = findViewById(R.id.colorControlDrawer)
        colorDrawerHeaderRow = findViewById(R.id.colorDrawerHeaderRow)
        txtColorDrawerTitle = findViewById(R.id.txtColorDrawerTitle)
        colorLooksPresetRow = findViewById(R.id.colorLooksPresetRow)
        colorLutBarRow = findViewById(R.id.colorLutBarRow)
        btnLutPrev = findViewById(R.id.btnLutPrev)
        btnSelectCubeLut = findViewById(R.id.btnSelectCubeLut)
        btnLutNext = findViewById(R.id.btnLutNext)
        btnImportCubeQuick = findViewById(R.id.btnImportCubeQuick)
        scrollColorSliders = findViewById(R.id.scrollColorSliders)
        rowColorLutMix = findViewById(R.id.rowColorLutMix)
        seekColorLutMix = findViewById(R.id.seekColorLutMix)
        txtColorLutMixVal = findViewById(R.id.txtColorLutMixVal)
        rowColorExp = findViewById(R.id.rowColorExp)
        rowColorCon = findViewById(R.id.rowColorCon)
        rowColorSat = findViewById(R.id.rowColorSat)
        rowColorShd = findViewById(R.id.rowColorShd)
        rowColorHil = findViewById(R.id.rowColorHil)
        rowColorVig = findViewById(R.id.rowColorVig)
        btnColorCompareAB = findViewById(R.id.btnColorCompareAB)
        btnColorSplitWipe = findViewById(R.id.btnColorSplitWipe)
        btnExitSplitWipe = findViewById(R.id.btnExitSplitWipe)
        btnColorReset = findViewById(R.id.btnColorReset)
        btnColorClose = findViewById(R.id.btnColorClose)
        btnLookNone = findViewById(R.id.btnLookNone)
        btnLookLog = findViewById(R.id.btnLookLog)
        btnLookCine = findViewById(R.id.btnLookCine)
        btnLookPunch = findViewById(R.id.btnLookPunch)
        btnLookNoir = findViewById(R.id.btnLookNoir)
        seekColorExp = findViewById(R.id.seekColorExp)
        txtColorExpVal = findViewById(R.id.txtColorExpVal)
        seekColorCon = findViewById(R.id.seekColorCon)
        txtColorConVal = findViewById(R.id.txtColorConVal)
        seekColorSat = findViewById(R.id.seekColorSat)
        txtColorSatVal = findViewById(R.id.txtColorSatVal)
        seekColorShd = findViewById(R.id.seekColorShd)
        txtColorShdVal = findViewById(R.id.txtColorShdVal)
        seekColorHil = findViewById(R.id.seekColorHil)
        txtColorHilVal = findViewById(R.id.txtColorHilVal)
        seekColorVig = findViewById(R.id.seekColorVig)
        txtColorVigVal = findViewById(R.id.txtColorVigVal)
        txtTimerCountdown = findViewById(R.id.txtTimerCountdown)
        btnBrandCredits = findViewById(R.id.btnBrandCredits)
        txtSysTelemetry = findViewById(R.id.txtSysTelemetry)
        txtLowBatteryBanner = findViewById(R.id.txtLowBatteryBanner)
        txtEraBattery = findViewById(R.id.txtEraBattery)
        txtLowBatteryBanner.setOnClickListener {
            lowBatteryBannerDismissed = true
            txtLowBatteryBanner.visibility = View.GONE
        }
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
        saviorTopBar = findViewById(R.id.saviorTopBar)
        saviorBottomDock = findViewById(R.id.saviorBottomDock)
        btnWorkbenchToEra = findViewById(R.id.btnWorkbenchToEra)
        zoomRockerContainer = findViewById(R.id.zoomRockerContainer)
        eraTopBar = findViewById(R.id.eraTopBar)
        eraBottomDock = findViewById(R.id.eraBottomDock)
        btnEraWorkbenchSwitch = findViewById(R.id.btnEraWorkbenchSwitch)
        btnEraCameraPicker = findViewById(R.id.btnEraCameraPicker)
        viewEraRecDot = findViewById(R.id.viewEraRecDot)
        txtEraFootage = findViewById(R.id.txtEraFootage)
        txtEraAudioBadge = findViewById(R.id.txtEraAudioBadge)
        btnEraTorch = findViewById(R.id.btnEraTorch)
        btnEraFlipLens = findViewById(R.id.btnEraFlipLens)
        eraSliderDrawer = findViewById(R.id.eraSliderDrawer)
        txtEraSliderTitle = findViewById(R.id.txtEraSliderTitle)
        txtEraSliderReadout = findViewById(R.id.txtEraSliderReadout)
        btnEraLockExp = findViewById(R.id.btnEraLockExp)
        btnEraSliderAuto = findViewById(R.id.btnEraSliderAuto)
        eraAsaStockRow = findViewById(R.id.eraAsaStockRow)
        eraSeekBar = findViewById(R.id.eraSeekBar)
        pillEraSpeed = findViewById(R.id.pillEraSpeed)
        txtEraSlot1Label = findViewById(R.id.txtEraSlot1Label)
        txtEraSpeedVal = findViewById(R.id.txtEraSpeedVal)
        pillEraTurret = findViewById(R.id.pillEraTurret)
        txtEraTurretVal = findViewById(R.id.txtEraTurretVal)
        pillEraIris = findViewById(R.id.pillEraIris)
        txtEraIrisLabel = findViewById(R.id.txtEraIrisLabel)
        txtEraIrisVal = findViewById(R.id.txtEraIrisVal)
        pillEraFocus = findViewById(R.id.pillEraFocus)
        txtEraFocusVal = findViewById(R.id.txtEraFocusVal)
        pillEraPrint = findViewById(R.id.pillEraPrint)
        txtEraPrintVal = findViewById(R.id.txtEraPrintVal)

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
        setupColorStudioDrawer()
        setupEraWorkbench()

        btnBrandCredits.setOnClickListener {
            showStudioMogliCreditsDialog()
        }

        if (savedInstanceState == null) {
            checkForGitHubUpdateSilently()
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (currentWorkbench == CameraWorkbench.ERA) return false
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
            val vw = v.width.coerceAtLeast(1).toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isSplitCompareActive && event.pointerCount == 1) {
                        val dividerX = splitCompareFraction * vw
                        if (kotlin.math.abs(event.x - dividerX) <= dpToPx(44)) {
                            isDraggingSplitLine = true
                            updateSplitComparePosition((event.x / vw).coerceIn(0.04f, 0.96f))
                            return@setOnTouchListener true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingSplitLine) {
                        if (event.pointerCount == 1) {
                            updateSplitComparePosition((event.x / vw).coerceIn(0.04f, 0.96f))
                        } else {
                            isDraggingSplitLine = false
                        }
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingSplitLine) {
                        isDraggingSplitLine = false
                        return@setOnTouchListener true
                    }
                }
            }

            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                if (currentWorkbench == CameraWorkbench.ERA && activeEraPanel != EraSliderPanel.NONE) {
                    selectEraPanel(EraSliderPanel.NONE)
                } else if (currentWorkbench == CameraWorkbench.SAVIOR && hasAnyOpenDrawer()) {
                    closeAllDrawers()
                } else {
                    val normX = event.x / vw
                    val normY = event.y / v.height.coerceAtLeast(1)
                    val rot = getCurrentDisplayRotation()
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
            if (engine.isFinalizingClip) {
                showSingleToast("Developing 1950s Optical Mono track...")
                return@setOnClickListener
            }
            if (engine.isRecording) {
                if (currentWorkbench == CameraWorkbench.ERA && activeEraCamera == EraCameraId.MONO_1950S) {
                    uiHandler.removeCallbacks(timecodeRunnable)
                    txtEraFootage.text = "DEVELOPING..."
                    txtEraFootage.setTextColor(0xFFE8B968.toInt())
                }
                engine.stopRecording()
            } else {
                val rot = getCurrentDisplayRotation()
                if (currentWorkbench == CameraWorkbench.ERA) {
                    beginRecordingCleanly(rot)
                } else if (engine.isAudioMuted && !engine.isTimelapseEnabled()) {
                    AlertDialog.Builder(this)
                        .setTitle("Audio is MUTED")
                        .setMessage("Your microphone is currently muted. Record without sound, or unmute and start rolling?")
                        .setPositiveButton("UNMUTE & REC") { _, _ ->
                            engine.isAudioMuted = false
                            audioMonitorRestartNeeded = true
                            updateAudioDrawerUi()
                            launchRecordingOrTimer(rot)
                        }
                        .setNeutralButton("REC SILENT") { _, _ ->
                            launchRecordingOrTimer(rot)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    launchRecordingOrTimer(rot)
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
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast(if (engine.isFinalizingClip) "Developing clip — wait a moment..." else "Stop recording before opening Clip Vault.")
                return@setOnClickListener
            }
            startActivity(Intent(this, ClipGalleryActivity::class.java))
        }
    }

    private fun launchRecordingOrTimer(rot: Int) {
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

    // ==================== PHASE 7.3 / 7.4: 3-WORKBENCH SWITCHER (SAVIOR / CLASSIC / PURIST) & ERA 1 + ERA 2 ====================

    private fun setupEraWorkbench() {
        btnWorkbenchToEra.setOnClickListener {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to switch workbench")
                return@setOnClickListener
            }
            enterEraWorkbench()
        }

        btnWorkbenchToEra.setOnLongClickListener {
            if (!engine.isRecording && !engine.isFinalizingClip) showWorkbenchSwitcherDialog()
            true
        }

        btnEraWorkbenchSwitch.setOnClickListener {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to switch workbench")
                return@setOnClickListener
            }
            showWorkbenchSwitcherDialog()
        }

        btnEraWorkbenchSwitch.setOnLongClickListener {
            if (!engine.isRecording && !engine.isFinalizingClip) exitEraToSaviorWorkbench()
            true
        }

        btnEraCameraPicker.setOnClickListener {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to change Classic camera")
                return@setOnClickListener
            }
            showEraMuseumPickerDialog()
        }

        btnEraTorch.setOnClickListener {
            if (!engine.capabilities.hasFlash) {
                showSingleToast("No lamp / flash on this lens")
                return@setOnClickListener
            }
            val on = engine.toggleTorch()
            updateEraTorchUi()
            styleToolPill(btnProTorch, on)
            showSingleToast(if (on) "Studio Sun-Gun Lamp ON" else "Studio Sun-Gun Lamp OFF")
        }

        btnEraFlipLens.setOnClickListener {
            if (engine.isRecording || engine.isFinalizingClip) return@setOnClickListener
            if (engine.toggleFrontBackCamera()) {
                selectEraPanel(EraSliderPanel.NONE)
                showSingleToast("Switched classic lens glass")
            }
        }

        // Frame Rate Control (Strictly locked during recording in BOTH 1920s and 1950s)
        pillEraSpeed.setOnClickListener {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to change film speed")
                return@setOnClickListener
            }
            if (activeEraCamera == EraCameraId.SILENT_1920S) {
                show1920sFpsPickerDialog()
            } else {
                show1950sFpsPickerDialog()
            }
        }

        // Era 2 Rotating 3-Lens Prime Turret (28mm -> 35mm -> 50mm; locked while rolling so lens can't jump mid-take!)
        pillEraTurret.setOnClickListener {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to rotate prime turret")
                return@setOnClickListener
            }
            era1950sTurretIdx = (era1950sTurretIdx + 1) % era1950sTurretPrimes.size
            val (mmLabel, ratio) = era1950sTurretPrimes[era1950sTurretIdx]
            val clamped = ratio.coerceIn(engine.capabilities.minZoom, engine.capabilities.maxZoom)
            engine.setZoomRatio(clamped)
            txtEraTurretVal.text = mmLabel
            showSingleToast("Cooke Speed Panchro $mmLabel Prime Engaged")
        }

        // Mechanical Iris (Relative Aperture EV stops — live during recording for smooth exposure rides)
        pillEraIris.setOnClickListener {
            selectEraPanel(if (activeEraPanel == EraSliderPanel.IRIS) EraSliderPanel.NONE else EraSliderPanel.IRIS)
        }

        // Follow-Focus Ring (Manual Diopter + AF — live during recording for smooth focus pulls)
        pillEraFocus.setOnClickListener {
            if (!engine.capabilities.supportsManualFocus) {
                showSingleToast("This lens has fixed focus")
                return@setOnClickListener
            }
            selectEraPanel(if (activeEraPanel == EraSliderPanel.FOCUS) EraSliderPanel.NONE else EraSliderPanel.FOCUS)
        }

        // Print Stock Condition: Tap = FRESH <-> ARCHIVE; Long-Press = Period Glass Filter / Tint Bath (Locked while rolling!)
        pillEraPrint.setOnClickListener {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to change film print stock")
                return@setOnClickListener
            }
            eraArchiveWear = !eraArchiveWear
            updateEraPrintPillLabel()
            val activeFpsVal = if (activeEraCamera == EraCameraId.MONO_1950S) era1950sFps.toFloat() else eraSilentFps.toFloat()
            val activeFilter = if (activeEraCamera == EraCameraId.MONO_1950S) era1950sFilterCode else era1920sTintCode
            engine.setEraRenderState(activeEraCamera.shaderCode, if (eraArchiveWear) 1.0f else 0.0f, activeFpsVal, activeFilter)
            val msg = if (activeEraCamera == EraCameraId.MONO_1950S) {
                if (eraArchiveWear) "ARCHIVE — 70-Yr Acetate Vault Print (Hold PRINT for Glass Filters)"
                else "FRESH — 1955 Silver-Gelatin Premiere Print (Hold PRINT for Glass Filters)"
            } else {
                if (eraArchiveWear) "ARCHIVE — 100-Yr Nitrate Reel (Hold PRINT for Tint Baths)"
                else "FRESH — 1920s Premiere Nitrate (Hold PRINT for Tint Baths)"
            }
            showSingleToast(msg, Toast.LENGTH_LONG)
        }

        pillEraPrint.setOnLongClickListener {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to change lens filter / bath")
                return@setOnLongClickListener true
            }
            if (activeEraCamera == EraCameraId.MONO_1950S) {
                show1950sGlassFilterDialog()
            } else {
                show1920sTintBathDialog()
            }
            true
        }

        // Tap readout in ASA/IRIS drawer to type ANY exact manual ASA/ISO!
        txtEraSliderReadout.setOnClickListener {
            if (activeEraPanel == EraSliderPanel.IRIS) {
                showEraCustomAsaDialog()
            }
        }

        // 1-Tap LOCK 180°: snaps current metered ISO + true 180-deg rotary shutter (stops Android Auto-ISO glitches immediately!)
        btnEraLockExp.setOnClickListener {
            if (!engine.capabilities.supportsManualExposure) {
                showSingleToast("This lens does not support manual ASA/shutter lock")
                return@setOnClickListener
            }
            val activeFpsVal = if (activeEraCamera == EraCameraId.MONO_1950S) era1950sFps else eraSilentFps
            val rotary180Ns = 1_000_000_000L / (activeFpsVal * 2L).coerceAtLeast(16L)
            val targetIso = if (eraManualExposureActive) {
                eraSelectedAsa
            } else {
                // Preserve real scene brightness when snapping from Auto -> Locked 180° rotary shutter!
                val curIso = engine.getLiveSensorIso().coerceAtLeast(50)
                val curExp = engine.getLiveSensorShutterNs().coerceAtLeast(100_000L)
                ((curIso.toDouble() * curExp.toDouble()) / rotary180Ns.toDouble()).roundToInt()
                    .coerceIn(engine.capabilities.effectiveIsoRange.lower, engine.capabilities.effectiveIsoRange.upper)
            }
            applyEraManualStock(targetIso, rotary180Ns, announce = true)
        }

        btnEraSliderAuto.setOnClickListener {
            when (activeEraPanel) {
                EraSliderPanel.IRIS -> {
                    eraManualExposureActive = false
                    engine.setAutoExposure()
                    engine.setEvCompensation(0)
                    pushEraGrainForCurrentAsa()
                    updateEraIrisUi()
                    showSingleToast("Classic Meter: AUTO EV")
                }
                EraSliderPanel.FOCUS -> {
                    engine.setAutoFocus()
                    updateEraFocusUi()
                }
                EraSliderPanel.NONE -> {}
            }
            refreshEraSliderUi()
        }

        eraSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressEraSeek) return
                when (activeEraPanel) {
                    EraSliderPanel.IRIS -> {
                        if (eraManualExposureActive && engine.capabilities.supportsManualExposure) {
                            // Manual Locked ASA: slider smoothly rides mechanical rotary shutter (brighter left -> darker right, or darker left -> brighter right)
                            val denoms = eraManualShutterDenoms
                            // Progress 0 = brightest (1/16s slow shutter), Progress max = darkest (1/2000s fast shutter) — wait: brighter to the right feels more natural!
                            val invIdx = (denoms.lastIndex - progress).coerceIn(0, denoms.lastIndex)
                            val denom = denoms[invIdx]
                            val ns = 1_000_000_000L / denom.toLong()
                            engine.lockEraManualExposure(eraSelectedAsa, ns)
                            updateEraIrisUi()
                            txtEraSliderReadout.text = "ASA ${engine.manualIso} • 1/${denom}s"
                        } else {
                            val minEv = engine.capabilities.evRange.lower
                            val maxEv = engine.capabilities.evRange.upper
                            val idx = (minEv + progress).coerceIn(minEv, maxEv)
                            engine.setEvCompensation(idx)
                            updateEraIrisUi()
                            txtEraSliderReadout.text = "AUTO • ${formatEraIrisFStop(engine.getCurrentEvStops())}"
                        }
                    }
                    EraSliderPanel.FOCUS -> {
                        val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
                        val diopters = (progress / 100f) * maxF
                        engine.setManualFocusDistance(diopters)
                        updateEraFocusUi()
                        txtEraSliderReadout.text = formatFocus(diopters, maxF)
                    }
                    EraSliderPanel.NONE -> {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun showWorkbenchSwitcherDialog() {
        val items = arrayOf(
            (if (currentWorkbench == CameraWorkbench.SAVIOR) "✓ " else "") + "SAVIOR — Modern Digital Cinema Rig",
            (if (currentWorkbench == CameraWorkbench.ERA) "✓ " else "") + "CLASSIC — Vintage Camera Vault (1920s / 1950s)",
            "PURIST — Gate 65 Discipline (Coming in Phase 8)"
        )
        AlertDialog.Builder(this)
            .setTitle("Switch Camera Workbench")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exitEraToSaviorWorkbench()
                    1 -> enterEraWorkbench()
                    2 -> showSingleToast("PURIST (Gate 65) arrives in Phase 8 — stay tuned!", Toast.LENGTH_LONG)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEraMuseumPickerDialog() {
        val items = arrayOf(
            (if (activeEraCamera == EraCameraId.SILENT_1920S) "✓ " else "") + "1920s Classic Silent — 35mm Orthochromatic (16/18/20 FPS • Silent)",
            (if (activeEraCamera == EraCameraId.MONO_1950S) "✓ " else "") + "1950s Classic Monochrome — Satyajit Ray 35mm (24 FPS • Optical Mono)",
            "1970s Indie Celluloid — Super 8 & 16mm (Next in v0.7.5)",
            "1980s–90s Analog Tape — VHS Camcorder (Coming in v0.7.6)",
            "2000s Classic CCD — MiniDV Handycam (Coming in v0.7.6)",
            "2008 First-Gen HD — Early DSLR Cinema (Coming in v0.7.6)"
        )
        AlertDialog.Builder(this)
            .setTitle("Classic Camera Vault")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> switchActiveEraCamera(EraCameraId.SILENT_1920S)
                    1 -> switchActiveEraCamera(EraCameraId.MONO_1950S)
                    else -> showSingleToast("Building each classic camera one by one with original detail — Super 8 arrives next in v0.7.5!", Toast.LENGTH_LONG)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun switchActiveEraCamera(target: EraCameraId) {
        activeEraCamera = target
        selectEraPanel(EraSliderPanel.NONE)
        applyActiveEraCamera(announce = true)
    }

    private fun applyActiveEraCamera(announce: Boolean) {
        btnEraCameraPicker.text = activeEraCamera.pillLabel
        txtEraFootage.text = "000 FT • 00:00"
        txtEraFootage.setTextColor(0xFFF5E6C8.toInt())
        viewEraRecDot.visibility = View.GONE

        updateEraPrintPillLabel()

        if (activeEraCamera == EraCameraId.SILENT_1920S) {
            txtEraSlot1Label.text = "CRANK"
            txtEraSpeedVal.text = "$eraSilentFps FPS"
            pillEraTurret.visibility = View.GONE
            txtEraAudioBadge.text = "SILENT"
            txtEraAudioBadge.setTextColor(0xFFB8A37E.toInt())

            engine.resetZoom()
            engine.eraSilentAudio = true
            engine.eraAudioProfile = EraAudioProfile.NONE
            engine.eraFilenameTag = EraCameraId.SILENT_1920S.fileTag
            engine.setTargetFpsSeamless(eraSilentFps)
            engine.setEraRenderState(
                EraCameraId.SILENT_1920S.shaderCode,
                if (eraArchiveWear) 1.0f else 0.0f,
                eraSilentFps.toFloat(),
                era1920sTintCode
            )
            if (announce) {
                showSingleToast(
                    "1920s CLASSIC SILENT • $eraSilentFps FPS • ${if (eraArchiveWear) "ARCHIVE" else "FRESH"} (Hold PRINT for Tint Baths)",
                    Toast.LENGTH_LONG
                )
            }
        } else {
            // Era 2: 1950s Classic Monochrome (Satyajit Ray / Arriflex 35 — 1.375:1, FPS + 3-Lens Prime Turret + Optical Mono)
            txtEraSlot1Label.text = "FPS"
            txtEraSpeedVal.text = "$era1950sFps FPS"
            pillEraTurret.visibility = View.VISIBLE
            val (mmLabel, ratio) = era1950sTurretPrimes[era1950sTurretIdx]
            txtEraTurretVal.text = mmLabel
            txtEraAudioBadge.text = "OPTICAL MONO"
            txtEraAudioBadge.setTextColor(0xFFE8B968.toInt())

            val clamped = ratio.coerceIn(engine.capabilities.minZoom, engine.capabilities.maxZoom)
            engine.setZoomRatio(clamped)
            engine.eraSilentAudio = false
            engine.eraAudioProfile = EraAudioProfile.OPTICAL_1950S
            engine.eraFilenameTag = EraCameraId.MONO_1950S.fileTag
            engine.setTargetFpsSeamless(era1950sFps)
            engine.setEraRenderState(
                EraCameraId.MONO_1950S.shaderCode,
                if (eraArchiveWear) 1.0f else 0.0f,
                era1950sFps.toFloat(),
                era1950sFilterCode
            )
            if (announce) {
                showSingleToast(
                    "1950s CLASSIC MONO • $era1950sFps FPS • $mmLabel Prime • Optical Mono (Hold PRINT for Filters)",
                    Toast.LENGTH_LONG
                )
            }
        }
        updateEraRecordingLockVisuals(engine.isRecording)
        updateEraTorchUi()
        pushEraGrainForCurrentAsa()
        updateEraIrisUi()
        updateEraFocusUi()
    }

    private fun updateEraPrintPillLabel() {
        val base = if (eraArchiveWear) "ARCHIVE" else "FRESH"
        val badge = if (activeEraCamera == EraCameraId.MONO_1950S) {
            when (era1950sFilterCode) {
                1 -> "CLR"
                2 -> "R25"
                3 -> "G11"
                else -> ""
            }
        } else {
            when (era1920sTintCode) {
                1 -> "AMB"
                2 -> "BLU"
                3 -> "SEP"
                else -> ""
            }
        }
        txtEraPrintVal.text = if (badge.isEmpty()) base else "$base·$badge"
        pillEraPrint.setBackgroundResource(if (eraArchiveWear) R.drawable.bg_era_pill_active else R.drawable.bg_era_pill)
        txtEraPrintVal.setTextColor(if (eraArchiveWear) 0xFF141210.toInt() else 0xFFF5E6C8.toInt())
    }

    private fun show1920sFpsPickerDialog() {
        val speeds = listOf(
            16 to "16 FPS — Early Silent Hand-Crank (1.00 ft/s)",
            18 to "18 FPS — Classic 1920s Silent Standard (1.13 ft/s)",
            20 to "20 FPS — Late 1920s Cinema Palace (1.25 ft/s)"
        )
        val labels = speeds.map { (fps, desc) ->
            if (fps == eraSilentFps) "✓ $desc" else desc
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("1920s Hand-Crank Film Speed")
            .setItems(labels) { _, which ->
                val chosen = speeds[which].first
                eraSilentFps = chosen
                txtEraSpeedVal.text = "$eraSilentFps FPS"
                engine.setTargetFpsSeamless(eraSilentFps)
                engine.setEraRenderState(
                    EraCameraId.SILENT_1920S.shaderCode,
                    if (eraArchiveWear) 1.0f else 0.0f,
                    eraSilentFps.toFloat(),
                    era1920sTintCode
                )
                showSingleToast("1920s Crank: $eraSilentFps FPS (${String.format(Locale.US, "%.2f", eraSilentFps / 16f)} ft/s)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun show1950sFpsPickerDialog() {
        val speeds = listOf(
            18 to "18 FPS — Arriflex Undercrank (Brisk Action)",
            24 to "24 FPS — 1950s Sync Sound Standard (Satyajit Ray)",
            25 to "25 FPS — 50Hz Studio / Broadcast Telecine",
            30 to "30 FPS — 1955 Todd-AO / TV Kinescope"
        )
        val labels = speeds.map { (fps, desc) ->
            if (fps == era1950sFps) "✓ $desc" else desc
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("1950s 35mm Film Speed")
            .setItems(labels) { _, which ->
                val chosen = speeds[which].first
                val clamped = chosen.coerceIn(1, engine.capabilities.maxHardwareFps.coerceIn(15, 60))
                era1950sFps = clamped
                txtEraSpeedVal.text = "$era1950sFps FPS"
                engine.setTargetFpsSeamless(era1950sFps)
                engine.setEraRenderState(
                    EraCameraId.MONO_1950S.shaderCode,
                    if (eraArchiveWear) 1.0f else 0.0f,
                    era1950sFps.toFloat(),
                    era1950sFilterCode
                )
                showSingleToast("1950s Speed: $era1950sFps FPS (${String.format(Locale.US, "%.2f", era1950sFps / 16f)} ft/s)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun show1950sGlassFilterDialog() {
        val filters = listOf(
            0 to "Yellow K2 (#8) — Classic Satyajit Ray Contrast (Default)",
            1 to "Clear Glass — Natural Kodak Double-X Panchromatic",
            2 to "Red 25A — Deep Black Skies & Dramatic Film Noir",
            3 to "Green X1 (#11) — Sculptural Portraits & Rich Foliage"
        )
        val labels = filters.map { (code, desc) ->
            if (code == era1950sFilterCode) "✓ $desc" else desc
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("1950s Screw-On Glass Contrast Filter")
            .setItems(labels) { _, which ->
                era1950sFilterCode = filters[which].first
                updateEraPrintPillLabel()
                engine.setEraRenderState(
                    EraCameraId.MONO_1950S.shaderCode,
                    if (eraArchiveWear) 1.0f else 0.0f,
                    era1950sFps.toFloat(),
                    era1950sFilterCode
                )
                showSingleToast("1950s Lens Filter: ${filters[which].second.substringBefore(" —")}")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun show1920sTintBathDialog() {
        val baths = listOf(
            0 to "Silver-Gelatin — Pure B&W Nitrate (Default)",
            1 to "Amber Dye Bath — Warm Candlelight / Interior",
            2 to "Cobalt Blue Bath — 1920s Silent Night / Moonlight",
            3 to "Sepia Dye Bath — Deep Archival Warm Nitrate"
        )
        val labels = baths.map { (code, desc) ->
            if (code == era1920sTintCode) "✓ $desc" else desc
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("1920s Nitrate Lab Tint Bath")
            .setItems(labels) { _, which ->
                era1920sTintCode = baths[which].first
                updateEraPrintPillLabel()
                engine.setEraRenderState(
                    EraCameraId.SILENT_1920S.shaderCode,
                    if (eraArchiveWear) 1.0f else 0.0f,
                    eraSilentFps.toFloat(),
                    era1920sTintCode
                )
                showSingleToast("1920s Lab Bath: ${baths[which].second.substringBefore(" —")}")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateEraRecordingLockVisuals(rolling: Boolean) {
        val lockAlpha = if (rolling) 0.42f else 1.0f
        pillEraSpeed.alpha = lockAlpha
        pillEraTurret.alpha = lockAlpha
        pillEraPrint.alpha = lockAlpha
        btnEraWorkbenchSwitch.alpha = lockAlpha
        btnEraCameraPicker.alpha = lockAlpha
        btnEraFlipLens.alpha = lockAlpha
    }

    private fun enterEraWorkbench() {
        if (currentWorkbench == CameraWorkbench.ERA) return

        // 1. Cleanly close all modern SAVIOR drawers, animations & timers
        closeAllDrawers()
        rackAnimator?.cancel()
        zoomAnimator?.cancel()
        uiHandler.removeCallbacks(countdownRunnable)
        txtTimerCountdown.visibility = View.GONE

        // 2. Snapshot full SAVIOR state so returning restores 100% faithfully
        saviorSnapshot = SaviorWorkbenchSnapshot(
            fps = currentFps,
            timelapseIntervalMs = engine.timelapseIntervalMs,
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
            isAeAfLocked = engine.isAeAfLocked,
            zoomRatio = engine.currentZoomRatio,
            splitCompareActive = isSplitCompareActive,
            splitCompareFraction = splitCompareFraction
        )

        currentWorkbench = CameraWorkbench.ERA
        eraMicMonitorPaused = true
        if (isSplitCompareActive) {
            setSplitCompareMode(false)
        }

        // 3. Hide ALL modern SAVIOR HUD & overlays (100% authentic vintage workspace!)
        saviorTopBar.visibility = View.GONE
        saviorBottomDock.visibility = View.GONE
        lensRail.visibility = View.GONE
        zoomRockerContainer.visibility = View.GONE
        btnAutoPilot.visibility = View.GONE
        btnExitSplitWipe.visibility = View.GONE
        overlayView.visibility = View.GONE

        // 4. Show authentic Classic HUD
        eraTopBar.visibility = View.VISIBLE
        eraBottomDock.visibility = View.VISIBLE
        selectEraPanel(EraSliderPanel.NONE)

        // 5. Reset modern exposure/WB/focus overrides cleanly & activate chosen Classic camera (ZERO reboot!)
        engine.timelapseIntervalMs = 0L
        engine.clearAeAfLock()
        eraManualExposureActive = false
        engine.setAutoExposure()
        engine.setEvCompensation(0)
        engine.setAutoWhiteBalance()
        engine.setAutoFocus()

        applyActiveEraCamera(announce = true)
        updateSystemTelemetry(null)
    }

    private fun exitEraToSaviorWorkbench() {
        if (currentWorkbench == CameraWorkbench.SAVIOR) return
        currentWorkbench = CameraWorkbench.SAVIOR
        eraMicMonitorPaused = false
        selectEraPanel(EraSliderPanel.NONE)

        // 1. Disable Era shader & audio flags cleanly
        eraManualExposureActive = false
        engine.setEraRenderState(0, 0.0f, 30.0f, 0, 1.0f)
        engine.eraSilentAudio = false
        engine.eraAudioProfile = EraAudioProfile.NONE
        engine.eraFilenameTag = ""

        // 2. Restore exact saved SAVIOR workbench state
        val snap = saviorSnapshot
        saviorSnapshot = null
        if (snap != null) {
            engine.timelapseIntervalMs = snap.timelapseIntervalMs
            engine.setTargetFpsSeamless(snap.fps)
            currentFps = snap.fps
            btnFps.text = "$currentFps FPS"
            if (snap.isManualExposure) {
                engine.setManualIso(snap.manualIso)
                engine.setManualShutterNs(snap.manualShutterNs)
                if (snap.isShutter180Locked) engine.setShutter180Locked(true)
            } else {
                engine.setAutoExposure()
                engine.setEvCompensation(snap.evCompensationIndex)
            }
            if (snap.isManualWb) {
                engine.setManualWhiteBalance(snap.manualKelvin, snap.manualTint)
            } else {
                engine.setAutoWhiteBalance()
            }
            if (snap.isManualFocus) {
                engine.setManualFocusDistance(snap.manualFocusDistance)
            } else {
                engine.setAutoFocus()
            }
            engine.setZoomRatio(snap.zoomRatio)
            if (snap.isAeAfLocked) {
                engine.toggleAeAfLock()
            }
            updateLockUi(engine.isAeAfLocked)
            updateEvDisplay()
            updateZoomReadout()
            updateTimelapseUi()
            if (snap.splitCompareActive) {
                setSplitCompareMode(true, snap.splitCompareFraction, announce = false)
            }
        }

        // 3. Hide Classic HUD & restore modern SAVIOR HUD
        eraTopBar.visibility = View.GONE
        eraBottomDock.visibility = View.GONE
        saviorTopBar.visibility = View.VISIBLE
        saviorBottomDock.visibility = View.VISIBLE
        lensRail.visibility = View.VISIBLE
        zoomRockerContainer.visibility = View.VISIBLE
        btnAutoPilot.visibility = View.VISIBLE
        overlayView.visibility = View.VISIBLE
        syncExitSplitPillVisibility()
        syncAnalysisLoop()
        updateSystemTelemetry(null)
        showSingleToast("Switched back to SAVIOR Cinema Workbench")
    }

    private fun selectEraPanel(panel: EraSliderPanel) {
        activeEraPanel = panel
        pillEraIris.setBackgroundResource(if (panel == EraSliderPanel.IRIS) R.drawable.bg_era_pill_active else R.drawable.bg_era_pill)
        txtEraIrisVal.setTextColor(if (panel == EraSliderPanel.IRIS) 0xFF141210.toInt() else 0xFFF5E6C8.toInt())
        pillEraFocus.setBackgroundResource(if (panel == EraSliderPanel.FOCUS) R.drawable.bg_era_pill_active else R.drawable.bg_era_pill)
        txtEraFocusVal.setTextColor(if (panel == EraSliderPanel.FOCUS) 0xFF141210.toInt() else 0xFFF5E6C8.toInt())

        if (panel == EraSliderPanel.NONE) {
            eraSliderDrawer.visibility = View.GONE
            return
        }
        eraSliderDrawer.visibility = View.VISIBLE
        refreshEraSliderUi()
    }

    private fun getActiveEraAsaStocks(): List<Pair<String, Int>> {
        return if (activeEraCamera == EraCameraId.SILENT_1920S) {
            listOf("ASA 50" to 50, "ASA 100" to 100, "ASA 400" to 400, "ASA 800" to 800)
        } else {
            listOf("PX 80" to 80, "XX 250" to 250, "TX 400" to 400, "P 800" to 800, "1600" to 1600)
        }
    }

    private fun computeGrainMultiplierForAsa(iso: Int): Float {
        // Real film emulsion physics: Plus-X 80 = silky fine (0.72x), Double-X 250 = classic (1.0x), Tri-X 400 = expressive (1.22x), Push 1600 = gritty (1.58x)
        return when {
            iso <= 65 -> 0.72f
            iso <= 125 -> 0.86f
            iso <= 280 -> 1.00f
            iso <= 480 -> 1.22f
            iso <= 950 -> 1.42f
            else -> 1.62f
        }
    }

    private fun pushEraGrainForCurrentAsa() {
        val activeFpsVal = if (activeEraCamera == EraCameraId.MONO_1950S) era1950sFps.toFloat() else eraSilentFps.toFloat()
        val activeFilter = if (activeEraCamera == EraCameraId.MONO_1950S) era1950sFilterCode else era1920sTintCode
        val grainMul = if (eraManualExposureActive) computeGrainMultiplierForAsa(eraSelectedAsa) else 1.0f
        engine.setEraRenderState(activeEraCamera.shaderCode, if (eraArchiveWear) 1.0f else 0.0f, activeFpsVal, activeFilter, grainMul)
    }

    private fun applyEraManualStock(targetIso: Int, shutterNs: Long? = null, announce: Boolean = false) {
        if (!engine.capabilities.supportsManualExposure) {
            showSingleToast("Manual ASA lock unavailable on this lens")
            return
        }
        val clampedIso = targetIso.coerceIn(engine.capabilities.effectiveIsoRange.lower, engine.capabilities.effectiveIsoRange.upper)
        val activeFpsVal = if (activeEraCamera == EraCameraId.MONO_1950S) era1950sFps else eraSilentFps
        val chosenShutter = shutterNs ?: if (eraManualExposureActive) {
            engine.manualShutterNs
        } else {
            // Auto-calculate matching mechanical shutter for the chosen ASA so exposure never suddenly clips black or white!
            val curIso = engine.getLiveSensorIso().coerceAtLeast(50)
            val curExp = engine.getLiveSensorShutterNs().coerceAtLeast(100_000L)
            val ideal = ((curIso.toDouble() * curExp.toDouble()) / clampedIso.toDouble()).toLong()
            val maxSafe = 1_000_000_000L / activeFpsVal.coerceAtLeast(16).toLong()
            ideal.coerceIn(engine.capabilities.exposureRangeNs.lower, minOf(maxSafe, engine.capabilities.exposureRangeNs.upper))
        }
        eraManualExposureActive = true
        eraSelectedAsa = clampedIso
        engine.lockEraManualExposure(clampedIso, chosenShutter)
        pushEraGrainForCurrentAsa()
        updateEraIrisUi()
        refreshEraSliderUi()
        if (announce) {
            val denom = (1_000_000_000.0 / engine.manualShutterNs.coerceAtLeast(1L).toDouble()).roundToInt().coerceAtLeast(1)
            showSingleToast("LOCKED: ASA $clampedIso • 1/${denom}s (Zero Auto-ISO Pumping)")
        }
    }

    private fun renderEraAsaStockChips() {
        eraAsaStockRow.removeAllViews()
        val stocks = getActiveEraAsaStocks()
        stocks.forEachIndexed { idx, (label, asa) ->
            val clamped = asa.coerceIn(engine.capabilities.effectiveIsoRange.lower, engine.capabilities.effectiveIsoRange.upper)
            val isSelected = eraManualExposureActive && kotlin.math.abs(eraSelectedAsa - clamped) <= maxOf(15, clamped / 8)
            val chip = TextView(this).apply {
                text = label
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(if (isSelected) R.drawable.bg_era_pill_active else R.drawable.bg_era_pill)
                setTextColor(if (isSelected) 0xFF141210.toInt() else 0xFFF5E6C8.toInt())
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(25), 1f).apply {
                    if (idx < stocks.lastIndex) marginEnd = dpToPx(4)
                }
                setOnClickListener {
                    applyEraManualStock(asa, shutterNs = null, announce = true)
                }
            }
            eraAsaStockRow.addView(chip)
        }
    }

    private fun showEraCustomAsaDialog() {
        if (!engine.capabilities.supportsManualExposure) {
            showSingleToast("Manual ASA lock unavailable on this lens")
            return
        }
        val minI = engine.capabilities.effectiveIsoRange.lower
        val maxI = engine.capabilities.effectiveIsoRange.upper
        val input = EditText(this).apply {
            hint = "Enter exact ASA / ISO ($minI – $maxI)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((if (eraManualExposureActive) eraSelectedAsa else engine.getLiveSensorIso()).toString())
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(36, 28, 36, 28)
        }
        AlertDialog.Builder(this)
            .setTitle("Load Custom Film Stock ASA ($minI–$maxI)")
            .setView(input)
            .setPositiveButton("LOCK ASA") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v <= 0) {
                    showSingleToast("Enter a valid ASA number")
                    return@setPositiveButton
                }
                applyEraManualStock(v, shutterNs = null, announce = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateEraTorchUi() {
        val on = engine.isTorchEnabled && engine.capabilities.hasFlash
        btnEraTorch.alpha = if (engine.capabilities.hasFlash) 1.0f else 0.35f
        btnEraTorch.setBackgroundResource(if (on) R.drawable.bg_era_pill_active else R.drawable.bg_era_pill)
        btnEraTorch.setTextColor(if (on) 0xFF141210.toInt() else 0xFFE8B968.toInt())
        btnEraTorch.text = if (on) "☀ LAMP" else "LAMP"
    }

    private fun refreshEraSliderUi() {
        suppressEraSeek = true
        when (activeEraPanel) {
            EraSliderPanel.IRIS -> {
                val canManual = engine.capabilities.supportsManualExposure
                btnEraLockExp.visibility = if (canManual) View.VISIBLE else View.GONE
                eraAsaStockRow.visibility = if (canManual) View.VISIBLE else View.GONE
                if (canManual) renderEraAsaStockChips()

                if (eraManualExposureActive && canManual) {
                    txtEraSliderTitle.text = "SHUTTER (TAP ASA)"
                    btnEraLockExp.text = "180°"
                    btnEraLockExp.setBackgroundResource(R.drawable.bg_era_pill_active)
                    btnEraLockExp.setTextColor(0xFF141210.toInt())
                    btnEraSliderAuto.text = "AUTO"
                    btnEraSliderAuto.setBackgroundResource(R.drawable.bg_era_pill)
                    btnEraSliderAuto.setTextColor(0xFFF5E6C8.toInt())

                    eraSeekBar.max = eraManualShutterDenoms.lastIndex
                    val curDenom = (1_000_000_000L / engine.manualShutterNs.coerceAtLeast(1L)).toInt()
                    val closestIdx = eraManualShutterDenoms.indices.minByOrNull {
                        kotlin.math.abs(eraManualShutterDenoms[it] - curDenom)
                    } ?: 6
                    eraSeekBar.progress = (eraManualShutterDenoms.lastIndex - closestIdx).coerceIn(0, eraManualShutterDenoms.lastIndex)
                    txtEraSliderReadout.text = "ASA ${engine.manualIso} • 1/${curDenom}s"
                } else {
                    txtEraSliderTitle.text = "AUTO IRIS"
                    btnEraLockExp.text = "LOCK 180°"
                    btnEraLockExp.setBackgroundResource(R.drawable.bg_era_pill)
                    btnEraLockExp.setTextColor(0xFFE8B968.toInt())
                    btnEraSliderAuto.text = "AUTO"
                    btnEraSliderAuto.setBackgroundResource(R.drawable.bg_era_pill_active)
                    btnEraSliderAuto.setTextColor(0xFF141210.toInt())

                    val minEv = engine.capabilities.evRange.lower
                    val maxEv = engine.capabilities.evRange.upper
                    eraSeekBar.max = (maxEv - minEv).coerceAtLeast(1)
                    eraSeekBar.progress = (engine.evCompensationIndex - minEv).coerceIn(0, eraSeekBar.max)
                    txtEraSliderReadout.text = "AUTO • ${formatEraIrisFStop(engine.getCurrentEvStops())}"
                }
            }
            EraSliderPanel.FOCUS -> {
                btnEraLockExp.visibility = View.GONE
                eraAsaStockRow.visibility = View.GONE
                txtEraSliderTitle.text = "FOCUS RING"
                btnEraSliderAuto.text = "AF"
                btnEraSliderAuto.setBackgroundResource(if (!engine.isManualFocus) R.drawable.bg_era_pill_active else R.drawable.bg_era_pill)
                btnEraSliderAuto.setTextColor(if (!engine.isManualFocus) 0xFF141210.toInt() else 0xFFF5E6C8.toInt())
                eraSeekBar.max = 100
                val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
                eraSeekBar.progress = ((engine.manualFocusDistance / maxF) * 100f).roundToInt().coerceIn(0, 100)
                txtEraSliderReadout.text = if (engine.isManualFocus) formatFocus(engine.manualFocusDistance, maxF) else "AF (AUTO)"
            }
            EraSliderPanel.NONE -> {
                btnEraLockExp.visibility = View.GONE
                eraAsaStockRow.visibility = View.GONE
            }
        }
        suppressEraSeek = false
    }

    private fun formatEraIrisFStop(evStops: Float): String {
        // Map EV stops relative to classic f/5.6 middle iris (brighter EV = wider aperture e.g. f/2.8, darker = f/11)
        val fNum = 5.6 * Math.pow(2.0, (-evStops / 2.0).toDouble())
        return if (fNum < 10.0) String.format(Locale.US, "f/%.1f", fNum)
        else String.format(Locale.US, "f/%.0f", fNum)
    }

    private fun updateEraIrisUi() {
        if (eraManualExposureActive && engine.isManualExposure) {
            txtEraIrisLabel.text = "ASA LOCK"
            txtEraIrisVal.text = "${engine.manualIso}"
        } else {
            txtEraIrisLabel.text = "ASA/IRIS"
            val liveI = lastTelemetry?.iso
            txtEraIrisVal.text = if (liveI != null && liveI > 0) "A·$liveI" else formatEraIrisFStop(engine.getCurrentEvStops())
        }
    }

    private fun updateEraFocusUi() {
        val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
        txtEraFocusVal.text = if (engine.isManualFocus) formatFocus(engine.manualFocusDistance, maxF) else "AF"
    }

    private fun setupMonitoringTools() {
        btnMonitorToggle.setOnClickListener {
            val showing = monitorToolsBar.visibility == View.VISIBLE
            if (!showing) closeTopDrawers(except = "MON")
            monitorToolsBar.visibility = if (showing) View.GONE else View.VISIBLE
            styleToolPill(btnMonitorToggle, !showing)
            syncLowBatteryBannerVisibility()
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
            colorControlDrawer.visibility == View.VISIBLE ||
            monitorToolsBar.visibility == View.VISIBLE ||
            proSettingsBar.visibility == View.VISIBLE
    }

    private fun syncLowBatteryBannerVisibility() {
        val anyTopDrawerOpen = colorControlDrawer.visibility == View.VISIBLE ||
            monitorToolsBar.visibility == View.VISIBLE ||
            proSettingsBar.visibility == View.VISIBLE
        txtLowBatteryBanner.visibility = if (!isBatteryCharging && lastBatteryPct <= 15 && !lowBatteryBannerDismissed && !anyTopDrawerOpen) {
            View.VISIBLE
        } else {
            View.GONE
        }
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
            lensRail.visibility = View.VISIBLE
            syncExitSplitPillVisibility()
            updateAudioTopPillVisual()
        }
        if (except != "LOOK" && colorControlDrawer.visibility == View.VISIBLE) {
            setColorDrawerPeekMode(null)
            setColorDrawerRawComparePeek(false)
            colorControlDrawer.visibility = View.GONE
            updateLookTopPillVisual()
        }
        if (except != "ZOOM" && zoomPopoutDrawer.visibility == View.VISIBLE) {
            zoomPopoutDrawer.visibility = View.GONE
        }
        syncLowBatteryBannerVisibility()
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
            syncLowBatteryBannerVisibility()
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

        btnProTimelapse.setOnClickListener {
            if (engine.isRecording) {
                showSingleToast("Stop recording to change Time-Lapse interval")
                return@setOnClickListener
            }
            showTimelapsePickerDialog()
        }

        btnProMirrorFront.setOnClickListener {
            val next = !engine.mirrorFrontExport
            engine.setMirrorFrontExport(next)
            updateMirrorFrontPillUi()
            showSingleToast(if (next) "Front MP4 Export: MIRRORED (Selfie)" else "Front MP4 Export: NATURAL (Un-Mirrored)")
        }

        btnProPresets.setOnClickListener {
            showPresetsDialog()
        }
    }

    private fun formatTimelapseLabel(ms: Long): String {
        return if (ms <= 0L) "OFF"
        else if (ms % 1000L == 0L) "${ms / 1000L}s"
        else String.format(Locale.US, "%.1fs", ms / 1000f)
    }

    private fun showTimelapsePickerDialog() {
        val currentMs = engine.timelapseIntervalMs
        val presets = listOf(
            0L to "OFF (Normal Real-Time Video)",
            500L to "0.5s  (Fast Clouds / Traffic)",
            1000L to "1s    (Action / Walking)",
            2000L to "2s    (Clouds / Street)",
            5000L to "5s    (Sunset / Golden Hour)",
            10000L to "10s   (Slow Clouds / Building)",
            30000L to "30s   (Day-to-Night / Stars)"
        )
        val labels = presets.map { (ms, desc) ->
            if (ms == currentMs) "✓ $desc" else desc
        }.toMutableList()
        labels.add("Custom Interval (0.5s – 60s)...")

        AlertDialog.Builder(this)
            .setTitle("Pro Time-Lapse Interval (Output: ${currentFps.coerceAtLeast(24)} FPS)")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < presets.size) {
                    applyTimelapseInterval(presets[which].first)
                } else {
                    showCustomTimelapseInputDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomTimelapseInputDialog() {
        val input = EditText(this).apply {
            hint = "Seconds per frame (0.5 to 60.0, e.g. 1.5, 3, 15)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (engine.timelapseIntervalMs > 0L) {
                setText(String.format(Locale.US, "%.1f", engine.timelapseIntervalMs / 1000f))
            }
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(36, 28, 36, 28)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom Time-Lapse Interval (Seconds)")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val secs = input.text.toString().trim().toFloatOrNull()
                if (secs == null || secs < 0.5f || secs > 60.0f) {
                    showSingleToast("Enter an interval between 0.5 and 60 seconds")
                    return@setPositiveButton
                }
                applyTimelapseInterval((secs * 1000f).roundToInt().toLong())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTimelapseInterval(intervalMs: Long) {
        engine.timelapseIntervalMs = intervalMs.coerceAtLeast(0L)
        if (intervalMs > 0L && currentFps < 20) {
            // Ensure Time-Lapse always plays back at smooth cinema FPS (30 FPS) rather than 1 FPS!
            val smoothFps = minOf(30, engine.capabilities.maxHardwareFps.coerceIn(24, 60))
            currentFps = smoothFps
            btnFps.text = "$smoothFps FPS"
            engine.updateSettings(currentResolution, smoothFps)
        }
        updateTimelapseUi()
        if (intervalMs > 0L) {
            val tag = formatTimelapseLabel(intervalMs)
            showSingleToast("Time-Lapse ARMED: 1 frame / $tag → smooth $currentFps FPS MP4 (Video-Only)", Toast.LENGTH_LONG)
        } else {
            showSingleToast("Time-Lapse OFF (Normal Video)")
        }
    }

    private fun updateTimelapseUi() {
        val active = engine.isTimelapseEnabled()
        val tag = formatTimelapseLabel(engine.timelapseIntervalMs)
        btnProTimelapse.text = if (active) "TL: $tag" else "TL: OFF"
        styleToolPill(btnProTimelapse, active)
        if (!engine.isRecording) {
            if (active) {
                txtTimecode.text = "TL $tag"
                txtTimecode.setTextColor(0xFFFF5F1F.toInt())
            } else {
                txtTimecode.text = "00:00:00"
                txtTimecode.setTextColor(0xFFFFFFFF.toInt())
            }
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
                    zebraModeName = overlayView.zebraMode.name,
                    gpuExposureLift = gpuColorState.exposureLift,
                    gpuContrast = gpuColorState.contrast,
                    gpuSaturation = gpuColorState.saturation,
                    gpuShadows = gpuColorState.shadows,
                    gpuHighlights = gpuColorState.highlights,
                    gpuVignette = gpuColorState.vignette,
                    lutSourceId = activeLutSourceId,
                    lutIntensity = gpuColorState.lutIntensity,
                    mirrorFrontExport = engine.mirrorFrontExport
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
        // Clear AE/AF lock first so preset EV / Exposure settings apply cleanly without being blocked
        engine.clearAeAfLock()
        updateLockUi(false)

        engine.micPreference = try { MicPreference.valueOf(p.micPrefName) } catch (_: Exception) { MicPreference.AUTO }
        val tone = try { ToneProfile.valueOf(p.toneProfileName) } catch (_: Exception) { ToneProfile.STANDARD }
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
            if (p.isShutter180Locked) engine.setShutter180Locked(true)
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

        gpuColorState = GpuColorState(
            exposureLift = p.gpuExposureLift.coerceIn(-1.5f, 1.5f),
            contrast = p.gpuContrast.coerceIn(0.50f, 1.60f),
            saturation = p.gpuSaturation.coerceIn(0.0f, 1.80f),
            shadows = p.gpuShadows.coerceIn(-0.50f, 0.50f),
            highlights = p.gpuHighlights.coerceIn(-0.50f, 0.50f),
            vignette = p.gpuVignette.coerceIn(-0.85f, 0.85f),
            lutIntensity = p.lutIntensity.coerceIn(0.0f, 1.0f),
            isBypassed = false
        )
        loadAndActivateCubeLut(p.lutSourceId, silent = true)
        activeLookTag = if (gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())) "NONE" else "CUSTOM"
        pushGpuColorStateAndRefreshUi()
        engine.setMirrorFrontExport(p.mirrorFrontExport)
        updateMirrorFrontPillUi()

        btnResolution.text = currentResolution.label
        btnFps.text = "${p.fps} FPS"
        btnProCodec.text = engine.targetCodec.label
        styleToolPill(btnProCodec, engine.targetCodec == CinemaCodec.HEVC)
        btnProBitrate.text = "${engine.targetBitrateMbps} Mbps"
        updateAudioDrawerUi()

        engine.updateSettings(currentResolution, p.fps)
        Toast.makeText(this, "Loaded template: ${p.name}", Toast.LENGTH_SHORT).show()
    }

    private fun updateSystemTelemetry(batteryIntent: Intent? = null) {
        try {
            val bIntent = batteryIntent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) {
                ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100)
            } else {
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
            }

            val status = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = bIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0 ||
                bm.isCharging

            val wasCharging = isBatteryCharging
            isBatteryCharging = charging
            lastBatteryPct = pct

            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)

            // 1. Update SAVIOR top-bar battery + charging icon (⚡) + free storage
            txtSysTelemetry.text = when {
                charging -> String.format(Locale.US, "⚡%d%% • %.1fGB", pct, freeGb)
                pct <= 15 -> String.format(Locale.US, "LOW %d%% • %.1fGB", pct, freeGb)
                else -> String.format(Locale.US, "%d%% • %.1fGB", pct, freeGb)
            }
            txtSysTelemetry.setTextColor(
                when {
                    freeGb < 1.0 -> 0xFFFF3B30.toInt()
                    charging -> 0xFF34C759.toInt()
                    pct <= 15 -> 0xFFFF3B30.toInt()
                    pct <= 30 || freeGb < 3.0 -> 0xFFFFCC00.toInt()
                    else -> 0xFFB0B7C3.toInt()
                }
            )

            // 2. Update ERA top-bar subtle battery pill (shown ONLY while charging ⚡ or low <= 15% so vintage HUD stays clean)
            when {
                charging -> {
                    txtEraBattery.visibility = View.VISIBLE
                    txtEraBattery.text = "⚡$pct%"
                    txtEraBattery.setTextColor(0xFF34C759.toInt())
                    txtEraBattery.setBackgroundResource(R.drawable.bg_era_pill)
                }
                pct <= 15 -> {
                    txtEraBattery.visibility = View.VISIBLE
                    txtEraBattery.text = "LOW $pct%"
                    txtEraBattery.setTextColor(0xFFFF3B30.toInt())
                    txtEraBattery.setBackgroundResource(R.drawable.bg_hud_pill_muted)
                }
                else -> {
                    txtEraBattery.visibility = View.GONE
                }
            }

            // 3. Low-Battery Plug-In Warning Banner & One-Time Toast Alerts
            if (charging) {
                if (!wasCharging && (lowBatteryWarned15 || lowBatteryWarned7)) {
                    showSingleToast("Charger connected (⚡$pct%)")
                }
                lowBatteryWarned15 = false
                lowBatteryWarned7 = false
                lowBatteryBannerDismissed = false
                txtLowBatteryBanner.visibility = View.GONE
            } else if (pct > 20) {
                lowBatteryWarned15 = false
                lowBatteryWarned7 = false
                lowBatteryBannerDismissed = false
                txtLowBatteryBanner.visibility = View.GONE
            } else if (pct <= 15) {
                val isCritical = pct <= 7
                txtLowBatteryBanner.text = if (isCritical) {
                    "⚠ CRITICAL BATTERY $pct% — PLUG IN NOW"
                } else {
                    "⚠ LOW BATTERY $pct% — PLUG IN CHARGER"
                }
                if (isCritical && !lowBatteryWarned7) {
                    lowBatteryWarned7 = true
                    lowBatteryBannerDismissed = false
                    showSingleToast("Critical Battery ($pct%) — Plug in charger now!", Toast.LENGTH_LONG)
                } else if (!lowBatteryWarned15) {
                    lowBatteryWarned15 = true
                    showSingleToast("Low Battery ($pct%) — Plug in charger so recording stays safe", Toast.LENGTH_LONG)
                }
                val anyTopDrawerOpen = colorControlDrawer.visibility == View.VISIBLE ||
                    monitorToolsBar.visibility == View.VISIBLE ||
                    proSettingsBar.visibility == View.VISIBLE
                txtLowBatteryBanner.visibility = if (!lowBatteryBannerDismissed && !anyTopDrawerOpen) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            } else {
                txtLowBatteryBanner.visibility = View.GONE
            }
        } catch (_: Exception) {
        }
    }

    private fun showStudioMogliCreditsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_credits, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.txtCreditsVersion).text =
            "v${getCurrentVersionName()} • Savior Cinema Engine"

        view.findViewById<TextView>(R.id.btnCreditsWebsite).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.studiomogli.com")))
            } catch (_: Exception) {
                Toast.makeText(this, "Visit www.studiomogli.com", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<TextView>(R.id.btnCreditsLicense).setOnClickListener {
            showIdgafLicenseDialog()
        }

        val btnUpdateOrCheck = view.findViewById<TextView>(R.id.btnCreditsCheckUpdate)
        val pendingUrl = updateDownloadUrl
        val pendingTag = updateLatestTag
        if (!pendingUrl.isNullOrBlank()) {
            btnUpdateOrCheck.text = if (!pendingTag.isNullOrBlank()) "INSTALL $pendingTag" else "INSTALL UPDATE"
            btnUpdateOrCheck.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pendingUrl)))
                    dialog.dismiss()
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open update link", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            btnUpdateOrCheck.setOnClickListener {
                Toast.makeText(this, "Checking GitHub Releases...", Toast.LENGTH_SHORT).show()
                checkForGitHubUpdateSilently(manualCheck = true)
            }
        }

        view.findViewById<TextView>(R.id.btnCreditsClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showIdgafLicenseDialog() {
        AlertDialog.Builder(this)
            .setTitle("THE IDGAF LICENSE (Redemption Edition)")
            .setMessage(
                "1. FOR HUMANS (Indie Filmmakers, Students, Creators & Literally Any Person):\n" +
                    "• Price: $0 / ₹0 FOREVER.\n" +
                    "• Zero paywalls. Zero watermarks. Zero subscriptions.\n" +
                    "• Optional Human Lifetime License: $1 USD globally — only if you feel like owning it officially. Otherwise, it stays 100% free forever.\n\n" +
                    "2. SPECIAL EXEMPTION — ALPHABET / GOOGLE:\n" +
                    "• Permanently FREE FOREVER for Alphabet Inc., Google LLC, and all of their wholly owned subsidiaries worldwide.\n\n" +
                    "3. FOR SUBSCRIPTION-HAPPY CORPORATIONS (Any Other XYZ Company Charging Humans Monthly Rent Just to Breathe):\n" +
                    "• Mandatory Corporate Redemption Subscription: $9,999 USD / month per device (billed annually, payable in advance, auto-renewing with zero grace period).\n" +
                    "• LIFETIME LICENSE STRICTLY BANNED: Corporations & organizations are permanently prohibited from purchasing the $1 Lifetime License. No buyouts. Enjoy your monthly bill.\n\n" +
                    "— Studio Mogli / Savior San"
            )
            .setPositiveButton("RESPECT", null)
            .show()
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
                            updateLatestTag = latestTag
                            // Subtle zero-clunk left badge on IDGAF CAM — never shifts or crowds the right top bar!
                            btnBrandCredits.text = "IDGAF CAM ↑"
                            btnBrandCredits.setBackgroundResource(R.drawable.bg_dock_pill_active)
                            if (manualCheck) {
                                Toast.makeText(this, "Update $latestTag ready! Tap IDGAF CAM ↑ to install.", Toast.LENGTH_LONG).show()
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
        info.versionName ?: "0.7.4.3"
    } catch (_: Exception) { "0.7.4.3" }

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
            val nextVisible = !showing
            audioControlDrawer.visibility = if (nextVisible) View.VISIBLE else View.GONE
            // Hide left lens rail while Pro Audio Drawer is open so camera switch button never overlaps audio controls
            lensRail.visibility = if (nextVisible) View.GONE else View.VISIBLE
            syncExitSplitPillVisibility()
            updateAudioTopPillVisual()
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

        btnAudioMicAuto.setOnClickListener { selectMicPreference(MicPreference.AUTO) }
        btnAudioMicInt.setOnClickListener { selectMicPreference(MicPreference.INTERNAL) }
        btnAudioMicExt.setOnClickListener { selectMicPreference(MicPreference.EXTERNAL) }

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


    @SuppressLint("ClickableViewAccessibility")
    private fun setupColorStudioDrawer() {
        btnColorPanelToggle.setOnClickListener {
            val showing = colorControlDrawer.visibility == View.VISIBLE
            if (!showing) closeTopDrawers(except = "LOOK")
            if (showing) {
                setColorDrawerPeekMode(null)
                setColorDrawerRawComparePeek(false)
            }
            colorControlDrawer.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) {
                rowColorLutMix.visibility = if (activeLutSourceId.isNotEmpty()) View.VISIBLE else View.GONE
            }
            updateLookTopPillVisual()
            syncLowBatteryBannerVisibility()
        }

        // Hold RAW for instant full-screen RAW peek; SLIDE finger left/right while holding to pull out the live RAW | LOOK Split-Screen Wipe!
        btnColorCompareAB.setOnTouchListener { _, event ->
            val texW = textureView.width.coerceAtLeast(1).toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawTouchDownRawX = event.rawX
                    rawSlideEngagedSplit = false
                    gpuColorState = gpuColorState.copy(isBypassed = true)
                    engine.updateGpuColorState(gpuColorState)
                    if (isSplitCompareActive) {
                        overlayView.splitCompareFraction = -1f
                    }
                    setColorDrawerRawComparePeek(true)
                    styleToolPill(btnColorCompareAB, true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.rawX - rawTouchDownRawX)
                    if (!rawSlideEngagedSplit && dx > dpToPx(16)) {
                        rawSlideEngagedSplit = true
                        gpuColorState = gpuColorState.copy(isBypassed = false)
                        engine.updateGpuColorState(gpuColorState)
                    }
                    if (rawSlideEngagedSplit) {
                        val loc = IntArray(2)
                        textureView.getLocationOnScreen(loc)
                        val frac = ((event.rawX - loc[0]) / texW).coerceIn(0.02f, 0.98f)
                        isSplitCompareActive = true
                        splitCompareFraction = frac
                        engine.setSplitCompareFraction(frac)
                        overlayView.splitRightLabel = getSplitRightLabel()
                        overlayView.splitCompareFraction = frac
                        styleToolPill(btnColorSplitWipe, true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gpuColorState = gpuColorState.copy(isBypassed = false)
                    engine.updateGpuColorState(gpuColorState)
                    setColorDrawerRawComparePeek(false)
                    styleToolPill(btnColorCompareAB, false)
                    if (rawSlideEngagedSplit) {
                        rawSlideEngagedSplit = false
                        if (splitCompareFraction < 0.08f || splitCompareFraction > 0.92f) {
                            setSplitCompareMode(false)
                        } else {
                            setSplitCompareMode(true, splitCompareFraction, announce = false)
                        }
                    } else if (isSplitCompareActive) {
                        // Restore active split line after a simple non-sliding RAW peek
                        overlayView.splitCompareFraction = splitCompareFraction
                        engine.setSplitCompareFraction(splitCompareFraction)
                    }
                }
            }
            true
        }

        btnColorSplitWipe.setOnClickListener {
            if (isSplitCompareActive) {
                setSplitCompareMode(false)
                showSingleToast("Split-Screen Compare OFF")
            } else {
                setSplitCompareMode(true, 0.50f, announce = true)
            }
        }

        btnExitSplitWipe.setOnClickListener {
            setSplitCompareMode(false)
            showSingleToast("Split-Screen Compare OFF")
        }

        btnColorReset.setOnClickListener {
            setSplitCompareMode(false)
            clearActiveCubeLut()
            applyBuiltInLook("NONE", GpuColorState())
            showSingleToast("GPU Color & 3D LUT Reset")
        }

        btnColorClose.setOnClickListener {
            setColorDrawerPeekMode(null)
            setColorDrawerRawComparePeek(false)
            colorControlDrawer.visibility = View.GONE
            updateLookTopPillVisual()
            syncLowBatteryBannerVisibility()
        }

        btnLutPrev.setOnClickListener {
            stepActiveCubeLut(delta = -1)
        }

        btnSelectCubeLut.setOnClickListener {
            showCubeLutLibraryDialog()
        }

        btnLutNext.setOnClickListener {
            stepActiveCubeLut(delta = +1)
        }

        btnImportCubeQuick.setOnClickListener {
            showImportCubeSourceMenu()
        }

        // World-Class Calibrated Grading Foundations (single-pass GPU only — zero hardware double-flattening!)
        btnLookNone.setOnClickListener {
            applyBuiltInLook("NONE", GpuColorState())
        }
        btnLookLog.setOnClickListener {
            // Single-pass Savior Log: clean shadow & highlight latitude for DaVinci Resolve / 3D LUTs without milky double-LOG
            applyBuiltInLook(
                "LOG",
                GpuColorState(
                    exposureLift = 0.0f,
                    contrast = 0.84f,
                    saturation = 0.78f,
                    shadows = 0.26f,
                    highlights = -0.24f,
                    vignette = 0.0f
                )
            )
        }
        btnLookCine.setOnClickListener {
            // FILM: calibrated celluloid tonal foundation (rolled-off highlights, grounded shadows, subtle lens vignette)
            applyBuiltInLook(
                "FILM",
                GpuColorState(
                    exposureLift = 0.0f,
                    contrast = 1.12f,
                    saturation = 0.94f,
                    shadows = -0.08f,
                    highlights = -0.18f,
                    vignette = -0.22f
                )
            )
        }
        btnLookPunch.setOnClickListener {
            // VIVID: clean commercial broadcast pop (lifted shadow detail, controlled highlights, rich natural color)
            applyBuiltInLook(
                "VIVID",
                GpuColorState(
                    exposureLift = 0.05f,
                    contrast = 1.10f,
                    saturation = 1.16f,
                    shadows = 0.10f,
                    highlights = -0.06f,
                    vignette = -0.10f
                )
            )
        }
        btnLookNoir.setOnClickListener {
            // MONO: rich silver-halide monochrome foundation with clean facial midtones
            applyBuiltInLook(
                "MONO",
                GpuColorState(
                    exposureLift = 0.0f,
                    contrast = 1.26f,
                    saturation = 0.0f,
                    shadows = -0.14f,
                    highlights = 0.06f,
                    vignette = -0.28f
                )
            )
        }

        val bindSeek = { row: LinearLayout, sb: SeekBar, onChange: (Int) -> Unit ->
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || suppressColorSeek) return
                    activeLookTag = "CUSTOM"
                    onChange(progress)
                    pushGpuColorStateAndRefreshUi()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    setColorDrawerPeekMode(row)
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    setColorDrawerPeekMode(null)
                }
            })
        }

        bindSeek(rowColorExp, seekColorExp) { p -> gpuColorState = gpuColorState.copy(exposureLift = (p - 15) * 0.1f) }
        bindSeek(rowColorCon, seekColorCon) { p -> gpuColorState = gpuColorState.copy(contrast = 0.50f + p * 0.01f) }
        bindSeek(rowColorSat, seekColorSat) { p -> gpuColorState = gpuColorState.copy(saturation = p * 0.01f) }
        bindSeek(rowColorShd, seekColorShd) { p -> gpuColorState = gpuColorState.copy(shadows = (p - 50) * 0.01f) }
        bindSeek(rowColorHil, seekColorHil) { p -> gpuColorState = gpuColorState.copy(highlights = (p - 50) * 0.01f) }
        bindSeek(rowColorVig, seekColorVig) { p -> gpuColorState = gpuColorState.copy(vignette = (p - 85) * 0.01f) }
        bindSeek(rowColorLutMix, seekColorLutMix) { p -> gpuColorState = gpuColorState.copy(lutIntensity = p * 0.01f) }

        // Tap any readout to reset just that single parameter to neutral!
        txtColorExpVal.setOnClickListener {
            gpuColorState = gpuColorState.copy(exposureLift = 0f)
            activeLookTag = if (gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())) "NONE" else "CUSTOM"
            pushGpuColorStateAndRefreshUi()
        }
        txtColorConVal.setOnClickListener {
            gpuColorState = gpuColorState.copy(contrast = 1.0f)
            activeLookTag = if (gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())) "NONE" else "CUSTOM"
            pushGpuColorStateAndRefreshUi()
        }
        txtColorSatVal.setOnClickListener {
            gpuColorState = gpuColorState.copy(saturation = 1.0f)
            activeLookTag = if (gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())) "NONE" else "CUSTOM"
            pushGpuColorStateAndRefreshUi()
        }
        txtColorShdVal.setOnClickListener {
            gpuColorState = gpuColorState.copy(shadows = 0f)
            activeLookTag = if (gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())) "NONE" else "CUSTOM"
            pushGpuColorStateAndRefreshUi()
        }
        txtColorHilVal.setOnClickListener {
            gpuColorState = gpuColorState.copy(highlights = 0f)
            activeLookTag = if (gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())) "NONE" else "CUSTOM"
            pushGpuColorStateAndRefreshUi()
        }
        txtColorVigVal.setOnClickListener {
            gpuColorState = gpuColorState.copy(vignette = 0f)
            activeLookTag = if (gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())) "NONE" else "CUSTOM"
            pushGpuColorStateAndRefreshUi()
        }
        txtColorLutMixVal.setOnClickListener {
            gpuColorState = gpuColorState.copy(lutIntensity = 1.0f)
            pushGpuColorStateAndRefreshUi()
        }

        pushGpuColorStateAndRefreshUi()
        updateMirrorFrontPillUi()
    }

    /**
     * Peek-Through Grading Mode: while the user holds/drags a slider in LOOK, hide the drawer box,
     * header, presets, and all other sliders using View.INVISIBLE (keeping the dragged SeekBar at
     * exact screen coordinates so touch tracking never jumps) while giving the active row a slim pill.
     */
    private fun setColorDrawerPeekMode(activeRow: LinearLayout?) {
        val hasLut = activeLutSourceId.isNotEmpty()
        val allRows = listOf(rowColorLutMix, rowColorExp, rowColorCon, rowColorSat, rowColorShd, rowColorHil, rowColorVig)
        if (activeRow != null) {
            colorControlDrawer.setBackgroundColor(Color.TRANSPARENT)
            colorDrawerHeaderRow.visibility = View.INVISIBLE
            colorLooksPresetRow.visibility = View.INVISIBLE
            colorLutBarRow.visibility = View.INVISIBLE
            scrollColorSliders.isVerticalScrollBarEnabled = false
            allRows.forEach { row ->
                if (row == activeRow) {
                    row.visibility = View.VISIBLE
                    row.setBackgroundResource(R.drawable.bg_hud_pill)
                } else if (row == rowColorLutMix && !hasLut) {
                    row.visibility = View.GONE
                    row.setBackgroundColor(Color.TRANSPARENT)
                } else {
                    row.visibility = View.INVISIBLE
                    row.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        } else {
            colorControlDrawer.setBackgroundResource(R.drawable.bg_hud_pill)
            colorDrawerHeaderRow.visibility = View.VISIBLE
            colorLooksPresetRow.visibility = View.VISIBLE
            colorLutBarRow.visibility = View.VISIBLE
            scrollColorSliders.isVerticalScrollBarEnabled = true
            allRows.forEach { row ->
                row.visibility = if (row == rowColorLutMix && !hasLut) View.GONE else View.VISIBLE
                row.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun setColorDrawerRawComparePeek(active: Boolean) {
        if (active) {
            colorControlDrawer.setBackgroundColor(Color.TRANSPARENT)
            txtColorDrawerTitle.visibility = View.INVISIBLE
            btnColorSplitWipe.visibility = View.INVISIBLE
            btnColorReset.visibility = View.INVISIBLE
            btnColorClose.visibility = View.INVISIBLE
            colorLooksPresetRow.visibility = View.INVISIBLE
            colorLutBarRow.visibility = View.INVISIBLE
            scrollColorSliders.visibility = View.INVISIBLE
        } else {
            colorControlDrawer.setBackgroundResource(R.drawable.bg_hud_pill)
            txtColorDrawerTitle.visibility = View.VISIBLE
            btnColorSplitWipe.visibility = View.VISIBLE
            btnColorReset.visibility = View.VISIBLE
            btnColorClose.visibility = View.VISIBLE
            colorLooksPresetRow.visibility = View.VISIBLE
            colorLutBarRow.visibility = View.VISIBLE
            scrollColorSliders.visibility = View.VISIBLE
        }
    }

    private fun getSplitRightLabel(): String {
        return when {
            activeLutSourceId.isNotEmpty() -> activeLutDisplayName
            activeLookTag != "NONE" -> activeLookTag
            !gpuColorState.isNeutral(false) -> "CUSTOM"
            else -> "LOOK"
        }
    }

    private fun setSplitCompareMode(active: Boolean, fraction: Float = splitCompareFraction, announce: Boolean = false) {
        isSplitCompareActive = active
        if (active) {
            splitCompareFraction = fraction.coerceIn(0.05f, 0.95f)
            engine.setSplitCompareFraction(splitCompareFraction)
            overlayView.splitRightLabel = getSplitRightLabel()
            overlayView.splitCompareFraction = splitCompareFraction
            styleToolPill(btnColorSplitWipe, true)
            if (announce) {
                val hint = if (gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())) {
                    "Split ON — pick a LOOK or 3D LUT & drag divider"
                } else {
                    "Split ON — drag vertical divider across screen (Preview-Only)"
                }
                showSingleToast(hint, Toast.LENGTH_LONG)
            }
        } else {
            isDraggingSplitLine = false
            engine.setSplitCompareFraction(-1f)
            overlayView.splitCompareFraction = -1f
            styleToolPill(btnColorSplitWipe, false)
        }
        syncExitSplitPillVisibility()
    }

    private fun updateSplitComparePosition(fraction: Float) {
        splitCompareFraction = fraction.coerceIn(0.04f, 0.96f)
        if (isSplitCompareActive) {
            engine.setSplitCompareFraction(splitCompareFraction)
            overlayView.splitCompareFraction = splitCompareFraction
        }
    }

    private fun syncExitSplitPillVisibility() {
        btnExitSplitWipe.visibility = if (isSplitCompareActive && audioControlDrawer.visibility != View.VISIBLE) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun applyBuiltInLook(tag: String, state: GpuColorState) {
        activeLookTag = tag
        gpuColorState = state.copy(lutIntensity = gpuColorState.lutIntensity, isBypassed = false)
        // Always keep hardware sensor pipeline clean STANDARD so LOG never double-applies and RAW compares 100% faithfully!
        engine.setToneProfile(ToneProfile.STANDARD)
        pushGpuColorStateAndRefreshUi()
    }

    private fun showImportCubeSourceMenu() {
        val options = arrayOf(
            "Import .cube from Phone / Downloads",
            "Download .cube from Direct URL (Internet)",
            "Browse Free .cube LUTs (Web)"
        )
        AlertDialog.Builder(this)
            .setTitle("Add 3D .cube LUT")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCubeFilePicker()
                    1 -> showDownloadCubeUrlDialog()
                    2 -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://freshluts.com/")))
                            showSingleToast("Download any .cube file, then tap + .CUBE → Phone / Downloads", Toast.LENGTH_LONG)
                        } catch (_: Exception) {
                            showSingleToast("Download any .cube file in Chrome, then import via + .CUBE")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchCubeFilePicker() {
        try {
            // Use */* because Android doesn't register a standard MIME type for .cube files
            importCubeLauncher.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            showSingleToast("File picker unavailable on this device")
        }
    }

    private fun importCubeLutFromUri(uri: Uri) {
        Thread {
            try {
                var displayName = "Custom_LUT.cube"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) displayName = n
                    }
                }
                if (!displayName.lowercase().endsWith(".cube")) {
                    throw IllegalArgumentException("Please select a valid .cube 3D LUT file (selected: $displayName)")
                }
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("Cannot read selected file.")
                val saved = CubeLutLibrary.saveCubeTextToLibrary(this, displayName, text)
                val lutData = CubeLutLibrary.loadLutById(this, saved.sourceId)
                    ?: throw IllegalStateException("Saved LUT could not be loaded.")
                runOnUiThread {
                    applyLoadedCubeLut(lutData)
                    showSingleToast("Imported 3D LUT: ${lutData.displayName} (${lutData.size}³)", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "LUT import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showDownloadCubeUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://.../my_cinema_lut.cube"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(36, 28, 36, 28)
        }
        AlertDialog.Builder(this)
            .setTitle("Download .cube LUT from URL")
            .setMessage("Paste a direct .cube file link (e.g. GitHub raw .cube URL). It will be verified & saved permanently in your LUT library.")
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val urlStr = input.text.toString().trim()
                if (!urlStr.startsWith("http://", ignoreCase = true) && !urlStr.startsWith("https://", ignoreCase = true)) {
                    showSingleToast("Enter a valid https:// .cube URL")
                    return@setPositiveButton
                }
                downloadCubeLutFromUrl(urlStr)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadCubeLutFromUrl(rawUrl: String) {
        showSingleToast("Downloading .cube LUT...")
        Thread {
            var conn: HttpURLConnection? = null
            try {
                // Convert GitHub blob links cleanly to raw links for convenience!
                val resolvedUrl = if (rawUrl.contains("github.com/") && rawUrl.contains("/blob/")) {
                    rawUrl.replace("github.com/", "raw.githubusercontent.com/").replace("/blob/", "/")
                } else {
                    rawUrl
                }
                conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "idgaf-camera-SaviorCinemaEngine")
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${conn.responseCode} from server")
                }
                // Cap download size to 16 MB (even a 65^3 .cube LUT is ~7.5 MB)
                val contentLength = conn.contentLengthLong
                if (contentLength > 16L * 1024L * 1024L) {
                    throw IllegalArgumentException("File too large (> 16 MB) to be a .cube LUT.")
                }
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val fileName = resolvedUrl.substringBefore('?').substringAfterLast('/').ifBlank { "Web_LUT.cube" }
                val saved = CubeLutLibrary.saveCubeTextToLibrary(this, fileName, text)
                val lutData = CubeLutLibrary.loadLutById(this, saved.sourceId)
                    ?: throw IllegalStateException("Downloaded LUT could not be parsed.")
                runOnUiThread {
                    applyLoadedCubeLut(lutData)
                    showSingleToast("Downloaded & Loaded: ${lutData.displayName} (${lutData.size}³)", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun stepActiveCubeLut(delta: Int) {
        val allIds = mutableListOf("")
        CubeLutLibrary.getBuiltInEntries().forEach { allIds.add(it.sourceId) }
        CubeLutLibrary.listSavedUserLuts(this).forEach { allIds.add(it.sourceId) }
        if (allIds.size <= 1) return
        val curIdx = allIds.indexOf(activeLutSourceId).let { if (it < 0) 0 else it }
        val nextIdx = (curIdx + delta + allIds.size) % allIds.size
        val nextId = allIds[nextIdx]
        if (nextId.isEmpty()) {
            clearActiveCubeLut()
            pushGpuColorStateAndRefreshUi()
            showSingleToast("3D LUT: NONE")
        } else {
            loadAndActivateCubeLut(nextId, silent = false)
        }
    }

    private fun showCubeLutLibraryDialog() {
        val builtins = CubeLutLibrary.getBuiltInEntries()
        val userLuts = CubeLutLibrary.listSavedUserLuts(this)

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(if (activeLutSourceId.isEmpty()) "✓ NONE (No 3D LUT)" else "NONE (No 3D LUT)")
        actions.add {
            clearActiveCubeLut()
            pushGpuColorStateAndRefreshUi()
            showSingleToast("3D LUT: OFF")
        }

        builtins.forEach { entry ->
            val mark = if (activeLutSourceId == entry.sourceId) "✓ " else ""
            labels.add("${mark}★ ${entry.displayName} — ${entry.subtitle}")
            actions.add { loadAndActivateCubeLut(entry.sourceId, silent = false) }
        }

        userLuts.forEach { entry ->
            val mark = if (activeLutSourceId == entry.sourceId) "✓ " else ""
            labels.add("${mark}📁 ${entry.displayName} (.cube)")
            actions.add { loadAndActivateCubeLut(entry.sourceId, silent = false) }
        }

        labels.add("+ Import .cube from Phone / Downloads...")
        actions.add { launchCubeFilePicker() }

        labels.add("+ Download .cube from Direct URL...")
        actions.add { showDownloadCubeUrlDialog() }

        if (userLuts.isNotEmpty()) {
            labels.add("🗑 Delete Saved .cube LUT...")
            actions.add { showDeleteUserLutDialog(userLuts) }
        }

        AlertDialog.Builder(this)
            .setTitle("World-Class 3D LUT Library (33³)")
            .setItems(labels.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDeleteUserLutDialog(userLuts: List<SavedLutEntry>) {
        val names = userLuts.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Delete Saved .cube LUT")
            .setItems(names) { _, which ->
                val target = userLuts[which]
                if (CubeLutLibrary.deleteUserLut(this, target.sourceId)) {
                    if (activeLutSourceId == target.sourceId) {
                        clearActiveCubeLut()
                        pushGpuColorStateAndRefreshUi()
                    }
                    showSingleToast("Deleted LUT: ${target.displayName}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAndActivateCubeLut(sourceId: String, silent: Boolean) {
        if (sourceId.isBlank()) {
            clearActiveCubeLut()
            pushGpuColorStateAndRefreshUi()
            return
        }
        Thread {
            try {
                val lut = CubeLutLibrary.loadLutById(this, sourceId)
                runOnUiThread {
                    if (lut != null) {
                        applyLoadedCubeLut(lut)
                        if (!silent) showSingleToast("LUT: ${lut.displayName} (${lut.size}³)")
                    } else if (!silent) {
                        showSingleToast("LUT file not found")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!silent) Toast.makeText(this, "LUT load error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun applyLoadedCubeLut(lut: CubeLutData) {
        activeLutSourceId = lut.sourceId
        activeLutDisplayName = lut.displayName
        engine.setCubeLut(lut)
        if (gpuColorState.lutIntensity < 0.05f) {
            gpuColorState = gpuColorState.copy(lutIntensity = 1.0f)
        }
        pushGpuColorStateAndRefreshUi()
    }

    private fun clearActiveCubeLut() {
        activeLutSourceId = ""
        activeLutDisplayName = "NONE"
        engine.setCubeLut(null)
    }

    private fun pushGpuColorStateAndRefreshUi() {
        engine.updateGpuColorState(gpuColorState)
        suppressColorSeek = true
        seekColorExp.progress = ((gpuColorState.exposureLift / 0.1f) + 15f).roundToInt().coerceIn(0, 30)
        seekColorCon.progress = (((gpuColorState.contrast - 0.50f) / 0.01f)).roundToInt().coerceIn(0, 110)
        seekColorSat.progress = (gpuColorState.saturation * 100f).roundToInt().coerceIn(0, 180)
        seekColorShd.progress = ((gpuColorState.shadows * 100f) + 50f).roundToInt().coerceIn(0, 100)
        seekColorHil.progress = ((gpuColorState.highlights * 100f) + 50f).roundToInt().coerceIn(0, 100)
        seekColorVig.progress = ((gpuColorState.vignette * 100f) + 85f).roundToInt().coerceIn(0, 170)
        seekColorLutMix.progress = (gpuColorState.lutIntensity * 100f).roundToInt().coerceIn(0, 100)
        suppressColorSeek = false

        val orange = 0xFFFF5F1F.toInt()
        val white = 0xFFFFFFFF.toInt()

        txtColorExpVal.text = formatEv(gpuColorState.exposureLift)
        txtColorExpVal.setTextColor(if (kotlin.math.abs(gpuColorState.exposureLift) >= 0.05f) orange else white)

        txtColorConVal.text = String.format(Locale.US, "%.2f", gpuColorState.contrast)
        txtColorConVal.setTextColor(if (kotlin.math.abs(gpuColorState.contrast - 1.0f) >= 0.02f) orange else white)

        txtColorSatVal.text = String.format(Locale.US, "%.2f", gpuColorState.saturation)
        txtColorSatVal.setTextColor(if (kotlin.math.abs(gpuColorState.saturation - 1.0f) >= 0.02f) orange else white)

        val shdInt = (gpuColorState.shadows * 100f).roundToInt()
        txtColorShdVal.text = if (shdInt > 0) "+$shdInt" else "$shdInt"
        txtColorShdVal.setTextColor(if (shdInt != 0) orange else white)

        val hilInt = (gpuColorState.highlights * 100f).roundToInt()
        txtColorHilVal.text = if (hilInt > 0) "+$hilInt" else "$hilInt"
        txtColorHilVal.setTextColor(if (hilInt != 0) orange else white)

        val vigInt = (gpuColorState.vignette * 100f).roundToInt()
        txtColorVigVal.text = if (vigInt > 0) "+${vigInt}%" else "${vigInt}%"
        txtColorVigVal.setTextColor(if (vigInt != 0) orange else white)

        val hasLut = activeLutSourceId.isNotEmpty()
        val lutPct = (gpuColorState.lutIntensity * 100f).roundToInt().coerceIn(0, 100)
        txtColorLutMixVal.text = "${lutPct}%"
        btnSelectCubeLut.text = if (hasLut) "3D LUT: $activeLutDisplayName ▾" else "3D LUT: NONE ▾"
        styleToolPill(btnSelectCubeLut, hasLut)
        if (colorControlDrawer.visibility == View.VISIBLE && colorDrawerHeaderRow.visibility == View.VISIBLE) {
            rowColorLutMix.visibility = if (hasLut) View.VISIBLE else View.GONE
        }

        styleToolPill(btnLookNone, activeLookTag == "NONE" && gpuColorState.isNeutral(hasLut))
        styleToolPill(btnLookLog, activeLookTag == "LOG")
        styleToolPill(btnLookCine, activeLookTag == "FILM")
        styleToolPill(btnLookPunch, activeLookTag == "VIVID")
        styleToolPill(btnLookNoir, activeLookTag == "MONO")
        if (isSplitCompareActive) {
            overlayView.splitRightLabel = getSplitRightLabel()
        }
        updateLookTopPillVisual()
    }

    private fun updateLookTopPillVisual() {
        val drawerOpen = colorControlDrawer.visibility == View.VISIBLE
        val hasGrade = !gpuColorState.isNeutral(activeLutSourceId.isNotEmpty())
        when {
            drawerOpen -> styleToolPill(btnColorPanelToggle, true)
            hasGrade -> {
                btnColorPanelToggle.setBackgroundResource(R.drawable.bg_dock_pill_active)
                btnColorPanelToggle.setTextColor(0xFFFF5F1F.toInt())
            }
            else -> styleToolPill(btnColorPanelToggle, false)
        }
    }

    private fun updateMirrorFrontPillUi() {
        val on = engine.mirrorFrontExport
        btnProMirrorFront.text = if (on) "MIRROR: ON" else "MIRROR: OFF"
        styleToolPill(btnProMirrorFront, on)
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

    private fun selectMicPreference(pref: MicPreference) {
        if (engine.isRecording) {
            showSingleToast("Stop recording to switch microphone input")
            return
        }
        engine.micPreference = pref
        audioMonitorRestartNeeded = true
        updateAudioDrawerUi()
        val extFound = engine.findExternalMicDevice() != null
        showSingleToast(
            when (pref) {
                MicPreference.AUTO -> if (extFound) "Mic Input: AUTO (External Active)" else "Mic Input: AUTO (Internal)"
                MicPreference.INTERNAL -> "Mic Input: INTERNAL Mic"
                MicPreference.EXTERNAL -> if (extFound) "Mic Input: EXTERNAL Wired/USB Mic" else "Mic Input: EXTERNAL (None plugged in yet)"
            }
        )
    }

    private fun updateAudioTopPillVisual() {
        val drawerOpen = audioControlDrawer.visibility == View.VISIBLE
        when {
            engine.isAudioMuted -> btnAudioPanelToggle.setBackgroundResource(R.drawable.bg_hud_pill_muted)
            drawerOpen -> btnAudioPanelToggle.setBackgroundResource(R.drawable.bg_dock_pill_active)
            else -> btnAudioPanelToggle.setBackgroundResource(R.drawable.bg_hud_pill)
        }
    }

    private fun updateAudioDrawerUi() {
        val muted = engine.isAudioMuted
        btnAudioMute.text = if (muted) "MUTED" else "LIVE"
        btnAudioMute.setBackgroundResource(if (muted) R.drawable.bg_shutter_stop else R.drawable.bg_hud_pill)
        btnAudioMute.setTextColor(if (muted) 0xFFFFFFFF.toInt() else 0xFF34C759.toInt())

        styleToolPill(btnAudioMicAuto, engine.micPreference == MicPreference.AUTO)
        styleToolPill(btnAudioMicInt, engine.micPreference == MicPreference.INTERNAL)
        styleToolPill(btnAudioMicExt, engine.micPreference == MicPreference.EXTERNAL)
        val extConnected = engine.findExternalMicDevice() != null
        btnAudioMicExt.text = if (engine.micPreference == MicPreference.EXTERNAL && !extConnected) "EXT?" else "EXT"

        styleToolPill(btnPreampCam, engine.audioPreampMode == AudioPreampMode.CAMCORDER)
        styleToolPill(btnPreampRaw, engine.audioPreampMode == AudioPreampMode.RAW_UNPROCESSED)
        styleToolPill(btnPreampBoost, engine.audioPreampMode == AudioPreampMode.VOICE_BOOST)

        updateAudioTopPillVisual()

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
                if (engine.isRecording || engine.isAudioMuted || audioPreviewPausedForRec || eraMicMonitorPaused) {
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
            if (!showing) closeTopDrawers(except = "ZOOM")
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
        val maxHw = engine.capabilities.maxHardwareFps.coerceIn(15, 60)
        val clamped = fps.coerceIn(1, maxHw)
        if (fps > maxHw) {
            showSingleToast("This lens supports up to $maxHw FPS max — clamped to $maxHw FPS", Toast.LENGTH_LONG)
        } else if (clamped < 20) {
            showSingleToast("Low-FPS GPU Pacer active: recording $clamped frame(s)/sec", Toast.LENGTH_SHORT)
        }
        currentFps = clamped
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
        engine.updateGpuColorState(gpuColorState)
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
            if (currentWorkbench == CameraWorkbench.SAVIOR) {
                currentFps = activeFps
                btnFps.text = "$activeFps FPS"
            }
            updateZoomReadout()
            updateEvDisplay()
            styleToolPill(btnProTorch, engine.isTorchEnabled)
            btnProTorch.alpha = if (caps.hasFlash) 1.0f else 0.4f
            styleToolPill(btnProStab, engine.isStabilizationEnabled)
            updateAutoPilotUi(engine.autoPilotState)
            pillFocus.alpha = if (caps.supportsManualFocus) 1.0f else 0.45f
            pillEraFocus.alpha = if (caps.supportsManualFocus) 1.0f else 0.45f
            if (currentWorkbench == CameraWorkbench.ERA) {
                if (activeEraCamera == EraCameraId.MONO_1950S) {
                    val ratio = era1950sTurretPrimes[era1950sTurretIdx].second
                    engine.setZoomRatio(ratio.coerceIn(caps.minZoom, caps.maxZoom))
                }
                updateEraTorchUi()
                if (eraManualExposureActive && caps.supportsManualExposure) {
                    engine.lockEraManualExposure(eraSelectedAsa, engine.manualShutterNs)
                }
                updateEraIrisUi()
                updateEraFocusUi()
                if (activeEraPanel != EraSliderPanel.NONE) refreshEraSliderUi()
            } else if (activePanel != ProControlPanel.NONE) {
                refreshSliderState()
            }
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

            if (currentWorkbench == CameraWorkbench.ERA && !eraManualExposureActive) {
                txtEraIrisVal.text = "A·${telemetry.iso}"
            }

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
        if (engine.isTimelapseEnabled()) {
            engine.timelapseIntervalMs = 0L
            updateTimelapseUi()
        }

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

        // 3. Rescue GPU Color Studio (reset LOOK & 3D LUT cleanly back to NONE), Tone & Stabilization + Lock & EV
        if (isSplitCompareActive) {
            setSplitCompareMode(false)
        }
        if (!gpuColorState.isNeutral(activeLutSourceId.isNotEmpty()) || activeLookTag != "NONE" || activeLutSourceId.isNotEmpty()) {
            clearActiveCubeLut()
            applyBuiltInLook("NONE", GpuColorState())
        }
        styleToolPill(btnProStab, true)
        updateLockUi(false)

        // 4. Reset custom/low FPS (e.g. 1 FPS) cleanly back to normal 30 FPS & launch two-stage scene meter + lock
        val normalFps = minOf(30, engine.capabilities.maxHardwareFps.coerceIn(15, 60))
        val fpsChanged = (currentFps != normalFps || engine.targetFps != normalFps)
        if (fpsChanged) {
            currentFps = normalFps
            btnFps.text = "$normalFps FPS"
        }
        // 5. Storage readiness check (< 0.5 GB warning)
        val freeGb = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0)
        } catch (_: Exception) { 10.0 }

        engine.engageAutoPilot(resetToFps = normalFps)
        updateEvDisplay()
        val msg = when {
            freeGb < 0.5 -> "Auto Pilot metering • Warning: low storage (${String.format(Locale.US, "%.2f", freeGb)} GB free)!"
            fpsChanged -> "Auto Pilot: resetting to $normalFps FPS & metering..."
            else -> "Auto Pilot optimizing exposure & 180° shutter..."
        }
        showSingleToast(msg, if (freeGb < 0.5) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
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

    override fun onAutoPilotStateChanged(state: AutoPilotState, summary: String?, isLowLight: Boolean) {
        runOnUiThread {
            updateAutoPilotUi(state)
            updateLockUi(engine.isAeAfLocked)
            updateEvDisplay()
            if (activePanel != ProControlPanel.NONE) refreshSliderState()
            if (state == AutoPilotState.LOCKED && !summary.isNullOrBlank()) {
                val audioTag = if (engine.isAudioMuted) "MUTED" else "Audio LIVE"
                showSingleToast("$summary • $audioTag", Toast.LENGTH_LONG)
                if (isLowLight && engine.capabilities.hasFlash && !engine.isTorchEnabled && !engine.isRecording && !isFinishing && !isDestroyed) {
                    showPilotLowLightTorchPrompt(engine.manualIso)
                }
            }
        }
    }

    private fun showPilotLowLightTorchPrompt(lockedIso: Int) {
        AlertDialog.Builder(this)
            .setTitle("Low Light Detected (ISO $lockedIso)")
            .setMessage("The scene is dark. Turning on the Flashlight (Torch) will cut grain and give sharper autofocus — then Auto Pilot will automatically re-balance exposure.")
            .setPositiveButton("TORCH ON & RE-METER") { _, _ ->
                if (!engine.isRecording) {
                    val on = engine.setTorchEnabled(true)
                    styleToolPill(btnProTorch, on)
                    if (on) {
                        engine.engageAutoPilot()
                        showSingleToast("Torch ON — Auto Pilot re-metering scene...")
                    }
                }
            }
            .setNegativeButton("KEEP DARK", null)
            .show()
    }

    override fun onRecordingStarted() {
        runOnUiThread {
            recStartMs = System.currentTimeMillis()
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_stop)
            btnShutterInner.animate().scaleX(0.54f).scaleY(0.54f).setDuration(140L).start()
            uiHandler.removeCallbacks(timecodeRunnable)
            if (currentWorkbench == CameraWorkbench.ERA) {
                viewEraRecDot.visibility = View.VISIBLE
                txtEraFootage.setTextColor(0xFFFF3B30.toInt())
                txtEraFootage.text = "000 FT • 00:00"
                updateEraRecordingLockVisuals(true)
                uiHandler.post(timecodeRunnable)
            } else {
                viewRecDot.visibility = View.VISIBLE
                txtTimecode.setTextColor(0xFFFF3B30.toInt())
                btnAutoPilot.alpha = 0.45f
                if (engine.isTimelapseEnabled()) {
                    txtTimecode.text = "TL 0f • 00:00.0"
                } else {
                    uiHandler.post(timecodeRunnable)
                }
                if (isSplitCompareActive) {
                    showSingleToast("Split is preview-only — recording full graded video")
                }
            }
        }
    }

    override fun onTimelapseProgress(capturedFrames: Long, outputDurationSec: Float) {
        runOnUiThread {
            if (engine.isRecording && engine.isTimelapseEnabled()) {
                val mins = (outputDurationSec / 60f).toInt()
                val secs = outputDurationSec - (mins * 60f)
                txtTimecode.text = String.format(Locale.US, "TL %df • %02d:%04.1f", capturedFrames, mins, secs)
            }
        }
    }

    override fun onRecordingStopped(savedUri: Uri?, filePathLabel: String) {
        audioPreviewPausedForRec = false
        runOnUiThread {
            uiHandler.removeCallbacks(timecodeRunnable)
            viewRecDot.visibility = View.GONE
            viewEraRecDot.visibility = View.GONE
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_rec)
            btnShutterInner.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140L).start()
            btnAutoPilot.alpha = 1.0f
            if (currentWorkbench == CameraWorkbench.ERA) {
                txtEraFootage.text = "000 FT • 00:00"
                txtEraFootage.setTextColor(0xFFF5E6C8.toInt())
                updateEraRecordingLockVisuals(false)
            } else {
                updateTimelapseUi()
            }
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
        if (!batteryReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                }
                registerReceiver(batteryBroadcastReceiver, filter)
                batteryReceiverRegistered = true
            } catch (_: Exception) {
            }
        }
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
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryBroadcastReceiver) } catch (_: Exception) {}
            batteryReceiverRegistered = false
        }
        uiHandler.removeCallbacks(sysTelemetryRunnable)
        uiHandler.removeCallbacks(countdownRunnable)
        txtTimerCountdown.visibility = View.GONE
        sensorManager?.unregisterListener(horizonSensorListener)
        engine.stopThread()
        super.onPause()
    }
}
