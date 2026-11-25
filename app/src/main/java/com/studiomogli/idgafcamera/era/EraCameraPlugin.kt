package com.studiomogli.idgafcamera.era

import android.content.Context
import android.view.View
import com.studiomogli.idgafcamera.AntiFlickerMode
import com.studiomogli.idgafcamera.CameraCapabilities
import com.studiomogli.idgafcamera.CinemaCodec
import com.studiomogli.idgafcamera.CinemaResolution
import com.studiomogli.idgafcamera.LiveTelemetry
import java.io.File

enum class EraWorkbenchCategory(val label: String) {
    ERA("ERA"),
    PURIST("PURIST"),
    CREATOR("CREATOR")
}

/**
 * Generic GPU Optical Pre-Pass, Film Transport & Shader Uniform configuration driven by any drop-in EraCameraPlugin.
 * Supports both monochrome spectral eras (1920s, 1950s) and full-color eras (1970s Super 8, 1980s VHS, 2000s DV, GTA Creator)
 * without ever modifying CinemaGlRenderer.kt!
 */
data class EraRenderConfig(
    val useOpticalFbo: Boolean = true,
    val prepassColorMode: Int = 0, // 0 = Monochrome Spectral (dot(rgb, prepassWeights)), 1 = Full Color RGB Bloom/Trail
    val prepassWeightR: Float = 0.28f,
    val prepassWeightG: Float = 0.58f,
    val prepassWeightB: Float = 0.14f,
    val bloomKnee: Float = 0.72f,
    val bloomRadiusFraction: Float = 0.0080f, // Fraction of frame short-edge per Gaussian blur step
    val wear: Float = 0.5f,
    val fps: Float = 24.0f,
    val filterCode: Int = 0,
    val grainMul: Float = 1.0f,
    val gateAspect: Float = 1.3333f,
    val enableHandCrankFlicker: Boolean = false,
    val flickerScale: Float = 0.0f,
    val weaveScale: Float = 0.0f,
    val dustProbA: Float = 0.0f,
    val dustProbB: Float = 0.0f,
    val scratchProb: Float = 0.0f,
    val extra0X: Float = 0.0f,
    val extra0Y: Float = 0.0f,
    val extra0Z: Float = 0.0f,
    val extra0W: Float = 0.0f,
    val extra1X: Float = 0.0f,
    val extra1Y: Float = 0.0f,
    val extra1Z: Float = 0.0f,
    val extra1W: Float = 0.0f
)

/**
 * Clean, frozen bridge provided by MainActivity to any active drop-in EraCameraPlugin.
 * Plugins NEVER touch SAVIOR views or Camera2 internals directly.
 */
interface EraHostBridge {
    fun openWorkbenchDrawer()
    fun openEraVaultDrawer()
    fun exitToSavior()

    fun pushRenderConfig(config: EraRenderConfig)
    fun applyFormat(resolution: CinemaResolution, fps: Int, bitrateMbps: Int, codec: CinemaCodec)
    fun setTargetFpsSeamless(fps: Int)

    fun setAutoExposure()
    fun setEvCompensation(index: Int): Boolean
    fun lockManualExposure(iso: Int, shutterNs: Long)
    fun setAutoFocus()
    fun setManualFocusDistance(diopters: Float)
    fun toggleTorch(): Boolean
    fun toggleFrontBackCamera(): Boolean

    fun cycleAntiFlickerMode(): AntiFlickerMode
    fun getAntiFlickerMode(): AntiFlickerMode
    fun compute180ShutterNs(fps: Int): Long
    fun snapShutterToAntiFlickerNs(desiredNs: Long, maxAllowedNs: Long): Long

    fun getCapabilities(): CameraCapabilities
    fun getLiveIso(): Int
    fun getLiveShutterNs(): Long
    fun getCurrentEvStops(): Float
    fun getEvCompensationIndex(): Int
    fun isManualExposure(): Boolean
    fun getManualIso(): Int
    fun getManualShutterNs(): Long
    fun isManualFocus(): Boolean
    fun getManualFocusDistance(): Float
    fun isTorchEnabled(): Boolean
    fun isRecording(): Boolean
    fun isFinalizingClip(): Boolean

    fun showToast(message: String, longDuration: Boolean = false)
    fun openNumericInput(
        title: String,
        hint: String,
        initialValue: String,
        allowDecimal: Boolean,
        step: Float,
        classicStyle: Boolean = true,
        onApply: (String) -> Boolean
    )
}

/**
 * Drop-in Plug-In Contract for every Era, Purist, or Stylized Camera Mode.
 * Each camera lives in ONE standalone .kt file inside com.studiomogli.idgafcamera.era.*
 * with its own GLSL fragment shader, custom HUD view, audio config, and transport parameters.
 */
interface EraCameraPlugin {
    val id: String
    val slotOrder: Int
    val category: EraWorkbenchCategory
    val chipLabel: String
    val description: String
    val fileTag: String

    val defaultResolution: CinemaResolution
    val defaultFps: Int
    val defaultBitrateMbps: Int
    val defaultCodec: CinemaCodec
    val isSilentAudio: Boolean
    val usesPostAudioProcessor: Boolean
    val disableDigitalEisAndSharpening: Boolean
    val accentColorArgb: Int

    /**
     * Complete OpenGL ES 2.0 fragment shader source.
     * Standard varyings provided by VERTEX_SHADER:
     *   varying vec2 vTexCoord;
     *   varying vec2 vRawUv;
     *   varying vec2 vQuadCoord;
     *   varying vec2 vScreenPos;
     * Standard uniforms bound automatically by CinemaGlRenderer (declare only what you use):
     *   samplerExternalOES uTexture (unit 0)
     *   sampler2D uGrainAtlas (unit 1)
     *   sampler2D uBloomTexture (unit 2)
     *   sampler2D uTrailTexture (unit 3)
     *   mat4 uFragStMatrix
     *   int uHasOpticalFbo
     *   vec2 uStepX, uStepY
     *   float uTimeSec, uViewportAspect, uGateAspect
     *   float uWear, uFps, uGrainMul, uMotionBlend
     *   int uFilter
     *   vec4 uFilmSeed, uFilmTransport, uDefectA, uDefectB
     *   vec4 uExtra0, uExtra1
     */
    fun getFragmentShaderSource(): String

    fun getInitialRenderConfig(): EraRenderConfig

    /**
     * Build the plugin's self-contained HUD overlay (top bar + bottom dock + drawers).
     * Mounted inside MainActivity's eraPluginContainer when active, removed cleanly on exit.
     */
    fun createHudView(context: Context, host: EraHostBridge): View

    fun onEnter(host: EraHostBridge) {}
    fun onExit(host: EraHostBridge) {}
    fun onCameraReady(host: EraHostBridge, caps: CameraCapabilities) {}
    fun onTelemetryUpdated(host: EraHostBridge, telemetry: LiveTelemetry) {}
    fun onRecordingStarted(host: EraHostBridge) {}
    fun onRecordingTick(host: EraHostBridge, elapsedMs: Long) {}
    fun onRecordingFinalizing(host: EraHostBridge) {}
    fun onRecordingStopped(host: EraHostBridge) {}
    fun onBatteryUpdated(host: EraHostBridge, pct: Int, charging: Boolean) {}

    /**
     * Called when the user taps the viewfinder while this plugin is active.
     * Return true if the plugin consumed the tap (e.g. closed its own open drawer).
     */
    fun onViewfinderTapped(host: EraHostBridge): Boolean = false

    /**
     * Optional lossless-video post-recording audio processor (runs on background thread when usesPostAudioProcessor == true).
     * Return true if outputFile was written successfully; return false to safely commit raw inputFile as fallback.
     */
    fun processRecordedMp4Audio(inputFile: File, outputFile: File): Boolean = false
}
