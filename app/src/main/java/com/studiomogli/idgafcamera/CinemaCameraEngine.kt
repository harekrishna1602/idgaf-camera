package com.studiomogli.idgafcamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class CinemaLens(
    val cameraId: String,
    val label: String,
    val isFront: Boolean,
    val focalLength: Float,
    val eqFocalMm: Int,
    val sensorOrientation: Int
)

data class CameraCapabilities(
    val isoRange: Range<Int> = Range(100, 3200),
    val effectiveIsoRange: Range<Int> = Range(100, 6400),
    val postRawBoostRange: Range<Int> = Range(100, 100),
    val supportsPostRawBoost: Boolean = false,
    val exposureRangeNs: Range<Long> = Range(125_000L, 100_000_000L),
    val minFocusDistance: Float = 0f,
    val supportsManualExposure: Boolean = false,
    val supportsManualFocus: Boolean = false,
    val minZoom: Float = 1.0f,
    val maxZoom: Float = 4.0f,
    val hasFlash: Boolean = false,
    val supportsEis: Boolean = false,
    val supportsOis: Boolean = false,
    val supportedResolutions: List<CinemaResolution> = listOf(CinemaResolution.FHD_1080P),
    val evRange: Range<Int> = Range(-12, 12),
    val evStep: Float = 0.333f,
    val maxHardwareFps: Int = 30
)

data class LiveTelemetry(
    val iso: Int,
    val shutterNs: Long,
    val focusDistance: Float,
    val kelvin: Int,
    val isManualExposure: Boolean,
    val isManualFocus: Boolean,
    val isManualWb: Boolean,
    val isLocked: Boolean
)

enum class CinemaResolution(val label: String, val width: Int, val height: Int) {
    SD_480P("480P", 854, 480),
    HD_720P("720P", 1280, 720),
    FHD_1080P("1080P", 1920, 1080),
    QHD_2K("2K", 2560, 1440),
    UHD_4K("4K", 3840, 2160)
}

enum class CinemaCodec(val label: String, val encoder: Int) {
    H264("H.264", MediaRecorder.VideoEncoder.H264),
    HEVC("HEVC", MediaRecorder.VideoEncoder.HEVC)
}

enum class MicPreference(val label: String) {
    AUTO("MIC: AUTO"),
    INTERNAL("MIC: INT"),
    EXTERNAL("MIC: EXT")
}

enum class AudioPreampMode(val label: String, val audioSource: Int) {
    CAMCORDER("CAM", MediaRecorder.AudioSource.CAMCORDER),
    RAW_UNPROCESSED("RAW", MediaRecorder.AudioSource.UNPROCESSED),
    VOICE_BOOST("BOOST", MediaRecorder.AudioSource.VOICE_RECOGNITION)
}

enum class ToneProfile(val label: String) {
    STANDARD("STD"),
    FLAT("FLAT"),
    CINEMA("CINE")
}

enum class AutoPilotState {
    OFF,
    METERING,
    LOCKED
}

interface CinemaEngineListener {
    fun onLensesReady(lenses: List<CinemaLens>, activeIndex: Int)
    fun onCameraReady(
        previewSize: Size,
        activeRes: Size,
        activeFps: Int,
        lens: CinemaLens,
        caps: CameraCapabilities
    )
    fun onTelemetryUpdated(telemetry: LiveTelemetry)
    fun onRecordingStarted()
    fun onRecordingStopped(savedUri: Uri?, filePathLabel: String)
    fun onTimelapseProgress(capturedFrames: Long, outputDurationSec: Float)
    fun onAutoPilotStateChanged(state: AutoPilotState, summary: String? = null, isLowLight: Boolean = false)
    fun onError(message: String)
}

class CinemaCameraEngine(
    private val context: Context,
    private val listener: CinemaEngineListener
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeBuilder: CaptureRequest.Builder? = null
    private var mediaRecorder: MediaRecorder? = null
    private var activeOutputPfd: ParcelFileDescriptor? = null
    private var activeMediaUri: Uri? = null
    private var activeFileLabel: String = ""

    private var glRenderer: CinemaGlRenderer? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewViewWidth: Int = 1920
    private var previewViewHeight: Int = 1080
    private var currentDisplayRotation: Int = Surface.ROTATION_90
    private var lenses: List<CinemaLens> = emptyList()
    private var activeLensIndex: Int = 0

    var targetResolution: CinemaResolution = CinemaResolution.FHD_1080P
    var targetFps: Int = 30
    var targetCodec: CinemaCodec = CinemaCodec.H264
    var targetBitrateMbps: Int = 35
    var micPreference: MicPreference = MicPreference.AUTO
    @Volatile var audioPreampMode: AudioPreampMode = AudioPreampMode.CAMCORDER
    @Volatile var isAudioMuted: Boolean = false
    @Volatile var audioMonitorTrimDb: Int = 0
    @Volatile var timelapseIntervalMs: Long = 0L
    fun isTimelapseEnabled(): Boolean = timelapseIntervalMs > 0L
    @Volatile var eraSilentAudio: Boolean = false
    @Volatile var eraAudioProfile: EraAudioProfile = EraAudioProfile.NONE
    @Volatile var eraFilenameTag: String = ""
    @Volatile var isFinalizingClip: Boolean = false
        private set
    private var activeTempRawFile: File? = null
    private var activeTargetFileName: String = ""
    @Volatile var eraModeCode: Int = 0
        private set
    @Volatile var eraWearLevel: Float = 0.0f
        private set
    @Volatile var eraFps: Float = 16.0f
        private set
    @Volatile var toneProfile: ToneProfile = ToneProfile.STANDARD
        private set
    @Volatile var isRecording: Boolean = false
        private set

    var capabilities: CameraCapabilities = CameraCapabilities()
        private set

    // Pro Manual Controls State
    var isManualExposure: Boolean = false
        private set
    var manualIso: Int = 200
        private set
    var manualShutterNs: Long = 16_666_666L
        private set
    var isShutter180Locked: Boolean = false
        private set

    var isManualFocus: Boolean = false
        private set
    var manualFocusDistance: Float = 0f
        private set

    var isManualWb: Boolean = false
        private set
    var manualKelvin: Int = 5600
        private set
    var manualTint: Int = 0 // -50 (Green) .. +50 (Magenta)
        private set

    // Calibrated ISP color pipeline anchor captured from live AWB (eliminates green cast!)
    private var lastAwbTransform: ColorSpaceTransform? = null
    private var refNeutralRedGain: Float = 1.75f
    private var refNeutralGreenEven: Float = 1.0f
    private var refNeutralGreenOdd: Float = 1.0f
    private var refNeutralBlueGain: Float = 1.65f
    private var hasCalibratedWbAnchor: Boolean = false

    var isAeAfLocked: Boolean = false
        private set

    var autoPilotState: AutoPilotState = AutoPilotState.OFF
        private set
    private var autoPilotStartMs: Long = 0L
    private var autoPilotFramesSeen: Int = 0
    @Volatile private var pendingAutoPilotOnOpen: Boolean = false

    var currentZoomRatio: Float = 1.0f
        private set
    var isTorchEnabled: Boolean = false
        private set
    var isStabilizationEnabled: Boolean = true
        private set
    var evCompensationIndex: Int = 0
        private set
    private var sensorActiveArray: Rect? = null

    // Live sensor readouts (used to prevent brightness burst on REC or Auto->Manual switch)
    private var liveIso: Int = 200
    private var liveShutterNs: Long = 16_666_666L
    private var liveFocusDistance: Float = 0f
    private var liveKelvin: Int = 5600
    private var smoothedLiveKelvin: Float = 5600f
    private var lastTelemetryDispatchMs: Long = 0L

    private var resolvedVideoSize: Size = Size(1920, 1080)
    private var resolvedPreviewSize: Size = Size(1920, 1080)
    private var resolvedFpsRange: Range<Int> = Range(30, 30)

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val rawSens = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val rawBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
            if (rawSens != null) {
                liveIso = ((rawSens.toLong() * rawBoost.coerceAtLeast(100).toLong()) / 100L)
                    .toInt()
                    .coerceAtLeast(1)
            }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveShutterNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { liveFocusDistance = it }

            if (!isManualWb) {
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { tf ->
                    lastAwbTransform = tf
                }
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
                    val estK = rggbGainsToKelvin(gains)
                    smoothedLiveKelvin = if (!hasCalibratedWbAnchor) {
                        estK.toFloat()
                    } else {
                        smoothedLiveKelvin * 0.78f + estK * 0.22f
                    }
                    liveKelvin = ((smoothedLiveKelvin / 50f).toInt() * 50).coerceIn(2500, 8500)

                    // Anchor neutral 5600K reference gains to this sensor's real AWB calibration
                    val (rCurve, bCurve) = kelvinCurveMultipliers(liveKelvin)
                    val gEven = gains.greenEven.coerceIn(0.5f, 2.5f)
                    val gOdd = gains.greenOdd.coerceIn(0.5f, 2.5f)
                    val rNeutral = (gains.red / rCurve).coerceIn(0.5f, 4.5f)
                    val bNeutral = (gains.blue / bCurve).coerceIn(0.5f, 4.5f)
                    if (!hasCalibratedWbAnchor) {
                        refNeutralRedGain = rNeutral
                        refNeutralGreenEven = gEven
                        refNeutralGreenOdd = gOdd
                        refNeutralBlueGain = bNeutral
                        hasCalibratedWbAnchor = true
                    } else {
                        refNeutralRedGain = refNeutralRedGain * 0.85f + rNeutral * 0.15f
                        refNeutralGreenEven = refNeutralGreenEven * 0.85f + gEven * 0.15f
                        refNeutralGreenOdd = refNeutralGreenOdd * 0.85f + gOdd * 0.15f
                        refNeutralBlueGain = refNeutralBlueGain * 0.85f + bNeutral * 0.15f
                    }
                }
            }

            // Stage 2 of Auto Pilot: lock exposure + WB cleanly once AE & AWB settle on the visible scene
            if (autoPilotState == AutoPilotState.METERING) {
                autoPilotFramesSeen++
                val elapsed = System.currentTimeMillis() - autoPilotStartMs
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                val aeReady = aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                val awbReady = awbState == null ||
                    awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                    awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED

                val minSettleMet = elapsed >= 520L && autoPilotFramesSeen >= 8 && aeReady && awbReady
                val timedOut = elapsed >= 1250L
                if (minSettleMet || timedOut) {
                    finalizeAutoPilotSceneLock()
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastTelemetryDispatchMs >= 140L) {
                lastTelemetryDispatchMs = now
                listener.onTelemetryUpdated(
                    LiveTelemetry(
                        iso = if (isManualExposure) manualIso else liveIso,
                        shutterNs = if (isManualExposure) manualShutterNs else liveShutterNs,
                        focusDistance = if (isManualFocus) manualFocusDistance else liveFocusDistance,
                        kelvin = if (isManualWb) manualKelvin else liveKelvin,
                        isManualExposure = isManualExposure,
                        isManualFocus = isManualFocus,
                        isManualWb = isManualWb,
                        isLocked = isAeAfLocked
                    )
                )
            }
        }
    }

    fun startThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("IdgafCinemaThread").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
        if (glRenderer == null) {
            glRenderer = CinemaGlRenderer { err -> listener.onError(err) }.also { it.start() }
        } else {
            glRenderer?.start()
        }
        glRenderer?.colorState = gpuColorState
        glRenderer?.splitCompareFraction = splitCompareFraction
        glRenderer?.eraModeCode = eraModeCode
        glRenderer?.eraWearLevel = eraWearLevel
        glRenderer?.eraFps = eraFps
        glRenderer?.mirrorFrontExport = mirrorFrontExport
        glRenderer?.setCubeLut(activeCubeLut)
        glRenderer?.onTimelapseFrameCaptured = { frames ->
            val outSec = frames.toFloat() / targetFps.coerceIn(1, 60).toFloat()
            listener.onTimelapseProgress(frames, outSec)
        }
        if (lenses.isEmpty()) {
            discoverLenses()
        }
    }

    fun stopThread() {
        closeCamera()
        glRenderer?.release()
        glRenderer = null
        previewSurfaceTexture = null
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: Exception) {
        }
        backgroundThread = null
        backgroundHandler = null
    }

    @Volatile var gpuColorState: GpuColorState = GpuColorState()
        private set
    @Volatile var splitCompareFraction: Float = -1.0f
        private set
    @Volatile var activeCubeLut: CubeLutData? = null
        private set
    @Volatile var mirrorFrontExport: Boolean = false
        private set

    fun updateGpuScopes(state: GpuScopeState) {
        glRenderer?.scopeState = state
    }

    fun updateGpuColorState(state: GpuColorState) {
        gpuColorState = state
        glRenderer?.colorState = state
    }

    fun setSplitCompareFraction(fraction: Float) {
        splitCompareFraction = fraction
        glRenderer?.splitCompareFraction = fraction
    }

    fun setEraRenderState(modeCode: Int, wearLevel: Float, fps: Float) {
        eraModeCode = modeCode
        eraWearLevel = wearLevel.coerceIn(0f, 1f)
        eraFps = fps.coerceIn(1f, 60f)
        glRenderer?.eraModeCode = eraModeCode
        glRenderer?.eraWearLevel = eraWearLevel
        glRenderer?.eraFps = eraFps
    }

    /**
     * Zero-reboot FPS change when the GPU Frame-Pacer can gate directly from the active hardware stream
     * (e.g. 16 FPS / 18 FPS on a 30 FPS sensor). Falls back to clean reopen only if desired FPS exceeds sensor upper bound.
     */
    fun setTargetFpsSeamless(fps: Int) {
        if (isRecording) return
        val clamped = fps.coerceIn(1, capabilities.maxHardwareFps.coerceIn(15, 60))
        targetFps = clamped
        if (isShutter180Locked && isManualExposure) {
            manualShutterNs = compute180ShutterNs(clamped)
        }
        if (clamped > resolvedFpsRange.upper) {
            closeCamera()
            openActiveCamera()
        }
    }

    fun setCubeLut(lut: CubeLutData?) {
        activeCubeLut = lut
        glRenderer?.setCubeLut(lut)
    }

    fun setMirrorFrontExport(enabled: Boolean) {
        mirrorFrontExport = enabled
        glRenderer?.mirrorFrontExport = enabled
    }

    fun onPreviewSizeChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            previewViewWidth = width
            previewViewHeight = height
            glRenderer?.updatePreviewSize(width, height)
        }
    }

    fun setDisplayRotation(rotation: Int) {
        currentDisplayRotation = rotation
        glRenderer?.setDisplayRotation(rotation)
    }

    fun onPreviewSurfaceDestroyed() {
        previewSurfaceTexture = null
        glRenderer?.detachPreviewSurfaceSync()
    }

    private fun discoverLenses() {
        val backCandidates = mutableListOf<CinemaLens>()
        var frontLens: CinemaLens? = null

        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val videoSizes = map.getOutputSizes(MediaRecorder::class.java)
                if (videoSizes == null || videoSizes.isEmpty()) continue

                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focals?.firstOrNull() ?: 4.0f
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val eqMm = if (sensorSize != null && sensorSize.width > 0.5f) {
                    ((focal * 36.0f) / sensorSize.width).toInt().coerceIn(10, 200)
                } else {
                    (focal * 5.5f).toInt().coerceIn(12, 120)
                }
                val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCandidates.add(CinemaLens(id, "${eqMm}mm", false, focal, eqMm, orientation))
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontLens == null) {
                    frontLens = CinemaLens(id, "${eqMm}mm", true, focal, eqMm, orientation)
                }
            }
        } catch (e: Exception) {
            listener.onError("Lens scan error: ${e.message}")
        }

        val sortedBack = backCandidates
            .distinctBy { "%.1f".format(Locale.US, it.focalLength) }
            .sortedBy { it.focalLength }

        val labeledBack = sortedBack.take(3).map { it.copy(label = "${it.eqFocalMm}mm") }

        val combined = mutableListOf<CinemaLens>()
        combined.addAll(labeledBack)
        if (frontLens != null) combined.add(frontLens)

        lenses = combined
        // Pick standard wide lens (~22-28mm) as default, or first back lens
        val defaultIdx = lenses.indexOfFirst { !it.isFront && it.eqFocalMm in 21..30 }
            .takeIf { it >= 0 } ?: lenses.indexOfFirst { !it.isFront }.coerceAtLeast(0)
        activeLensIndex = defaultIdx
        listener.onLensesReady(lenses, activeLensIndex)
    }

    fun getActiveLens(): CinemaLens? = lenses.getOrNull(activeLensIndex)

    fun getRecordingMaxAmplitude(): Int {
        return try {
            if (isRecording && !isAudioMuted) mediaRecorder?.maxAmplitude ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }

    fun findExternalMicDevice(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_LINE_ANALOG
        }
    }

    fun attachSurfaceAndOpen(texture: SurfaceTexture, width: Int, height: Int, displayRotation: Int) {
        previewSurfaceTexture = texture
        previewViewWidth = width.coerceAtLeast(1)
        previewViewHeight = height.coerceAtLeast(1)
        currentDisplayRotation = displayRotation
        glRenderer?.attachPreviewSurface(texture, previewViewWidth, previewViewHeight, displayRotation)
        openActiveCamera()
    }

    fun toggleFrontBackCamera(): Boolean {
        if (isRecording || lenses.isEmpty()) return false
        val current = getActiveLens() ?: return false
        val targetIdx = if (current.isFront) {
            lenses.indexOfFirst { !it.isFront && it.eqFocalMm in 21..30 }
                .takeIf { it >= 0 } ?: lenses.indexOfFirst { !it.isFront }
        } else {
            lenses.indexOfFirst { it.isFront }
        }
        if (targetIdx >= 0 && targetIdx != activeLensIndex) {
            selectLens(targetIdx)
            return true
        }
        return false
    }

    fun selectLens(index: Int) {
        if (isRecording || index !in lenses.indices || index == activeLensIndex) return
        activeLensIndex = index
        // Reset lens-specific manual focus when switching glass
        isManualFocus = false
        currentZoomRatio = 1.0f
        isTorchEnabled = false
        lastAwbTransform = null
        hasCalibratedWbAnchor = false
        clearAutoPilotSilently()
        closeCamera()
        openActiveCamera()
    }

    fun updateSettings(resolution: CinemaResolution, fps: Int) {
        if (isRecording) return
        targetResolution = resolution
        targetFps = fps
        if (isShutter180Locked && isManualExposure) {
            manualShutterNs = compute180ShutterNs(fps)
        }
        closeCamera()
        openActiveCamera()
    }

    // ==================== AUTO PILOT (1-TAP SCENE RESCUE & STABLE LOCK) ====================

    /**
     * Stage 1: Instantly rescues buried/broken exposure, WB, tint, EV, focus, lock, tone & stabilization,
     * lets the sensor meter the real visible scene, then Stage 2 locks Exposure + WB steadily while
     * keeping continuous autofocus (AF-C) active. Does NOT start recording automatically.
     */
    fun engageAutoPilot(resetToFps: Int? = null) {
        isAeAfLocked = false
        evCompensationIndex = 0
        isManualExposure = false
        isShutter180Locked = false
        isManualWb = false
        manualTint = 0
        isManualFocus = false
        isStabilizationEnabled = true
        toneProfile = ToneProfile.STANDARD
        hasCalibratedWbAnchor = false

        // If FPS was stuck on a custom/low rate (e.g. 1 FPS), cleanly reopen camera at normal FPS first,
        // then automatically run scene metering & lock the instant the new session configures!
        if (resetToFps != null && targetFps != resetToFps && !isRecording) {
            targetFps = resetToFps.coerceIn(1, capabilities.maxHardwareFps.coerceIn(15, 60))
            pendingAutoPilotOnOpen = true
            autoPilotState = AutoPilotState.METERING
            listener.onAutoPilotStateChanged(AutoPilotState.METERING, null)
            closeCamera()
            openActiveCamera()
            return
        }

        pendingAutoPilotOnOpen = false
        autoPilotState = AutoPilotState.METERING
        autoPilotStartMs = System.currentTimeMillis()
        autoPilotFramesSeen = 0
        listener.onAutoPilotStateChanged(AutoPilotState.METERING, null)

        val session = captureSession
        val builder = activeBuilder
        if (session != null && builder != null) {
            backgroundHandler?.post {
                try {
                    applyCinemaControls(builder)
                    // Clear any stale custom tap-to-focus metering region so the whole frame meters cleanly
                    val resetRegion = arrayOf(MeteringRectangle(0, 0, 0, 0, MeteringRectangle.METERING_WEIGHT_DONT_CARE))
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, resetRegion)
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, resetRegion)
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    session.capture(builder.build(), captureCallback, backgroundHandler)

                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                    session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                } catch (_: Exception) {
                    pushUpdatedRepeatingRequest()
                }
            }
        }
    }

    /**
     * Disengages Auto Pilot and releases Exposure & WB back to standard Auto (keeps AF-C active).
     */
    fun disengageAutoPilot() {
        pendingAutoPilotOnOpen = false
        if (autoPilotState == AutoPilotState.OFF) return
        autoPilotState = AutoPilotState.OFF
        isManualExposure = false
        isShutter180Locked = false
        isManualWb = false
        manualTint = 0
        isManualFocus = false
        isAeAfLocked = false
        pushUpdatedRepeatingRequest()
        listener.onAutoPilotStateChanged(AutoPilotState.OFF, null)
    }

    private fun clearAutoPilotSilently() {
        if (autoPilotState != AutoPilotState.OFF) {
            autoPilotState = AutoPilotState.OFF
            listener.onAutoPilotStateChanged(AutoPilotState.OFF, null)
        }
    }

    private fun finalizeAutoPilotSceneLock(pushRequest: Boolean = true) {
        if (autoPilotState != AutoPilotState.METERING) return

        var isLowLight = false
        var modeTag = "LOCKED"

        // Smart Auto Pilot v2 Exposure Optimizer: preserves exact metered brightness while re-balancing
        // ISO & Shutter toward clean 180-deg cinema motion blur, base-ISO daylight, or low-noise low-light!
        if (capabilities.supportsManualExposure && liveIso > 0 && liveShutterNs > 0L) {
            val minIso = capabilities.effectiveIsoRange.lower.coerceAtLeast(1)
            val maxIso = capabilities.effectiveIsoRange.upper.coerceAtLeast(minIso)
            val minExp = capabilities.exposureRangeNs.lower.coerceAtLeast(10_000L)
            val maxExp = capabilities.exposureRangeNs.upper.coerceAtLeast(minExp)

            val fps = targetFps.coerceIn(1, 60)
            val ideal180Ns = (1_000_000_000L / (fps * 2L)).coerceIn(minExp, maxExp)
            val maxVideoShutterNs = (1_000_000_000L / fps.toLong()).coerceIn(minExp, maxExp)
            val evProduct = liveIso.toDouble() * liveShutterNs.toDouble()
            val isoAt180 = Math.round(evProduct / ideal180Ns.toDouble()).toInt()

            when {
                isoAt180 < minIso -> {
                    // Bright daylight: lock lowest noise base ISO & let shutter absorb excess light
                    manualIso = minIso
                    manualShutterNs = (evProduct / minIso.toDouble()).toLong().coerceIn(minExp, maxExp)
                    isShutter180Locked = false
                    modeTag = "DAYLIGHT"
                }
                isoAt180 <= 800 && isoAt180 <= maxIso -> {
                    // Cinema sweet spot: true 180-degree shutter rule with clean low ISO!
                    manualIso = isoAt180
                    manualShutterNs = ideal180Ns
                    isShutter180Locked = true
                    modeTag = "180° CINE"
                }
                else -> {
                    // Dim / Low-Light: open shutter cleanly up to 1/fps first to slash grain before raising ISO
                    val isoAt360 = Math.round(evProduct / maxVideoShutterNs.toDouble()).toInt()
                    if (isoAt360 in minIso..800 && isoAt360 <= maxIso) {
                        manualIso = minOf(800, maxIso)
                        manualShutterNs = (evProduct / manualIso.toDouble()).toLong().coerceIn(ideal180Ns, maxVideoShutterNs)
                        isShutter180Locked = false
                        modeTag = "LOW-LIGHT"
                    } else {
                        manualShutterNs = maxVideoShutterNs
                        manualIso = isoAt360.coerceIn(minIso, maxIso)
                        isShutter180Locked = false
                        modeTag = "DARK"
                    }
                }
            }
            isManualExposure = true
            isLowLight = (manualIso >= 1600)
        } else {
            isLowLight = (liveIso >= 1600)
        }

        manualKelvin = liveKelvin.coerceIn(2500, 8500)
        manualTint = 0
        isManualWb = true
        // Keep Focus LIVE in AF-C so moving subjects remain sharp automatically!
        isManualFocus = false
        isAeAfLocked = false

        autoPilotState = AutoPilotState.LOCKED
        if (pushRequest) {
            pushUpdatedRepeatingRequest()
        }

        val denom = (1_000_000_000.0 / manualShutterNs.coerceAtLeast(1L).toDouble()).toInt().coerceAtLeast(1)
        val summary = "PILOT ($modeTag) • ISO $manualIso • 1/${denom}s • ${manualKelvin}K • AF-C"
        listener.onAutoPilotStateChanged(AutoPilotState.LOCKED, summary, isLowLight)
    }

    // ==================== PRO MANUAL CONTROLS API ====================

    fun setManualIso(iso: Int) {
        clearAutoPilotSilently()
        if (!capabilities.supportsManualExposure) return
        if (!isManualExposure) {
            manualShutterNs = if (isShutter180Locked) {
                compute180ShutterNs(targetFps)
            } else {
                liveShutterNs.coerceIn(capabilities.exposureRangeNs.lower, maxOf(capabilities.exposureRangeNs.lower, minOf(capabilities.exposureRangeNs.upper, 100_000_000L)))
            }
            isManualExposure = true
        }
        manualIso = iso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
        pushUpdatedRepeatingRequest()
    }

    fun setManualShutterNs(shutterNs: Long) {
        clearAutoPilotSilently()
        if (!capabilities.supportsManualExposure) return
        if (!isManualExposure) {
            manualIso = liveIso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
            isManualExposure = true
        }
        isShutter180Locked = false
        manualShutterNs = shutterNs.coerceIn(capabilities.exposureRangeNs.lower, capabilities.exposureRangeNs.upper)
        pushUpdatedRepeatingRequest()
    }

    fun setManualShutterAngle(angleDegrees: Float) {
        clearAutoPilotSilently()
        if (!capabilities.supportsManualExposure) return
        if (!isManualExposure) {
            manualIso = liveIso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
            isManualExposure = true
        }
        val clampedAngle = angleDegrees.coerceIn(11.25f, 360f)
        isShutter180Locked = kotlin.math.abs(clampedAngle - 180f) < 1f
        val fps = targetFps.coerceAtLeast(1)
        val rawNs = ((clampedAngle / 360.0) * (1_000_000_000.0 / fps)).toLong()
        manualShutterNs = rawNs.coerceIn(capabilities.exposureRangeNs.lower, capabilities.exposureRangeNs.upper)
        pushUpdatedRepeatingRequest()
    }

    fun getCurrentShutterAngle(): Float {
        val fps = targetFps.coerceAtLeast(1)
        val ns = if (isManualExposure) manualShutterNs else liveShutterNs
        return ((ns.toDouble() * fps / 1_000_000_000.0) * 360.0).toFloat().coerceIn(1f, 360f)
    }

    fun setZoomRatio(zoom: Float) {
        val clamped = zoom.coerceIn(capabilities.minZoom, capabilities.maxZoom)
        if (abs(clamped - currentZoomRatio) < 0.005f) return
        currentZoomRatio = clamped
        pushUpdatedRepeatingRequest()
    }

    fun resetZoom() {
        setZoomRatio(1.0f)
    }

    fun toggleTorch(): Boolean {
        return setTorchEnabled(!isTorchEnabled)
    }

    fun setTorchEnabled(enabled: Boolean): Boolean {
        if (!capabilities.hasFlash) {
            isTorchEnabled = false
            return false
        }
        isTorchEnabled = enabled
        pushUpdatedRepeatingRequest()
        return isTorchEnabled
    }

    fun toggleStabilization(): Boolean {
        isStabilizationEnabled = !isStabilizationEnabled
        pushUpdatedRepeatingRequest()
        return isStabilizationEnabled
    }

    fun setStabilizationEnabled(enabled: Boolean) {
        isStabilizationEnabled = enabled
        pushUpdatedRepeatingRequest()
    }

    fun setToneProfile(profile: ToneProfile) {
        toneProfile = profile
        pushUpdatedRepeatingRequest()
    }

    fun toggleShutter180Rule(): Boolean {
        clearAutoPilotSilently()
        if (!capabilities.supportsManualExposure) return false
        isShutter180Locked = !isShutter180Locked
        if (isShutter180Locked) {
            if (!isManualExposure) {
                manualIso = liveIso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
                isManualExposure = true
            }
            manualShutterNs = compute180ShutterNs(targetFps)
            pushUpdatedRepeatingRequest()
        }
        return isShutter180Locked
    }

    fun setEvCompensation(index: Int): Boolean {
        if (isAeAfLocked) return false
        clearAutoPilotSilently()
        val clamped = index.coerceIn(capabilities.evRange.lower, capabilities.evRange.upper)
        evCompensationIndex = clamped
        if (isManualExposure) {
            isManualExposure = false
            isShutter180Locked = false
        }
        pushUpdatedRepeatingRequest()
        return true
    }

    fun getCurrentEvStops(): Float = evCompensationIndex * capabilities.evStep

    fun setAutoExposure() {
        clearAutoPilotSilently()
        isManualExposure = false
        isShutter180Locked = false
        pushUpdatedRepeatingRequest()
    }

    fun setManualFocusDistance(diopters: Float) {
        clearAutoPilotSilently()
        if (!capabilities.supportsManualFocus) return
        isManualFocus = true
        manualFocusDistance = diopters.coerceIn(0f, capabilities.minFocusDistance)
        pushUpdatedRepeatingRequest()
    }

    fun setAutoFocus() {
        isManualFocus = false
        pushUpdatedRepeatingRequest()
    }

    fun setManualWhiteBalance(kelvin: Int, tint: Int) {
        clearAutoPilotSilently()
        isManualWb = true
        manualKelvin = kelvin.coerceIn(2500, 8500)
        manualTint = tint.coerceIn(-50, 50)
        pushUpdatedRepeatingRequest()
    }

    fun setManualWhiteBalanceKelvin(kelvin: Int) {
        clearAutoPilotSilently()
        isManualWb = true
        manualKelvin = kelvin.coerceIn(2500, 8500)
        pushUpdatedRepeatingRequest()
    }

    fun setManualWhiteBalanceTint(tint: Int) {
        clearAutoPilotSilently()
        if (!isManualWb) {
            manualKelvin = liveKelvin.coerceIn(2500, 8500)
            isManualWb = true
        }
        manualTint = tint.coerceIn(-50, 50)
        pushUpdatedRepeatingRequest()
    }

    fun setAutoWhiteBalance() {
        clearAutoPilotSilently()
        isManualWb = false
        manualTint = 0
        pushUpdatedRepeatingRequest()
    }

    fun getLiveKelvin(): Int = liveKelvin

    fun setShutter180Locked(locked: Boolean) {
        if (!capabilities.supportsManualExposure) {
            isShutter180Locked = false
            return
        }
        isShutter180Locked = locked
        if (locked) {
            if (!isManualExposure) {
                manualIso = liveIso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
                isManualExposure = true
            }
            manualShutterNs = compute180ShutterNs(targetFps)
            pushUpdatedRepeatingRequest()
        }
    }

    fun clearAeAfLock() {
        if (isAeAfLocked) {
            isAeAfLocked = false
            pushUpdatedRepeatingRequest()
        }
    }

    fun toggleAeAfLock(): Boolean {
        clearAutoPilotSilently()
        isAeAfLocked = !isAeAfLocked
        if (isAeAfLocked && !isManualExposure && capabilities.supportsManualExposure) {
            // Freeze exact current exposure (including post-RAW boost) to eliminate any ISP drift
            manualIso = liveIso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
            manualShutterNs = liveShutterNs.coerceIn(capabilities.exposureRangeNs.lower, capabilities.exposureRangeNs.upper)
        }
        pushUpdatedRepeatingRequest()
        return isAeAfLocked
    }

    private fun compute180ShutterNs(fps: Int): Long {
        val denom = (fps * 2).coerceAtLeast(24)
        val rawNs = 1_000_000_000L / denom
        return rawNs.coerceIn(capabilities.exposureRangeNs.lower, capabilities.exposureRangeNs.upper)
    }

    private fun applyCinemaControls(builder: CaptureRequest.Builder, lockAutoForRecordStart: Boolean = false) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, resolvedFpsRange)
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)

        // 1. Exposure (Manual ISO/Shutter + Extended Post-RAW Boost vs Locked Auto vs Auto)
        if (isManualExposure && capabilities.supportsManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            applyEffectiveIso(builder, manualIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
        } else if ((isAeAfLocked || lockAutoForRecordStart) && capabilities.supportsManualExposure && liveIso > 0 && liveShutterNs > 0L) {
            // Anti-burst lock: freeze exact live effective ISO & shutter so REC never flashes or dims
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            applyEffectiveIso(builder, liveIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, liveShutterNs.coerceIn(capabilities.exposureRangeNs.lower, capabilities.exposureRangeNs.upper))
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.CONTROL_AE_LOCK,
                isAeAfLocked || lockAutoForRecordStart || autoPilotState == AutoPilotState.LOCKED
            )
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                evCompensationIndex.coerceIn(capabilities.evRange.lower, capabilities.evRange.upper)
            )
        }

        // 2. Focus (Manual Diopter vs Locked AF vs Continuous Video AF)
        if (isManualFocus && capabilities.supportsManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        } else if (isAeAfLocked && capabilities.supportsManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, liveFocusDistance.coerceIn(0f, capabilities.minFocusDistance))
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // 3. White Balance (Calibrated ISP Color Matrix + Sensor-Anchored Kelvin/Tint Gains vs AWB)
        if (isManualWb) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinAndTintToRggbGains(manualKelvin, manualTint))
            val calibratedMatrix = lastAwbTransform ?: ColorSpaceTransform(
                intArrayOf(
                    1, 1, 0, 1, 0, 1,
                    0, 1, 1, 1, 0, 1,
                    0, 1, 0, 1, 1, 1
                )
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, calibratedMatrix)
        } else {
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, isAeAfLocked || lockAutoForRecordStart)
        }

        // 3.5. Hardware Zoom (CONTROL_ZOOM_RATIO on API 30+, SCALER_CROP_REGION fallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(
                CaptureRequest.CONTROL_ZOOM_RATIO,
                currentZoomRatio.coerceIn(capabilities.minZoom, capabilities.maxZoom)
            )
        } else {
            val activeRect = sensorActiveArray
            if (activeRect != null && currentZoomRatio > 1.01f) {
                val cropW = (activeRect.width() / currentZoomRatio).toInt().coerceAtLeast(1)
                val cropH = (activeRect.height() / currentZoomRatio).toInt().coerceAtLeast(1)
                val left = (activeRect.width() - cropW) / 2
                val top = (activeRect.height() - cropH) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
            } else if (activeRect != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, activeRect)
            }
        }

        // 3.6. Hardware Torch / Video Light (works on back & front lenses if hardware flash exists)
        if (capabilities.hasFlash && isTorchEnabled) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }

        // 3.7. Hardware Video Stabilization (OIS / EIS)
        if (isStabilizationEnabled) {
            if (capabilities.supportsEis) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            if (capabilities.supportsOis) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
        } else {
            if (capabilities.supportsEis) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            if (capabilities.supportsOis) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
        }

        // 4. Flat / Cinema Tone Curve
        when (toneProfile) {
            ToneProfile.STANDARD -> {
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            }
            ToneProfile.FLAT -> {
                // Lifted shadows & rolled-off highlights for easier color grading
                val flatCurve = floatArrayOf(
                    0.00f, 0.06f,
                    0.25f, 0.34f,
                    0.50f, 0.54f,
                    0.75f, 0.76f,
                    1.00f, 0.94f
                )
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                builder.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(flatCurve, flatCurve, flatCurve))
            }
            ToneProfile.CINEMA -> {
                // Filmic S-curve with deep blacks & smooth highlight roll-off
                val cineCurve = floatArrayOf(
                    0.00f, 0.00f,
                    0.20f, 0.14f,
                    0.50f, 0.50f,
                    0.80f, 0.86f,
                    1.00f, 0.98f
                )
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                builder.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(cineCurve, cineCurve, cineCurve))
            }
        }
    }

    private fun pushUpdatedRepeatingRequest() {
        val session = captureSession ?: return
        val builder = activeBuilder ?: return
        backgroundHandler?.post {
            try {
                applyCinemaControls(builder)
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Splits effective ISO into analog SENSOR_SENSITIVITY + hardware CONTROL_POST_RAW_SENSITIVITY_BOOST,
     * allowing manual mode to reach full Auto low-light brightness above the analog ISO ceiling.
     */
    private fun applyEffectiveIso(builder: CaptureRequest.Builder, effectiveIso: Int) {
        val analogMin = capabilities.isoRange.lower
        val analogMax = capabilities.isoRange.upper.coerceAtLeast(analogMin)
        val boostMin = capabilities.postRawBoostRange.lower.coerceAtLeast(100)
        val boostMax = capabilities.postRawBoostRange.upper.coerceAtLeast(boostMin)

        if (effectiveIso <= analogMax || !capabilities.supportsPostRawBoost || boostMax <= 100) {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, effectiveIso.coerceIn(analogMin, analogMax))
            if (capabilities.supportsPostRawBoost) {
                builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100.coerceIn(boostMin, boostMax))
            }
        } else {
            val neededBoost = ((effectiveIso * 100.0) / analogMax.toDouble()).toInt().coerceIn(boostMin, boostMax)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, analogMax)
            builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, neededBoost)
        }
    }

    /**
     * Perceptually balanced log-Kelvin curve relative to 5600K daylight:
     * 2500K = Cool Blue (low R, high B), 5600K = Neutral (1.0, 1.0), 8500K = Warm Amber (high R, low B).
     */
    private fun kelvinCurveMultipliers(kelvin: Int): Pair<Float, Float> {
        val k = kelvin.coerceIn(2500, 8500).toDouble()
        val delta = ln(k / 5600.0)
        val rMul = exp(0.68 * delta).toFloat()
        val bMul = exp(-0.78 * delta).toFloat()
        return rMul to bMul
    }

    /**
     * Computes sensor-calibrated RGGB gains anchored to the phone's real AWB calibration.
     * Matching manualKelvin to liveKelvin with tint=0 reproduces AWB's exact color with zero green cast.
     */
    private fun kelvinAndTintToRggbGains(kelvin: Int, tint: Int): RggbChannelVector {
        val (rCurve, bCurve) = kelvinCurveMultipliers(kelvin)
        val clampedTint = tint.coerceIn(-50, 50).toDouble()
        val greenScale = exp(-0.005 * clampedTint).toFloat()
        val rbScale = exp(0.0025 * clampedTint).toFloat()

        val rGain = (refNeutralRedGain * rCurve * rbScale).coerceIn(0.4f, 6.0f)
        val gEven = (refNeutralGreenEven * greenScale).coerceIn(0.4f, 3.0f)
        val gOdd = (refNeutralGreenOdd * greenScale).coerceIn(0.4f, 3.0f)
        val bGain = (refNeutralBlueGain * bCurve * rbScale).coerceIn(0.4f, 6.0f)

        return RggbChannelVector(rGain, gEven, gOdd, bGain)
    }

    /**
     * Estimates live scene color temperature (Kelvin) from Camera2 AWB RGGB channel gains.
     */
    private fun rggbGainsToKelvin(gains: RggbChannelVector): Int {
        val g = ((gains.greenEven + gains.greenOdd) * 0.5f).coerceAtLeast(0.01f)
        val rn = (gains.red / g).coerceAtLeast(0.2f)
        val bn = (gains.blue / g).coerceAtLeast(0.2f)
        val ratio = (rn / bn).toDouble().coerceIn(0.35, 3.0)
        val k = 5200.0 + 3800.0 * ln(ratio)
        return k.toInt().coerceIn(2500, 8500)
    }

    @SuppressLint("MissingPermission")
    fun openActiveCamera() {
        val lens = getActiveLens() ?: return
        if (previewSurfaceTexture == null) return

        try {
            val chars = cameraManager.getCameraCharacteristics(lens.cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                listener.onError("Camera stream map unavailable.")
                return
            }

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val boostRange = chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            sensorActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null
            val maxDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f
            val minZ = (zoomRange?.lower ?: 1.0f).coerceAtLeast(0.5f)
            val maxZ = (zoomRange?.upper ?: maxDigital).coerceAtMost(10.0f).coerceAtLeast(minZ)

            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-12, 12)
            val evStepRational = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val evStep = evStepRational?.toFloat()?.takeIf { it > 0f } ?: 0.333f
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            val supportsEis = eisModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            val supportsOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

            val videoSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
            val maxHardwareW = videoSizes.maxOfOrNull { it.width } ?: 1920
            val maxHardwareH = videoSizes.maxOfOrNull { it.height } ?: 1080

            val supportedRes = CinemaResolution.values().filter { res ->
                videoSizes.any { s -> s.width >= res.width - 100 && s.height >= res.height - 60 } ||
                    (res.width <= maxHardwareW && res.height <= maxHardwareH)
            }.ifEmpty { listOf(CinemaResolution.FHD_1080P) }

            if (targetResolution !in supportedRes) {
                targetResolution = supportedRes.lastOrNull { it.width <= 1920 } ?: supportedRes.first()
            }

            val rawAnalog = isoRange ?: Range(100, 3200)
            val analogLower = rawAnalog.lower.coerceAtLeast(1)
            val analogUpper = rawAnalog.upper.coerceAtLeast(analogLower)
            val resolvedAnalogIso = Range(analogLower, analogUpper)

            val rawBoost = boostRange ?: Range(100, 100)
            val boostLower = rawBoost.lower.coerceAtLeast(100)
            val boostUpper = rawBoost.upper.coerceAtLeast(boostLower)
            val resolvedBoost = Range(boostLower, boostUpper)

            val boostMax = boostUpper
            val boostedMaxLong = (analogUpper.toLong() * boostMax.toLong()) / 100L
            // Never cap below the sensor's own native analog ISO ceiling!
            val safeCap = maxOf(analogUpper.toLong(), 32000L).coerceAtMost(Int.MAX_VALUE.toLong())
            val effMaxIso = boostedMaxLong.coerceIn(analogUpper.toLong(), safeCap).toInt()
            val resolvedEffectiveIso = Range(analogLower, effMaxIso.coerceAtLeast(analogLower))

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val maxHwFps = (fpsRanges.maxOfOrNull { it.upper } ?: 30).coerceIn(15, 60)
            if (targetFps > maxHwFps) {
                targetFps = maxHwFps
            }

            capabilities = CameraCapabilities(
                isoRange = resolvedAnalogIso,
                effectiveIsoRange = resolvedEffectiveIso,
                postRawBoostRange = resolvedBoost,
                supportsPostRawBoost = (boostRange != null && boostMax > 100),
                exposureRangeNs = expRange ?: Range(125_000L, 100_000_000L),
                minFocusDistance = minFocus,
                supportsManualExposure = (isoRange != null && expRange != null),
                supportsManualFocus = (minFocus > 0f),
                minZoom = minZ,
                maxZoom = maxZ,
                hasFlash = hasFlash,
                supportsEis = supportsEis,
                supportsOis = supportsOis,
                supportedResolutions = supportedRes,
                evRange = evRange,
                evStep = evStep,
                maxHardwareFps = maxHwFps
            )
            evCompensationIndex = evCompensationIndex.coerceIn(evRange.lower, evRange.upper)
            if (!hasFlash) isTorchEnabled = false
            currentZoomRatio = currentZoomRatio.coerceIn(minZ, maxZ)

            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            // Pick a video size supported by BOTH MediaRecorder and SurfaceTexture (for our OpenGL ES OES input!)
            val sharedSizes = videoSizes.filter { vs ->
                previewSizes.any { ps -> ps.width == vs.width && ps.height == vs.height }
            }.toTypedArray()
            val candidateSizes = if (sharedSizes.isNotEmpty()) sharedSizes else previewSizes.ifEmpty { videoSizes }
            resolvedVideoSize = chooseClosest16x9Size(candidateSizes, targetResolution.width, targetResolution.height)
            resolvedPreviewSize = resolvedVideoSize

            resolvedFpsRange = chooseBestFpsRange(fpsRanges, targetFps)

            cameraManager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    listener.onError("Camera hardware error ($error)")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            listener.onError("Open camera failed: ${e.message}")
        }
    }

    private fun startPreviewSession() {
        val camera = cameraDevice ?: return
        val renderer = glRenderer ?: return
        val lens = getActiveLens() ?: return

        try {
            closeSession()
            val glInputSurface = renderer.prepareCameraInputSurface(
                width = resolvedVideoSize.width,
                height = resolvedVideoSize.height,
                sensorOrientation = lens.sensorOrientation,
                isFront = lens.isFront
            ) ?: run {
                listener.onError("OpenGL input surface unavailable.")
                return
            }

            // Single continuous TEMPLATE_RECORD stream into OpenGL ES — zero session restarts when REC starts/stops!
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(glInputSurface)
                applyCinemaControls(this)
            }
            activeBuilder = builder

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(glInputSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                            listener.onCameraReady(
                                resolvedPreviewSize,
                                resolvedVideoSize,
                                targetFps,
                                lens,
                                capabilities
                            )
                            if (pendingAutoPilotOnOpen) {
                                pendingAutoPilotOnOpen = false
                                engageAutoPilot(resetToFps = null)
                            }
                        } catch (e: Exception) {
                            listener.onError("Preview start failed: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        listener.onError("Camera GL session configuration failed.")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            listener.onError("Session error: ${e.message}")
        }
    }

    fun triggerTapToFocus(normX: Float, normY: Float, displayRotation: Int) {
        if (isManualFocus || isAeAfLocked) return
        val session = captureSession ?: return
        val builder = activeBuilder ?: return
        val lens = getActiveLens() ?: return

        backgroundHandler?.post {
            try {
                val chars = cameraManager.getCameraCharacteristics(lens.cameraId)
                val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
                val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                if (maxAfRegions <= 0) return@post

                // Map landscape screen touch coordinates into Camera2 sensor coordinates
                var sx = normX.coerceIn(0f, 1f)
                var sy = normY.coerceIn(0f, 1f)
                if (displayRotation == Surface.ROTATION_270) {
                    sx = 1f - sx
                    sy = 1f - sy
                }
                if (lens.isFront) {
                    sx = 1f - sx
                }

                val cx = (sx * sensorRect.width()).toInt()
                val cy = (sy * sensorRect.height()).toInt()
                val halfW = (sensorRect.width() * 0.10f).toInt()
                val halfH = (sensorRect.height() * 0.10f).toInt()

                val rect = Rect(
                    (cx - halfW).coerceIn(0, sensorRect.width() - 1),
                    (cy - halfH).coerceIn(0, sensorRect.height() - 1),
                    (cx + halfW).coerceIn(1, sensorRect.width()),
                    (cy + halfH).coerceIn(1, sensorRect.height())
                )
                val metering = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))

                // 1. Cancel any previous AF trigger
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                session.capture(builder.build(), captureCallback, backgroundHandler)

                // 2. Apply AF/AE regions and trigger immediate AF + AE precapture re-metering
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, metering)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

                if (!isManualExposure) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, metering)
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
                session.capture(builder.build(), captureCallback, backgroundHandler)

                // 3. Keep repeating request active on the tapped metering region
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                if (!isManualExposure) {
                    builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                }
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)

                // 4. After 2.5s, smoothly resume continuous video AF so Auto stays responsive
                backgroundHandler?.postDelayed({
                    if (!isManualFocus && !isAeAfLocked && captureSession == session) {
                        try {
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                        } catch (_: Exception) {
                        }
                    }
                }, 2500L)
            } catch (_: Exception) {
            }
        }
    }

    fun startRecording(displayRotation: Int) {
        if (isRecording || isFinalizingClip) return
        val renderer = glRenderer ?: return
        val lens = getActiveLens() ?: return
        if (cameraDevice == null || captureSession == null) return

        // Audit fix: if user taps REC while Auto Pilot is still metering, snap-lock Exposure + WB
        // right now BEFORE MediaRecorder starts so exposure/color never jump mid-recording!
        if (autoPilotState == AutoPilotState.METERING) {
            finalizeAutoPilotSceneLock(pushRequest = true)
        }

        try {
            val recorder = createAndPrepareMediaRecorder(lens, displayRotation)
            mediaRecorder = recorder

            val attached = renderer.attachEncoderSurfaceSync(
                recorderSurface = recorder.surface,
                width = resolvedVideoSize.width,
                height = resolvedVideoSize.height,
                rotation = displayRotation,
                desiredFps = targetFps,
                hwFpsUpper = resolvedFpsRange.upper,
                timelapseIntervalMs = timelapseIntervalMs
            )
            if (!attached) {
                throw IllegalStateException("Failed to attach OpenGL encoder surface")
            }

            recorder.start()
            isRecording = true
            // Enable GL encoder pass ONLY after MediaRecorder.start() has initialized its audio + video tracks!
            renderer.setEncoderRecordingActive(true)
            listener.onRecordingStarted()
        } catch (e: Exception) {
            glRenderer?.detachEncoderSurfaceSync()
            cleanupRecorderOnFailure()
            listener.onError("Record start failed: ${e.message}")
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        // Step 1: Synchronously detach GL encoder surface BEFORE MediaRecorder.stop() so EGL never writes to a dead surface!
        glRenderer?.detachEncoderSurfaceSync()

        var stopSucceeded = false
        try {
            mediaRecorder?.stop()
            stopSucceeded = true
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null

        try {
            activeOutputPfd?.close()
        } catch (_: Exception) {
        }
        activeOutputPfd = null

        val tempRaw = activeTempRawFile
        val targetName = activeTargetFileName
        val label = activeFileLabel
        activeTempRawFile = null
        activeTargetFileName = ""

        if (tempRaw != null) {
            activeMediaUri = null
            if (!stopSucceeded || !tempRaw.exists() || tempRaw.length() < 1024L) {
                try { tempRaw.delete() } catch (_: Exception) {}
                listener.onRecordingStopped(null, "")
                return
            }

            // Phase 7.4 Era Optical Mono Audio DSP Remux (lossless video copy + authentic 1950s Academy Curve audio)
            val profile = eraAudioProfile
            val archiveWear = (eraWearLevel > 0.25f)
            isFinalizingClip = true
            Thread {
                val processedFile = File(context.cacheDir, "era_proc_${System.currentTimeMillis()}.mp4")
                var finalUri: Uri? = null
                try {
                    val dspOk = EraAudioProcessor.processMp4Audio(
                        inputFile = tempRaw,
                        outputFile = processedFile,
                        profile = profile,
                        archiveWear = archiveWear
                    )
                    val sourceToCommit = if (dspOk && processedFile.exists() && processedFile.length() > 1024L) {
                        processedFile
                    } else {
                        tempRaw // Ironclad fallback: never lose a take!
                    }
                    finalUri = commitCacheMp4ToMediaStore(sourceToCommit, targetName)
                } catch (_: Exception) {
                    try {
                        finalUri = commitCacheMp4ToMediaStore(tempRaw, targetName)
                    } catch (_: Exception) {
                    }
                } finally {
                    try { tempRaw.delete() } catch (_: Exception) {}
                    try { processedFile.delete() } catch (_: Exception) {}
                    isFinalizingClip = false
                    listener.onRecordingStopped(finalUri, label)
                }
            }.start()
            return
        }

        val savedUri = activeMediaUri
        activeMediaUri = null
        if (!stopSucceeded && savedUri != null) {
            try {
                context.contentResolver.delete(savedUri, null, null)
            } catch (_: Exception) {
            }
            if (isTimelapseEnabled()) {
                listener.onError("Time-Lapse too short — wait for at least 2 frames to capture.")
            }
            listener.onRecordingStopped(null, "")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && savedUri != null) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(savedUri, values, null, null)
            } catch (_: Exception) {
            }
        }

        listener.onRecordingStopped(savedUri, label)
    }

    private fun commitCacheMp4ToMediaStore(sourceFile: File, fileName: String): Uri? {
        if (!sourceFile.exists() || sourceFile.length() < 1024L) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/IDGAF_Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { ins -> ins.copyTo(out, 65_536) }
            }
            val doneVals = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, doneVals, null, null)
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "IDGAF_Camera"
            )
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            sourceFile.inputStream().use { ins ->
                outFile.outputStream().use { out -> ins.copyTo(out, 65_536) }
            }
            Uri.fromFile(outFile)
        }
    }

    private fun formatTimelapseFileTag(): String {
        val ms = timelapseIntervalMs
        return if (ms % 1000L == 0L) "${ms / 1000L}s" else String.format(Locale.US, "%.1fs", ms / 1000f).replace('.', 'p')
    }

    private fun createAndPrepareMediaRecorder(lens: CinemaLens, displayRotation: Int): MediaRecorder {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val isTl = isTimelapseEnabled()
        val eraTag = eraFilenameTag.trim()
        val fileName = when {
            eraTag.isNotEmpty() -> "IDGAF_${eraTag}_${targetFps}fps_$timestamp.mp4"
            isTl -> "IDGAF_TL${formatTimelapseFileTag()}_${resolvedVideoSize.height}p_${targetFps}fps_$timestamp.mp4"
            else -> "IDGAF_${resolvedVideoSize.height}p_${targetFps}fps_$timestamp.mp4"
        }
        activeFileLabel = "Movies/IDGAF_Camera/$fileName"

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val useEraAudioDsp = (eraAudioProfile != EraAudioProfile.NONE && !eraSilentAudio && !isTl)
        val recordAudio = if (useEraAudioDsp) true else (!isAudioMuted && !isTl && !eraSilentAudio)
        if (recordAudio) {
            recorder.setAudioSource(if (useEraAudioDsp) MediaRecorder.AudioSource.CAMCORDER else audioPreampMode.audioSource)
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        if (useEraAudioDsp) {
            val rawCache = File(context.cacheDir, "era_raw_${System.currentTimeMillis()}.mp4")
            activeTempRawFile = rawCache
            activeTargetFileName = fileName
            activeMediaUri = null
            recorder.setOutputFile(rawCache.absolutePath)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activeTempRawFile = null
            activeTargetFileName = ""
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/IDGAF_Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            activeMediaUri = uri
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Cannot open output descriptor")
            activeOutputPfd = pfd
            recorder.setOutputFile(pfd.fileDescriptor)
        } else {
            activeTempRawFile = null
            activeTargetFileName = ""
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "IDGAF_Camera"
            )
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            activeMediaUri = Uri.fromFile(outFile)
            recorder.setOutputFile(outFile.absolutePath)
        }

        val bitrateBps = targetBitrateMbps.coerceIn(2, 120) * 1_000_000
        recorder.setVideoEncodingBitRate(bitrateBps)
        recorder.setVideoFrameRate(targetFps.coerceIn(1, 60))
        recorder.setVideoSize(resolvedVideoSize.width, resolvedVideoSize.height)
        recorder.setVideoEncoder(targetCodec.encoder)
        if (recordAudio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            if (useEraAudioDsp) {
                recorder.setAudioChannels(1)
                recorder.setAudioEncodingBitRate(96_000)
                recorder.setAudioSamplingRate(44_100)
            } else {
                recorder.setAudioEncodingBitRate(256_000)
                recorder.setAudioSamplingRate(48_000)
            }
        }
        // Upright orientation is baked directly inside CinemaGlRenderer's GPU encoder pass
        recorder.setOrientationHint(0)

        // Route external USB/Wired mic if preferred or detected in AUTO mode (API 28+)
        if (recordAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && micPreference != MicPreference.INTERNAL) {
            val extMic = findExternalMicDevice()
            if (extMic != null) {
                try {
                    recorder.setPreferredDevice(extMic)
                } catch (_: Exception) {
                }
            }
        }

        recorder.prepare()
        return recorder
    }

    private fun cleanupRecorderOnFailure() {
        isRecording = false
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        try {
            activeOutputPfd?.close()
        } catch (_: Exception) {
        }
        activeOutputPfd = null
        activeMediaUri?.let { uri ->
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        activeMediaUri = null
        activeTempRawFile?.let { f ->
            try { f.delete() } catch (_: Exception) {}
        }
        activeTempRawFile = null
        activeTargetFileName = ""
    }

    private fun closeSession() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
    }

    fun closeCamera() {
        if (isRecording) stopRecording()
        if (autoPilotState == AutoPilotState.METERING && !pendingAutoPilotOnOpen) clearAutoPilotSilently()
        closeSession()
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
    }

    private fun chooseClosest16x9Size(choices: Array<Size>, targetW: Int, targetH: Int): Size {
        if (choices.isEmpty()) return Size(1920, 1080)
        val aspect16x9 = choices.filter { s ->
            val ratio = s.width.toFloat() / s.height.toFloat().coerceAtLeast(1f)
            abs(ratio - (16f / 9f)) < 0.08f && s.width <= targetW && s.height <= targetH
        }
        if (aspect16x9.isNotEmpty()) {
            return aspect16x9.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
        }
        val capped = choices.filter { it.width <= targetW && it.height <= targetH }
        return (capped.ifEmpty { choices.toList() }).maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    }

    private fun chooseBestFpsRange(ranges: Array<Range<Int>>, desiredFps: Int): Range<Int> {
        if (ranges.isEmpty()) return Range(30, 30)
        // Exact fixed hardware match (e.g., 24/24, 30/30, 60/60)
        ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }?.let { return it }
        // Exact upper match (e.g., 15..24 for 24fps)
        ranges.firstOrNull { it.upper == desiredFps }?.let { return it }
        // For custom low FPS (< 20, e.g. 1 FPS, 5 FPS, 12 FPS), keep a smooth 30fps viewfinder stream
        // while CinemaGlRenderer's GPU Frame-Pacer gates Pass 2 to exact desiredFps!
        if (desiredFps < 20) {
            ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
            ranges.firstOrNull { it.upper == 30 }?.let { return it }
        }
        return ranges.minByOrNull { abs(it.upper - desiredFps) } ?: Range(30, 30)
    }
}
