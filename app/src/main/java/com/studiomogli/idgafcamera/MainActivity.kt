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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.res.ColorStateList
import android.widget.EditText
import android.widget.ImageView
import androidx.core.widget.ImageViewCompat
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import com.studiomogli.idgafcamera.era.EraCameraPlugin
import com.studiomogli.idgafcamera.era.EraHostBridge
import com.studiomogli.idgafcamera.era.EraPluginRegistry
import com.studiomogli.idgafcamera.era.EraRenderConfig
import com.studiomogli.idgafcamera.era.EraWorkbenchCategory
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
enum class EraDrawerMode { BOTH, WORKBENCH_ONLY, ERA_VAULT_ONLY }

data class SaviorWorkbenchSnapshot(
    val resolution: CinemaResolution,
    val bitrateMbps: Int,
    val codec: CinemaCodec,
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
    val splitCompareFraction: Float,
    val recordAspectMode: RecordAspectMode = RecordAspectMode.WIDE_16_9,
    val aspectGuide: AspectGuide = AspectGuide.OFF,
    val frameCropRecEnabled: Boolean = false
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
    private lateinit var proSettingsBar: LinearLayout
    private lateinit var proTimerSubPanel: LinearLayout
    private lateinit var proTimerRow: LinearLayout
    private lateinit var proTimelapseSubPanel: LinearLayout
    private lateinit var proTimelapseRow1: LinearLayout
    private lateinit var proTimelapseRow2: LinearLayout
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
    private var timerDelaySec = 0
    private var countdownRemaining = 0
    private var pendingRecRotation = Surface.ROTATION_90
    private lateinit var btnBrandCredits: TextView
    private lateinit var txtSysTelemetry: TextView
    private lateinit var txtLowBatteryBanner: TextView
    private var isBatteryCharging: Boolean = false
    private var lastBatteryPct: Int = 100
    private var lowBatteryWarned15: Boolean = false
    private var lowBatteryWarned7: Boolean = false
    private var lowBatteryBannerDismissed: Boolean = false
    private var batteryReceiverRegistered: Boolean = false
    private lateinit var presetStore: PresetStore
    private var updateDownloadUrl: String? = null
    private var updateLatestTag: String? = null
    private var activeCreditsUpdateBtn: TextView? = null
    private var activeCreditsDialog: AlertDialog? = null
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
    private lateinit var monitorToolsBar: LinearLayout
    private lateinit var monConfigSubPanel: LinearLayout
    private lateinit var monPeakColorRow: LinearLayout
    private lateinit var monPeakSensRow: LinearLayout
    private lateinit var monZebraLevelRow: LinearLayout
    private lateinit var btnToolGrid: TextView
    private lateinit var btnToolPeak: TextView
    private lateinit var btnToolZebra: TextView
    private lateinit var btnToolFalseColor: TextView
    private lateinit var btnToolHist: TextView
    private lateinit var btnToolHorizon: TextView
    private lateinit var btnToolOutMon: TextView
    private lateinit var btnToolMonConfig: TextView
    private lateinit var outputMonitorCard: LinearLayout
    private lateinit var viewOutMonDot: View
    private lateinit var txtOutMonBadge: TextView
    private lateinit var btnOutMonClose: TextView
    private lateinit var btnOutMonResize: TextView
    private lateinit var outputMonitorTextureView: TextureView
    private var isOutputMonitorEnabled: Boolean = false
    private var outputMonitorScale: Float = 1.0f
    private var outMonLastDragX: Float = 0f
    private var outMonLastDragY: Float = 0f
    private var outMonResizeLastX: Float = 0f
    private var outMonResizeLastY: Float = 0f
    private var sensorManager: SensorManager? = null
    private var smoothedRoll = 0f

    // SAVIOR Core + Drop-In Era Plug-In Mount (ZERO hardcoded Era code in SAVIOR core!)
    private lateinit var saviorTopBar: LinearLayout
    private lateinit var saviorBottomDock: LinearLayout
    private lateinit var btnWorkbenchToEra: TextView
    private lateinit var zoomRockerContainer: LinearLayout
    private lateinit var eraPluginContainer: FrameLayout
    private var currentWorkbench: CameraWorkbench = CameraWorkbench.SAVIOR
    private var installedEraPlugins: List<EraCameraPlugin> = emptyList()
    private var activeEraPlugin: EraCameraPlugin? = null
    @Volatile private var eraMicMonitorPaused: Boolean = false
    private var saviorSnapshot: SaviorWorkbenchSnapshot? = null

    private lateinit var txtTimecode: TextView
    private lateinit var viewRecDot: View
    private lateinit var btnFormatToggle: TextView
    private lateinit var leftRailWrapper: LinearLayout
    private lateinit var btnCleanUiToggle: ImageView
    private lateinit var btnRestoreCleanUi: ImageView
    private lateinit var rightRailContainer: LinearLayout
    private lateinit var formatDrawer: LinearLayout
    private lateinit var txtFormatSummary: TextView
    private lateinit var btnFormatClose: TextView
    private lateinit var formatResRow: LinearLayout
    private lateinit var formatFpsRow: LinearLayout
    private lateinit var formatBitrateRow: LinearLayout
    private lateinit var formatFrameRatioRow: LinearLayout
    private lateinit var formatCodecAspectRow: LinearLayout
    private lateinit var workbenchEraDrawer: LinearLayout
    private lateinit var txtEraDrawerTitle: TextView
    private lateinit var workbenchSection: LinearLayout
    private lateinit var eraVaultSection: LinearLayout
    private lateinit var btnEraDrawerClose: TextView
    private lateinit var workbenchSelectRow: LinearLayout
    private lateinit var eraVaultSelectRow: LinearLayout
    private lateinit var eraVaultSelectRow2: LinearLayout
    private lateinit var txtEraVaultDesc: TextView
    private var activeEraDrawerMode: EraDrawerMode = EraDrawerMode.BOTH
    private lateinit var cinemaInputDrawer: LinearLayout
    private lateinit var txtCinemaInputTitle: TextView
    private lateinit var txtCinemaInputHint: TextView
    private lateinit var btnCinemaInputCancel: TextView
    private lateinit var btnCinemaInputMinus: TextView
    private lateinit var edtCinemaInput: EditText
    private lateinit var btnCinemaInputPlus: TextView
    private lateinit var btnCinemaInputApply: TextView
    private var cinemaInputStep: Float = 1f
    private var cinemaInputAllowDecimal: Boolean = false
    private var cinemaInputOnApply: ((String) -> Boolean)? = null
    private var isCleanFrameMode: Boolean = false
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
    private lateinit var btnWbCustom: TextView
    private lateinit var btnSliderAuto: TextView
    private lateinit var btnIsoCustom: TextView
    private lateinit var isoPresetRow: LinearLayout
    private lateinit var btnShutterAntiFlicker: TextView
    private lateinit var btnShutterCustom: TextView
    private lateinit var btnShutterUnit: TextView
    private lateinit var shutterPresetRow: LinearLayout
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
    private val shutterDenoms = listOf(24, 25, 30, 33, 48, 50, 60, 100, 120, 200, 250, 500, 1000, 2000, 4000)

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
                overlayView.displayRotation = curRot
                engine.setDisplayRotation(curRot)
                syncOutputMonitorUi()
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
                    activeEraPlugin?.onRecordingTick(eraHostBridge, elapsedMs)
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
        proTimerSubPanel = findViewById(R.id.proTimerSubPanel)
        proTimerRow = findViewById(R.id.proTimerRow)
        proTimelapseSubPanel = findViewById(R.id.proTimelapseSubPanel)
        proTimelapseRow1 = findViewById(R.id.proTimelapseRow1)
        proTimelapseRow2 = findViewById(R.id.proTimelapseRow2)
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
        txtLowBatteryBanner.setOnClickListener {
            lowBatteryBannerDismissed = true
            txtLowBatteryBanner.visibility = View.GONE
        }
        presetStore = PresetStore(this)
        btnMonitorToggle = findViewById(R.id.btnMonitorToggle)
        monitorToolsBar = findViewById(R.id.monitorToolsBar)
        monConfigSubPanel = findViewById(R.id.monConfigSubPanel)
        monPeakColorRow = findViewById(R.id.monPeakColorRow)
        monPeakSensRow = findViewById(R.id.monPeakSensRow)
        monZebraLevelRow = findViewById(R.id.monZebraLevelRow)
        btnToolGrid = findViewById(R.id.btnToolGrid)
        btnToolPeak = findViewById(R.id.btnToolPeak)
        btnToolZebra = findViewById(R.id.btnToolZebra)
        btnToolFalseColor = findViewById(R.id.btnToolFalseColor)
        btnToolHist = findViewById(R.id.btnToolHist)
        btnToolHorizon = findViewById(R.id.btnToolHorizon)
        btnToolOutMon = findViewById(R.id.btnToolOutMon)
        btnToolMonConfig = findViewById(R.id.btnToolMonConfig)
        outputMonitorCard = findViewById(R.id.outputMonitorCard)
        viewOutMonDot = findViewById(R.id.viewOutMonDot)
        txtOutMonBadge = findViewById(R.id.txtOutMonBadge)
        btnOutMonClose = findViewById(R.id.btnOutMonClose)
        btnOutMonResize = findViewById(R.id.btnOutMonResize)
        outputMonitorTextureView = findViewById(R.id.outputMonitorTextureView)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        lastDisplayRotation = getCurrentDisplayRotation()
        overlayView.displayRotation = lastDisplayRotation
        saviorTopBar = findViewById(R.id.saviorTopBar)
        saviorBottomDock = findViewById(R.id.saviorBottomDock)
        btnWorkbenchToEra = findViewById(R.id.btnWorkbenchToEra)
        zoomRockerContainer = findViewById(R.id.zoomRockerContainer)
        eraPluginContainer = findViewById(R.id.eraPluginContainer)
        installedEraPlugins = EraPluginRegistry.discoverAll()

        txtTimecode = findViewById(R.id.txtTimecode)
        viewRecDot = findViewById(R.id.viewRecDot)
        btnFormatToggle = findViewById(R.id.btnFormatToggle)
        leftRailWrapper = findViewById(R.id.leftRailWrapper)
        btnCleanUiToggle = findViewById(R.id.btnCleanUiToggle)
        btnRestoreCleanUi = findViewById(R.id.btnRestoreCleanUi)
        rightRailContainer = findViewById(R.id.rightRailContainer)
        formatDrawer = findViewById(R.id.formatDrawer)
        txtFormatSummary = findViewById(R.id.txtFormatSummary)
        btnFormatClose = findViewById(R.id.btnFormatClose)
        formatResRow = findViewById(R.id.formatResRow)
        formatFpsRow = findViewById(R.id.formatFpsRow)
        formatBitrateRow = findViewById(R.id.formatBitrateRow)
        formatFrameRatioRow = findViewById(R.id.formatFrameRatioRow)
        formatCodecAspectRow = findViewById(R.id.formatCodecAspectRow)
        workbenchEraDrawer = findViewById(R.id.workbenchEraDrawer)
        txtEraDrawerTitle = findViewById(R.id.txtEraDrawerTitle)
        workbenchSection = findViewById(R.id.workbenchSection)
        eraVaultSection = findViewById(R.id.eraVaultSection)
        btnEraDrawerClose = findViewById(R.id.btnEraDrawerClose)
        workbenchSelectRow = findViewById(R.id.workbenchSelectRow)
        eraVaultSelectRow = findViewById(R.id.eraVaultSelectRow)
        eraVaultSelectRow2 = findViewById(R.id.eraVaultSelectRow2)
        txtEraVaultDesc = findViewById(R.id.txtEraVaultDesc)
        cinemaInputDrawer = findViewById(R.id.cinemaInputDrawer)
        txtCinemaInputTitle = findViewById(R.id.txtCinemaInputTitle)
        txtCinemaInputHint = findViewById(R.id.txtCinemaInputHint)
        btnCinemaInputCancel = findViewById(R.id.btnCinemaInputCancel)
        btnCinemaInputMinus = findViewById(R.id.btnCinemaInputMinus)
        edtCinemaInput = findViewById(R.id.edtCinemaInput)
        btnCinemaInputPlus = findViewById(R.id.btnCinemaInputPlus)
        btnCinemaInputApply = findViewById(R.id.btnCinemaInputApply)
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
        btnWbCustom = findViewById(R.id.btnWbCustom)
        btnSliderAuto = findViewById(R.id.btnSliderAuto)
        btnIsoCustom = findViewById(R.id.btnIsoCustom)
        isoPresetRow = findViewById(R.id.isoPresetRow)
        btnShutterAntiFlicker = findViewById(R.id.btnShutterAntiFlicker)
        btnShutterCustom = findViewById(R.id.btnShutterCustom)
        btnShutterUnit = findViewById(R.id.btnShutterUnit)
        shutterPresetRow = findViewById(R.id.shutterPresetRow)
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
        setupUnifiedInlineDrawersAndCleanUi()

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
                if (cinemaInputDrawer.visibility == View.VISIBLE) {
                    closeCinemaInputDrawer()
                } else if (workbenchEraDrawer.visibility == View.VISIBLE) {
                    closeWorkbenchEraDrawer()
                } else if (currentWorkbench == CameraWorkbench.ERA && activeEraPlugin?.onViewfinderTapped(eraHostBridge) == true) {
                    // Consumed by active Era Plug-In
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
                showSingleToast("Developing audio track...")
                return@setOnClickListener
            }
            if (engine.isRecording) {
                val p = activeEraPlugin
                if (currentWorkbench == CameraWorkbench.ERA && p != null && p.usesPostAudioProcessor && !p.isSilentAudio) {
                    uiHandler.removeCallbacks(timecodeRunnable)
                    p.onRecordingFinalizing(eraHostBridge)
                }
                engine.stopRecording()
            } else {
                val rot = getCurrentDisplayRotation()
                if (currentWorkbench == CameraWorkbench.ERA) {
                    beginRecordingCleanly(rot)
                } else if (engine.isAudioMuted && !engine.isTimelapseEnabled()) {
                    IdgafUiDialogs.showActionDialog(
                        context = this,
                        title = "AUDIO MUTED",
                        message = "Microphone is muted. Unmute before recording, or record silent video?",
                        primaryLabel = "UNMUTE & REC",
                        onPrimary = {
                            engine.isAudioMuted = false
                            audioMonitorRestartNeeded = true
                            updateAudioDrawerUi()
                            launchRecordingOrTimer(rot)
                        },
                        secondaryLabel = "REC SILENT",
                        onSecondary = { launchRecordingOrTimer(rot) },
                        cancelLabel = "CANCEL"
                    )
                } else {
                    launchRecordingOrTimer(rot)
                }
            }
        }

        btnFormatToggle.setOnClickListener {
            if (engine.isRecording) {
                showSingleToast("Stop recording to change format")
                return@setOnClickListener
            }
            val showing = formatDrawer.visibility == View.VISIBLE
            if (!showing) closeTopDrawers(except = "FORMAT")
            formatDrawer.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) renderFormatDrawerChips()
            updateFormatPillUi()
            syncLowBatteryBannerVisibility()
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
        val delaySec = timerDelaySec
        if (delaySec > 0) {
            pendingRecRotation = rot
            countdownRemaining = delaySec
            uiHandler.removeCallbacks(countdownRunnable)
            uiHandler.post(countdownRunnable)
        } else {
            beginRecordingCleanly(rot)
        }
    }

    // ==================== FROZEN SAVIOR -> DROP-IN ERA PLUG-IN HOST BRIDGE ====================

    private val eraHostBridge: EraHostBridge = object : EraHostBridge {
        override fun openWorkbenchDrawer() {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to switch workbench")
                return
            }
            toggleWorkbenchEraDrawer(EraDrawerMode.WORKBENCH_ONLY)
        }

        override fun openEraVaultDrawer() {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to select Era camera")
                return
            }
            toggleWorkbenchEraDrawer(EraDrawerMode.ERA_VAULT_ONLY)
        }

        override fun exitToSavior() {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to switch workbench")
                return
            }
            exitEraToSaviorWorkbench()
        }

        override fun pushRenderConfig(config: EraRenderConfig) {
            engine.updateEraRenderConfig(config)
        }

        override fun applyFormat(resolution: CinemaResolution, fps: Int, bitrateMbps: Int, codec: CinemaCodec) {
            val sup = engine.capabilities.supportedResolutions
            val resolvedRes = when {
                resolution in sup -> resolution
                CinemaResolution.FHD_1080P in sup -> CinemaResolution.FHD_1080P
                else -> sup.lastOrNull { it.width <= resolution.width } ?: sup.firstOrNull() ?: CinemaResolution.FHD_1080P
            }
            engine.targetBitrateMbps = bitrateMbps.coerceIn(2, 120)
            engine.targetCodec = codec
            if (engine.targetResolution != resolvedRes) {
                engine.updateSettings(resolvedRes, fps)
            } else {
                engine.setTargetFpsSeamless(fps)
            }
        }

        override fun setTargetFpsSeamless(fps: Int) = engine.setTargetFpsSeamless(fps)
        override fun setAutoExposure() = engine.setAutoExposure()
        override fun setEvCompensation(index: Int): Boolean = engine.setEvCompensation(index)
        override fun lockManualExposure(iso: Int, shutterNs: Long) = engine.lockEraManualExposure(iso, shutterNs)
        override fun setAutoFocus() = engine.setAutoFocus()
        override fun setManualFocusDistance(diopters: Float) = engine.setManualFocusDistance(diopters)
        override fun toggleTorch(): Boolean {
            val on = engine.toggleTorch()
            styleToolPill(btnProTorch, on)
            return on
        }
        override fun toggleFrontBackCamera(): Boolean = engine.toggleFrontBackCamera()

        override fun cycleAntiFlickerMode(): AntiFlickerMode {
            this@MainActivity.cycleAntiFlickerMode()
            return engine.antiFlickerMode
        }
        override fun getAntiFlickerMode(): AntiFlickerMode = engine.antiFlickerMode
        override fun compute180ShutterNs(fps: Int): Long = engine.compute180ShutterNs(fps)
        override fun snapShutterToAntiFlickerNs(desiredNs: Long, maxAllowedNs: Long): Long =
            engine.snapShutterToAntiFlickerNs(desiredNs, maxAllowedNs)

        override fun getCapabilities(): CameraCapabilities = engine.capabilities
        override fun getLiveIso(): Int = engine.getLiveSensorIso()
        override fun getLiveShutterNs(): Long = engine.getLiveSensorShutterNs()
        override fun getCurrentEvStops(): Float = engine.getCurrentEvStops()
        override fun getEvCompensationIndex(): Int = engine.evCompensationIndex
        override fun isManualExposure(): Boolean = engine.isManualExposure
        override fun getManualIso(): Int = engine.manualIso
        override fun getManualShutterNs(): Long = engine.manualShutterNs
        override fun isManualFocus(): Boolean = engine.isManualFocus
        override fun getManualFocusDistance(): Float = engine.manualFocusDistance
        override fun isTorchEnabled(): Boolean = engine.isTorchEnabled
        override fun isRecording(): Boolean = engine.isRecording
        override fun isFinalizingClip(): Boolean = engine.isFinalizingClip

        override fun showToast(message: String, longDuration: Boolean) {
            showSingleToast(message, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        }

        override fun openNumericInput(
            title: String,
            hint: String,
            initialValue: String,
            allowDecimal: Boolean,
            step: Float,
            classicStyle: Boolean,
            onApply: (String) -> Boolean
        ) {
            openCinemaInputDrawer(
                title = title,
                hint = hint,
                initialValue = initialValue,
                allowDecimal = allowDecimal,
                step = step,
                isClassicStyle = classicStyle,
                onApply = onApply
            )
        }
    }

    private fun setupEraWorkbench() {
        btnWorkbenchToEra.setOnClickListener {
            if (engine.isRecording || engine.isFinalizingClip) {
                showSingleToast("Stop recording to switch workbench")
                return@setOnClickListener
            }
            toggleWorkbenchEraDrawer(EraDrawerMode.BOTH)
        }
    }

    // ==================== PHASE 7.5.1: UNIFIED INLINE DRAWERS, CLEAN-FRAME EYE & CINEMA INPUT BAR ====================

    private fun setupUnifiedInlineDrawersAndCleanUi() {
        btnCleanUiToggle.setOnClickListener { setCleanFrameMode(true) }
        btnRestoreCleanUi.setOnClickListener { setCleanFrameMode(false) }

        btnFormatClose.setOnClickListener {
            formatDrawer.visibility = View.GONE
            updateFormatPillUi()
            syncLowBatteryBannerVisibility()
        }

        btnEraDrawerClose.setOnClickListener {
            closeWorkbenchEraDrawer()
        }

        btnCinemaInputCancel.setOnClickListener {
            closeCinemaInputDrawer()
        }

        btnCinemaInputApply.setOnClickListener {
            submitCinemaInputDrawer()
        }

        edtCinemaInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCinemaInputDrawer()
                true
            } else {
                false
            }
        }

        // Open keyboard ONLY when the user explicitly taps the text box (NEVER auto-popup on drawer open!)
        edtCinemaInput.setOnClickListener {
            edtCinemaInput.requestFocus()
            edtCinemaInput.setSelection(edtCinemaInput.text?.length ?: 0)
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(edtCinemaInput, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {
            }
        }

        btnCinemaInputMinus.setOnClickListener { stepCinemaInputValue(-1f) }
        btnCinemaInputPlus.setOnClickListener { stepCinemaInputValue(+1f) }

        updateFormatPillUi()
        updateCleanUiToggleStyle()
    }

    private fun updateCleanUiToggleStyle() {
        val plugin = activeEraPlugin
        if (currentWorkbench == CameraWorkbench.ERA && plugin != null) {
            btnCleanUiToggle.setBackgroundResource(R.drawable.bg_era_pill)
            ImageViewCompat.setImageTintList(btnCleanUiToggle, ColorStateList.valueOf(plugin.accentColorArgb))
        } else {
            btnCleanUiToggle.setBackgroundResource(R.drawable.bg_hud_pill)
            ImageViewCompat.setImageTintList(btnCleanUiToggle, ColorStateList.valueOf(0xFFFFFFFF.toInt()))
        }
    }

    private fun setCleanFrameMode(enabled: Boolean) {
        if (isCleanFrameMode == enabled) return
        isCleanFrameMode = enabled
        if (enabled) {
            closeCinemaInputDrawer()
            closeWorkbenchEraDrawer()
            closeAllDrawers()
            saviorTopBar.visibility = View.GONE
            saviorBottomDock.visibility = View.GONE
            eraPluginContainer.visibility = View.GONE
            leftRailWrapper.visibility = View.GONE
            zoomRockerContainer.visibility = View.GONE
            rightRailContainer.visibility = View.GONE
            txtLowBatteryBanner.visibility = View.GONE
            btnExitSplitWipe.visibility = View.GONE
            outputMonitorCard.visibility = View.GONE

            btnRestoreCleanUi.visibility = View.VISIBLE
            updateRestoreCleanEyeBadge()
        } else {
            btnRestoreCleanUi.visibility = View.GONE
            leftRailWrapper.visibility = View.VISIBLE
            rightRailContainer.visibility = View.VISIBLE
            if (currentWorkbench == CameraWorkbench.ERA) {
                eraPluginContainer.visibility = View.VISIBLE
                lensRail.visibility = View.GONE
            } else {
                saviorTopBar.visibility = View.VISIBLE
                saviorBottomDock.visibility = View.VISIBLE
                lensRail.visibility = View.VISIBLE
                zoomRockerContainer.visibility = View.VISIBLE
                syncExitSplitPillVisibility()
                if (isOutputMonitorEnabled) outputMonitorCard.visibility = View.VISIBLE
            }
            updateCleanUiToggleStyle()
            syncLowBatteryBannerVisibility()
        }
    }

    private fun updateRestoreCleanEyeBadge() {
        if (!isCleanFrameMode) return
        if (engine.isRecording) {
            ImageViewCompat.setImageTintList(btnRestoreCleanUi, ColorStateList.valueOf(0xFFFF3B30.toInt()))
            btnRestoreCleanUi.setBackgroundResource(R.drawable.bg_hud_pill_muted)
            btnRestoreCleanUi.alpha = 0.80f
        } else {
            ImageViewCompat.setImageTintList(btnRestoreCleanUi, ColorStateList.valueOf(0xFFFFFFFF.toInt()))
            btnRestoreCleanUi.setBackgroundResource(R.drawable.bg_hud_pill)
            btnRestoreCleanUi.alpha = 0.42f
        }
    }

    private fun toggleWorkbenchEraDrawer(mode: EraDrawerMode) {
        val alreadyOpenSameMode = (workbenchEraDrawer.visibility == View.VISIBLE && activeEraDrawerMode == mode)
        if (alreadyOpenSameMode) {
            closeWorkbenchEraDrawer()
            return
        }
        closeTopDrawers(except = "ERA_VAULT")
        activeEraDrawerMode = mode
        renderWorkbenchEraDrawerChips(mode)
        workbenchEraDrawer.visibility = View.VISIBLE
        if (currentWorkbench == CameraWorkbench.SAVIOR) {
            btnWorkbenchToEra.setBackgroundResource(R.drawable.bg_lens_active)
            btnWorkbenchToEra.setTextColor(0xFF0B0D10.toInt())
        }
        syncLowBatteryBannerVisibility()
    }

    private fun closeWorkbenchEraDrawer() {
        if (workbenchEraDrawer.visibility == View.VISIBLE) {
            workbenchEraDrawer.visibility = View.GONE
        }
        btnWorkbenchToEra.setBackgroundResource(R.drawable.bg_hud_pill)
        btnWorkbenchToEra.setTextColor(0xFFFF5F1F.toInt())
        syncLowBatteryBannerVisibility()
    }

    private fun renderWorkbenchEraDrawerChips(mode: EraDrawerMode) {
        val showWb = (mode == EraDrawerMode.BOTH || mode == EraDrawerMode.WORKBENCH_ONLY)
        val showVault = (mode == EraDrawerMode.BOTH || mode == EraDrawerMode.ERA_VAULT_ONLY)

        workbenchSection.visibility = if (showWb) View.VISIBLE else View.GONE
        eraVaultSection.visibility = if (showVault) View.VISIBLE else View.GONE
        txtEraDrawerTitle.text = when (mode) {
            EraDrawerMode.WORKBENCH_ONLY -> "SWITCH WORKBENCH"
            EraDrawerMode.ERA_VAULT_ONLY -> "ERA CAMERAS"
            EraDrawerMode.BOTH -> "WORKBENCH & ERA VAULT"
        }

        val eraPlugins = installedEraPlugins.filter { it.category == EraWorkbenchCategory.ERA }
        val puristPlugin = installedEraPlugins.firstOrNull { it.category == EraWorkbenchCategory.PURIST }
        val creatorPlugin = installedEraPlugins.firstOrNull { it.category == EraWorkbenchCategory.CREATOR }

        if (showWb) {
            workbenchSelectRow.removeAllViews()
            val wbEntries = mutableListOf(
                Triple("SAVIOR", currentWorkbench == CameraWorkbench.SAVIOR) {
                    closeWorkbenchEraDrawer()
                    exitEraToSaviorWorkbench()
                },
                Triple("ERA", currentWorkbench == CameraWorkbench.ERA && activeEraPlugin?.category == EraWorkbenchCategory.ERA) {
                    val target = activeEraPlugin?.takeIf { it.category == EraWorkbenchCategory.ERA } ?: eraPlugins.firstOrNull()
                    if (target != null) {
                        closeWorkbenchEraDrawer()
                        enterEraPlugin(target)
                    } else {
                        txtEraVaultDesc.text = "Era Cameras — Coming Soon"
                        showSingleToast("Era Cameras — Coming Soon")
                    }
                },
                Triple("CLASSIFIED", currentWorkbench == CameraWorkbench.ERA && activeEraPlugin?.category == EraWorkbenchCategory.PURIST) {
                    if (puristPlugin != null) {
                        closeWorkbenchEraDrawer()
                        enterEraPlugin(puristPlugin)
                    } else {
                        txtEraVaultDesc.text = "CLASSIFIED — Locked Future Workflow"
                        showSingleToast("CLASSIFIED — Locked")
                    }
                }
            )
            if (creatorPlugin != null) {
                wbEntries.add(
                    Triple(creatorPlugin.chipLabel, currentWorkbench == CameraWorkbench.ERA && activeEraPlugin?.id == creatorPlugin.id) {
                        closeWorkbenchEraDrawer()
                        enterEraPlugin(creatorPlugin)
                    }
                )
            }

            wbEntries.forEachIndexed { idx, (label, active, onClick) ->
                val isAvailable = when (idx) {
                    0 -> true
                    1 -> eraPlugins.isNotEmpty()
                    2 -> puristPlugin != null
                    else -> true
                }
                val chip = TextView(this).apply {
                    text = label
                    textSize = 9.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    alpha = if (isAvailable) 1.0f else 0.42f
                    setBackgroundResource(if (active) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                    setTextColor(if (active) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (idx < wbEntries.lastIndex) marginEnd = dpToPx(5)
                    }
                    setOnClickListener { onClick() }
                }
                workbenchSelectRow.addView(chip)
            }
        }

        if (showVault) {
            eraVaultSelectRow.removeAllViews()
            eraVaultSelectRow2.removeAllViews()

            if (eraPlugins.isEmpty()) {
                eraVaultSelectRow2.visibility = View.GONE
                val emptyChip = TextView(this).apply {
                    text = "COMING SOON"
                    textSize = 8.8f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_hud_pill)
                    setTextColor(0xFF8E96A4.toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
                }
                eraVaultSelectRow.addView(emptyChip)
            } else {
                val row1Plugins = eraPlugins.take(3)
                val row2Plugins = eraPlugins.drop(3).take(3)

                fun fillRow(rowView: LinearLayout, plugins: List<EraCameraPlugin>) {
                    plugins.forEachIndexed { idx, plugin ->
                        val active = (currentWorkbench == CameraWorkbench.ERA && activeEraPlugin?.id == plugin.id)
                        val chip = TextView(this).apply {
                            text = plugin.chipLabel
                            textSize = 9f
                            typeface = android.graphics.Typeface.MONOSPACE
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setBackgroundResource(if (active) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                            setTextColor(if (active) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                                if (idx < plugins.lastIndex) marginEnd = dpToPx(5)
                            }
                            setOnClickListener {
                                closeWorkbenchEraDrawer()
                                enterEraPlugin(plugin)
                            }
                        }
                        rowView.addView(chip)
                    }
                }

                fillRow(eraVaultSelectRow, row1Plugins)
                if (row2Plugins.isNotEmpty()) {
                    eraVaultSelectRow2.visibility = View.VISIBLE
                    fillRow(eraVaultSelectRow2, row2Plugins)
                } else {
                    eraVaultSelectRow2.visibility = View.GONE
                }
            }
        }

        txtEraVaultDesc.text = when {
            activeEraPlugin != null -> activeEraPlugin!!.description
            eraPlugins.isEmpty() -> "SAVIOR Cinema Engine • Classic Era Cameras Coming Soon"
            else -> "SAVIOR Cinema Engine • ${eraPlugins.size} Era Camera(s) Ready"
        }
    }

    private fun updateFormatPillUi() {
        val isPort = (engine.recordAspectMode == RecordAspectMode.VERT_9_16_PORTRAIT)
        val hasCropRec = !isPort && engine.frameCropRecEnabled &&
            overlayView.aspectGuide != AspectGuide.OFF && overlayView.aspectGuide != AspectGuide.WIDE_169
        btnFormatToggle.text = when {
            isPort -> "9:16P • $currentFps"
            hasCropRec -> "${overlayView.aspectGuide.label} • $currentFps"
            else -> "${currentResolution.label} • $currentFps"
        }
        val open = formatDrawer.visibility == View.VISIBLE
        if (open) {
            styleToolPill(btnFormatToggle, true)
        } else {
            btnFormatToggle.setBackgroundResource(R.drawable.bg_hud_pill)
            btnFormatToggle.setTextColor(0xFFFF5F1F.toInt())
        }
        val aspectShort = when {
            isPort -> "9:16P"
            hasCropRec -> "${overlayView.aspectGuide.label} CROP"
            overlayView.aspectGuide != AspectGuide.OFF -> "${overlayView.aspectGuide.label} GUIDE"
            else -> "16:9"
        }
        txtFormatSummary.text = "${currentResolution.label} • $currentFps FPS • ${engine.targetBitrateMbps}M • $aspectShort"
    }

    private fun renderFormatDrawerChips() {
        formatResRow.removeAllViews()
        val allRes = CinemaResolution.values()
        val supported = engine.capabilities.supportedResolutions
        allRes.forEachIndexed { idx, res ->
            val ok = res in supported
            val selected = (res == currentResolution)
            val chip = TextView(this).apply {
                text = res.label
                textSize = 9.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                alpha = if (ok) 1.0f else 0.35f
                setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                setTextColor(if (selected) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx < allRes.lastIndex) marginEnd = dpToPx(4)
                }
                setOnClickListener {
                    if (!ok) {
                        showSingleToast("${res.label} is not supported by this lens hardware")
                        return@setOnClickListener
                    }
                    applySelectedResolution(res)
                }
            }
            formatResRow.addView(chip)
        }

        formatFpsRow.removeAllViews()
        val maxHw = engine.capabilities.maxHardwareFps.coerceIn(15, 60)
        val presets = listOf(20, 24, 30, 60)
        val isCustom = currentFps !in presets
        presets.forEachIndexed { idx, fps ->
            val ok = fps <= maxHw
            val selected = (currentFps == fps)
            val chip = TextView(this).apply {
                text = "$fps"
                textSize = 9.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                alpha = if (ok) 1.0f else 0.35f
                setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                setTextColor(if (selected) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = dpToPx(4)
                }
                setOnClickListener {
                    applySelectedFps(fps)
                    renderFormatDrawerChips()
                }
            }
            formatFpsRow.addView(chip)
        }
        val customChip = TextView(this).apply {
            text = if (isCustom) "${currentFps}✎" else "CUSTOM"
            textSize = 8.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(if (isCustom) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
            setTextColor(if (isCustom) 0xFF0B0D10.toInt() else 0xFFFF5F1F.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.35f)
            setOnClickListener {
                formatDrawer.visibility = View.GONE
                updateFormatPillUi()
                showCustomFpsInputDialog()
            }
        }
        formatFpsRow.addView(customChip)

        // Row 3: Bitrate (Mbps) — directly inside VIDEO FORMAT so users see auto-adjust when changing Resolution!
        formatBitrateRow.removeAllViews()
        val drawerBitrates = listOf(4, 12, 24, 35, 50, 75, 100)
        drawerBitrates.forEachIndexed { idx, mbps ->
            val selected = (engine.targetBitrateMbps == mbps)
            val chip = TextView(this).apply {
                text = "${mbps}M"
                textSize = 8.8f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                setTextColor(if (selected) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx < drawerBitrates.lastIndex) marginEnd = dpToPx(3)
                }
                setOnClickListener {
                    engine.targetBitrateMbps = mbps
                    bitrateIdx = bitrateOptions.indexOf(mbps).takeIf { it >= 0 } ?: 4
                    renderFormatDrawerChips()
                    showSingleToast("Bitrate: $mbps Mbps")
                }
            }
            formatBitrateRow.addView(chip)
        }

        // Row 4: Frame Ratio Selector (16:9 | 2.39:1 | 1.85:1 | 4:3 | 1:1 | 9:16)
        formatFrameRatioRow.removeAllViews()
        val isPort = (engine.recordAspectMode == RecordAspectMode.VERT_9_16_PORTRAIT)
        val ratioChoices = listOf(
            AspectGuide.OFF to "16:9",
            AspectGuide.SCOPE_239 to "2.39:1",
            AspectGuide.FLAT_185 to "1.85:1",
            AspectGuide.CLASSIC_43 to "4:3",
            AspectGuide.SQUARE_11 to "1:1",
            AspectGuide.VERT_916 to "9:16"
        )
        ratioChoices.forEachIndexed { idx, (guide, lbl) ->
            val selected = !isPort && (
                overlayView.aspectGuide == guide ||
                    (guide == AspectGuide.OFF && overlayView.aspectGuide == AspectGuide.WIDE_169)
                )
            val chip = TextView(this).apply {
                text = lbl
                textSize = 8.4f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                setTextColor(if (selected) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx < ratioChoices.lastIndex) marginEnd = dpToPx(3)
                }
                setOnClickListener {
                    selectFrameRatio(guide)
                }
            }
            formatFrameRatioRow.addView(chip)
        }

        // Row 5: Codec (H.264 / HEVC) + Frame Crop Toggle (GUIDE / CROP REC) + Full-Res 9:16 PORT
        formatCodecAspectRow.removeAllViews()
        val codecChip = TextView(this).apply {
            text = engine.targetCodec.label
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val isHevc = engine.targetCodec == CinemaCodec.HEVC
            setBackgroundResource(if (isHevc) R.drawable.bg_dock_pill_active else R.drawable.bg_hud_pill)
            setTextColor(if (isHevc) 0xFFFF5F1F.toInt() else 0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f).apply {
                marginEnd = dpToPx(5)
            }
            setOnClickListener {
                engine.targetCodec = if (engine.targetCodec == CinemaCodec.H264) CinemaCodec.HEVC else CinemaCodec.H264
                renderFormatDrawerChips()
                showSingleToast("Codec: ${engine.targetCodec.label}")
            }
        }
        formatCodecAspectRow.addView(codecChip)

        val canCrop = !isPort && overlayView.aspectGuide != AspectGuide.OFF && overlayView.aspectGuide != AspectGuide.WIDE_169
        val cropActive = canCrop && engine.frameCropRecEnabled
        val cropChip = TextView(this).apply {
            text = if (cropActive) "CROP: REC" else "GUIDE ONLY"
            textSize = 8.8f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = if (canCrop) 1.0f else 0.40f
            setBackgroundResource(if (cropActive) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
            setTextColor(if (cropActive) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.25f).apply {
                marginEnd = dpToPx(5)
            }
            setOnClickListener {
                if (!canCrop) {
                    showSingleToast("Pick a frame ratio (2.39:1, 1.85:1, 4:3, 1:1 or 9:16) to enable CROP REC")
                    return@setOnClickListener
                }
                setFrameCropRecEnabled(!engine.frameCropRecEnabled, announce = true)
            }
        }
        formatCodecAspectRow.addView(cropChip)

        val portChip = TextView(this).apply {
            text = "9:16 PORT"
            textSize = 8.8f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(if (isPort) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
            setTextColor(if (isPort) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f)
            setOnClickListener {
                val nextMode = if (isPort) RecordAspectMode.WIDE_16_9 else RecordAspectMode.VERT_9_16_PORTRAIT
                setRecordAspectMode(nextMode, showNotice = true)
            }
        }
        formatCodecAspectRow.addView(portChip)

        updateFormatPillUi()
    }

    private fun selectFrameRatio(guide: AspectGuide) {
        if (engine.isRecording) {
            showSingleToast("Stop recording to change frame ratio")
            return
        }
        if (engine.recordAspectMode == RecordAspectMode.VERT_9_16_PORTRAIT) {
            engine.recordAspectMode = RecordAspectMode.WIDE_16_9
            overlayView.recordAspectMode = RecordAspectMode.WIDE_16_9
        }
        overlayView.aspectGuide = guide
        engine.activeFrameGuide = guide
        if (guide == AspectGuide.OFF || guide == AspectGuide.WIDE_169) {
            engine.frameCropRecEnabled = false
            overlayView.frameCropRecEnabled = false
        }
        syncOutputMonitorUi()
        if (formatDrawer.visibility == View.VISIBLE) {
            renderFormatDrawerChips()
        } else {
            updateFormatPillUi()
        }
        val msg = when {
            guide == AspectGuide.OFF -> "Frame: Full 16:9 Wide"
            engine.frameCropRecEnabled -> "Frame Crop Rec: ${guide.label} (Exact MP4 Crop)"
            else -> "Frame Guide: ${guide.label} (Tap GUIDE ONLY → CROP: REC to crop MP4)"
        }
        showSingleToast(msg)
    }

    private fun setFrameCropRecEnabled(enabled: Boolean, announce: Boolean) {
        if (engine.isRecording) {
            showSingleToast("Stop recording to toggle Frame Crop")
            return
        }
        engine.frameCropRecEnabled = enabled
        overlayView.frameCropRecEnabled = enabled
        syncOutputMonitorUi()
        if (formatDrawer.visibility == View.VISIBLE) {
            renderFormatDrawerChips()
        } else {
            updateFormatPillUi()
        }
        if (announce) {
            val g = overlayView.aspectGuide
            showSingleToast(
                if (enabled) "FRAME CROP ON — Recording exact ${g.label} MP4 (no post crop needed!)"
                else "FRAME CROP OFF — ${g.label} guide overlay only (records full 16:9)"
            )
        }
    }

    private fun setRecordAspectMode(mode: RecordAspectMode, showNotice: Boolean) {
        if (engine.isRecording) {
            showSingleToast("Stop recording to change orientation")
            return
        }
        val changed = (engine.recordAspectMode != mode)
        engine.recordAspectMode = mode
        overlayView.recordAspectMode = mode
        syncOutputMonitorUi()
        if (formatDrawer.visibility == View.VISIBLE) {
            renderFormatDrawerChips()
        } else {
            updateFormatPillUi()
        }

        if (showNotice && changed) {
            when (mode) {
                RecordAspectMode.VERT_9_16_PORTRAIT -> {
                    formatDrawer.visibility = View.GONE
                    updateFormatPillUi()
                    val rotDeg = if (getCurrentDisplayRotation() == Surface.ROTATION_270) 90f else -90f
                    IdgafUiDialogs.showActionDialog(
                        context = this,
                        title = "9:16 FULL PORTRAIT",
                        message = "Hold your phone upright like you're reading this. Your video will record and save vertically in full 9:16 portrait with zero cropping.",
                        primaryLabel = "GOT IT",
                        onPrimary = {},
                        cancelLabel = null,
                        rotationDegrees = rotDeg
                    )
                }
                RecordAspectMode.WIDE_16_9 -> {
                    showSingleToast("Recording Format: Landscape")
                }
            }
        }
    }

    private fun applySelectedResolution(chosen: CinemaResolution) {
        currentResolution = chosen
        val smartMbps = when (chosen) {
            CinemaResolution.SD_480P -> 4
            CinemaResolution.HD_720P -> 12
            CinemaResolution.FHD_1080P -> 24
            CinemaResolution.QHD_2K -> 50
            CinemaResolution.UHD_4K -> 75
        }
        engine.targetBitrateMbps = smartMbps
        bitrateIdx = bitrateOptions.indexOf(smartMbps).takeIf { it >= 0 } ?: 3
        engine.updateSettings(currentResolution, currentFps)
        renderFormatDrawerChips()
        showSingleToast("Format: ${chosen.label} • Auto Bitrate: $smartMbps Mbps")
    }

    private fun openCinemaInputDrawer(
        title: String,
        hint: String,
        initialValue: String,
        allowDecimal: Boolean,
        allowSigned: Boolean = false,
        step: Float = 1f,
        isClassicStyle: Boolean = false,
        onApply: (String) -> Boolean
    ) {
        cinemaInputStep = step
        cinemaInputAllowDecimal = allowDecimal
        cinemaInputOnApply = onApply

        txtCinemaInputTitle.text = title
        txtCinemaInputTitle.setTextColor(if (isClassicStyle) 0xFFE8B968.toInt() else 0xFFFF5F1F.toInt())
        txtCinemaInputHint.text = "$hint • Tap box to type"
        cinemaInputDrawer.setBackgroundResource(if (isClassicStyle) R.drawable.bg_era_pill else R.drawable.bg_hud_pill)
        btnCinemaInputApply.setBackgroundResource(if (isClassicStyle) R.drawable.bg_era_pill_active else R.drawable.bg_lens_active)
        btnCinemaInputApply.setTextColor(if (isClassicStyle) 0xFF141210.toInt() else 0xFF0B0D10.toInt())

        var flags = InputType.TYPE_CLASS_NUMBER
        if (allowDecimal) flags = flags or InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (allowSigned) flags = flags or InputType.TYPE_NUMBER_FLAG_SIGNED
        edtCinemaInput.inputType = flags
        edtCinemaInput.setText(initialValue)
        edtCinemaInput.hint = hint
        // Keep keyboard CLOSED by default so user can tap − / + freely without keyboard blocking viewfinder!
        edtCinemaInput.clearFocus()
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edtCinemaInput.windowToken, 0)
        } catch (_: Exception) {
        }
        cinemaInputDrawer.visibility = View.VISIBLE
    }

    private fun stepCinemaInputValue(dir: Float) {
        val raw = edtCinemaInput.text?.toString()?.trim().orEmpty()
        val cur = raw.toFloatOrNull() ?: 0f
        val next = cur + (cinemaInputStep * dir)
        val formatted = if (cinemaInputAllowDecimal) {
            String.format(Locale.US, "%.1f", next)
        } else {
            next.roundToInt().toString()
        }
        edtCinemaInput.setText(formatted)
        edtCinemaInput.setSelection(formatted.length)
    }

    private fun submitCinemaInputDrawer() {
        val text = edtCinemaInput.text?.toString()?.trim().orEmpty()
        val ok = cinemaInputOnApply?.invoke(text) ?: true
        if (ok) {
            closeCinemaInputDrawer()
        }
    }

    private fun closeCinemaInputDrawer() {
        if (cinemaInputDrawer.visibility != View.VISIBLE) return
        cinemaInputDrawer.visibility = View.GONE
        cinemaInputOnApply = null
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edtCinemaInput.windowToken, 0)
        } catch (_: Exception) {
        }
        edtCinemaInput.clearFocus()
        enableImmersiveFullscreen()
    }

    private fun enterEraPlugin(plugin: EraCameraPlugin) {
        if (currentWorkbench == CameraWorkbench.ERA && activeEraPlugin?.id == plugin.id) return

        closeAllDrawers()
        rackAnimator?.cancel()
        zoomAnimator?.cancel()
        uiHandler.removeCallbacks(countdownRunnable)
        txtTimerCountdown.visibility = View.GONE

        // Snapshot SAVIOR state ONCE when first leaving SAVIOR (never overwrite snapshot when switching Era A -> Era B!)
        if (currentWorkbench == CameraWorkbench.SAVIOR) {
            saviorSnapshot = SaviorWorkbenchSnapshot(
                resolution = currentResolution,
                bitrateMbps = engine.targetBitrateMbps,
                codec = engine.targetCodec,
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
                splitCompareFraction = splitCompareFraction,
                recordAspectMode = engine.recordAspectMode,
                aspectGuide = overlayView.aspectGuide,
                frameCropRecEnabled = engine.frameCropRecEnabled
            )
        } else {
            activeEraPlugin?.onExit(eraHostBridge)
        }

        currentWorkbench = CameraWorkbench.ERA
        activeEraPlugin = plugin
        eraMicMonitorPaused = true
        if (isSplitCompareActive) {
            setSplitCompareMode(false)
        }

        saviorTopBar.visibility = View.GONE
        saviorBottomDock.visibility = View.GONE
        lensRail.visibility = View.GONE
        zoomRockerContainer.visibility = View.GONE
        btnAutoPilot.visibility = View.GONE
        btnExitSplitWipe.visibility = View.GONE
        overlayView.visibility = View.GONE

        eraPluginContainer.removeAllViews()
        val hud = plugin.createHudView(this, eraHostBridge)
        eraPluginContainer.addView(
            hud,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        eraPluginContainer.visibility = View.VISIBLE

        engine.timelapseIntervalMs = 0L
        engine.recordAspectMode = RecordAspectMode.WIDE_16_9
        overlayView.recordAspectMode = RecordAspectMode.WIDE_16_9
        engine.clearAeAfLock()
        engine.resetZoom()
        engine.setAutoExposure()
        engine.setEvCompensation(0)
        engine.setAutoWhiteBalance()
        engine.setAutoFocus()

        engine.setEraPlugin(plugin, plugin.getInitialRenderConfig())
        eraHostBridge.applyFormat(
            resolution = plugin.defaultResolution,
            fps = plugin.defaultFps,
            bitrateMbps = plugin.defaultBitrateMbps,
            codec = plugin.defaultCodec
        )
        plugin.onEnter(eraHostBridge)

        updateCleanUiToggleStyle()
        updateSystemTelemetry(null)
    }

    private fun exitEraToSaviorWorkbench() {
        if (currentWorkbench == CameraWorkbench.SAVIOR) return
        activeEraPlugin?.onExit(eraHostBridge)
        activeEraPlugin = null
        currentWorkbench = CameraWorkbench.SAVIOR
        eraMicMonitorPaused = false

        eraPluginContainer.removeAllViews()
        eraPluginContainer.visibility = View.GONE

        engine.setEraPlugin(null, null)

        val snap = saviorSnapshot
        saviorSnapshot = null
        if (snap != null) {
            currentResolution = snap.resolution
            engine.targetBitrateMbps = snap.bitrateMbps
            bitrateIdx = bitrateOptions.indexOf(snap.bitrateMbps).takeIf { it >= 0 } ?: 4
            engine.targetCodec = snap.codec
            engine.timelapseIntervalMs = snap.timelapseIntervalMs
            engine.recordAspectMode = snap.recordAspectMode
            overlayView.recordAspectMode = snap.recordAspectMode
            overlayView.aspectGuide = snap.aspectGuide
            engine.activeFrameGuide = snap.aspectGuide
            engine.frameCropRecEnabled = snap.frameCropRecEnabled
            overlayView.frameCropRecEnabled = snap.frameCropRecEnabled
            currentFps = snap.fps
            if (engine.targetResolution != snap.resolution) {
                engine.updateSettings(snap.resolution, snap.fps)
            } else {
                engine.setTargetFpsSeamless(snap.fps)
            }
            updateFormatPillUi()
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

        saviorTopBar.visibility = View.VISIBLE
        saviorBottomDock.visibility = View.VISIBLE
        lensRail.visibility = View.VISIBLE
        zoomRockerContainer.visibility = View.VISIBLE
        btnAutoPilot.visibility = View.VISIBLE
        overlayView.visibility = View.VISIBLE
        updateCleanUiToggleStyle()
        syncExitSplitPillVisibility()
        syncAnalysisLoop()
        updateSystemTelemetry(null)
        showSingleToast("Switched back to SAVIOR Cinema Core")
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

        btnToolPeak.setOnClickListener {
            overlayView.showPeaking = !overlayView.showPeaking
            updateMonitorPillLabels()
            syncAnalysisLoop()
        }

        btnToolZebra.setOnClickListener {
            overlayView.showZebras = !overlayView.showZebras
            updateMonitorPillLabels()
            syncAnalysisLoop()
        }

        btnToolFalseColor.setOnClickListener {
            overlayView.showFalseColor = !overlayView.showFalseColor
            styleToolPill(btnToolFalseColor, overlayView.showFalseColor)
            syncAnalysisLoop()
        }

        btnToolHist.setOnClickListener {
            overlayView.showHistogram = !overlayView.showHistogram
            styleToolPill(btnToolHist, overlayView.showHistogram)
            syncExitSplitPillVisibility()
            syncAnalysisLoop()
        }

        btnToolHorizon.setOnClickListener {
            overlayView.showHorizon = !overlayView.showHorizon
            styleToolPill(btnToolHorizon, overlayView.showHorizon)
            syncHorizonSensor()
        }

        btnToolOutMon.setOnClickListener {
            setOutputMonitorEnabled(!isOutputMonitorEnabled, announce = true)
        }

        btnOutMonClose.setOnClickListener {
            setOutputMonitorEnabled(false, announce = false)
        }

        outputMonitorTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (isOutputMonitorEnabled) {
                    engine.attachOutputMonitorSurface(surface, width, height) { pulseOutputMonitorDot() }
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                engine.updateOutputMonitorSize(width, height)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                engine.detachOutputMonitorSurface()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        outputMonitorCard.setOnTouchListener { v, event ->
            val parentView = v.parent as? View ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    outMonLastDragX = event.rawX
                    outMonLastDragY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - outMonLastDragX
                    val dy = event.rawY - outMonLastDragY
                    outMonLastDragX = event.rawX
                    outMonLastDragY = event.rawY
                    v.x = v.x + dx
                    v.y = v.y + dy
                    clampOutputMonitorToParent(v, parentView)
                    true
                }
                else -> true
            }
        }

        btnOutMonResize.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    outMonResizeLastX = event.rawX
                    outMonResizeLastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - outMonResizeLastX
                    val dy = event.rawY - outMonResizeLastY
                    outMonResizeLastX = event.rawX
                    outMonResizeLastY = event.rawY
                    val rot = outputMonitorCard.rotation
                    val localGrowPx = when {
                        rot < -45f -> (dx - dy) * 0.5f   // -90 deg (Portrait upright on ROTATION_90)
                        rot > 45f -> (dy - dx) * 0.5f    // +90 deg (Portrait upright on ROTATION_270)
                        else -> (dx + dy) * 0.5f         // 0 deg (Normal landscape)
                    }
                    val stepScale = localGrowPx / dpToPx(150).coerceAtLeast(1).toFloat()
                    val nextScale = (outputMonitorScale + stepScale).coerceIn(0.75f, 1.85f)
                    if (kotlin.math.abs(nextScale - outputMonitorScale) >= 0.01f) {
                        outputMonitorScale = nextScale
                        syncOutputMonitorUi()
                    }
                    true
                }
                else -> true
            }
        }

        btnToolMonConfig.setOnClickListener {
            val showing = monConfigSubPanel.visibility == View.VISIBLE
            monConfigSubPanel.visibility = if (showing) View.GONE else View.VISIBLE
            styleToolPill(btnToolMonConfig, !showing)
            if (!showing) renderMonConfigInlineChips()
        }
    }

    private fun clampOutputMonitorToParent(card: View, parentView: View) {
        val pw = parentView.width.toFloat()
        val ph = parentView.height.toFloat()
        val cw = card.width.toFloat()
        val ch = card.height.toFloat()
        if (pw <= 0f || ph <= 0f || cw <= 0f || ch <= 0f) return
        val rotated = kotlin.math.abs(card.rotation) > 45f
        val halfVisW = if (rotated) ch * 0.5f else cw * 0.5f
        val halfVisH = if (rotated) cw * 0.5f else ch * 0.5f
        val minX = halfVisW - cw * 0.5f
        val maxX = (pw - halfVisW - cw * 0.5f).coerceAtLeast(minX)
        val minY = halfVisH - ch * 0.5f
        val maxY = (ph - halfVisH - ch * 0.5f).coerceAtLeast(minY)
        card.x = card.x.coerceIn(minX, maxX)
        card.y = card.y.coerceIn(minY, maxY)
    }

    private fun setOutputMonitorEnabled(enabled: Boolean, announce: Boolean) {
        isOutputMonitorEnabled = enabled
        styleToolPill(btnToolOutMon, enabled)
        if (enabled) {
            if (!isCleanFrameMode) {
                outputMonitorCard.visibility = View.VISIBLE
            }
            syncOutputMonitorUi()
            if (outputMonitorTextureView.isAvailable) {
                val st = outputMonitorTextureView.surfaceTexture
                if (st != null) {
                    engine.attachOutputMonitorSurface(
                        st,
                        outputMonitorTextureView.width.coerceAtLeast(1),
                        outputMonitorTextureView.height.coerceAtLeast(1)
                    ) { pulseOutputMonitorDot() }
                }
            }
            if (announce) {
                showSingleToast("Live Output Monitor ON — drag to move, ⤡ corner to resize")
            }
        } else {
            engine.detachOutputMonitorSurface()
            outputMonitorCard.visibility = View.GONE
            if (announce) {
                showSingleToast("Live Output Monitor OFF")
            }
        }
    }

    private fun pulseOutputMonitorDot() {
        runOnUiThread {
            if (!isOutputMonitorEnabled) return@runOnUiThread
            viewOutMonDot.alpha = 1.0f
            viewOutMonDot.animate().cancel()
            viewOutMonDot.scaleX = 1.45f
            viewOutMonDot.scaleY = 1.45f
            viewOutMonDot.animate().scaleX(1.0f).scaleY(1.0f).setDuration(220L).start()
        }
    }

    private fun syncOutputMonitorUi(tlFrames: Long = -1L) {
        engine.syncOutputMonitorParams()
        if (!isOutputMonitorEnabled) return

        val isEra = (currentWorkbench == CameraWorkbench.ERA)
        val isPort = (!isEra && engine.recordAspectMode == RecordAspectMode.VERT_9_16_PORTRAIT)
        val activeCropGuide = if (!isEra && !isPort && engine.frameCropRecEnabled &&
            overlayView.aspectGuide != AspectGuide.OFF && overlayView.aspectGuide != AspectGuide.WIDE_169
        ) overlayView.aspectGuide else null

        val (baseWdp, baseHdp) = when {
            isPort -> 82 to 146
            activeCropGuide == AspectGuide.SCOPE_239 -> 186 to 78
            activeCropGuide == AspectGuide.FLAT_185 -> 172 to 93
            activeCropGuide == AspectGuide.CLASSIC_43 -> 140 to 105
            activeCropGuide == AspectGuide.SQUARE_11 -> 116 to 116
            activeCropGuide == AspectGuide.VERT_916 -> 82 to 146
            else -> 168 to 95
        }

        val scale = outputMonitorScale.coerceIn(0.75f, 1.85f)
        val wantW = dpToPx((baseWdp * scale).roundToInt())
        val wantH = dpToPx((baseHdp * scale).roundToInt())
        val lp = outputMonitorTextureView.layoutParams
        if (lp.width != wantW || lp.height != wantH) {
            lp.width = wantW
            lp.height = wantH
            outputMonitorTextureView.layoutParams = lp
        }

        val wantRot = if (isPort) {
            if (getCurrentDisplayRotation() == Surface.ROTATION_270) 90f else -90f
        } else {
            0f
        }
        if (outputMonitorCard.rotation != wantRot) {
            outputMonitorCard.rotation = wantRot
        }
        outputMonitorCard.post {
            (outputMonitorCard.parent as? View)?.let { p -> clampOutputMonitorToParent(outputMonitorCard, p) }
        }

        val isTl = engine.isTimelapseEnabled() && !isEra
        val isRec = engine.isRecording
        val aspectLbl = when {
            isPort -> "9:16P"
            activeCropGuide != null -> activeCropGuide.label
            else -> "16:9"
        }

        txtOutMonBadge.text = when {
            isRec && isTl && tlFrames >= 0L -> "TL REC • ${tlFrames}F"
            isRec && isTl -> "TL REC • ${formatTimelapseLabel(engine.timelapseIntervalMs)}"
            isRec -> "REC OUT • $aspectLbl"
            isTl -> "TL OUT • ${formatTimelapseLabel(engine.timelapseIntervalMs)}"
            currentFps < 20 -> "OUT • ${currentFps}FPS"
            else -> "OUT • $aspectLbl"
        }
        txtOutMonBadge.setTextColor(
            when {
                isRec -> 0xFFFF3B30.toInt()
                isTl -> 0xFFFF5F1F.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        )
        viewOutMonDot.setBackgroundResource(
            when {
                isRec -> R.drawable.bg_shutter_rec
                isTl -> R.drawable.bg_lens_active
                else -> R.drawable.bg_pilot_active
            }
        )
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

    private fun renderMonConfigInlineChips() {
        fun fillRow(row: LinearLayout, items: List<Triple<String, Boolean, () -> Unit>>) {
            row.removeAllViews()
            items.forEachIndexed { idx, (lbl, active, onClick) ->
                val c = TextView(this).apply {
                    text = lbl
                    textSize = 8.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setBackgroundResource(if (active) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                    setTextColor(if (active) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (idx < items.lastIndex) marginEnd = dpToPx(4)
                    }
                    setOnClickListener { onClick() }
                }
                row.addView(c)
            }
        }

        fillRow(monPeakColorRow, PeakingColor.values().map { pc ->
            Triple(pc.label.take(3), overlayView.peakingColor == pc) {
                overlayView.peakingColor = pc
                if (!overlayView.showPeaking) overlayView.showPeaking = true
                updateMonitorPillLabels()
                syncAnalysisLoop()
                renderMonConfigInlineChips()
            }
        })

        fillRow(monPeakSensRow, PeakingSensitivity.values().map { ps ->
            Triple(ps.label, overlayView.peakingSensitivity == ps) {
                overlayView.peakingSensitivity = ps
                if (!overlayView.showPeaking) overlayView.showPeaking = true
                updateMonitorPillLabels()
                syncAnalysisLoop()
                renderMonConfigInlineChips()
            }
        })

        fillRow(monZebraLevelRow, ZebraMode.values().map { zm ->
            Triple(zm.shortLabel, overlayView.zebraMode == zm) {
                overlayView.zebraMode = zm
                if (!overlayView.showZebras) overlayView.showZebras = true
                updateMonitorPillLabels()
                syncAnalysisLoop()
                renderMonConfigInlineChips()
            }
        })
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
            proSettingsBar.visibility == View.VISIBLE ||
            formatDrawer.visibility == View.VISIBLE ||
            workbenchEraDrawer.visibility == View.VISIBLE ||
            cinemaInputDrawer.visibility == View.VISIBLE
    }

    private fun syncLowBatteryBannerVisibility() {
        if (isCleanFrameMode) {
            txtLowBatteryBanner.visibility = View.GONE
            return
        }
        val anyTopDrawerOpen = colorControlDrawer.visibility == View.VISIBLE ||
            monitorToolsBar.visibility == View.VISIBLE ||
            proSettingsBar.visibility == View.VISIBLE ||
            formatDrawer.visibility == View.VISIBLE ||
            workbenchEraDrawer.visibility == View.VISIBLE ||
            cinemaInputDrawer.visibility == View.VISIBLE
        txtLowBatteryBanner.visibility = if (!isBatteryCharging && lastBatteryPct <= 15 && !lowBatteryBannerDismissed && !anyTopDrawerOpen) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun closeTopDrawers(except: String? = null) {
        if (except != "MON" && monitorToolsBar.visibility == View.VISIBLE) {
            monitorToolsBar.visibility = View.GONE
            monConfigSubPanel.visibility = View.GONE
            styleToolPill(btnToolMonConfig, false)
            styleToolPill(btnMonitorToggle, false)
        }
        if (except != "PRO" && proSettingsBar.visibility == View.VISIBLE) {
            proSettingsBar.visibility = View.GONE
            proTimerSubPanel.visibility = View.GONE
            proTimelapseSubPanel.visibility = View.GONE
            styleToolPill(btnProSettingsToggle, false)
        }
        if (except != "FORMAT" && formatDrawer.visibility == View.VISIBLE) {
            formatDrawer.visibility = View.GONE
            updateFormatPillUi()
        }
        if (except != "ERA_VAULT" && workbenchEraDrawer.visibility == View.VISIBLE) {
            closeWorkbenchEraDrawer()
        }
        if (except != "AUDIO" && audioControlDrawer.visibility == View.VISIBLE) {
            audioControlDrawer.visibility = View.GONE
            if (!isCleanFrameMode && currentWorkbench == CameraWorkbench.SAVIOR) {
                lensRail.visibility = View.VISIBLE
            }
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
        if (cinemaInputDrawer.visibility == View.VISIBLE) {
            closeCinemaInputDrawer()
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
            if (engine.isRecording) {
                showSingleToast("Stop recording to change self-timer")
                return@setOnClickListener
            }
            val showing = proTimerSubPanel.visibility == View.VISIBLE
            proTimelapseSubPanel.visibility = View.GONE
            proTimerSubPanel.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) renderTimerInlineChips()
        }

        btnProTimelapse.setOnClickListener {
            if (engine.isRecording) {
                showSingleToast("Stop recording to change Time-Lapse interval")
                return@setOnClickListener
            }
            val showing = proTimelapseSubPanel.visibility == View.VISIBLE
            proTimerSubPanel.visibility = View.GONE
            proTimelapseSubPanel.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) renderTimelapseInlineChips()
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

    private fun renderTimerInlineChips() {
        proTimerRow.removeAllViews()
        val presets = listOf(0 to "OFF", 3 to "3s", 5 to "5s", 10 to "10s", -1 to "CUSTOM")
        presets.forEachIndexed { idx, (sec, lbl) ->
            val isCustomActive = (sec == -1 && timerDelaySec > 0 && timerDelaySec !in listOf(3, 5, 10))
            val selected = (sec == timerDelaySec) || isCustomActive
            val chip = TextView(this).apply {
                text = if (isCustomActive) "${timerDelaySec}s✎" else lbl
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                setTextColor(if (selected) 0xFF0B0D10.toInt() else if (sec == -1) 0xFFFF5F1F.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, if (sec == -1) 1.3f else 1f).apply {
                    if (idx < presets.lastIndex) marginEnd = dpToPx(4)
                }
                setOnClickListener {
                    if (sec == -1) {
                        showCustomTimerInputDialog()
                    } else {
                        applySelfTimerDelay(sec)
                        renderTimerInlineChips()
                    }
                }
            }
            proTimerRow.addView(chip)
        }
    }

    private fun showCustomTimerInputDialog() {
        val initVal = if (timerDelaySec > 0) timerDelaySec else 15
        openCinemaInputDrawer(
            title = "CUSTOM SELF-TIMER (SEC)",
            hint = "1 to 60 seconds (Step ±1s)",
            initialValue = initVal.toString(),
            allowDecimal = false,
            step = 1f
        ) { raw ->
            val v = raw.toIntOrNull()
            if (v == null || v !in 1..60) {
                showSingleToast("Enter 1 to 60 seconds")
                false
            } else {
                applySelfTimerDelay(v)
                if (proTimerSubPanel.visibility == View.VISIBLE) renderTimerInlineChips()
                true
            }
        }
    }

    private fun applySelfTimerDelay(seconds: Int) {
        timerDelaySec = seconds.coerceIn(0, 60)
        btnProTimer.text = if (timerDelaySec == 0) "TMR: OFF" else "TMR: ${timerDelaySec}s"
        styleToolPill(btnProTimer, timerDelaySec > 0)
        showSingleToast(if (timerDelaySec == 0) "Self-Timer OFF" else "Self-Timer: ${timerDelaySec}s")
    }

    private fun formatTimelapseLabel(ms: Long): String {
        return if (ms <= 0L) "OFF"
        else if (ms % 1000L == 0L) "${ms / 1000L}s"
        else String.format(Locale.US, "%.1fs", ms / 1000f)
    }

    private fun renderTimelapseInlineChips() {
        val currentMs = engine.timelapseIntervalMs
        val row1Items = listOf(0L to "OFF", 500L to "0.5s", 1000L to "1s", 2000L to "2s")
        val row2Items = listOf(5000L to "5s", 10000L to "10s", 30000L to "30s", -1L to "CUSTOM")

        fun fill(row: LinearLayout, items: List<Pair<Long, String>>) {
            row.removeAllViews()
            items.forEachIndexed { idx, (ms, lbl) ->
                val isCustomActive = (ms == -1L && currentMs > 0L && currentMs !in listOf(500L, 1000L, 2000L, 5000L, 10000L, 30000L))
                val selected = (ms == currentMs) || isCustomActive
                val chip = TextView(this).apply {
                    text = if (isCustomActive) "${formatTimelapseLabel(currentMs)}✎" else lbl
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                    setTextColor(if (selected) 0xFF0B0D10.toInt() else if (ms == -1L) 0xFFFF5F1F.toInt() else 0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (idx < items.lastIndex) marginEnd = dpToPx(4)
                    }
                    setOnClickListener {
                        if (ms == -1L) {
                            showCustomTimelapseInputDialog()
                        } else {
                            applyTimelapseInterval(ms)
                            renderTimelapseInlineChips()
                        }
                    }
                }
                row.addView(chip)
            }
        }
        fill(proTimelapseRow1, row1Items)
        fill(proTimelapseRow2, row2Items)
    }

    private fun showCustomTimelapseInputDialog() {
        val initStr = if (engine.timelapseIntervalMs > 0L) {
            String.format(Locale.US, "%.1f", engine.timelapseIntervalMs / 1000f)
        } else "2.0"
        openCinemaInputDrawer(
            title = "CUSTOM TIME-LAPSE (SEC)",
            hint = "0.5 to 60.0 sec/frame (Step ±0.5s)",
            initialValue = initStr,
            allowDecimal = true,
            step = 0.5f
        ) { raw ->
            val secs = raw.toFloatOrNull()
            if (secs == null || secs < 0.5f || secs > 60.0f) {
                showSingleToast("Enter 0.5 to 60.0 seconds")
                false
            } else {
                applyTimelapseInterval((secs * 1000f).roundToInt().toLong())
                if (proTimelapseSubPanel.visibility == View.VISIBLE) renderTimelapseInlineChips()
                true
            }
        }
    }

    private fun applyTimelapseInterval(intervalMs: Long) {
        engine.timelapseIntervalMs = intervalMs.coerceAtLeast(0L)
        if (intervalMs > 0L && currentFps < 20) {
            // Ensure Time-Lapse always plays back at smooth cinema FPS (30 FPS) rather than 1 FPS!
            val smoothFps = minOf(30, engine.capabilities.maxHardwareFps.coerceIn(24, 60))
            currentFps = smoothFps
            updateFormatPillUi()
            engine.updateSettings(currentResolution, smoothFps)
        }
        updateTimelapseUi()
        syncOutputMonitorUi()
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
        val listItems = mutableListOf<IdgafListItem>()
        listItems.add(
            IdgafListItem(
                title = "+ SAVE CURRENT TEMPLATE",
                subtitle = "Save resolution, FPS, bitrate, exposure, WB, grids & LOOK",
                accentTitle = true,
                onClick = { showSavePresetDialog() }
            )
        )
        saved.forEach { preset ->
            val resClean = preset.resolutionName.replace("FHD_", "").replace("UHD_", "").replace("QHD_", "").replace("HD_", "").replace("SD_", "")
            listItems.add(
                IdgafListItem(
                    title = preset.name,
                    subtitle = "$resClean • ${preset.fps} FPS • ${preset.bitrateMbps} Mbps • ${preset.codecName}",
                    trailingLabel = "DEL",
                    trailingColor = 0xFFFF3B30.toInt(),
                    onTrailingClick = {
                        presetStore.delete(preset.name)
                        showSingleToast("Deleted '${preset.name}'")
                        showPresetsDialog()
                    },
                    onClick = { applyPreset(preset) }
                )
            )
        }
        IdgafUiDialogs.showListSheet(
            context = this,
            title = "SHOOTING TEMPLATES",
            subtitle = if (saved.isEmpty()) "No saved templates yet" else "${saved.size} saved • Tap to load",
            items = listItems
        )
    }

    private fun showSavePresetDialog() {
        IdgafUiDialogs.showTextInputDialog(
            context = this,
            title = "SAVE SHOOTING TEMPLATE",
            hint = "e.g. Studio 4K, Night Cine, Reels",
            primaryLabel = "SAVE",
            onPrimary = { rawName ->
                val name = rawName.trim().ifEmpty { "Template ${presetStore.getAll().size + 1}" }
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
                    frameCropRecEnabled = engine.frameCropRecEnabled,
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
                showSingleToast("Saved template: $name")
            }
        )
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
        engine.activeFrameGuide = overlayView.aspectGuide
        engine.frameCropRecEnabled = p.frameCropRecEnabled && overlayView.aspectGuide != AspectGuide.OFF && overlayView.aspectGuide != AspectGuide.WIDE_169
        overlayView.frameCropRecEnabled = engine.frameCropRecEnabled
        syncOutputMonitorUi()

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

        updateFormatPillUi()
        if (formatDrawer.visibility == View.VISIBLE) renderFormatDrawerChips()
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

            // 2. Forward battery telemetry cleanly to active Era Plug-In (if any)
            if (currentWorkbench == CameraWorkbench.ERA) {
                activeEraPlugin?.onBatteryUpdated(eraHostBridge, pct, charging)
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
                syncLowBatteryBannerVisibility()
            } else {
                txtLowBatteryBanner.visibility = View.GONE
            }
        } catch (_: Exception) {
        }
    }

    private fun applyCreditsUpdateButtonState(btn: TextView, dialog: AlertDialog) {
        val pendingUrl = updateDownloadUrl
        val pendingTag = updateLatestTag
        if (!pendingUrl.isNullOrBlank()) {
            btn.text = if (!pendingTag.isNullOrBlank()) "INSTALL $pendingTag" else "INSTALL UPDATE"
            btn.setBackgroundResource(R.drawable.bg_pilot_active)
            btn.setTextColor(0xFF0B0D10.toInt())
            btn.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pendingUrl)))
                    dialog.dismiss()
                } catch (_: Exception) {
                    showSingleToast("Unable to open update link")
                }
            }
        } else {
            btn.text = "CHECK FOR UPDATES"
            btn.setBackgroundResource(R.drawable.bg_lens_active)
            btn.setTextColor(0xFF0B0D10.toInt())
            btn.setOnClickListener {
                btn.text = "CHECKING..."
                checkForGitHubUpdateSilently(manualCheck = true)
            }
        }
    }

    private fun showStudioMogliCreditsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_credits, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.txtCreditsVersion).text =
            "v${getCurrentVersionName()} • SAVIOR Cinema Engine"

        view.findViewById<TextView>(R.id.btnCreditsWebsite).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.studiomogli.com")))
            } catch (_: Exception) {
                showSingleToast("Visit www.studiomogli.com")
            }
        }

        view.findViewById<TextView>(R.id.btnCreditsLicense).setOnClickListener {
            showIdgafLicenseDialog()
        }

        val btnUpdateOrCheck = view.findViewById<TextView>(R.id.btnCreditsCheckUpdate)
        activeCreditsUpdateBtn = btnUpdateOrCheck
        activeCreditsDialog = dialog
        applyCreditsUpdateButtonState(btnUpdateOrCheck, dialog)

        dialog.setOnDismissListener {
            if (activeCreditsDialog === dialog) {
                activeCreditsDialog = null
                activeCreditsUpdateBtn = null
            }
        }

        view.findViewById<TextView>(R.id.btnCreditsClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showIdgafLicenseDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_license, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<TextView>(R.id.btnLicenseRespect).setOnClickListener {
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
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    if (manualCheck) {
                        runOnUiThread {
                            activeCreditsUpdateBtn?.let { btn ->
                                activeCreditsDialog?.let { dlg -> applyCreditsUpdateButtonState(btn, dlg) }
                            }
                            showSingleToast("Update check failed (HTTP ${conn?.responseCode})")
                        }
                    }
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val latestTag = json.optString("tag_name", "").trim()
                if (latestTag.isEmpty() || !isNewerVersion(latestTag, getCurrentVersionName())) {
                    if (manualCheck) {
                        runOnUiThread {
                            activeCreditsUpdateBtn?.let { btn ->
                                activeCreditsDialog?.let { dlg -> applyCreditsUpdateButtonState(btn, dlg) }
                            }
                            showSingleToast("You're on the latest version (v${getCurrentVersionName()})")
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
                            // Keep IDGAF CAM text, append ↑ arrow, and turn pill light green when update is ready!
                            btnBrandCredits.text = "IDGAF CAM ↑"
                            btnBrandCredits.setBackgroundResource(R.drawable.bg_pilot_active)
                            btnBrandCredits.setTextColor(0xFF0B0D10.toInt())
                            // Live-refresh the open Credits dialog button immediately (no close/reopen needed!)
                            val openBtn = activeCreditsUpdateBtn
                            val openDlg = activeCreditsDialog
                            if (openBtn != null && openDlg != null && openDlg.isShowing) {
                                applyCreditsUpdateButtonState(openBtn, openDlg)
                            }
                            if (manualCheck) {
                                showSingleToast("Update $latestTag found! Tap INSTALL $latestTag")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                if (manualCheck) {
                    runOnUiThread {
                        activeCreditsUpdateBtn?.let { btn ->
                            activeCreditsDialog?.let { dlg -> applyCreditsUpdateButtonState(btn, dlg) }
                        }
                        showSingleToast("Unable to check updates right now")
                    }
                }
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
        info.versionName ?: "0.7.7.2"
    } catch (_: Exception) { "0.7.7.2" }

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
        val visible = isSplitCompareActive && audioControlDrawer.visibility != View.VISIBLE && !isCleanFrameMode && currentWorkbench == CameraWorkbench.SAVIOR
        btnExitSplitWipe.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val lp = btnExitSplitWipe.layoutParams as? android.widget.FrameLayout.LayoutParams
            if (lp != null) {
                lp.marginStart = if (overlayView.showHistogram) dpToPx(160) else dpToPx(20)
                btnExitSplitWipe.layoutParams = lp
            }
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
        IdgafUiDialogs.showListSheet(
            context = this,
            title = "ADD 3D .CUBE LUT",
            subtitle = "Imported LUTs save permanently in your library",
            items = listOf(
                IdgafListItem(
                    title = "IMPORT FROM PHONE / DOWNLOADS",
                    subtitle = "Pick any .cube file stored on this device",
                    accentTitle = true,
                    onClick = { launchCubeFilePicker() }
                ),
                IdgafListItem(
                    title = "DOWNLOAD FROM DIRECT URL",
                    subtitle = "Paste a direct https:// .cube link",
                    onClick = { showDownloadCubeUrlDialog() }
                ),
                IdgafListItem(
                    title = "BROWSE FREE .CUBE LUTS",
                    subtitle = "Open freshluts.com in browser",
                    onClick = {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://freshluts.com/")))
                            showSingleToast("Download a .cube file, then import via + .CUBE")
                        } catch (_: Exception) {
                            showSingleToast("Download a .cube file in browser, then import via + .CUBE")
                        }
                    }
                )
            )
        )
    }

    private fun launchCubeFilePicker() {
        try {
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
                    throw IllegalArgumentException("Select a valid .cube 3D LUT file ($displayName)")
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
        IdgafUiDialogs.showTextInputDialog(
            context = this,
            title = "DOWNLOAD .CUBE FROM URL",
            message = "Paste a direct .cube file URL (GitHub blob links auto-convert to raw).",
            hint = "https://.../cinema_lut.cube",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            primaryLabel = "DOWNLOAD",
            onPrimary = { rawUrl ->
                val urlStr = rawUrl.trim()
                if (!urlStr.startsWith("http://", ignoreCase = true) && !urlStr.startsWith("https://", ignoreCase = true)) {
                    showSingleToast("Enter a valid https:// .cube URL")
                } else {
                    downloadCubeLutFromUrl(urlStr)
                }
            }
        )
    }

    private fun downloadCubeLutFromUrl(rawUrl: String) {
        showSingleToast("Downloading .cube LUT...")
        Thread {
            var conn: HttpURLConnection? = null
            try {
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
        val items = mutableListOf<IdgafListItem>()

        items.add(
            IdgafListItem(
                title = "NONE (NO 3D LUT)",
                subtitle = "Disable 3D LUT layer",
                isActive = activeLutSourceId.isEmpty(),
                onClick = {
                    clearActiveCubeLut()
                    pushGpuColorStateAndRefreshUi()
                    showSingleToast("3D LUT: OFF")
                }
            )
        )

        userLuts.forEach { entry ->
            items.add(
                IdgafListItem(
                    title = entry.displayName,
                    subtitle = "Imported .cube 3D LUT",
                    isActive = (activeLutSourceId == entry.sourceId),
                    trailingLabel = "DEL",
                    trailingColor = 0xFFFF3B30.toInt(),
                    onTrailingClick = {
                        if (CubeLutLibrary.deleteUserLut(this, entry.sourceId)) {
                            if (activeLutSourceId == entry.sourceId) {
                                clearActiveCubeLut()
                                pushGpuColorStateAndRefreshUi()
                            }
                            showSingleToast("Deleted LUT: ${entry.displayName}")
                            showCubeLutLibraryDialog()
                        }
                    },
                    onClick = { loadAndActivateCubeLut(entry.sourceId, silent = false) }
                )
            )
        }

        builtins.forEach { entry ->
            items.add(
                IdgafListItem(
                    title = entry.displayName,
                    subtitle = entry.subtitle,
                    isActive = (activeLutSourceId == entry.sourceId),
                    onClick = { loadAndActivateCubeLut(entry.sourceId, silent = false) }
                )
            )
        }

        items.add(
            IdgafListItem(
                title = "+ IMPORT .CUBE FROM PHONE",
                subtitle = "Load custom .cube LUT from storage",
                accentTitle = true,
                onClick = { launchCubeFilePicker() }
            )
        )
        items.add(
            IdgafListItem(
                title = "+ DOWNLOAD .CUBE FROM URL",
                subtitle = "Download .cube directly from internet link",
                accentTitle = true,
                onClick = { showDownloadCubeUrlDialog() }
            )
        )

        IdgafUiDialogs.showListSheet(
            context = this,
            title = "3D LUT LIBRARY",
            subtitle = "${builtins.size} Cinema Stocks • ${userLuts.size} Custom .cube",
            items = items
        )
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
        if (engine.isAeAfLocked && (panel == ProControlPanel.EV || panel == ProControlPanel.ISO || panel == ProControlPanel.SHUTTER)) {
            showSingleToast("AE/AF Lock is ON — turn off LOCK first")
            return
        }

        val minEvStops = engine.capabilities.evRange.lower * engine.capabilities.evStep
        val maxEvStops = engine.capabilities.evRange.upper * engine.capabilities.evStep
        val live = lastTelemetry

        when (panel) {
            ProControlPanel.EV -> {
                openCinemaInputDrawer(
                    title = "EXPOSURE COMP (EV STOPS)",
                    hint = String.format(Locale.US, "Range %.1f to +%.1f (Step ±0.3)", minEvStops, maxEvStops),
                    initialValue = String.format(Locale.US, "%.1f", engine.getCurrentEvStops()),
                    allowDecimal = true,
                    allowSigned = true,
                    step = 0.3f
                ) { raw ->
                    val stops = raw.toFloatOrNull()
                    if (stops == null) {
                        showSingleToast("Enter a valid EV stop (e.g. -1.0, 0, +1.5)")
                        false
                    } else {
                        val step = engine.capabilities.evStep.coerceAtLeast(0.01f)
                        if (engine.setEvCompensation((stops / step).roundToInt())) updateEvDisplay()
                        refreshSliderState()
                        true
                    }
                }
            }
            ProControlPanel.ISO -> {
                val curIso = if (engine.isManualExposure) engine.manualIso else (live?.iso ?: engine.manualIso)
                openCinemaInputDrawer(
                    title = "EXACT MANUAL ISO",
                    hint = "Range ${engine.capabilities.effectiveIsoRange.lower}–${engine.capabilities.effectiveIsoRange.upper} (Step ±50)",
                    initialValue = curIso.toString(),
                    allowDecimal = false,
                    step = 50f
                ) { raw ->
                    val v = raw.toIntOrNull()
                    if (v == null || v <= 0) {
                        showSingleToast("Enter a valid ISO number")
                        false
                    } else {
                        engine.setManualIso(v)
                        refreshSliderState()
                        true
                    }
                }
            }
            ProControlPanel.SHUTTER -> {
                if (shutterInAngleMode) {
                    val curAng = engine.getCurrentShutterAngle().roundToInt()
                    openCinemaInputDrawer(
                        title = "SHUTTER ANGLE (DEGREES)",
                        hint = "11° to 360° (Step ±15° • Cinema 180°)",
                        initialValue = curAng.toString(),
                        allowDecimal = false,
                        step = 15f
                    ) { raw ->
                        val v = raw.toIntOrNull()
                        if (v == null || v !in 11..360) {
                            showSingleToast("Enter 11 to 360 degrees")
                            false
                        } else {
                            engine.setManualShutterAngle(v.toFloat())
                            refreshSliderState()
                            true
                        }
                    }
                } else {
                    val shownNs = if (engine.isManualExposure) engine.manualShutterNs else (live?.shutterNs ?: engine.manualShutterNs)
                    val curDenom = (1_000_000_000L / shownNs.coerceAtLeast(1L)).toInt()
                    openCinemaInputDrawer(
                        title = "SHUTTER SPEED (1 / X SEC)",
                        hint = "Denominator e.g. 48, 50, 60, 120, 500 (Step ±10)",
                        initialValue = curDenom.toString(),
                        allowDecimal = false,
                        step = 10f
                    ) { raw ->
                        val v = raw.toIntOrNull()
                        if (v == null || v !in 1..8000) {
                            showSingleToast("Enter 1 to 8000")
                            false
                        } else {
                            engine.setManualShutterNs(1_000_000_000L / v)
                            refreshSliderState()
                            true
                        }
                    }
                }
            }
            ProControlPanel.WB -> {
                val curK = if (engine.isManualWb) engine.manualKelvin else (live?.kelvin ?: engine.getLiveKelvin())
                openCinemaInputDrawer(
                    title = "WHITE BALANCE (KELVIN)",
                    hint = "2500K – 8500K (Step ±100K)",
                    initialValue = curK.toString(),
                    allowDecimal = false,
                    step = 100f
                ) { raw ->
                    val v = raw.toIntOrNull()
                    if (v == null || v !in 2500..8500) {
                        showSingleToast("Enter 2500 to 8500 Kelvin")
                        false
                    } else {
                        engine.setManualWhiteBalanceKelvin(v)
                        refreshSliderState()
                        true
                    }
                }
            }
            ProControlPanel.FOCUS -> {
                val maxF = engine.capabilities.minFocusDistance.coerceAtLeast(0.01f)
                val curPct = ((engine.manualFocusDistance / maxF) * 100f).roundToInt().coerceIn(0, 100)
                openCinemaInputDrawer(
                    title = "FOCUS DISTANCE (%)",
                    hint = "0 = Infinity ∞ • 100 = Macro (Step ±5%)",
                    initialValue = curPct.toString(),
                    allowDecimal = false,
                    step = 5f
                ) { raw ->
                    val v = raw.toIntOrNull()
                    if (v == null || v !in 0..100) {
                        showSingleToast("Enter 0 to 100%")
                        false
                    } else {
                        engine.setManualFocusDistance((v / 100f) * maxF)
                        refreshSliderState()
                        true
                    }
                }
            }
            ProControlPanel.NONE -> {}
        }
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
            openCinemaInputDrawer(
                title = "ZOOM PULL DURATION (SEC)",
                hint = "0.2 to 15.0 seconds (Step ±0.5s)",
                initialValue = String.format(Locale.US, "%.1f", zoomPullDurationMs / 1000f),
                allowDecimal = true,
                step = 0.5f
            ) { raw ->
                val secs = raw.toFloatOrNull()
                if (secs != null && secs in 0.2f..15.0f) {
                    zoomPullDurationMs = (secs * 1000f).toLong()
                    btnZoomTime.text = String.format(Locale.US, "%.1fs", secs)
                    true
                } else {
                    showSingleToast("Enter 0.2 to 15.0 seconds")
                    false
                }
            }
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

    private fun showCustomFpsInputDialog() {
        val maxHw = engine.capabilities.maxHardwareFps.coerceIn(15, 60)
        openCinemaInputDrawer(
            title = "CUSTOM FRAME RATE (FPS)",
            hint = "1 to $maxHw FPS (Step ±1 • GPU Pacer below 20)",
            initialValue = currentFps.toString(),
            allowDecimal = false,
            step = 1f
        ) { raw ->
            val typed = raw.toIntOrNull()
            if (typed == null || typed !in 1..60) {
                showSingleToast("Enter a value between 1 and 60 FPS")
                false
            } else {
                applySelectedFps(typed)
                true
            }
        }
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
        updateFormatPillUi()
        engine.updateSettings(currentResolution, currentFps)
        syncOutputMonitorUi()
    }

    private fun setupProDockAndDrawer() {
        pillEv.setOnClickListener {
            if (engine.isManualExposure) {
                showSingleToast("Manual ISO/Shutter active — switch ISO/SHUTTER to AUTO to use EV")
                return@setOnClickListener
            }
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

        btnWbCustom.setOnClickListener {
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
                ProControlPanel.ISO, ProControlPanel.SHUTTER -> {
                    engine.setAutoExposure()
                    updateEvDisplay()
                }
                ProControlPanel.FOCUS -> {
                    rackAnimator?.cancel()
                    engine.setAutoFocus()
                }
                ProControlPanel.WB -> engine.setAutoWhiteBalance()
                ProControlPanel.NONE -> {}
            }
            refreshSliderState()
        }

        btnShutterAntiFlicker.setOnClickListener {
            cycleAntiFlickerMode()
            refreshSliderState()
        }

        btnIsoCustom.setOnClickListener {
            showTapToTypeValueDialog()
        }

        btnShutterCustom.setOnClickListener {
            showTapToTypeValueDialog()
        }

        btnShutterUnit.setOnClickListener {
            shutterInAngleMode = !shutterInAngleMode
            btnShutterUnit.text = if (shutterInAngleMode) "ANG°" else "SPD"
            refreshSliderState()
        }

        btnPullTime.setOnClickListener {
            openCinemaInputDrawer(
                title = "FOCUS PULL DURATION (SEC)",
                hint = "0.2 to 15.0 seconds (Step ±0.2s)",
                initialValue = String.format(Locale.US, "%.1f", focusPullDurationMs / 1000f),
                allowDecimal = true,
                step = 0.2f
            ) { raw ->
                val secs = raw.toFloatOrNull()
                if (secs != null && secs in 0.2f..15.0f) {
                    focusPullDurationMs = (secs * 1000f).toLong()
                    btnPullTime.text = String.format(Locale.US, "%.1fs", secs)
                    true
                } else {
                    showSingleToast("Enter 0.2 to 15.0 seconds")
                    false
                }
            }
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
                            showSingleToast(if (engine.isManualExposure) "Manual ISO/Shutter active — switch to AUTO to use EV" else "AE/AF Lock is ON — turn off LOCK first")
                            refreshSliderState()
                        }
                    }
                    ProControlPanel.ISO -> {
                        val iso = progressToLogIso(progress)
                        engine.setManualIso(iso)
                        txtSliderReadout.text = "ISO $iso"
                        renderIsoPresetChips(iso)
                        updateEvDisplay()
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
                            renderShutterPresetChips(engine.manualShutterNs)
                        } else {
                            val idx = progress.coerceIn(0, shutterDenoms.lastIndex)
                            val denom = shutterDenoms[idx]
                            val ns = 1_000_000_000L / denom
                            engine.setManualShutterNs(ns)
                            txtSliderReadout.text = "1/${denom}s"
                            btnSlider180.setBackgroundResource(R.drawable.bg_hud_pill)
                            btnSlider180.setTextColor(0xFFFFFFFF.toInt())
                            renderShutterPresetChips(ns)
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
        btnWbCustom.visibility = if (panel == ProControlPanel.WB) View.VISIBLE else View.GONE
        btnIsoCustom.visibility = if (panel == ProControlPanel.ISO) View.VISIBLE else View.GONE
        isoPresetRow.visibility = if (panel == ProControlPanel.ISO) View.VISIBLE else View.GONE
        btnShutterAntiFlicker.visibility = if (panel == ProControlPanel.SHUTTER) View.VISIBLE else View.GONE
        btnShutterCustom.visibility = if (panel == ProControlPanel.SHUTTER) View.VISIBLE else View.GONE
        btnShutterUnit.visibility = if (panel == ProControlPanel.SHUTTER) View.VISIBLE else View.GONE
        btnSlider180.visibility = if (panel == ProControlPanel.SHUTTER) View.VISIBLE else View.GONE
        shutterPresetRow.visibility = if (panel == ProControlPanel.SHUTTER) View.VISIBLE else View.GONE
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
                renderIsoPresetChips(shownIso)
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
                btnShutterAntiFlicker.text = engine.antiFlickerMode.label
                if (engine.antiFlickerMode != AntiFlickerMode.OFF) {
                    btnShutterAntiFlicker.setBackgroundResource(R.drawable.bg_dock_pill_active)
                    btnShutterAntiFlicker.setTextColor(0xFFFF5F1F.toInt())
                } else {
                    btnShutterAntiFlicker.setBackgroundResource(R.drawable.bg_hud_pill)
                    btnShutterAntiFlicker.setTextColor(0xFF8E96A4.toInt())
                }
                btnSlider180.setBackgroundResource(if (engine.isShutter180Locked) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                btnSlider180.setTextColor(if (engine.isShutter180Locked) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                renderShutterPresetChips(shownNs)
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

    private fun cycleAntiFlickerMode() {
        val modes = AntiFlickerMode.values()
        val next = modes[(modes.indexOf(engine.antiFlickerMode) + 1) % modes.size]
        engine.setAntiFlickerMode(next)
        val msg = when (next) {
            AntiFlickerMode.HZ_50 -> "Anti-Flicker: 50Hz (India/EU — 1/50 & 1/100 safe)"
            AntiFlickerMode.HZ_60 -> "Anti-Flicker: 60Hz (US/NTSC — 1/60 & 1/120 safe)"
            AntiFlickerMode.OFF -> "Anti-Flicker: OFF (Pure 180° Shutter)"
        }
        showSingleToast(msg)
    }

    private fun renderIsoPresetChips(shownIso: Int) {
        isoPresetRow.removeAllViews()
        val minIso = engine.capabilities.effectiveIsoRange.lower
        val maxIso = engine.capabilities.effectiveIsoRange.upper
        val standardStops = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
        var presets = standardStops.filter { it in minIso..maxIso }
        if (presets.size > 7) {
            // Keep the 7 cleanest everyday cinema stops (50..3200 or 100..6400)
            presets = if (minIso <= 50) listOf(50, 100, 200, 400, 800, 1600, 3200).filter { it in minIso..maxIso }
            else presets.take(7)
        }
        if (presets.isEmpty()) {
            presets = listOf(minIso, maxIso).distinct()
        }
        presets.forEachIndexed { idx, isoVal ->
            val tol = maxOf(5, isoVal / 20)
            val selected = engine.isManualExposure && kotlin.math.abs(shownIso - isoVal) <= tol
            val chip = TextView(this).apply {
                text = isoVal.toString()
                textSize = 8.8f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                setTextColor(if (selected) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx < presets.lastIndex) marginEnd = dpToPx(3)
                }
                setOnClickListener {
                    if (engine.isAeAfLocked) {
                        showSingleToast("AE/AF Lock is ON — turn off LOCK first")
                        return@setOnClickListener
                    }
                    engine.setManualIso(isoVal)
                    updateEvDisplay()
                    refreshSliderState()
                }
            }
            isoPresetRow.addView(chip)
        }
    }

    private fun renderShutterPresetChips(shownNs: Long) {
        shutterPresetRow.removeAllViews()
        if (shutterInAngleMode) {
            val curAng = engine.getCurrentShutterAngle()
            val quickAngles = listOf(45f to "45°", 90f to "90°", 144f to "144°", 172.8f to "172.8°", 180f to "180°", 270f to "270°", 360f to "360°")
            quickAngles.forEachIndexed { idx, (ang, label) ->
                val selected = engine.isManualExposure && kotlin.math.abs(curAng - ang) <= 2.5f
                val chip = TextView(this).apply {
                    text = label
                    textSize = 8.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                    setTextColor(if (selected) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (idx < quickAngles.lastIndex) marginEnd = dpToPx(3)
                    }
                    setOnClickListener {
                        if (engine.isAeAfLocked) {
                            showSingleToast("AE/AF Lock is ON — turn off LOCK first")
                            return@setOnClickListener
                        }
                        engine.setManualShutterAngle(ang)
                        refreshSliderState()
                    }
                }
                shutterPresetRow.addView(chip)
            }
        } else {
            val curDenom = (1_000_000_000.0 / shownNs.coerceAtLeast(1L).toDouble()).roundToInt()
            val quickDenoms = when (engine.antiFlickerMode) {
                AntiFlickerMode.HZ_50 -> listOf(25, 50, 100, 200, 500, 1000)
                AntiFlickerMode.HZ_60 -> listOf(30, 60, 120, 250, 500, 1000)
                AntiFlickerMode.OFF -> listOf(24, 48, 50, 60, 100, 250, 500)
            }
            quickDenoms.forEachIndexed { idx, denom ->
                val tol = maxOf(2, denom / 15)
                val selected = engine.isManualExposure && kotlin.math.abs(curDenom - denom) <= tol
                val chip = TextView(this).apply {
                    text = "1/$denom"
                    textSize = 8.8f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setBackgroundResource(if (selected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                    setTextColor(if (selected) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (idx < quickDenoms.lastIndex) marginEnd = dpToPx(3)
                    }
                    setOnClickListener {
                        if (engine.isAeAfLocked) {
                            showSingleToast("AE/AF Lock is ON — turn off LOCK first")
                            return@setOnClickListener
                        }
                        engine.setManualShutterNs(1_000_000_000L / denom.toLong())
                        refreshSliderState()
                    }
                }
                shutterPresetRow.addView(chip)
            }
        }
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
        if (engine.isManualExposure) {
            txtEvVal.text = "MAN"
            txtEvVal.setTextColor(0xFF8E96A4.toInt())
            pillEv.alpha = 0.55f
            if (activePanel == ProControlPanel.EV) {
                selectPanel(ProControlPanel.NONE)
            }
        } else {
            pillEv.alpha = 1.0f
            val stops = engine.getCurrentEvStops()
            txtEvVal.text = formatEv(stops)
            txtEvVal.setTextColor(if (engine.evCompensationIndex != 0) 0xFFFF5F1F.toInt() else 0xFFFFFFFF.toInt())
        }
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
        overlayView.displayRotation = lastDisplayRotation
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
        overlayView.displayRotation = lastDisplayRotation
        textureView.post {
            engine.onPreviewSizeChanged(textureView.width, textureView.height)
            engine.setDisplayRotation(lastDisplayRotation)
            syncOutputMonitorUi()
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
        overlayView.displayRotation = lastDisplayRotation
            configureTransform(previewSize)
            engine.updateGpuScopes(overlayView.toGpuScopeState())
            if (currentWorkbench == CameraWorkbench.SAVIOR) {
                currentResolution = engine.targetResolution
                currentFps = activeFps
            }
            updateFormatPillUi()
            updateZoomReadout()
            updateEvDisplay()
            styleToolPill(btnProTorch, engine.isTorchEnabled)
            btnProTorch.alpha = if (caps.hasFlash) 1.0f else 0.4f
            styleToolPill(btnProStab, engine.isStabilizationEnabled)
            updateAutoPilotUi(engine.autoPilotState)
            pillFocus.alpha = if (caps.supportsManualFocus) 1.0f else 0.45f
            if (currentWorkbench == CameraWorkbench.ERA) {
                activeEraPlugin?.onCameraReady(eraHostBridge, caps)
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
            updateEvDisplay()

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

            if (currentWorkbench == CameraWorkbench.ERA) {
                activeEraPlugin?.onTelemetryUpdated(eraHostBridge, telemetry)
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
        timerDelaySec = 0
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
            updateFormatPillUi()
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
        IdgafUiDialogs.showActionDialog(
            context = this,
            title = "DARK SCENE",
            message = "Scene is dark. Turn on camera light?",
            primaryLabel = "LIGHT ON",
            onPrimary = {
                if (!engine.isRecording) {
                    val on = engine.setTorchEnabled(true)
                    styleToolPill(btnProTorch, on)
                    if (on) {
                        engine.engageAutoPilot()
                        showSingleToast("Light ON — balancing scene...")
                    }
                }
            },
            cancelLabel = "KEEP OFF"
        )
    }

    override fun onRecordingStarted() {
        runOnUiThread {
            recStartMs = System.currentTimeMillis()
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_stop)
            btnShutterInner.animate().scaleX(0.54f).scaleY(0.54f).setDuration(140L).start()
            updateRestoreCleanEyeBadge()
            syncOutputMonitorUi()
            uiHandler.removeCallbacks(timecodeRunnable)
            if (currentWorkbench == CameraWorkbench.ERA) {
                activeEraPlugin?.onRecordingStarted(eraHostBridge)
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
                syncOutputMonitorUi(tlFrames = capturedFrames)
            }
        }
    }

    override fun onRecordingStopped(savedUri: Uri?, filePathLabel: String) {
        audioPreviewPausedForRec = false
        runOnUiThread {
            uiHandler.removeCallbacks(timecodeRunnable)
            viewRecDot.visibility = View.GONE
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_rec)
            btnShutterInner.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140L).start()
            updateRestoreCleanEyeBadge()
            syncOutputMonitorUi()
            btnAutoPilot.alpha = 1.0f
            if (currentWorkbench == CameraWorkbench.ERA) {
                activeEraPlugin?.onRecordingStopped(eraHostBridge)
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
        if (isOutputMonitorEnabled && outputMonitorTextureView.isAvailable) {
            outputMonitorTextureView.surfaceTexture?.let { st ->
                engine.attachOutputMonitorSurface(
                    st,
                    outputMonitorTextureView.width.coerceAtLeast(1),
                    outputMonitorTextureView.height.coerceAtLeast(1)
                ) { pulseOutputMonitorDot() }
            }
            syncOutputMonitorUi()
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
        engine.detachOutputMonitorSurface()
        engine.stopThread()
        super.onPause()
    }
}
