package com.studiomogli.idgafcamera

import android.graphics.Color
import com.studiomogli.idgafcamera.era.EraRenderConfig
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class GpuScopeState(
    val showPeaking: Boolean = false,
    val peakingColorArgb: Int = Color.argb(235, 57, 255, 20),
    val peakingThreshold: Float = 0.15f,
    val showZebras: Boolean = false,
    val zebraModeCode: Int = 2, // 0=Off, 1=70% Skin, 2=92% High, 3=100% Clip
    val showFalseColor: Boolean = false
)

data class GpuColorState(
    val exposureLift: Float = 0.0f, // -1.5 .. +1.5 EV stops
    val contrast: Float = 1.0f,     // 0.50 .. 1.60
    val saturation: Float = 1.0f,   // 0.00 .. 1.80
    val shadows: Float = 0.0f,      // -0.50 .. +0.50
    val highlights: Float = 0.0f,   // -0.50 .. +0.50
    val vignette: Float = 0.0f,     // -0.85 (Black) .. 0.00 .. +0.85 (White)
    val lutIntensity: Float = 1.0f, // 0.00 .. 1.00
    val isBypassed: Boolean = false
) {
    fun isNeutral(hasActiveLut: Boolean = false): Boolean =
        !hasActiveLut &&
            kotlin.math.abs(exposureLift) < 0.01f &&
            kotlin.math.abs(contrast - 1.0f) < 0.01f &&
            kotlin.math.abs(saturation - 1.0f) < 0.01f &&
            kotlin.math.abs(shadows) < 0.01f &&
            kotlin.math.abs(highlights) < 0.01f &&
            kotlin.math.abs(vignette) < 0.01f
}

/**
 * Phase 7.1 Dual-Surface OpenGL ES 2.0 GPU Pipeline:
 * - Camera2 renders into a single external OES SurfaceTexture managed on IdgafGlThread.
 * - Pass 1 renders full-bleed Preview to TextureView's SurfaceTexture WITH 60 FPS native-resolution
 *   GPU Monitoring Scopes (1-pixel 3x3 Sobel Focus Peaking, animated marching 45-deg Zebras, Full-HD False Color).
 * - Pass 2 (when recording) renders a 100% clean, hardware-timestamped frame to MediaRecorder's Surface
 *   without ever tearing down the Camera2 capture session.
 */
class CinemaGlRenderer(
    private val onGlError: (String) -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val FULL_QUAD_VERTICES = floatArrayOf(
            -1f, -1f,  // Bottom-Left
             1f, -1f,  // Bottom-Right
            -1f,  1f,  // Top-Left
             1f,  1f   // Top-Right
        )

        private val FULL_QUAD_TEXCOORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvpMatrix;
            uniform mat4 uStMatrix;
            varying vec2 vTexCoord;
            varying vec2 vRawUv;
            varying vec2 vQuadCoord;
            varying vec2 vScreenPos;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uStMatrix * aTexCoord).xy;
                vRawUv = aTexCoord.xy;
                vQuadCoord = aPosition.xy;
                vScreenPos = gl_Position.xy;
            }
        """

        // Offscreen Pass 1 Vertex Shader (renders directly in normalized sensor UV space [0..1])
        private const val OFFSCREEN_VERT_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uStMatrix;
            varying vec2 vTexCoord;
            varying vec2 vUv;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uStMatrix * aTexCoord).xy;
                vUv = aTexCoord.xy;
            }
        """

        // Offscreen Pass 1 Fragment Shader: Generic Era Optical Pre-Pass (supports BOTH Monochrome Spectral AND Full-Color RGB Eras!)
        private const val OPTICAL_PREPASS_FRAG_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTexture;
            uniform float uTrailAlpha;
            uniform int uPrepassColorMode;
            uniform vec3 uPrepassWeights;
            uniform float uBloomKnee;
            varying vec2 vTexCoord;
            void main() {
                vec3 rgb = texture2D(uTexture, vTexCoord).rgb;
                if (uPrepassColorMode == 1) {
                    // Full-color RGB trail & optical bloom pre-pass (1970s Super 8, 1980s VHS, 2000s DV, Creator modes)
                    gl_FragColor = vec4(rgb, uTrailAlpha);
                } else {
                    // Monochrome spectral extraction + soft-knee highlight excess (1920s Ortho, 1950s Panchromatic + Wratten filters)
                    float ySpectral = clamp(dot(rgb, uPrepassWeights), 0.0, 1.0);
                    float hi = smoothstep(uBloomKnee, 0.99, ySpectral) * ySpectral;
                    gl_FragColor = vec4(ySpectral, hi, ySpectral, uTrailAlpha);
                }
            }
        """

        // Offscreen Pass 2 & 3 Fragment Shader: 9-Tap Separable Wide Gaussian Optical Bloom
        private const val GAUSS_BLUR_FRAG_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uSourceTex;
            uniform vec2 uBlurDir;
            varying vec2 vUv;
            void main() {
                vec4 sum = texture2D(uSourceTex, vUv) * 0.192220;
                sum += texture2D(uSourceTex, clamp(vUv + uBlurDir * 1.0, vec2(0.002), vec2(0.998))) * 0.172514;
                sum += texture2D(uSourceTex, clamp(vUv - uBlurDir * 1.0, vec2(0.002), vec2(0.998))) * 0.172514;
                sum += texture2D(uSourceTex, clamp(vUv + uBlurDir * 2.0, vec2(0.002), vec2(0.998))) * 0.124708;
                sum += texture2D(uSourceTex, clamp(vUv - uBlurDir * 2.0, vec2(0.002), vec2(0.998))) * 0.124708;
                sum += texture2D(uSourceTex, clamp(vUv + uBlurDir * 3.0, vec2(0.002), vec2(0.998))) * 0.072613;
                sum += texture2D(uSourceTex, clamp(vUv - uBlurDir * 3.0, vec2(0.002), vec2(0.998))) * 0.072613;
                sum += texture2D(uSourceTex, clamp(vUv + uBlurDir * 4.0, vec2(0.002), vec2(0.998))) * 0.034055;
                sum += texture2D(uSourceTex, clamp(vUv - uBlurDir * 4.0, vec2(0.002), vec2(0.998))) * 0.034055;
                gl_FragColor = vec4(sum.rgb, 1.0);
            }
        """

        // Pure Frozen SAVIOR Cinema Fragment Shader (Zero Era code — all Era shaders live in standalone drop-in plugins!)
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform samplerExternalOES uTexture;
            uniform int uEnableScopes;
            uniform int uShowFalseColor;
            uniform int uShowPeaking;
            uniform vec3 uPeakingColor;
            uniform float uPeakingThreshold;
            uniform int uZebraMode;
            uniform vec2 uStepX;
            uniform vec2 uStepY;
            uniform float uTimeSec;

            // Live GPU Color Grade + 3D .cube LUT Atlas uniforms
            uniform float uExposureMul;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uShadows;
            uniform float uHighlights;
            uniform float uVignette;
            uniform sampler2D uLutTexture;
            uniform int uHasLut;
            uniform float uLutSize;
            uniform float uLutIntensity;
            uniform float uSplitPixelX;

            varying vec2 vTexCoord;
            varying vec2 vRawUv;
            varying vec2 vQuadCoord;
            varying vec2 vScreenPos;

            float calcLuma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            // Broadcast-grade trilinear 3D LUT lookup packed into 2D atlas (width = N*N, height = N)
            vec3 apply3dCubeLut(vec3 c) {
                vec3 rgb = clamp(c, 0.0, 1.0);
                float blueSlice = rgb.b * (uLutSize - 1.0);
                float b0 = floor(blueSlice);
                float b1 = min(b0 + 1.0, uLutSize - 1.0);
                float bFrac = blueSlice - b0;

                float invAtlasW = 1.0 / (uLutSize * uLutSize);
                float rOffset = rgb.r * (uLutSize - 1.0) + 0.5;
                float u0 = (b0 * uLutSize + rOffset) * invAtlasW;
                float u1 = (b1 * uLutSize + rOffset) * invAtlasW;
                float v  = (rgb.g * (uLutSize - 1.0) + 0.5) / uLutSize;

                vec3 s0 = texture2D(uLutTexture, vec2(u0, v)).rgb;
                vec3 s1 = texture2D(uLutTexture, vec2(u1, v)).rgb;
                return mix(s0, s1, bFrac);
            }

            vec3 applyFalseColor(float y) {
                if (y <= 0.05) return vec3(0.545, 0.361, 0.965);        // 0-5% IRE: Purple (Crushed Black)
                if (y <= 0.15) return vec3(0.145, 0.388, 0.922);        // 5-15% IRE: Blue (Deep Shadows)
                if (y >= 0.38 && y <= 0.44) return vec3(0.133, 0.773, 0.369); // 38-44% IRE: Green (Middle Grey 41%)
                if (y >= 0.56 && y <= 0.68) return vec3(0.957, 0.447, 0.714); // 56-68% IRE: Pink (Key Skin Tone)
                if (y >= 0.85 && y <= 0.95) return vec3(0.980, 0.800, 0.082); // 85-95% IRE: Yellow (Bright Highlights)
                if (y > 0.95) return vec3(0.937, 0.267, 0.267);         // 95-100% IRE: Red (Clipped)
                return vec3(y);                                         // Neutral Monochrome Luma
            }

            void main() {
                vec3 rawColor = texture2D(uTexture, vTexCoord).rgb;
                float rawY = calcLuma(rawColor);
                vec3 baseColor = rawColor;

                // 1. Live GPU Exposure Lift + EV Hardware Ceiling Overdrive
                if (abs(uExposureMul - 1.0) > 0.001) {
                    baseColor = clamp(baseColor * uExposureMul, 0.0, 1.0);
                }

                // 2. Smooth Shadows & Highlights Recovery / Punch (quadratic luma weights)
                if (abs(uShadows) > 0.001 || abs(uHighlights) > 0.001) {
                    float ly = calcLuma(baseColor);
                    float shdW = (1.0 - ly) * (1.0 - ly);
                    float hilW = ly * ly;
                    float delta = uShadows * shdW * 0.48 + uHighlights * hilW * 0.48;
                    baseColor = clamp(baseColor + vec3(delta), 0.0, 1.0);
                }

                // 3. Contrast around 0.50 midpoint
                if (abs(uContrast - 1.0) > 0.001) {
                    baseColor = clamp((baseColor - 0.5) * uContrast + 0.5, 0.0, 1.0);
                }

                // 4. Saturation (0.0 = Monochrome, 1.0 = Neutral, 1.8 = Vivid)
                if (abs(uSaturation - 1.0) > 0.001) {
                    float ly2 = calcLuma(baseColor);
                    baseColor = clamp(mix(vec3(ly2), baseColor, uSaturation), 0.0, 1.0);
                }

                // 5. Real 3D .cube LUT (applied after primary balance & before optical vignette)
                if (uHasLut == 1 && uLutIntensity > 0.001) {
                    vec3 lutColor = apply3dCubeLut(baseColor);
                    baseColor = clamp(mix(baseColor, lutColor, uLutIntensity), 0.0, 1.0);
                }

                // 6. Bipolar Cinema Vignette: negative (-0.85..0) = Black optical falloff, positive (0..+0.85) = White glow
                if (abs(uVignette) > 0.001) {
                    float d = length(vQuadCoord) * 0.7071;
                    float falloff = smoothstep(0.32, 1.08, d);
                    if (uVignette < 0.0) {
                        float vig = 1.0 - (-uVignette) * falloff;
                        baseColor *= clamp(vig, 0.0, 1.0);
                    } else {
                        float whiteAmt = clamp(uVignette * falloff, 0.0, 1.0);
                        baseColor = mix(baseColor, vec3(1.0), whiteAmt);
                    }
                }

                // Preview-Only Live RAW | LOOK Split-Screen Wipe (NEVER active in Pass 2 encoder!)
                if (uEnableScopes == 1 && uSplitPixelX >= 0.0 && gl_FragCoord.x < uSplitPixelX) {
                    baseColor = rawColor;
                }

                vec3 outColor = baseColor;

                // Monitoring scopes ONLY execute in Preview pass (uEnableScopes == 1)
                if (uEnableScopes == 1) {
                    float y = calcLuma(baseColor);

                    if (uShowFalseColor == 1) {
                        outColor = applyFalseColor(y);
                    }

                    if (uShowPeaking == 1) {
                        float tl = calcLuma(texture2D(uTexture, vTexCoord - uStepX - uStepY).rgb);
                        float tc = calcLuma(texture2D(uTexture, vTexCoord - uStepY).rgb);
                        float tr = calcLuma(texture2D(uTexture, vTexCoord + uStepX - uStepY).rgb);
                        float ml = calcLuma(texture2D(uTexture, vTexCoord - uStepX).rgb);
                        float mr = calcLuma(texture2D(uTexture, vTexCoord + uStepX).rgb);
                        float bl = calcLuma(texture2D(uTexture, vTexCoord - uStepX + uStepY).rgb);
                        float bc = calcLuma(texture2D(uTexture, vTexCoord + uStepY).rgb);
                        float br = calcLuma(texture2D(uTexture, vTexCoord + uStepX + uStepY).rgb);

                        float lap = abs(8.0 * rawY - (tl + tc + tr + ml + mr + bl + bc + br)) * 0.25;
                        float gx = (-tl - 2.0 * ml - bl + tr + 2.0 * mr + br) * 0.25;
                        float gy = (-tl - 2.0 * tc - tr + bl + 2.0 * bc + br) * 0.25;
                        float grad = length(vec2(gx, gy));

                        float sharpness = lap * smoothstep(0.06, 0.16, grad);
                        float mask = smoothstep(uPeakingThreshold, uPeakingThreshold + 0.05, sharpness);
                        outColor = mix(outColor, uPeakingColor, mask * 0.95);
                    }

                    if (uZebraMode > 0) {
                        bool hit = false;
                        vec3 zColor = vec3(1.0, 0.23, 0.19);
                        if (uZebraMode == 1) {
                            hit = (y >= 0.66 && y <= 0.75);
                            zColor = vec3(1.0, 0.80, 0.0);
                        } else if (uZebraMode == 2) {
                            hit = (y >= 0.92);
                            zColor = vec3(1.0, 0.23, 0.19);
                        } else if (uZebraMode == 3) {
                            hit = (y >= 0.98);
                            zColor = vec3(1.0, 0.09, 0.27);
                        }
                        if (hit) {
                            float stripe = fract((gl_FragCoord.x + gl_FragCoord.y - uTimeSec * 48.0) / 14.0);
                            if (stripe < 0.36) {
                                outColor = mix(outColor, zColor, 0.90);
                            }
                        }
                    }
                }

                gl_FragColor = vec4(outColor, 1.0);
            }
        """
    }

    private class PluginGlHandles(val programId: Int) {
        val aPosition = GLES20.glGetAttribLocation(programId, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(programId, "aTexCoord")
        val uMvpMatrix = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
        val uStMatrix = GLES20.glGetUniformLocation(programId, "uStMatrix")
        val uFragStMatrix = GLES20.glGetUniformLocation(programId, "uFragStMatrix")
        val uTexture = GLES20.glGetUniformLocation(programId, "uTexture")
        val uGrainAtlas = GLES20.glGetUniformLocation(programId, "uGrainAtlas")
        val uBloomTexture = GLES20.glGetUniformLocation(programId, "uBloomTexture")
        val uTrailTexture = GLES20.glGetUniformLocation(programId, "uTrailTexture")
        val uHasOpticalFbo = GLES20.glGetUniformLocation(programId, "uHasOpticalFbo")
        val uStepX = GLES20.glGetUniformLocation(programId, "uStepX")
        val uStepY = GLES20.glGetUniformLocation(programId, "uStepY")
        val uTimeSec = GLES20.glGetUniformLocation(programId, "uTimeSec")
        val uViewportAspect = GLES20.glGetUniformLocation(programId, "uViewportAspect")
        val uGateAspect = GLES20.glGetUniformLocation(programId, "uGateAspect")
        val uWear = GLES20.glGetUniformLocation(programId, "uWear")
        val uFps = GLES20.glGetUniformLocation(programId, "uFps")
        val uFilter = GLES20.glGetUniformLocation(programId, "uFilter")
        val uGrainMul = GLES20.glGetUniformLocation(programId, "uGrainMul")
        val uMotionBlend = GLES20.glGetUniformLocation(programId, "uMotionBlend")
        val uFilmSeed = GLES20.glGetUniformLocation(programId, "uFilmSeed")
        val uFilmTransport = GLES20.glGetUniformLocation(programId, "uFilmTransport")
        val uDefectA = GLES20.glGetUniformLocation(programId, "uDefectA")
        val uDefectB = GLES20.glGetUniformLocation(programId, "uDefectB")
        val uExtra0 = GLES20.glGetUniformLocation(programId, "uExtra0")
        val uExtra1 = GLES20.glGetUniformLocation(programId, "uExtra1")
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programId: Int = 0
    private var oesTextureId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uMvpMatrixLoc: Int = -1
    private var uStMatrixLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uEnableScopesLoc: Int = -1
    private var uShowFalseColorLoc: Int = -1
    private var uShowPeakingLoc: Int = -1
    private var uPeakingColorLoc: Int = -1
    private var uPeakingThresholdLoc: Int = -1
    private var uZebraModeLoc: Int = -1
    private var uStepXLoc: Int = -1
    private var uStepYLoc: Int = -1
    private var uTimeSecLoc: Int = -1
    private var uExposureMulLoc: Int = -1
    private var uContrastLoc: Int = -1
    private var uSaturationLoc: Int = -1
    private var uShadowsLoc: Int = -1
    private var uHighlightsLoc: Int = -1
    private var uVignetteLoc: Int = -1
    private var uLutTextureLoc: Int = -1
    private var uHasLutLoc: Int = -1
    private var uLutSizeLoc: Int = -1
    private var uLutIntensityLoc: Int = -1
    private var uSplitPixelXLoc: Int = -1

    // Dynamic Drop-In Era Plug-In GL Program Slot (compiled on-demand on IdgafGlThread!)
    private var pluginHandles: PluginGlHandles? = null
    private var compiledPluginId: String? = null
    @Volatile private var desiredPluginId: String? = null
    @Volatile private var desiredPluginFragShader: String? = null
    @Volatile private var pluginShaderDirty: Boolean = false
    @Volatile var eraRenderConfig: EraRenderConfig = EraRenderConfig()

    // Shared Offscreen Multi-Pass Optical Bloom + Temporal Motion-Blur FBO resources (for any Era Plug-In)
    private var trailProgramId: Int = 0
    private var trailAPosLoc: Int = -1
    private var trailATexLoc: Int = -1
    private var trailUStLoc: Int = -1
    private var trailUTexLoc: Int = -1
    private var trailUAlphaLoc: Int = -1
    private var trailUColorModeLoc: Int = -1
    private var trailUWeightsLoc: Int = -1
    private var trailUKneeLoc: Int = -1

    private var blurProgramId: Int = 0
    private var blurAPosLoc: Int = -1
    private var blurATexLoc: Int = -1
    private var blurUStLoc: Int = -1
    private var blurUSrcLoc: Int = -1
    private var blurUDirLoc: Int = -1

    private var trailFboId: Int = 0
    private var trailTexId: Int = 0
    private var bloomFboAId: Int = 0
    private var bloomTexAId: Int = 0
    private var bloomFboBId: Int = 0
    private var bloomTexBId: Int = 0
    private var opticalFbosReady: Boolean = false
    @Volatile private var trailNeedsReset: Boolean = true
    private val identityMat4 = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var lutTextureId: Int = 0
    private var dummyLutTextureId: Int = 0
    private var filmAtlasTextureId: Int = 0
    private var activeUploadedLutSize: Int = 0
    @Volatile private var desiredLutData: CubeLutData? = null
    @Volatile private var lutUploadDirty: Boolean = false

    private val vertexBuf: FloatBuffer = ByteBuffer.allocateDirect(FULL_QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(FULL_QUAD_VERTICES)
            position(0)
        }

    private val texCoordBuf: FloatBuffer = ByteBuffer.allocateDirect(FULL_QUAD_TEXCOORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(FULL_QUAD_TEXCOORDS)
            position(0)
        }

    private var cameraInputTexture: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val startTimeMs = System.currentTimeMillis()

    @Volatile var scopeState: GpuScopeState = GpuScopeState()
    @Volatile var colorState: GpuColorState = GpuColorState()
    @Volatile var evOverdriveStops: Float = 0.0f // Smooth GPU continuation when EV exceeds hardware +1.0 EV video ceiling
    @Volatile var splitCompareFraction: Float = -1.0f // < 0.0 = OFF, 0.0..1.0 = Live RAW | LOOK vertical wipe


    // 64-bit CPU Film-Transport Engine (updates once per film frame tick — ZERO repetition, ZERO GPU hash loops!)
    private var lastFilmTick: Long = -1L
    private var filmPrngState: Long = 0x19550826CAFEBABEL
    private var filmSeedX1: Float = 0.13f
    private var filmSeedY1: Float = 0.47f
    private var filmSeedX2: Float = 0.71f
    private var filmSeedY2: Float = 0.29f
    private var filmWeaveX: Float = 0.0f
    private var filmWeaveY: Float = 0.0f
    private var filmFlicker: Float = 0.0f
    private var filmScratchX: Float = -2.0f
    private var filmScratchLife: Int = 0
    private var filmScratchVel: Float = 0.0f
    private var filmBreathPhase: Double = 0.0
    private var defectAx: Float = -9.0f
    private var defectAy: Float = 0.0f
    private var defectAr: Float = 0.005f
    private var defectAw: Float = 0.0f
    private var defectBx: Float = -9.0f
    private var defectBy: Float = 0.0f
    private var defectBr: Float = 0.005f
    private var defectBw: Float = 0.0f
    @Volatile var liveShutterNs: Long = 16_666_666L
    @Volatile var mirrorFrontExport: Boolean = false

    @Volatile private var previewWidth: Int = 0
    @Volatile private var previewHeight: Int = 0
    @Volatile private var videoWidth: Int = 1920
    @Volatile private var videoHeight: Int = 1080
    @Volatile private var sensorOrientationDeg: Int = 90
    @Volatile private var isFrontCamera: Boolean = false
    @Volatile private var displayRotation: Int = Surface.ROTATION_90

    @Volatile private var encoderWidth: Int = 1920
    @Volatile private var encoderHeight: Int = 1080
    @Volatile private var encoderRotation: Int = Surface.ROTATION_90
    @Volatile private var encoderAspectMode: RecordAspectMode = RecordAspectMode.WIDE_16_9
    @Volatile private var encoderUsesHintRotation: Boolean = false
    @Volatile private var targetEncoderFps: Int = 30
    @Volatile private var hardwareFpsUpper: Int = 30
    @Volatile private var isEncoderRecording: Boolean = false
    @Volatile private var isReleased: Boolean = true

    // CLOCK_MONOTONIC (System.nanoTime) anchor & GPU Frame-Pacer for exact low-FPS / custom-FPS / Time-Lapse cadence
    private var encoderBaseCamTsNs: Long = -1L
    private var encoderBaseMonoNs: Long = -1L
    private var lastEncoderPtsNs: Long = -1L
    private var nextEncoderFrameDueNs: Long = -1L

    // Phase 7.2C Pro Time-Lapse Intervalometer state (0L = standard real-time video mode)
    @Volatile private var timelapseIntervalNs: Long = 0L
    private var timelapseBaseMonoNs: Long = -1L
    private var timelapseCapturedFrames: Long = 0L
    @Volatile var onTimelapseFrameCaptured: ((capturedFrames: Long) -> Unit)? = null

    fun start(): Boolean {
        if (glThread != null && !isReleased) return true
        val thread = HandlerThread("IdgafGlThread").also { it.start() }
        val handler = Handler(thread.looper)
        glThread = thread
        glHandler = handler

        var ok = false
        val latch = CountDownLatch(1)
        handler.post {
            ok = initEglAndShaders()
            isReleased = !ok
            latch.countDown()
        }
        try {
            latch.await(2000L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        return ok
    }

    fun prepareCameraInputSurface(
        width: Int,
        height: Int,
        sensorOrientation: Int,
        isFront: Boolean
    ): Surface? {
        val handler = glHandler ?: return null
        if (isReleased) return null
        var result: Surface? = null
        val latch = CountDownLatch(1)
        handler.post {
            try {
                videoWidth = width.coerceAtLeast(16)
                videoHeight = height.coerceAtLeast(16)
                sensorOrientationDeg = sensorOrientation
                isFrontCamera = isFront
                trailNeedsReset = true

                val st = cameraInputTexture
                if (st != null) {
                    st.setDefaultBufferSize(videoWidth, videoHeight)
                    if (cameraInputSurface == null || cameraInputSurface?.isValid != true) {
                        cameraInputSurface?.release()
                        cameraInputSurface = Surface(st)
                    }
                    result = cameraInputSurface
                }
            } catch (e: Exception) {
                onGlError("GL camera input surface error: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(1500L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        return result
    }

    fun attachPreviewSurface(surfaceTexture: SurfaceTexture, width: Int, height: Int, rotation: Int) {
        val handler = glHandler ?: return
        if (isReleased) return
        displayRotation = rotation
        handler.post {
            if (isReleased || eglDisplay == EGL14.EGL_NO_DISPLAY) return@post
            try {
                destroyPreviewSurfaceOnGlThread()
                previewWidth = width.coerceAtLeast(1)
                previewHeight = height.coerceAtLeast(1)
                val cfg = eglConfig ?: return@post
                val surface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    cfg,
                    surfaceTexture,
                    intArrayOf(EGL14.EGL_NONE),
                    0
                )
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                    previewEglSurface = surface
                }
            } catch (e: Exception) {
                onGlError("GL preview attach failed: ${e.message}")
            }
        }
    }

    fun updatePreviewSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            previewWidth = width
            previewHeight = height
        }
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    fun setCubeLut(lut: CubeLutData?) {
        desiredLutData = lut
        lutUploadDirty = true
    }

    fun setEraPluginShader(pluginId: String?, fragmentShaderSource: String?, initialConfig: EraRenderConfig?) {
        if (initialConfig != null) {
            eraRenderConfig = initialConfig
        }
        desiredPluginId = pluginId
        desiredPluginFragShader = fragmentShaderSource
        pluginShaderDirty = true
        trailNeedsReset = true
    }

    fun detachPreviewSurfaceSync() {
        val handler = glHandler ?: return
        val latch = CountDownLatch(1)
        handler.post {
            destroyPreviewSurfaceOnGlThread()
            latch.countDown()
        }
        try {
            latch.await(1000L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    fun attachEncoderSurfaceSync(
        recorderSurface: Surface,
        width: Int,
        height: Int,
        rotation: Int,
        desiredFps: Int,
        hwFpsUpper: Int,
        timelapseIntervalMs: Long = 0L,
        aspectMode: RecordAspectMode = RecordAspectMode.WIDE_16_9,
        usesHintRotation: Boolean = false
    ): Boolean {
        val handler = glHandler ?: return false
        if (isReleased) return false
        var ok = false
        val latch = CountDownLatch(1)
        handler.post {
            try {
                destroyEncoderSurfaceOnGlThread()
                val cfg = eglConfig ?: throw IllegalStateException("EGL config unavailable")
                val surface = EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    cfg,
                    recorderSurface,
                    intArrayOf(EGL14.EGL_NONE),
                    0
                )
                if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                    throw IllegalStateException("eglCreateWindowSurface for encoder failed")
                }
                encoderWidth = width.coerceAtLeast(16)
                encoderHeight = height.coerceAtLeast(16)
                encoderRotation = rotation
                encoderAspectMode = aspectMode
                encoderUsesHintRotation = usesHintRotation
                targetEncoderFps = desiredFps.coerceIn(1, 60)
                hardwareFpsUpper = hwFpsUpper.coerceIn(1, 120)
                isEncoderRecording = false
                encoderBaseCamTsNs = -1L
                encoderBaseMonoNs = -1L
                lastEncoderPtsNs = -1L
                nextEncoderFrameDueNs = -1L
                timelapseIntervalNs = if (timelapseIntervalMs > 0L) timelapseIntervalMs * 1_000_000L else 0L
                timelapseBaseMonoNs = -1L
                timelapseCapturedFrames = 0L
                encoderEglSurface = surface
                ok = true
            } catch (e: Exception) {
                onGlError("GL encoder surface error: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(1500L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        return ok
    }

    fun setEncoderRecordingActive(active: Boolean) {
        val handler = glHandler
        if (!active) {
            isEncoderRecording = false
        }
        handler?.post {
            if (active) {
                encoderBaseCamTsNs = -1L
                encoderBaseMonoNs = -1L
                lastEncoderPtsNs = -1L
                nextEncoderFrameDueNs = -1L
                timelapseBaseMonoNs = -1L
                timelapseCapturedFrames = 0L
            }
            isEncoderRecording = active
        }
    }

    fun detachEncoderSurfaceSync() {
        isEncoderRecording = false
        val handler = glHandler ?: return
        val latch = CountDownLatch(1)
        handler.post {
            isEncoderRecording = false
            encoderBaseCamTsNs = -1L
            encoderBaseMonoNs = -1L
            lastEncoderPtsNs = -1L
            nextEncoderFrameDueNs = -1L
            destroyEncoderSurfaceOnGlThread()
            latch.countDown()
        }
        try {
            latch.await(1000L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    fun release() {
        val handler = glHandler
        val thread = glThread
        glHandler = null
        glThread = null
        if (handler == null || thread == null) return

        val latch = CountDownLatch(1)
        handler.post {
            try {
                isReleased = true
                destroyEncoderSurfaceOnGlThread()
                destroyPreviewSurfaceOnGlThread()

                cameraInputSurface?.release()
                cameraInputSurface = null
                cameraInputTexture?.setOnFrameAvailableListener(null)
                cameraInputTexture?.release()
                cameraInputTexture = null

                if (lutTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                    lutTextureId = 0
                    activeUploadedLutSize = 0
                }
                if (dummyLutTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(dummyLutTextureId), 0)
                    dummyLutTextureId = 0
                }
                if (filmAtlasTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(filmAtlasTextureId), 0)
                    filmAtlasTextureId = 0
                }
                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    oesTextureId = 0
                }
                destroyOpticalFbosOnGlThread()
                if (trailProgramId != 0) {
                    GLES20.glDeleteProgram(trailProgramId)
                    trailProgramId = 0
                }
                if (blurProgramId != 0) {
                    GLES20.glDeleteProgram(blurProgramId)
                    blurProgramId = 0
                }
                pluginHandles?.let { h ->
                    if (h.programId != 0) GLES20.glDeleteProgram(h.programId)
                }
                pluginHandles = null
                compiledPluginId = null
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                        pbufferSurface = EGL14.EGL_NO_SURFACE
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                        eglContext = EGL14.EGL_NO_CONTEXT
                    }
                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = EGL14.EGL_NO_DISPLAY
                }
            } catch (_: Exception) {
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(1500L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        thread.quitSafely()
        try {
            thread.join(500L)
        } catch (_: Exception) {
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (isReleased || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val camTex = cameraInputTexture ?: return

        // Ensure valid GL context before updateTexImage()
        val primeSurface = if (previewEglSurface != EGL14.EGL_NO_SURFACE) previewEglSurface else pbufferSurface
        if (primeSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, primeSurface, primeSurface, eglContext)) return

        try {
            camTex.updateTexImage()
            camTex.getTransformMatrix(stMatrix)
        } catch (_: Exception) {
            return
        }

        val frameTimestampNs = camTex.timestamp
        val elapsedMs = System.currentTimeMillis() - startTimeMs
        val timeSec = (elapsedMs % 60_000L) / 1000f
        if (pluginShaderDirty) {
            syncPluginProgramOnGlThread()
        }
        val activePluginHandles = pluginHandles
        if (activePluginHandles != null) {
            val cfg = eraRenderConfig
            updateFilmTransportState(elapsedMs, cfg)
            if (cfg.useOpticalFbo && opticalFbosReady) {
                runOpticalPrePassesOnGlThread(cfg)
            }
        } else {
            trailNeedsReset = true
        }

        // vTexCoord is already in raw OES buffer (bx, by) coordinates, so exact 1-pixel steps are axis-aligned
        val stepXx = 1.0f / videoWidth.coerceAtLeast(16).toFloat()
        val stepXy = 0.0f
        val stepYx = 0.0f
        val stepYy = 1.0f / videoHeight.coerceAtLeast(16).toFloat()

        // Sync any pending 3D .cube LUT texture upload right on IdgafGlThread (zero stalls, instant switch!)
        if (lutUploadDirty) {
            uploadPendingLutOnGlThread()
        }

        // PASS 1: Full-Bleed Preview Surface WITH 60 FPS GPU Monitoring Scopes
        if (previewEglSurface != EGL14.EGL_NO_SURFACE && previewWidth > 0 && previewHeight > 0) {
            if (primeSurface != previewEglSurface) {
                EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)
            }
            GLES20.glViewport(0, 0, previewWidth, previewHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            buildPreviewMvp(mvpMatrix, previewWidth, previewHeight, displayRotation)
            drawQuad(
                enableScopes = true,
                viewportAspect = previewWidth.toFloat() / previewHeight.coerceAtLeast(1).toFloat(),
                stepXx = stepXx,
                stepXy = stepXy,
                stepYx = stepYx,
                stepYy = stepYy,
                timeSec = timeSec
            )
            EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
        }

        // PASS 2: Clean Hardware Encoder Surface (ONLY while MediaRecorder is actively started; ZERO scopes baked in!)
        if (isEncoderRecording && encoderEglSurface != EGL14.EGL_NO_SURFACE && encoderWidth > 0 && encoderHeight > 0) {
            val shouldEncodeFrame = shouldEncodeFrameNow()
            if (shouldEncodeFrame && EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)) {
                GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                buildEncoderMvp(mvpMatrix, encoderWidth, encoderHeight, encoderRotation)
                drawQuad(
                    enableScopes = false,
                    viewportAspect = encoderWidth.toFloat() / encoderHeight.coerceAtLeast(1).toFloat(),
                    stepXx = stepXx,
                    stepXy = stepXy,
                    stepYx = stepYx,
                    stepYy = stepYy,
                    timeSec = timeSec
                )
                val ptsNs = if (timelapseIntervalNs > 0L) {
                    computeTimelapseCompressedPtsNs()
                } else {
                    computeMonotonicEncoderPtsNs(frameTimestampNs)
                }
                EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, ptsNs)
                EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
                if (timelapseIntervalNs > 0L) {
                    onTimelapseFrameCaptured?.invoke(timelapseCapturedFrames)
                }
            }
        }
    }

    private fun buildPreviewMvp(outMvp: FloatArray, viewW: Int, viewH: Int, rot: Int) {
        // Camera2's SurfaceTexture stMatrix ALREADY rotates the sensor buffer to natural ROTATION_0 (portrait on phones).
        // Rotate cleanly from ROTATION_0 to the active displayRotation:
        // ROTATION_90 -> +90 deg CCW, ROTATION_270 -> -90 deg CW, ROTATION_180 -> 180 deg, ROTATION_0 -> 0 deg.
        val glRotDeg = displayRotationToGlDegrees(rot)
        val stSwapped = kotlin.math.abs(stMatrix[1]) > kotlin.math.abs(stMatrix[0])
        val rot0W = if (stSwapped) videoHeight.toFloat() else videoWidth.toFloat()
        val rot0H = if (stSwapped) videoWidth.toFloat() else videoHeight.toFloat()

        val rotSwapped = (glRotDeg == 90f || glRotDeg == -90f)
        val effVideoW = if (rotSwapped) rot0H else rot0W
        val effVideoH = if (rotSwapped) rot0W else rot0H

        val viewAspect = viewW.toFloat() / viewH.coerceAtLeast(1).toFloat()
        val videoAspect = effVideoW / effVideoH.coerceAtLeast(1f)

        // Full-bleed center-crop scale in post-rotation screen space (zero stretching, 1:1 square pixels)
        val scaleX: Float
        val scaleY: Float
        if (viewAspect > videoAspect) {
            scaleX = 1.0f
            scaleY = viewAspect / videoAspect
        } else {
            scaleX = videoAspect / viewAspect
            scaleY = 1.0f
        }

        Matrix.setIdentityM(outMvp, 0)
        Matrix.scaleM(outMvp, 0, scaleX, scaleY, 1.0f)
        if (glRotDeg != 0f) {
            Matrix.rotateM(outMvp, 0, glRotDeg, 0f, 0f, 1f)
        }
    }

    /**
     * True GPU Frame-Pacer Gate: when targetEncoderFps is below the hardware sensor's delivery rate
     * (e.g. 1 FPS, 5 FPS, 12 FPS, or 24 FPS on a 30 FPS sensor), skips intermediate frames on Pass 2
     * while leaving Pass 1 (live viewfinder) smooth. Never throttles when target >= hardware FPS.
     */
    private fun shouldEncodeFrameNow(): Boolean {
        // Phase 7.2C Time-Lapse Intervalometer Gate (captures Frame 0 immediately, then 1 frame every intervalNs)
        val tlNs = timelapseIntervalNs
        if (tlNs > 0L) {
            val nowNs = System.nanoTime()
            if (nextEncoderFrameDueNs < 0L) {
                nextEncoderFrameDueNs = nowNs + tlNs
                return true
            }
            if (nowNs + 12_000_000L < nextEncoderFrameDueNs) {
                return false
            }
            nextEncoderFrameDueNs += tlNs
            if (nextEncoderFrameDueNs < nowNs) {
                nextEncoderFrameDueNs = nowNs + tlNs
            }
            return true
        }

        // Standard Real-Time Video Frame-Pacer Gate
        val desired = targetEncoderFps.coerceIn(1, 60)
        if (desired >= hardwareFpsUpper) return true

        val nowNs = System.nanoTime()
        val intervalNs = 1_000_000_000L / desired.toLong()
        val lookaheadNs = minOf(intervalNs / 4L, 8_000_000L)

        if (nextEncoderFrameDueNs < 0L) {
            nextEncoderFrameDueNs = nowNs + intervalNs
            return true
        }
        if (nowNs + lookaheadNs < nextEncoderFrameDueNs) {
            return false
        }
        nextEncoderFrameDueNs += intervalNs
        if (nextEncoderFrameDueNs < nowNs) {
            nextEncoderFrameDueNs = nowNs + intervalNs
        }
        return true
    }

    /**
     * Generates strictly monotonic compressed timestamps for Time-Lapse MP4 output so frames
     * captured seconds apart play back smoothly at targetEncoderFps (e.g. 24 or 30 FPS).
     */
    private fun computeTimelapseCompressedPtsNs(): Long {
        if (timelapseBaseMonoNs < 0L) {
            timelapseBaseMonoNs = System.nanoTime()
        }
        val frameStepNs = 1_000_000_000L / targetEncoderFps.coerceIn(1, 60).toLong()
        val ptsNs = timelapseBaseMonoNs + (timelapseCapturedFrames * frameStepNs)
        timelapseCapturedFrames++
        lastEncoderPtsNs = ptsNs
        return ptsNs
    }

    /**
     * Converts Camera2's SurfaceTexture timestamp (which may be on CLOCK_BOOTTIME / REALTIME, including
     * hours of deep sleep!) onto MediaRecorder's exact CLOCK_MONOTONIC (System.nanoTime) timeline.
     * Preserves smooth hardware sensor frame pacing while guaranteeing exact MP4 duration & A/V sync.
     */
    private fun computeMonotonicEncoderPtsNs(camTsNs: Long): Long {
        val nowMonoNs = System.nanoTime()
        val candidateNs = if (encoderBaseMonoNs < 0L || camTsNs <= 0L) {
            encoderBaseCamTsNs = camTsNs
            encoderBaseMonoNs = nowMonoNs
            nowMonoNs
        } else {
            val deltaNs = camTsNs - encoderBaseCamTsNs
            val projectedNs = encoderBaseMonoNs + deltaNs
            // Guard against HAL timestamp resets or >250ms clock drift
            if (deltaNs < 0L || kotlin.math.abs(projectedNs - nowMonoNs) > 250_000_000L) {
                encoderBaseCamTsNs = camTsNs
                encoderBaseMonoNs = nowMonoNs
                nowMonoNs
            } else {
                projectedNs
            }
        }
        val monotonicNs = if (lastEncoderPtsNs >= 0L && candidateNs <= lastEncoderPtsNs) {
            lastEncoderPtsNs + 1_000_000L
        } else {
            candidateNs
        }
        lastEncoderPtsNs = monotonicNs
        return monotonicNs
    }

    private fun buildEncoderMvp(outMvp: FloatArray, encW: Int, encH: Int, rot: Int) {
        val stSwapped = kotlin.math.abs(stMatrix[1]) > kotlin.math.abs(stMatrix[0])
        val rot0W = if (stSwapped) videoHeight.toFloat() else videoWidth.toFloat()
        val rot0H = if (stSwapped) videoWidth.toFloat() else videoHeight.toFloat()
        val stDet = stMatrix[0] * stMatrix[5] - stMatrix[1] * stMatrix[4]
        val unMirror = if (isFrontCamera && stDet > 0f && !mirrorFrontExport) -1.0f else 1.0f

        when (encoderAspectMode) {
            RecordAspectMode.VERT_9_16_PORTRAIT -> {
                if (!encoderUsesHintRotation) {
                    // Direct Portrait MP4 (e.g. 1080x1920, hint=0): stMatrix is ALREADY upright Portrait (ROTATION_0)!
                    val encAspect = encW.toFloat() / encH.coerceAtLeast(1).toFloat()
                    val videoAspect = rot0W / rot0H.coerceAtLeast(1f)
                    val sx = if (encAspect > videoAspect) 1.0f else videoAspect / encAspect
                    val sy = if (encAspect > videoAspect) encAspect / videoAspect else 1.0f
                    Matrix.setIdentityM(outMvp, 0)
                    Matrix.scaleM(outMvp, 0, sx * unMirror, sy, 1.0f)
                } else {
                    // Fallback Hardware-Safe Portrait (1920x1080 + orientationHint=90/270): full landscape buffer rotated by player
                    val glRotDeg = displayRotationToGlDegrees(rot)
                    val rotSwapped = (glRotDeg == 90f || glRotDeg == -90f)
                    val effW = if (rotSwapped) rot0H else rot0W
                    val effH = if (rotSwapped) rot0W else rot0H
                    val encAspect = encW.toFloat() / encH.coerceAtLeast(1).toFloat()
                    val videoAspect = effW / effH.coerceAtLeast(1f)
                    val sx = if (encAspect > videoAspect) 1.0f else videoAspect / encAspect
                    val sy = if (encAspect > videoAspect) encAspect / videoAspect else 1.0f
                    // In rotated 90/270 buffer, portrait horizontal axis is screen Y -> unMirror on Y!
                    Matrix.setIdentityM(outMvp, 0)
                    Matrix.scaleM(outMvp, 0, sx, sy * unMirror, 1.0f)
                    if (glRotDeg != 0f) Matrix.rotateM(outMvp, 0, glRotDeg, 0f, 0f, 1f)
                }
            }
            RecordAspectMode.VERT_9_16_CROP -> {
                // Hold phone Horizontal (Landscape) -> Center-crop exact 9:16 vertical slice from upright landscape frame!
                val glRotDeg = displayRotationToGlDegrees(rot)
                val rotSwapped = (glRotDeg == 90f || glRotDeg == -90f)
                val effW = if (rotSwapped) rot0H else rot0W
                val effH = if (rotSwapped) rot0W else rot0H
                val videoAspect = effW / effH.coerceAtLeast(1f)
                val targetPortraitAspect = 9.0f / 16.0f
                val cropScaleX = videoAspect / targetPortraitAspect
                val cropScaleY = 1.0f

                Matrix.setIdentityM(outMvp, 0)
                if (encoderUsesHintRotation) {
                    // Fallback 1920x1080 + orientationHint=90: post-rotate +90 CCW so 90 CW playback stands 100% upright!
                    Matrix.rotateM(outMvp, 0, 90f, 0f, 0f, 1f)
                }
                Matrix.scaleM(outMvp, 0, cropScaleX * unMirror, cropScaleY, 1.0f)
                if (glRotDeg != 0f) Matrix.rotateM(outMvp, 0, glRotDeg, 0f, 0f, 1f)
            }
            RecordAspectMode.WIDE_16_9 -> {
                val glRotDeg = displayRotationToGlDegrees(rot)
                val rotSwapped = (glRotDeg == 90f || glRotDeg == -90f)
                val effVideoW = if (rotSwapped) rot0H else rot0W
                val effVideoH = if (rotSwapped) rot0W else rot0H
                val encAspect = encW.toFloat() / encH.coerceAtLeast(1).toFloat()
                val videoAspect = effVideoW / effVideoH.coerceAtLeast(1f)
                val scaleX = if (encAspect > videoAspect) 1.0f else videoAspect / encAspect
                val scaleY = if (encAspect > videoAspect) encAspect / videoAspect else 1.0f

                Matrix.setIdentityM(outMvp, 0)
                Matrix.scaleM(outMvp, 0, scaleX * unMirror, scaleY, 1.0f)
                if (glRotDeg != 0f) Matrix.rotateM(outMvp, 0, glRotDeg, 0f, 0f, 1f)
            }
        }
    }

    private fun displayRotationToGlDegrees(displayRot: Int): Float {
        return when (displayRot) {
            Surface.ROTATION_0 -> 0f
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> -90f
            else -> 90f
        }
    }

    private fun syncPluginProgramOnGlThread() {
        pluginShaderDirty = false
        val targetId = desiredPluginId
        val targetSrc = desiredPluginFragShader
        if (targetId == null || targetSrc.isNullOrBlank()) {
            pluginHandles?.let { if (it.programId != 0) GLES20.glDeleteProgram(it.programId) }
            pluginHandles = null
            compiledPluginId = null
            return
        }
        if (compiledPluginId == targetId && pluginHandles != null) return

        pluginHandles?.let { if (it.programId != 0) GLES20.glDeleteProgram(it.programId) }
        pluginHandles = null
        compiledPluginId = null

        val pid = createProgram(VERTEX_SHADER, targetSrc)
        if (pid == 0) {
            onGlError("Era Plug-In '$targetId' shader compile failed — falling back to SAVIOR.")
            return
        }
        val h = PluginGlHandles(pid)
        GLES20.glUseProgram(pid)
        if (h.uTexture >= 0) GLES20.glUniform1i(h.uTexture, 0)
        if (h.uGrainAtlas >= 0) GLES20.glUniform1i(h.uGrainAtlas, 1)
        if (h.uBloomTexture >= 0) GLES20.glUniform1i(h.uBloomTexture, 2)
        if (h.uTrailTexture >= 0) GLES20.glUniform1i(h.uTrailTexture, 3)
        pluginHandles = h
        compiledPluginId = targetId
    }

    private fun drawQuad(
        enableScopes: Boolean,
        viewportAspect: Float,
        stepXx: Float,
        stepXy: Float,
        stepYx: Float,
        stepYy: Float,
        timeSec: Float
    ) {
        val activePlugin = pluginHandles
        if (activePlugin != null) {
            drawPluginQuad(activePlugin, viewportAspect, stepXx, stepXy, stepYx, stepYy, timeSec)
            return
        }

        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)

        val scopes = scopeState
        GLES20.glUniform1i(uEnableScopesLoc, if (enableScopes) 1 else 0)
        GLES20.glUniform1i(uShowFalseColorLoc, if (enableScopes && scopes.showFalseColor) 1 else 0)
        GLES20.glUniform1i(uShowPeakingLoc, if (enableScopes && scopes.showPeaking) 1 else 0)

        val pr = Color.red(scopes.peakingColorArgb) / 255f
        val pg = Color.green(scopes.peakingColorArgb) / 255f
        val pb = Color.blue(scopes.peakingColorArgb) / 255f
        GLES20.glUniform3f(uPeakingColorLoc, pr, pg, pb)
        GLES20.glUniform1f(uPeakingThresholdLoc, scopes.peakingThreshold)

        GLES20.glUniform1i(uZebraModeLoc, if (enableScopes && scopes.showZebras) scopes.zebraModeCode else 0)
        GLES20.glUniform2f(uStepXLoc, stepXx, stepXy)
        GLES20.glUniform2f(uStepYLoc, stepYx, stepYy)
        GLES20.glUniform1f(uTimeSecLoc, timeSec)

        val cs = colorState
        val lutReady = (lutTextureId != 0 && activeUploadedLutSize >= 2)
        val tex1Id = if (lutReady) lutTextureId else dummyLutTextureId
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex1Id)
        GLES20.glUniform1i(uLutTextureLoc, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val evOverdriveMul = Math.pow(2.0, evOverdriveStops.coerceIn(-2.0f, 2.0f).toDouble()).toFloat()
        if (cs.isBypassed) {
            GLES20.glUniform1f(uExposureMulLoc, evOverdriveMul)
            GLES20.glUniform1f(uContrastLoc, 1.0f)
            GLES20.glUniform1f(uSaturationLoc, 1.0f)
            GLES20.glUniform1f(uShadowsLoc, 0.0f)
            GLES20.glUniform1f(uHighlightsLoc, 0.0f)
            GLES20.glUniform1f(uVignetteLoc, 0.0f)
            GLES20.glUniform1i(uHasLutLoc, 0)
            GLES20.glUniform1f(uLutSizeLoc, 33.0f)
            GLES20.glUniform1f(uLutIntensityLoc, 0.0f)
        } else {
            val expMul = Math.pow(2.0, cs.exposureLift.toDouble()).toFloat() * evOverdriveMul
            GLES20.glUniform1f(uExposureMulLoc, expMul)
            GLES20.glUniform1f(uContrastLoc, cs.contrast)
            GLES20.glUniform1f(uSaturationLoc, cs.saturation)
            GLES20.glUniform1f(uShadowsLoc, cs.shadows)
            GLES20.glUniform1f(uHighlightsLoc, cs.highlights)
            GLES20.glUniform1f(uVignetteLoc, cs.vignette)
            GLES20.glUniform1i(uHasLutLoc, if (lutReady) 1 else 0)
            GLES20.glUniform1f(uLutSizeLoc, activeUploadedLutSize.coerceAtLeast(2).toFloat())
            GLES20.glUniform1f(uLutIntensityLoc, cs.lutIntensity.coerceIn(0f, 1f))
        }

        val splitFrac = splitCompareFraction
        val splitPx = if (enableScopes && !cs.isBypassed && splitFrac >= 0.0f && splitFrac <= 1.0f && previewWidth > 0) {
            splitFrac * previewWidth.toFloat()
        } else {
            -1.0f
        }
        GLES20.glUniform1f(uSplitPixelXLoc, splitPx)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawPluginQuad(
        h: PluginGlHandles,
        viewportAspect: Float,
        stepXx: Float,
        stepXy: Float,
        stepYx: Float,
        stepYy: Float,
        timeSec: Float
    ) {
        val cfg = eraRenderConfig
        val useOptFbo = (cfg.useOpticalFbo && opticalFbosReady)

        GLES20.glUseProgram(h.programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (h.uTexture >= 0) GLES20.glUniform1i(h.uTexture, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (filmAtlasTextureId != 0) filmAtlasTextureId else dummyLutTextureId)
        if (h.uGrainAtlas >= 0) GLES20.glUniform1i(h.uGrainAtlas, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (useOptFbo) bloomTexAId else dummyLutTextureId)
        if (h.uBloomTexture >= 0) GLES20.glUniform1i(h.uBloomTexture, 2)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (useOptFbo) trailTexId else dummyLutTextureId)
        if (h.uTrailTexture >= 0) GLES20.glUniform1i(h.uTrailTexture, 3)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        if (h.uMvpMatrix >= 0) GLES20.glUniformMatrix4fv(h.uMvpMatrix, 1, false, mvpMatrix, 0)
        if (h.uStMatrix >= 0) GLES20.glUniformMatrix4fv(h.uStMatrix, 1, false, stMatrix, 0)
        if (h.uFragStMatrix >= 0) GLES20.glUniformMatrix4fv(h.uFragStMatrix, 1, false, stMatrix, 0)
        if (h.uHasOpticalFbo >= 0) GLES20.glUniform1i(h.uHasOpticalFbo, if (useOptFbo) 1 else 0)
        if (h.uStepX >= 0) GLES20.glUniform2f(h.uStepX, stepXx, stepXy)
        if (h.uStepY >= 0) GLES20.glUniform2f(h.uStepY, stepYx, stepYy)
        if (h.uTimeSec >= 0) GLES20.glUniform1f(h.uTimeSec, timeSec)
        if (h.uViewportAspect >= 0) GLES20.glUniform1f(h.uViewportAspect, viewportAspect.coerceIn(0.4f, 4.0f))
        if (h.uGateAspect >= 0) GLES20.glUniform1f(h.uGateAspect, cfg.gateAspect.coerceIn(1.0f, 2.6f))
        if (h.uWear >= 0) GLES20.glUniform1f(h.uWear, cfg.wear.coerceIn(0f, 1f))
        if (h.uFps >= 0) GLES20.glUniform1f(h.uFps, cfg.fps.coerceIn(1f, 60f))
        if (h.uFilter >= 0) GLES20.glUniform1i(h.uFilter, cfg.filterCode)
        if (h.uGrainMul >= 0) GLES20.glUniform1f(h.uGrainMul, cfg.grainMul.coerceIn(0.45f, 2.2f))
        if (h.uMotionBlend >= 0) GLES20.glUniform1f(h.uMotionBlend, computeDaylightMotionBlend())
        if (h.uFilmSeed >= 0) GLES20.glUniform4f(h.uFilmSeed, filmSeedX1, filmSeedY1, filmSeedX2, filmSeedY2)
        if (h.uFilmTransport >= 0) GLES20.glUniform4f(h.uFilmTransport, filmWeaveX, filmWeaveY, filmFlicker, filmScratchX)
        if (h.uDefectA >= 0) GLES20.glUniform4f(h.uDefectA, defectAx, defectAy, defectAr, defectAw)
        if (h.uDefectB >= 0) GLES20.glUniform4f(h.uDefectB, defectBx, defectBy, defectBr, defectBw)
        if (h.uExtra0 >= 0) GLES20.glUniform4f(h.uExtra0, cfg.extra0X, cfg.extra0Y, cfg.extra0Z, cfg.extra0W)
        if (h.uExtra1 >= 0) GLES20.glUniform4f(h.uExtra1, cfg.extra1X, cfg.extra1Y, cfg.extra1Z, cfg.extra1W)

        if (h.aPosition >= 0) {
            GLES20.glEnableVertexAttribArray(h.aPosition)
            GLES20.glVertexAttribPointer(h.aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        }
        if (h.aTexCoord >= 0) {
            GLES20.glEnableVertexAttribArray(h.aTexCoord)
            GLES20.glVertexAttribPointer(h.aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (h.aPosition >= 0) GLES20.glDisableVertexAttribArray(h.aPosition)
        if (h.aTexCoord >= 0) GLES20.glDisableVertexAttribArray(h.aTexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun uploadPendingLutOnGlThread() {
        lutUploadDirty = false
        val lut = desiredLutData
        if (lut == null) {
            if (lutTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                lutTextureId = 0
            }
            activeUploadedLutSize = 0
            return
        }
        try {
            if (lutTextureId == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                lutTextureId = ids[0]
            }
            val n = lut.size
            val atlasW = n * n
            val atlasH = n
            val buf = ByteBuffer.allocateDirect(lut.rgbaBytes.size).order(ByteOrder.nativeOrder())
            buf.put(lut.rgbaBytes)
            buf.position(0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            // NPOT OpenGL ES 2.0 strict requirement: CLAMP_TO_EDGE + LINEAR (zero mipmaps)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                atlasW,
                atlasH,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                buf
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            activeUploadedLutSize = n
        } catch (e: Exception) {
            activeUploadedLutSize = 0
            onGlError("3D LUT GPU upload failed: ${e.message}")
        }
    }

    private fun destroyPreviewSurfaceOnGlThread() {
        if (previewEglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
            }
            EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            previewEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyEncoderSurfaceOnGlThread() {
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            val fallback = if (previewEglSurface != EGL14.EGL_NO_SURFACE) previewEglSurface else pbufferSurface
            if (fallback != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, fallback, fallback, eglContext)
            }
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun initEglAndShaders(): Boolean {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                onGlError("EGL display unavailable")
                return false
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                onGlError("EGL initialize failed")
                return false
            }

            eglConfig = chooseEglConfig(requireRecordable = true) ?: chooseEglConfig(requireRecordable = false)
            val cfg = eglConfig ?: run {
                onGlError("No compatible EGLConfig found")
                return false
            }

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                onGlError("EGL context creation failed")
                return false
            }

            val pbufAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfg, pbufAttribs, 0)
            if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
                onGlError("EGL PBuffer creation failed")
                return false
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
                onGlError("EGL makeCurrent failed")
                return false
            }

            programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (programId == 0) {
                onGlError("OpenGL shader compilation failed")
                return false
            }

            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
            uMvpMatrixLoc = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
            uStMatrixLoc = GLES20.glGetUniformLocation(programId, "uStMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(programId, "uTexture")
            uEnableScopesLoc = GLES20.glGetUniformLocation(programId, "uEnableScopes")
            uShowFalseColorLoc = GLES20.glGetUniformLocation(programId, "uShowFalseColor")
            uShowPeakingLoc = GLES20.glGetUniformLocation(programId, "uShowPeaking")
            uPeakingColorLoc = GLES20.glGetUniformLocation(programId, "uPeakingColor")
            uPeakingThresholdLoc = GLES20.glGetUniformLocation(programId, "uPeakingThreshold")
            uZebraModeLoc = GLES20.glGetUniformLocation(programId, "uZebraMode")
            uStepXLoc = GLES20.glGetUniformLocation(programId, "uStepX")
            uStepYLoc = GLES20.glGetUniformLocation(programId, "uStepY")
            uTimeSecLoc = GLES20.glGetUniformLocation(programId, "uTimeSec")
            uExposureMulLoc = GLES20.glGetUniformLocation(programId, "uExposureMul")
            uContrastLoc = GLES20.glGetUniformLocation(programId, "uContrast")
            uSaturationLoc = GLES20.glGetUniformLocation(programId, "uSaturation")
            uShadowsLoc = GLES20.glGetUniformLocation(programId, "uShadows")
            uHighlightsLoc = GLES20.glGetUniformLocation(programId, "uHighlights")
            uVignetteLoc = GLES20.glGetUniformLocation(programId, "uVignette")
            uLutTextureLoc = GLES20.glGetUniformLocation(programId, "uLutTexture")
            uHasLutLoc = GLES20.glGetUniformLocation(programId, "uHasLut")
            uLutSizeLoc = GLES20.glGetUniformLocation(programId, "uLutSize")
            uLutIntensityLoc = GLES20.glGetUniformLocation(programId, "uLutIntensity")
            uSplitPixelXLoc = GLES20.glGetUniformLocation(programId, "uSplitPixelX")
            if (desiredLutData != null) {
                lutUploadDirty = true
            }

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            // Create a permanent 2x2 valid 2D texture for GL_TEXTURE1 so uLutTexture is ALWAYS complete on boot
            val dummyIds = IntArray(1)
            GLES20.glGenTextures(1, dummyIds, 0)
            dummyLutTextureId = dummyIds[0]
            val dummyBuf = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder()).apply {
                put(byteArrayOf(
                    0, 0, 0, -1,  -1, 0, 0, -1,
                    0, -1, 0, -1, -1, -1, -1, -1
                ))
                position(0)
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyLutTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, dummyBuf
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            // Synthesize & upload seamless 256x256 Pure Silver-Nitrate Emulsion Grain & Hand-Bath Atlas (ZERO tiled dots!)
            filmAtlasTextureId = createPhysicalFilmAtlasTexture()

            // Initialize Multi-Pass Optical Bloom (256x128) + Temporal Motion-Blur (512x256) FBO pipeline
            initOpticalFbosOnGlThread()

            // Lock SAVIOR sampler units immediately after program link (unit 0=OES, 1=LUT)
            GLES20.glUseProgram(programId)
            GLES20.glUniform1i(uTextureLoc, 0)
            GLES20.glUniform1i(uLutTextureLoc, 1)
            if (desiredPluginId != null && !desiredPluginFragShader.isNullOrBlank()) {
                pluginShaderDirty = true
            }

            cameraInputTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(videoWidth, videoHeight)
                setOnFrameAvailableListener(this@CinemaGlRenderer, glHandler)
            }
            cameraInputSurface = Surface(cameraInputTexture)
            return true
        } catch (e: Exception) {
            onGlError("OpenGL init error: ${e.message}")
            return false
        }
    }

    private fun chooseEglConfig(requireRecordable: Boolean): EGLConfig? {
        val attribList = if (requireRecordable) {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
        } else {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            return null
        }
        return if (numConfigs[0] > 0) configs[0] else null
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        if (prog == 0) return 0
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun nextFilmRand01(): Float {
        // 64-bit SplitMix64 step -> cryptographically uniform, period 2^64 (never repeats!)
        filmPrngState += -7046029254386353131L // 0x9E3779B97F4A7C15
        var z = filmPrngState
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return ((z ushr 40) and 0xFFFFFFL).toFloat() / 16777216.0f
    }

    private fun computeDaylightMotionBlend(): Float {
        // In bright sunlight, Android pushes shutter to 1/500s–1/4000s, making 16/18 FPS look sharp & jerky.
        // Automatically engage temporal 180-deg rotary-shutter trail when live shutter is faster than 1/90s!
        val ns = liveShutterNs.coerceAtLeast(100_000L)
        val slowThresh = 16_000_000.0 // 1/62s (natural motion blur already present)
        val fastThresh = 2_000_000.0  // 1/500s (bright sunlight -> full optical rotary trail)
        val t = ((slowThresh - ns.toDouble()) / (slowThresh - fastThresh)).coerceIn(0.0, 1.0)
        return (t * 0.55).toFloat()
    }

    private fun updateFilmTransportState(elapsedMs: Long, cfg: EraRenderConfig) {
        val fps = cfg.fps.coerceIn(12f, 60f).toDouble()
        val tick = ((elapsedMs / 1000.0) * fps).toLong()
        if (tick == lastFilmTick) return
        lastFilmTick = tick

        // 1. Non-repeating 64-bit SplitMix64 texture shifts for grain/texture atlas (period 2^64, never repeats!)
        filmSeedX1 = nextFilmRand01()
        filmSeedY1 = nextFilmRand01()
        filmSeedX2 = nextFilmRand01()
        filmSeedY2 = nextFilmRand01()

        // 2. Smooth intermittent-claw gate weave driven by plugin weaveScale
        val weaveAmp = cfg.weaveScale.coerceIn(0f, 2f)
        if (weaveAmp > 0.01f) {
            val targetWx = (nextFilmRand01() - 0.5f) * 0.0024f * weaveAmp
            val targetWy = (nextFilmRand01() - 0.5f) * 0.0056f * weaveAmp
            filmWeaveX = filmWeaveX * 0.40f + targetWx * 0.60f
            filmWeaveY = filmWeaveY * 0.35f + targetWy * 0.65f
        } else {
            filmWeaveX = 0f
            filmWeaveY = 0f
        }

        // 3. Exposure Transport Breathing / Lamp Shimmer driven by plugin flickerScale
        val flickAmp = cfg.flickerScale.coerceIn(0f, 3f)
        if (flickAmp > 0.001f) {
            if (cfg.enableHandCrankFlicker) {
                filmBreathPhase += 0.28 + nextFilmRand01() * 0.14
                val crankSine = kotlin.math.sin(filmBreathPhase).toFloat() * 0.018f
                val crankJitter = (nextFilmRand01() - 0.5f) * 0.032f
                val targetFlicker = (crankSine + crankJitter) * flickAmp
                filmFlicker = filmFlicker * 0.55f + targetFlicker * 0.45f
            } else {
                filmBreathPhase += 0.22
                val arc = (kotlin.math.sin(filmBreathPhase).toFloat() * 0.004f + (nextFilmRand01() - 0.5f) * 0.006f) * flickAmp
                filmFlicker = filmFlicker * 0.70f + arc * 0.30f
            }
        } else {
            filmFlicker = 0.0f
        }

        // 4. Sparse Event-Driven Physical Film Defects (ZERO tiled dots!)
        defectAx = -9.0f
        defectBx = -9.0f
        val pDustA = cfg.dustProbA.coerceIn(0f, 1f)
        val pDustB = cfg.dustProbB.coerceIn(0f, 1f)
        val pScratch = cfg.scratchProb.coerceIn(0f, 1f)
        if (pDustA <= 0.0001f && pDustB <= 0.0001f && pScratch <= 0.00001f) {
            filmScratchLife = 0
            filmScratchX = -2.0f
        } else {
            if (nextFilmRand01() < pDustA) {
                defectAx = (nextFilmRand01() * 2.40f) - 1.20f
                defectAy = (nextFilmRand01() * 1.84f) - 0.92f
                val isHair = nextFilmRand01() < 0.28f
                defectAr = if (isHair) (0.0032f + nextFilmRand01() * 0.0026f) else (0.0036f + nextFilmRand01() * 0.0046f)
                val shapeIdx = if (isHair) (2 + (nextFilmRand01() * 2f).toInt()) else (nextFilmRand01() * 2f).toInt()
                val pol = if (nextFilmRand01() < 0.80f) -1.0f else 1.0f
                defectAw = pol * (shapeIdx.toFloat() + nextFilmRand01() * 0.98f)
            }
            if (nextFilmRand01() < pDustB) {
                defectBx = (nextFilmRand01() * 2.40f) - 1.20f
                defectBy = (nextFilmRand01() * 1.84f) - 0.92f
                defectBr = 0.0030f + nextFilmRand01() * 0.0042f
                val shapeIdx = (nextFilmRand01() * 3f).toInt()
                val pol = if (nextFilmRand01() < 0.78f) -1.0f else 1.0f
                defectBw = pol * (shapeIdx.toFloat() + nextFilmRand01() * 0.98f)
            }

            if (filmScratchLife > 0) {
                filmScratchLife--
                filmScratchX += filmScratchVel
                if (filmScratchLife <= 0 || filmScratchX < -0.66f || filmScratchX > 0.66f) {
                    filmScratchX = -2.0f
                }
            } else {
                if (nextFilmRand01() < pScratch) {
                    filmScratchX = (nextFilmRand01() * 1.12f) - 0.56f
                    filmScratchVel = (nextFilmRand01() - 0.5f) * 0.0012f
                    filmScratchLife = 12 + (nextFilmRand01() * 15f).toInt()
                } else {
                    filmScratchX = -2.0f
                }
            }
        }
    }

    private fun initOpticalFbosOnGlThread() {
        destroyOpticalFbosOnGlThread()
        opticalFbosReady = false
        try {
            trailProgramId = createProgram(OFFSCREEN_VERT_SHADER, OPTICAL_PREPASS_FRAG_SHADER)
            blurProgramId = createProgram(OFFSCREEN_VERT_SHADER, GAUSS_BLUR_FRAG_SHADER)
            if (trailProgramId == 0 || blurProgramId == 0) return

            trailAPosLoc = GLES20.glGetAttribLocation(trailProgramId, "aPosition")
            trailATexLoc = GLES20.glGetAttribLocation(trailProgramId, "aTexCoord")
            trailUStLoc = GLES20.glGetUniformLocation(trailProgramId, "uStMatrix")
            trailUTexLoc = GLES20.glGetUniformLocation(trailProgramId, "uTexture")
            trailUAlphaLoc = GLES20.glGetUniformLocation(trailProgramId, "uTrailAlpha")
            trailUColorModeLoc = GLES20.glGetUniformLocation(trailProgramId, "uPrepassColorMode")
            trailUWeightsLoc = GLES20.glGetUniformLocation(trailProgramId, "uPrepassWeights")
            trailUKneeLoc = GLES20.glGetUniformLocation(trailProgramId, "uBloomKnee")

            blurAPosLoc = GLES20.glGetAttribLocation(blurProgramId, "aPosition")
            blurATexLoc = GLES20.glGetAttribLocation(blurProgramId, "aTexCoord")
            blurUStLoc = GLES20.glGetUniformLocation(blurProgramId, "uStMatrix")
            blurUSrcLoc = GLES20.glGetUniformLocation(blurProgramId, "uSourceTex")
            blurUDirLoc = GLES20.glGetUniformLocation(blurProgramId, "uBlurDir")

            fun makeFbo(w: Int, h: Int): Pair<Int, Int>? {
                val texIds = IntArray(1)
                val fboIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                GLES20.glGenFramebuffers(1, fboIds, 0)
                val tid = texIds[0]
                val fid = fboIds[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tid)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fid)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tid, 0)
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    GLES20.glDeleteFramebuffers(1, intArrayOf(fid), 0)
                    GLES20.glDeleteTextures(1, intArrayOf(tid), 0)
                    return null
                }
                return fid to tid
            }

            // Square Power-of-Two FBOs (512x512 & 256x256) eliminate aspect anisotropy on portrait-natural phone sensors!
            val trailPair = makeFbo(512, 512) ?: return
            val bloomAPair = makeFbo(256, 256) ?: return
            val bloomBPair = makeFbo(256, 256) ?: return

            trailFboId = trailPair.first; trailTexId = trailPair.second
            bloomFboAId = bloomAPair.first; bloomTexAId = bloomAPair.second
            bloomFboBId = bloomBPair.first; bloomTexBId = bloomBPair.second
            trailNeedsReset = true
            opticalFbosReady = true
        } catch (_: Exception) {
            destroyOpticalFbosOnGlThread()
            opticalFbosReady = false
        }
    }

    private fun destroyOpticalFbosOnGlThread() {
        opticalFbosReady = false
        val fbos = intArrayOf(trailFboId, bloomFboAId, bloomFboBId).filter { it != 0 }.toIntArray()
        if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        val texs = intArrayOf(trailTexId, bloomTexAId, bloomTexBId).filter { it != 0 }.toIntArray()
        if (texs.isNotEmpty()) GLES20.glDeleteTextures(texs.size, texs, 0)
        trailFboId = 0; trailTexId = 0
        bloomFboAId = 0; bloomTexAId = 0
        bloomFboBId = 0; bloomTexBId = 0
    }

    private fun runOpticalPrePassesOnGlThread(cfg: EraRenderConfig) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, trailFboId)
        GLES20.glViewport(0, 0, 512, 512)
        val motionAmt = computeDaylightMotionBlend()
        val alpha: Float
        if (trailNeedsReset || motionAmt < 0.05f) {
            GLES20.glDisable(GLES20.GL_BLEND)
            alpha = 1.0f
            trailNeedsReset = false
        } else {
            alpha = (1.0f - motionAmt * 0.72f).coerceIn(0.55f, 1.0f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        GLES20.glUseProgram(trailProgramId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(trailUTexLoc, 0)
        GLES20.glUniformMatrix4fv(trailUStLoc, 1, false, stMatrix, 0)
        GLES20.glUniform1f(trailUAlphaLoc, alpha)
        GLES20.glUniform1i(trailUColorModeLoc, cfg.prepassColorMode)
        GLES20.glUniform3f(trailUWeightsLoc, cfg.prepassWeightR, cfg.prepassWeightG, cfg.prepassWeightB)
        GLES20.glUniform1f(trailUKneeLoc, cfg.bloomKnee.coerceIn(0.1f, 0.98f))

        GLES20.glEnableVertexAttribArray(trailAPosLoc)
        GLES20.glVertexAttribPointer(trailAPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(trailATexLoc)
        GLES20.glVertexAttribPointer(trailATexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(trailAPosLoc)
        GLES20.glDisableVertexAttribArray(trailATexLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        val stSwapped = kotlin.math.abs(stMatrix[1]) > kotlin.math.abs(stMatrix[0])
        val rot0W = (if (stSwapped) videoHeight else videoWidth).coerceAtLeast(16).toFloat()
        val rot0H = (if (stSwapped) videoWidth else videoHeight).coerceAtLeast(16).toFloat()
        val minDim = minOf(rot0W, rot0H)
        val stepPx = minDim * cfg.bloomRadiusFraction.coerceIn(0.001f, 0.03f)
        val blurStepU = stepPx / rot0W
        val blurStepV = stepPx / rot0H

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFboBId)
        GLES20.glViewport(0, 0, 256, 256)
        GLES20.glUseProgram(blurProgramId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, trailTexId)
        GLES20.glUniform1i(blurUSrcLoc, 1)
        GLES20.glUniformMatrix4fv(blurUStLoc, 1, false, identityMat4, 0)
        GLES20.glUniform2f(blurUDirLoc, blurStepU, 0.0f)

        GLES20.glEnableVertexAttribArray(blurAPosLoc)
        GLES20.glVertexAttribPointer(blurAPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(blurATexLoc)
        GLES20.glVertexAttribPointer(blurATexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFboAId)
        GLES20.glViewport(0, 0, 256, 256)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bloomTexBId)
        GLES20.glUniform2f(blurUDirLoc, 0.0f, blurStepV)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(blurAPosLoc)
        GLES20.glDisableVertexAttribArray(blurATexLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Synthesizes a 256x256 seamless (GL_REPEAT) PURE Silver-Nitrate Emulsion Grain & Hand-Bath Atlas (ZERO tiled dots!):
     * - R: Coarse Clumped 1920s Silver-Nitrate Emulsion Crystals
     * - G: High-Frequency Organic Micro-Grain (used both for fine crystal texture & irregular speck silhouette perturbation)
     * - B: Neutral 0.5 (reserved — ZERO tiled dots!)
     * - A: Soft Low-Frequency Hand-Developed 1920s Chemical Bath Unevenness
     */
    private fun createPhysicalFilmAtlasTexture(): Int {
        val n = 256
        val total = n * n
        val rng = java.util.Random(19220304L) // Nosferatu premiere date seed!

        fun boxBlurToroidal(src: FloatArray, radius: Int, passes: Int): FloatArray {
            var cur = src.copyOf()
            val tmp = FloatArray(total)
            val diam = (radius * 2 + 1).toFloat()
            repeat(passes) {
                for (y in 0 until n) {
                    val row = y * n
                    var sum = 0f
                    for (dx in -radius..radius) {
                        sum += cur[row + ((dx + n) and 255)]
                    }
                    tmp[row] = sum / diam
                    for (x in 1 until n) {
                        sum += cur[row + ((x + radius) and 255)] - cur[row + ((x - radius - 1 + n) and 255)]
                        tmp[row + x] = sum / diam
                    }
                }
                for (x in 0 until n) {
                    var sum = 0f
                    for (dy in -radius..radius) {
                        sum += tmp[((dy + n) and 255) * n + x]
                    }
                    cur[x] = sum / diam
                    for (y in 1 until n) {
                        sum += tmp[((y + radius) and 255) * n + x] - tmp[((y - radius - 1 + n) and 255) * n + x]
                        cur[y * n + x] = sum / diam
                    }
                }
            }
            return cur
        }

        fun normalizeToUnit(arr: FloatArray, gain: Float): FloatArray {
            var mean = 0.0
            for (v in arr) mean += v
            mean /= arr.size
            var varSum = 0.0
            for (v in arr) {
                val d = v - mean
                varSum += d * d
            }
            val invStd = (1.0 / kotlin.math.sqrt(varSum / arr.size + 1e-8)).toFloat()
            val out = FloatArray(arr.size)
            for (i in arr.indices) {
                out[i] = (0.5f + ((arr[i] - mean.toFloat()) * invStd) * gain).coerceIn(0f, 1f)
            }
            return out
        }

        val rawR1 = FloatArray(total) { rng.nextGaussian().toFloat() }
        val rawR2 = FloatArray(total) { rng.nextGaussian().toFloat() }
        val blurR1 = boxBlurToroidal(rawR1, radius = 1, passes = 2)
        val blurR2 = boxBlurToroidal(rawR2, radius = 2, passes = 2)
        val combR = FloatArray(total) { i -> blurR1[i] * 1.35f + blurR2[i] * 0.85f + rawR1[i] * 0.35f }
        val chR = normalizeToUnit(combR, 0.19f)

        val rawG = FloatArray(total) { rng.nextGaussian().toFloat() }
        val blurG = boxBlurToroidal(rawG, radius = 1, passes = 1)
        val combG = FloatArray(total) { i -> rawG[i] * 0.72f + blurG[i] * 0.45f }
        val chG = normalizeToUnit(combG, 0.18f)

        val rawA = FloatArray(total) { rng.nextGaussian().toFloat() }
        val chA = normalizeToUnit(boxBlurToroidal(rawA, radius = 8, passes = 2), 0.20f)

        val rgba = ByteArray(total * 4)
        for (i in 0 until total) {
            val o = i * 4
            rgba[o] = (chR[i] * 255f).toInt().coerceIn(0, 255).toByte()
            rgba[o + 1] = (chG[i] * 255f).toInt().coerceIn(0, 255).toByte()
            rgba[o + 2] = 128.toByte() // Pure neutral — ZERO tiled black dots!
            rgba[o + 3] = (chA[i] * 255f).toInt().coerceIn(0, 255).toByte()
        }

        val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder()).apply {
            put(rgba)
            position(0)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n, n, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        return texId
    }

}
