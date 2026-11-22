package com.studiomogli.idgafcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), CinemaEngineListener {

    private lateinit var engine: CinemaCameraEngine
    private lateinit var textureView: TextureView
    private lateinit var focusReticle: View

    private lateinit var txtTimecode: TextView
    private lateinit var viewRecDot: View
    private lateinit var btnResolution: TextView
    private lateinit var btnFps: TextView
    private lateinit var lensRail: LinearLayout
    private lateinit var btnShutterInner: View
    private lateinit var btnLastClip: TextView
    private lateinit var txtBottomTelemetry: TextView

    private val fpsOptions = listOf(24, 30, 60)
    private var currentFpsIdx = 1 // 30fps default
    private var currentResolution = CinemaResolution.FHD_1080P
    private var lastSavedUri: Uri? = null

    private var recStartMs: Long = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
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

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Re-apply aspect transform if window resizes
        }

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
        txtTimecode = findViewById(R.id.txtTimecode)
        viewRecDot = findViewById(R.id.viewRecDot)
        btnResolution = findViewById(R.id.btnResolution)
        btnFps = findViewById(R.id.btnFps)
        lensRail = findViewById(R.id.lensRail)
        btnShutterInner = findViewById(R.id.btnShutterInner)
        btnLastClip = findViewById(R.id.btnLastClip)
        txtBottomTelemetry = findViewById(R.id.txtBottomTelemetry)

        engine = CinemaCameraEngine(this, this)

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
            btnFps.text = "${fps} FPS"
            engine.updateSettings(currentResolution, fps)
        }

        btnLastClip.setOnClickListener {
            val uri = lastSavedUri
            if (uri == null) {
                Toast.makeText(this, "Record a clip first — saved to Movies/IDGAF_Camera", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Open Gallery → Movies/IDGAF_Camera", Toast.LENGTH_SHORT).show()
            }
        }

        textureView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val normX = event.x / v.width.coerceAtLeast(1)
                val normY = event.y / v.height.coerceAtLeast(1)
                engine.triggerTapToFocus(normX, normY)
                showFocusReticle(event.x, event.y)
            }
            true
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
        runOnUiThread {
            renderLensRail(lenses, activeIndex)
        }
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
                val lp = LinearLayout.LayoutParams(
                    dpToPx(48),
                    dpToPx(40)
                ).apply {
                    bottomMargin = dpToPx(8)
                }
                layoutParams = lp
                setOnClickListener {
                    if (!engine.isRecording) {
                        engine.selectLens(idx)
                        renderLensRail(lenses, idx)
                    }
                }
            }
            lensRail.addView(pill)
        }
    }

    override fun onCameraReady(previewSize: Size, activeRes: Size, activeFps: Int, lens: CinemaLens) {
        runOnUiThread {
            configureTransform(previewSize)
            val resTag = if (activeRes.width >= 3840) "4K" else "${activeRes.height}P"
            btnResolution.text = resTag
            btnFps.text = "$activeFps FPS"
            val mbps = if (activeRes.width >= 3840) "50 Mbps" else "24 Mbps"
            txtBottomTelemetry.text =
                "CAMERA2 ENGINE  •  LENS ${lens.label} (${String.format(Locale.US, "%.1fmm", lens.focalLength)})  •  ${activeRes.width}x${activeRes.height} @ ${activeFps}fps  •  H.264 $mbps"
        }
    }

    override fun onRecordingStarted() {
        runOnUiThread {
            recStartMs = System.currentTimeMillis()
            viewRecDot.visibility = View.VISIBLE
            btnShutterInner.setBackgroundResource(R.drawable.bg_shutter_stop)
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
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        enableImmersiveFullscreen()
        engine.startThread()
        if (textureView.isAvailable) {
            openCameraIfReady()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        uiHandler.removeCallbacks(timecodeRunnable)
        engine.stopThread()
        super.onPause()
    }
}
