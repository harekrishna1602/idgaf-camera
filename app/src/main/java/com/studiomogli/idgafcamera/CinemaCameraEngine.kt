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
    val evStep: Float = 0.333f
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

    private var surfaceTexture: SurfaceTexture? = null
    private var lenses: List<CinemaLens> = emptyList()
    private var activeLensIndex: Int = 0

    var targetResolution: CinemaResolution = CinemaResolution.FHD_1080P
    var targetFps: Int = 30
    var targetCodec: CinemaCodec = CinemaCodec.H264
    var targetBitrateMbps: Int = 35
    var micPreference: MicPreference = MicPreference.AUTO
    var audioPreampMode: AudioPreampMode = AudioPreampMode.CAMCORDER
    var isAudioMuted: Boolean = false
    var audioMonitorTrimDb: Int = 0
    var toneProfile: ToneProfile = ToneProfile.STANDARD
        private set
    var isRecording: Boolean = false
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
                    smoothedLiveKelvin = smoothedLiveKelvin * 0.78f + estK * 0.22f
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
        if (lenses.isEmpty()) {
            discoverLenses()
        }
    }

    fun stopThread() {
        closeCamera()
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: Exception) {
        }
        backgroundThread = null
        backgroundHandler = null
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

    fun attachSurfaceAndOpen(texture: SurfaceTexture) {
        surfaceTexture = texture
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

    // ==================== PRO MANUAL CONTROLS API ====================

    fun setManualIso(iso: Int) {
        if (!capabilities.supportsManualExposure) return
        if (!isManualExposure) {
            manualShutterNs = if (isShutter180Locked) {
                compute180ShutterNs(resolvedFpsRange.upper)
            } else {
                liveShutterNs.coerceIn(capabilities.exposureRangeNs.lower, maxOf(capabilities.exposureRangeNs.lower, minOf(capabilities.exposureRangeNs.upper, 100_000_000L)))
            }
            isManualExposure = true
        }
        manualIso = iso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
        pushUpdatedRepeatingRequest()
    }

    fun setManualShutterNs(shutterNs: Long) {
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
        if (!capabilities.supportsManualExposure) return
        if (!isManualExposure) {
            manualIso = liveIso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
            isManualExposure = true
        }
        val clampedAngle = angleDegrees.coerceIn(11.25f, 360f)
        isShutter180Locked = kotlin.math.abs(clampedAngle - 180f) < 1f
        val fps = resolvedFpsRange.upper.coerceAtLeast(1)
        val rawNs = ((clampedAngle / 360.0) * (1_000_000_000.0 / fps)).toLong()
        manualShutterNs = rawNs.coerceIn(capabilities.exposureRangeNs.lower, capabilities.exposureRangeNs.upper)
        pushUpdatedRepeatingRequest()
    }

    fun getCurrentShutterAngle(): Float {
        val fps = resolvedFpsRange.upper.coerceAtLeast(1)
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
        if (!capabilities.hasFlash) {
            isTorchEnabled = false
            return false
        }
        isTorchEnabled = !isTorchEnabled
        pushUpdatedRepeatingRequest()
        return isTorchEnabled
    }

    fun toggleStabilization(): Boolean {
        isStabilizationEnabled = !isStabilizationEnabled
        pushUpdatedRepeatingRequest()
        return isStabilizationEnabled
    }

    fun setToneProfile(profile: ToneProfile) {
        toneProfile = profile
        pushUpdatedRepeatingRequest()
    }

    fun toggleShutter180Rule(): Boolean {
        if (!capabilities.supportsManualExposure) return false
        isShutter180Locked = !isShutter180Locked
        if (isShutter180Locked) {
            if (!isManualExposure) {
                manualIso = liveIso.coerceIn(capabilities.effectiveIsoRange.lower, capabilities.effectiveIsoRange.upper)
                isManualExposure = true
            }
            manualShutterNs = compute180ShutterNs(resolvedFpsRange.upper)
            pushUpdatedRepeatingRequest()
        }
        return isShutter180Locked
    }

    fun setEvCompensation(index: Int): Boolean {
        if (isAeAfLocked) return false
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
        isManualExposure = false
        isShutter180Locked = false
        pushUpdatedRepeatingRequest()
    }

    fun setManualFocusDistance(diopters: Float) {
        if (!capabilities.supportsManualFocus) return
        isManualFocus = true
        manualFocusDistance = diopters.coerceIn(0f, capabilities.minFocusDistance)
        pushUpdatedRepeatingRequest()
    }

    fun setAutoFocus() {
        isManualFocus = false
        pushUpdatedRepeatingRequest()
    }

    fun setManualWhiteBalanceKelvin(kelvin: Int) {
        isManualWb = true
        manualKelvin = kelvin.coerceIn(2500, 8500)
        pushUpdatedRepeatingRequest()
    }

    fun setManualWhiteBalanceTint(tint: Int) {
        if (!isManualWb) {
            manualKelvin = liveKelvin.coerceIn(2500, 8500)
            isManualWb = true
        }
        manualTint = tint.coerceIn(-50, 50)
        pushUpdatedRepeatingRequest()
    }

    fun setAutoWhiteBalance() {
        isManualWb = false
        manualTint = 0
        pushUpdatedRepeatingRequest()
    }

    fun getLiveKelvin(): Int = liveKelvin

    fun toggleAeAfLock(): Boolean {
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
            builder.set(CaptureRequest.CONTROL_AE_LOCK, isAeAfLocked || lockAutoForRecordStart)
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
        if (surfaceTexture == null) return

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
            val safeCap = maxOf(analogUpper.toLong(), 12800L).coerceAtMost(Int.MAX_VALUE.toLong())
            val effMaxIso = boostedMaxLong.coerceIn(analogUpper.toLong(), safeCap).toInt()
            val resolvedEffectiveIso = Range(analogLower, effMaxIso.coerceAtLeast(analogLower))

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
                evStep = evStep
            )
            evCompensationIndex = evCompensationIndex.coerceIn(evRange.lower, evRange.upper)
            if (!hasFlash) isTorchEnabled = false
            currentZoomRatio = currentZoomRatio.coerceIn(minZ, maxZ)

            resolvedVideoSize = chooseClosest16x9Size(videoSizes, targetResolution.width, targetResolution.height)

            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            resolvedPreviewSize = chooseClosest16x9Size(previewSizes, 1920, 1080)

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
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
        val texture = surfaceTexture ?: return
        val lens = getActiveLens() ?: return

        try {
            closeSession()
            texture.setDefaultBufferSize(resolvedPreviewSize.width, resolvedPreviewSize.height)
            val previewSurface = Surface(texture)

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                applyCinemaControls(this)
            }
            activeBuilder = builder

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(previewSurface),
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
                        } catch (e: Exception) {
                            listener.onError("Preview start failed: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        listener.onError("Preview configuration failed.")
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
        if (isRecording) return
        val camera = cameraDevice ?: return
        val texture = surfaceTexture ?: return
        val lens = getActiveLens() ?: return

        try {
            closeSession()
            val recorder = createAndPrepareMediaRecorder(lens, displayRotation)
            mediaRecorder = recorder

            texture.setDefaultBufferSize(resolvedPreviewSize.width, resolvedPreviewSize.height)
            val previewSurface = Surface(texture)
            val recordSurface = recorder.surface

            // Freeze current exposure on frame 1 to prevent auto-exposure brightness burst!
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recordSurface)
                applyCinemaControls(this, lockAutoForRecordStart = true)
            }
            activeBuilder = builder

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(previewSurface, recordSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
                            recorder.start()
                            isRecording = true
                            listener.onRecordingStarted()

                            // If user was in Auto mode (not manually locked), smoothly release AE lock after 600ms
                            if (!isManualExposure && !isAeAfLocked) {
                                backgroundHandler?.postDelayed({
                                    if (isRecording && !isManualExposure && !isAeAfLocked) {
                                        pushUpdatedRepeatingRequest()
                                    }
                                }, 600L)
                            }
                        } catch (e: Exception) {
                            cleanupRecorderOnFailure()
                            listener.onError("Record start failed: ${e.message}")
                            startPreviewSession()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cleanupRecorderOnFailure()
                        listener.onError("Record session failed (try 1080P or 30fps).")
                        startPreviewSession()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            cleanupRecorderOnFailure()
            listener.onError("MediaRecorder error: ${e.message}")
            startPreviewSession()
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (_: Exception) {
        }

        try {
            mediaRecorder?.stop()
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

        val savedUri = activeMediaUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && savedUri != null) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(savedUri, values, null, null)
            } catch (_: Exception) {
            }
        }

        val label = activeFileLabel
        activeMediaUri = null
        listener.onRecordingStopped(savedUri, label)

        startPreviewSession()
    }

    private fun createAndPrepareMediaRecorder(lens: CinemaLens, displayRotation: Int): MediaRecorder {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IDGAF_${resolvedVideoSize.height}p_${targetFps}fps_$timestamp.mp4"
        activeFileLabel = "Movies/IDGAF_Camera/$fileName"

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        if (!isAudioMuted) {
            recorder.setAudioSource(audioPreampMode.audioSource)
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        if (!isAudioMuted) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(256_000)
            recorder.setAudioSamplingRate(48_000)
        }
        recorder.setOrientationHint(computeOrientationHint(lens, displayRotation))

        // Route external USB/Wired mic if preferred or detected in AUTO mode (API 28+)
        if (!isAudioMuted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && micPreference != MicPreference.INTERNAL) {
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

    private fun computeOrientationHint(lens: CinemaLens, displayRotation: Int): Int {
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 90
        }
        return if (lens.isFront) {
            (lens.sensorOrientation + deviceDegrees) % 360
        } else {
            (lens.sensorOrientation - deviceDegrees + 360) % 360
        }
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
        ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }?.let { return it }
        ranges.firstOrNull { it.upper == desiredFps }?.let { return it }
        return ranges.minByOrNull { abs(it.upper - desiredFps) } ?: Range(30, 30)
    }
}
