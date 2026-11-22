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
import android.hardware.camera2.params.MeteringRectangle
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

data class CinemaLens(
    val cameraId: String,
    val label: String,
    val isFront: Boolean,
    val focalLength: Float,
    val sensorOrientation: Int
)

enum class CinemaResolution(val label: String, val width: Int, val height: Int, val bitrate: Int) {
    FHD_1080P("1080P", 1920, 1080, 24_000_000),
    UHD_4K("4K", 3840, 2160, 50_000_000)
}

interface CinemaEngineListener {
    fun onLensesReady(lenses: List<CinemaLens>, activeIndex: Int)
    fun onCameraReady(previewSize: Size, activeRes: Size, activeFps: Int, lens: CinemaLens)
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
    private var previewBuilder: CaptureRequest.Builder? = null
    private var mediaRecorder: MediaRecorder? = null
    private var activeOutputPfd: ParcelFileDescriptor? = null
    private var activeMediaUri: Uri? = null
    private var activeFileLabel: String = ""

    private var surfaceTexture: SurfaceTexture? = null
    private var lenses: List<CinemaLens> = emptyList()
    private var activeLensIndex: Int = 0

    var targetResolution: CinemaResolution = CinemaResolution.FHD_1080P
    var targetFps: Int = 30
    var isRecording: Boolean = false
        private set

    private var resolvedVideoSize: Size = Size(1920, 1080)
    private var resolvedPreviewSize: Size = Size(1920, 1080)
    private var resolvedFpsRange: Range<Int> = Range(30, 30)

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

        // Deduplicate back lenses by focal length and label cleanly (UW / 1x / 2x)
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

    fun attachSurfaceAndOpen(texture: SurfaceTexture) {
        surfaceTexture = texture
        openActiveCamera()
    }

    fun selectLens(index: Int) {
        if (isRecording || index !in lenses.indices || index == activeLensIndex) return
        activeLensIndex = index
        closeCamera()
        openActiveCamera()
    }

    fun updateSettings(resolution: CinemaResolution, fps: Int) {
        if (isRecording) return
        targetResolution = resolution
        targetFps = fps
        closeCamera()
        openActiveCamera()
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
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, resolvedFpsRange)
            }
            previewBuilder = builder

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            listener.onCameraReady(
                                resolvedPreviewSize,
                                resolvedVideoSize,
                                resolvedFpsRange.upper,
                                lens
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

    fun triggerTapToFocus(normX: Float, normY: Float) {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        val lens = getActiveLens() ?: return

        try {
            val chars = cameraManager.getCameraCharacteristics(lens.cameraId)
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            if (maxAfRegions <= 0) return

            val cx = (normX.coerceIn(0f, 1f) * sensorRect.width()).toInt()
            val cy = (normY.coerceIn(0f, 1f) * sensorRect.height()).toInt()
            val halfW = (sensorRect.width() * 0.08f).toInt()
            val halfH = (sensorRect.height() * 0.08f).toInt()

            val rect = Rect(
                (cx - halfW).coerceIn(0, sensorRect.width()),
                (cy - halfH).coerceIn(0, sensorRect.height()),
                (cx + halfW).coerceIn(0, sensorRect.width()),
                (cy + halfH).coerceIn(0, sensorRect.height())
            )
            val metering = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, metering)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, metering)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)

            // Resume continuous tracking smoothly after lock
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (_: Exception) {
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

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recordSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, resolvedFpsRange)
            }
            previewBuilder = builder

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(previewSurface, recordSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            recorder.start()
                            isRecording = true
                            listener.onRecordingStarted()
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

        val bitrate = if (resolvedVideoSize.width >= 3840) {
            CinemaResolution.UHD_4K.bitrate
        } else {
            CinemaResolution.FHD_1080P.bitrate
        }

        recorder.setVideoEncodingBitRate(bitrate)
        recorder.setVideoFrameRate(resolvedFpsRange.upper)
        recorder.setVideoSize(resolvedVideoSize.width, resolvedVideoSize.height)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(192_000)
        recorder.setAudioSamplingRate(48_000)
        recorder.setOrientationHint(computeOrientationHint(lens, displayRotation))
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
        // Prefer exact locked cinema range [fps, fps]
        ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }?.let { return it }
        // Next prefer variable range whose upper matches desiredFps
        ranges.firstOrNull { it.upper == desiredFps }?.let { return it }
        // Otherwise pick closest upper FPS
        return ranges.minByOrNull { abs(it.upper - desiredFps) } ?: Range(30, 30)
    }
}
