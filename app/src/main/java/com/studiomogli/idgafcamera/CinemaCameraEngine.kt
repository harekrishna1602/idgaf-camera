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
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
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
import kotlin.math.ln
import kotlin.math.pow

data class CinemaLens(
    val cameraId: String,
    val label: String,
    val isFront: Boolean,
    val focalLength: Float,
    val sensorOrientation: Int
)

data class CameraCapabilities(
    val isoRange: Range<Int> = Range(100, 3200),
    val exposureRangeNs: Range<Long> = Range(125_000L, 100_000_000L),
    val minFocusDistance: Float = 0f,
    val supportsManualExposure: Boolean = false,
    val supportsManualFocus: Boolean = false
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
    FHD_1080P("1080P", 1920, 1080),
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

    var isAeAfLocked: Boolean = false
        private set

    // Live sensor readouts (used to prevent brightness burst on REC or Auto->Manual switch)
    private var liveIso: Int = 200
    private var liveShutterNs: Long = 16_666_666L
    private var liveFocusDistance: Float = 0f
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
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveShutterNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { liveFocusDistance = it }

            val now = System.currentTimeMillis()
            if (now - lastTelemetryDispatchMs >= 160L) {
                lastTelemetryDispatchMs = now
                listener.onTelemetryUpdated(
                    LiveTelemetry(
                        iso = if (isManualExposure) manualIso else liveIso,
                        shutterNs = if (isManualExposure) manualShutterNs else liveShutterNs,
                        focusDistance = if (isManualFocus) manualFocusDistance else liveFocusDistance,
                        kelvin = manualKelvin,
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
                val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    backCandidates.add(CinemaLens(id, "1x", false, focal, orientation))
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontLens == null) {
                    frontLens = CinemaLens(id, "FR", true, focal, orientation)
                }
            }
        } catch (e: Exception) {
            listener.onError("Lens scan error: ${e.message}")
        }

        val sortedBack = backCandidates
            .distinctBy { "%.1f".format(Locale.US, it.focalLength) }
            .sortedBy { it.focalLength }

        val labeledBack = when (sortedBack.size) {
            0 -> emptyList()
            1 -> listOf(sortedBack[0].copy(label = "1x"))
            2 -> {
                if (sortedBack[0].focalLength < 2.8f) {
                    listOf(sortedBack[0].copy(label = "0.5x"), sortedBack[1].copy(label = "1x"))
                } else {
                    listOf(sortedBack[0].copy(label = "1x"), sortedBack[1].copy(label = "2x"))
                }
            }
            else -> {
                val list = mutableListOf<CinemaLens>()
                list.add(sortedBack.first().copy(label = "0.5x"))
                list.add(sortedBack[1].copy(label = "1x"))
                list.add(sortedBack.last().copy(label = "2x"))
                list
            }
        }

        val combined = mutableListOf<CinemaLens>()
        combined.addAll(labeledBack)
        if (frontLens != null) combined.add(frontLens)

        lenses = combined
        val defaultIdx = lenses.indexOfFirst { it.label == "1x" }.takeIf { it >= 0 } ?: 0
        activeLensIndex = defaultIdx
        listener.onLensesReady(lenses, activeLensIndex)
    }

    fun getActiveLens(): CinemaLens? = lenses.getOrNull(activeLensIndex)

    fun getRecordingMaxAmplitude(): Int {
        return try {
            if (isRecording) mediaRecorder?.maxAmplitude ?: 0 else 0
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

    fun selectLens(index: Int) {
        if (isRecording || index !in lenses.indices || index == activeLensIndex) return
        activeLensIndex = index
        // Reset lens-specific manual focus when switching glass
        isManualFocus = false
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
                liveShutterNs.coerceIn(capabilities.exposureRangeNs.lower, minOf(capabilities.exposureRangeNs.upper, 40_000_000L))
            }
            isManualExposure = true
        }
        manualIso = iso.coerceIn(capabilities.isoRange.lower, capabilities.isoRange.upper)
        pushUpdatedRepeatingRequest()
    }

    fun setManualShutterNs(shutterNs: Long) {
        if (!capabilities.supportsManualExposure) return
        if (!isManualExposure) {
            manualIso = liveIso.coerceIn(capabilities.isoRange.lower, capabilities.isoRange.upper)
            isManualExposure = true
        }
        isShutter180Locked = false
        manualShutterNs = shutterNs.coerceIn(capabilities.exposureRangeNs.lower, capabilities.exposureRangeNs.upper)
        pushUpdatedRepeatingRequest()
    }

    fun toggleShutter180Rule(): Boolean {
        if (!capabilities.supportsManualExposure) return false
        isShutter180Locked = !isShutter180Locked
        if (isShutter180Locked) {
            if (!isManualExposure) {
                manualIso = liveIso.coerceIn(capabilities.isoRange.lower, capabilities.isoRange.upper)
                isManualExposure = true
            }
            manualShutterNs = compute180ShutterNs(resolvedFpsRange.upper)
            pushUpdatedRepeatingRequest()
        }
        return isShutter180Locked
    }

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

    fun setAutoWhiteBalance() {
        isManualWb = false
        pushUpdatedRepeatingRequest()
    }

    fun toggleAeAfLock(): Boolean {
        isAeAfLocked = !isAeAfLocked
        if (isAeAfLocked && !isManualExposure && capabilities.supportsManualExposure) {
            // Freeze exact current exposure to eliminate any ISP drift
            manualIso = liveIso.coerceIn(capabilities.isoRange.lower, capabilities.isoRange.upper)
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

        // 1. Exposure (Manual ISO/Shutter vs Locked Auto vs Auto)
        if (isManualExposure && capabilities.supportsManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
        } else if ((isAeAfLocked || lockAutoForRecordStart) && capabilities.supportsManualExposure && liveIso > 0 && liveShutterNs > 0L) {
            // Anti-burst lock: freeze exact live ISO & shutter so REC never flashes bright
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, liveIso.coerceIn(capabilities.isoRange.lower, capabilities.isoRange.upper))
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, liveShutterNs.coerceIn(capabilities.exposureRangeNs.lower, capabilities.exposureRangeNs.upper))
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, isAeAfLocked || lockAutoForRecordStart)
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

        // 3. White Balance (Kelvin RGGB Gains vs Locked AWB vs Auto WB)
        if (isManualWb) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggbGains(manualKelvin))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, isAeAfLocked || lockAutoForRecordStart)
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
     * Converts color temperature in Kelvin (2500K-8500K) to Camera2 RGGB channel gains.
     */
    private fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val temp = kelvin.coerceIn(2500, 8500) / 100.0
        var r: Double
        var g: Double
        var b: Double

        if (temp <= 66.0) {
            r = 255.0
            g = 99.4708025861 * ln(temp) - 161.1195681661
            b = if (temp <= 19.0) 0.0 else 138.5177312231 * ln(temp - 10.0) - 305.0447927307
        } else {
            r = 329.698727446 * (temp - 60.0).pow(-0.1332047592)
            g = 288.1221695283 * (temp - 60.0).pow(-0.0755148492)
            b = 255.0
        }

        r = r.coerceIn(40.0, 255.0)
        g = g.coerceIn(40.0, 255.0)
        b = b.coerceIn(40.0, 255.0)

        // Invert relative to daylight (~5600K) so warming slider makes image warmer
        val rGain = (255.0 / b).toFloat().coerceIn(0.8f, 3.2f)
        val gGain = (255.0 / g).toFloat().coerceIn(0.9f, 1.4f)
        val bGain = (255.0 / r).toFloat().coerceIn(0.8f, 3.2f)

        // Normalize green channel to 1.0
        return RggbChannelVector(
            (rGain / gGain).coerceIn(0.7f, 3.0f),
            1.0f,
            1.0f,
            (bGain / gGain).coerceIn(0.7f, 3.0f)
        )
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
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

            capabilities = CameraCapabilities(
                isoRange = isoRange ?: Range(100, 3200),
                exposureRangeNs = expRange ?: Range(125_000L, 100_000_000L),
                minFocusDistance = minFocus,
                supportsManualExposure = (isoRange != null && expRange != null),
                supportsManualFocus = (minFocus > 0f)
            )

            val videoSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
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
                                resolvedFpsRange.upper,
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

                // 2. Apply AF/AE regions and trigger single-shot focus lock
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, metering)
                if (!isManualExposure) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, metering)
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), captureCallback, backgroundHandler)

                // 3. Keep repeating request locked on that region without re-triggering
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
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
        val fileName = "IDGAF_${resolvedVideoSize.height}p_${resolvedFpsRange.upper}fps_$timestamp.mp4"
        activeFileLabel = "Movies/IDGAF_Camera/$fileName"

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
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

        val bitrateBps = targetBitrateMbps.coerceIn(10, 120) * 1_000_000
        recorder.setVideoEncodingBitRate(bitrateBps)
        recorder.setVideoFrameRate(resolvedFpsRange.upper)
        recorder.setVideoSize(resolvedVideoSize.width, resolvedVideoSize.height)
        recorder.setVideoEncoder(targetCodec.encoder)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(256_000)
        recorder.setAudioSamplingRate(48_000)
        recorder.setOrientationHint(computeOrientationHint(lens, displayRotation))

        // Route external USB/Wired mic if preferred or detected in AUTO mode (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && micPreference != MicPreference.INTERNAL) {
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
