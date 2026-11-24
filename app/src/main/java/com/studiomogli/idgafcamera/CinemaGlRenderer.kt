package com.studiomogli.idgafcamera

import android.graphics.Color
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
            varying vec2 vQuadCoord;
            varying vec2 vScreenPos;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uStMatrix * aTexCoord).xy;
                vQuadCoord = aPosition.xy;
                vScreenPos = gl_Position.xy;
            }
        """

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

            // Phase 7.2A/7.2B Live GPU Color Grade + 3D .cube LUT Atlas uniforms (baked cleanly into preview & MP4)
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

            // Phase 7.3/7.4 Classic Camera Vault uniforms (0 = SAVIOR, 1 = 1920s Silent 35mm, 2 = 1950s Monochrome 35mm)
            uniform int uEraMode;
            uniform float uEraWear;
            uniform float uEraFps;
            uniform float uViewportAspect;

            varying vec2 vTexCoord;
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

            // Strictly bounded non-tiling procedural hash (all intermediates < 24.0 -> 100% artifact-free in BOTH highp FP32 & mediump FP16).
            // Eliminates repeated triangle/diamond moire bands on Mali/Adreno GPUs by splitting integer coordinates before nonlinear folding.
            float filmHash(vec2 cell, float seed) {
                vec2 cLo = mod(cell, vec2(127.0, 113.0));
                vec2 cHi = floor(cell / vec2(127.0, 113.0));
                float sLo = mod(seed, 89.0);
                float u = fract(cLo.x * 0.3183 + cLo.y * 0.7139 + cHi.x * 0.4171 + cHi.y * 0.2917 + sLo * 0.1931);
                float v = fract(cLo.x * 0.5877 + cLo.y * 0.2417 + cHi.x * 0.1639 + cHi.y * 0.6731 + sLo * 0.6180);
                float a = fract(u * 5.317 + v * 3.719 + u * v * 7.131);
                float b = fract(v * 6.131 + u * 2.893 + a * 4.723);
                return fract(a * 7.919 + b * 11.31 + a * b * 13.17);
            }

            // Organic silver-halide Gaussian-like grain crystal in [-0.5, +0.5] using two decorrelated taps (zero triangles, zero tiling)
            float organicGrain(vec2 cell, float tick) {
                float h1 = filmHash(cell, tick);
                float h2 = filmHash(cell + vec2(37.0, 53.0), tick + 19.0);
                return 0.5 * (h1 + h2) - 0.5;
            }

            // Era 1 (1920s Classic Silent 35mm): True optical emulation with night-and-day FRESH vs ARCHIVE stocks.
            // - FRESH (uEraWear = 0.0): Day-1 Premiere Nitrate — crisp center, soft Petzval edges, uncoated highlight glow,
            //   deep inky silver-gelatin blacks, cool-ivory silver tone, ultra-fine grain, ZERO scratches/dust.
            // - ARCHIVE (uEraWear = 1.0): 100-Year Vault Transfer — genuine gate weave, generational copy softening,
            //   aged sepia-amber nitrate patina, milky faded shadows, projector shutter flicker, clumped silver grain,
            //   3 razor-thin wandering dark/light hairline tramline scratches & circular single-frame micro-dust/pinholes.
            vec3 applyEra1920sSilent(vec3 rawRgb) {
                // 1. Baked 1.333:1 Silent Aperture Gate with soft anti-aliased rounded brass-gate corners
                float aspect = max(uViewportAspect, 0.5);
                vec2 gatePos = vec2(vScreenPos.x * aspect, vScreenPos.y);
                float gateHalfH = 0.965;
                float gateHalfW = 1.3333 * gateHalfH;
                float cornerR = 0.085;
                vec2 q = abs(gatePos) - vec2(gateHalfW - cornerR, gateHalfH - cornerR);
                float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cornerR;
                float gateMask = 1.0 - smoothstep(-0.007, 0.007, sdf);
                if (gateMask <= 0.001) {
                    return vec3(0.0);
                }

                float wear = clamp(uEraWear, 0.0, 1.0);
                vec2 gateUv = vec2(gatePos.x / gateHalfW, gatePos.y / gateHalfH);
                float rDist = length(gateUv) * 0.7071;
                float r2 = rDist * rDist;

                // Quantize time to authentic silent-film frame ticks (16 or 18 FPS)
                float frameTick = floor(uTimeSec * max(uEraFps, 12.0));

                // 2. Intermittent-Claw Gate Weave (rock-steady in FRESH, authentic shrunken-nitrate jitter in ARCHIVE)
                vec2 weaveUv = vTexCoord;
                if (wear > 0.15) {
                    float wx = (filmHash(vec2(frameTick, 13.0), 1.0) - 0.5) * 0.0032 * wear;
                    float wy = (filmHash(vec2(frameTick, 29.0), 2.0) - 0.5) * 0.0078 * wear;
                    weaveUv = clamp(vTexCoord + vec2(wx, wy), vec2(0.002), vec2(0.998));
                }

                // 3. Uncoated 1920s Petzval/Cooke Lens Optics: radial corner softening + bright-edge highlight halation
                vec3 centerSample = (wear > 0.15) ? texture2D(uTexture, weaveUv).rgb : rawRgb;
                float blurSpan = mix(0.85, 2.15, wear) + r2 * mix(1.85, 3.40, wear);
                vec2 dA = (uStepX + uStepY) * blurSpan;
                vec2 dB = (uStepX - uStepY) * blurSpan;
                vec3 softAvg = 0.25 * (
                    texture2D(uTexture, clamp(weaveUv + dA, vec2(0.002), vec2(0.998))).rgb +
                    texture2D(uTexture, clamp(weaveUv - dA, vec2(0.002), vec2(0.998))).rgb +
                    texture2D(uTexture, clamp(weaveUv + dB, vec2(0.002), vec2(0.998))).rgb +
                    texture2D(uTexture, clamp(weaveUv - dB, vec2(0.002), vec2(0.998))).rgb
                );

                // True 1920s Red-Blind Orthochromatic Emulsion Sensitivity (dark reds, luminous blue skies)
                float yCenter = dot(centerSample, vec3(0.08, 0.58, 0.34));
                float ySoft = dot(softAvg, vec3(0.08, 0.58, 0.34));
                float softMix = clamp(mix(0.10, 0.38, wear) + r2 * mix(0.28, 0.36, wear), 0.0, 0.72);
                float y = mix(yCenter, ySoft, softMix);
                // Uncoated silver-halide highlight bloom
                float bloom = max(ySoft - 0.68, 0.0) * mix(0.24, 0.42, wear);
                y = clamp(y + bloom, 0.0, 1.0);

                // 4. Hand-Crank / Projector Shutter Flicker + Distinct FRESH vs ARCHIVE Tone Curves
                float flickerAmp = mix(0.018, 0.095, wear);
                float flicker = 1.0 + (filmHash(vec2(frameTick, 7.0), 3.0) - 0.5) * 2.0 * flickerAmp;
                float lampBreath = 1.0 + sin(frameTick * 0.43 + vScreenPos.y * 1.4) * 0.032 * wear;
                y = clamp(y * flicker * lampBreath, 0.0, 1.0);

                float contrast = mix(1.36, 1.16, wear);
                float gammaVal = mix(1.03, 0.96, wear);
                float toe = mix(0.012, 0.082, wear);
                float shoulder = mix(0.985, 0.895, wear);
                y = clamp((y - 0.45) * contrast + 0.48, 0.0, 1.0);
                y = pow(y, gammaVal);
                y = clamp((y / (1.0 + 0.12 * y * y)) * 1.12, 0.0, 1.0);
                y = clamp(toe + y * (shoulder - toe), 0.0, 1.0);

                // 5. True Isotropic Organic 35mm Silver-Halide Emulsion Grain (integer cell pitch, zero patterns!)
                vec2 gFineCell = floor((gatePos + vec2(2.0, 2.0)) * 280.0);
                float gTotal = organicGrain(gFineCell, frameTick) * mix(0.068, 0.145, wear);
                if (wear > 0.25) {
                    vec2 gClumpCell = floor((gatePos + vec2(2.0, 2.0)) * 118.0);
                    gTotal += organicGrain(gClumpCell, frameTick + 41.0) * 0.088 * wear;
                }
                float grainMask = 0.42 + 0.58 * clamp(4.0 * y * (1.0 - y), 0.0, 1.0);
                y = clamp(y + gTotal * grainMask, 0.0, 1.0);

                // 6. ARCHIVE-ONLY True Physical Film Artifacts (ZERO artifacts in FRESH!)
                if (wear > 0.25) {
                    // A. 3 Razor-Thin Organically Wandering Vertical Tramline Scratches (2 dark gouges + 1 bright base scratch)
                    float slowT1 = floor(frameTick * 0.09);
                    float slowT2 = floor(frameTick * 0.14);
                    float slowT3 = floor(frameTick * 0.22);

                    float vis1 = step(0.32, filmHash(vec2(slowT1, 5.0), 11.0));
                    float vis2 = step(0.45, filmHash(vec2(slowT2, 9.0), 17.0));
                    float vis3 = step(0.56, filmHash(vec2(slowT3, 17.0), 23.0));

                    float x1 = (filmHash(vec2(slowT1, 2.0), 31.0) * 1.32 - 0.66) + sin(vScreenPos.y * 4.2 + slowT1) * 0.0045;
                    float x2 = (filmHash(vec2(slowT2, 6.0), 37.0) * 1.24 - 0.62) + sin(vScreenPos.y * 6.5 - slowT2 * 1.7) * 0.0038;
                    float x3 = (filmHash(vec2(slowT3, 14.0), 43.0) * 1.18 - 0.59) + sin(vScreenPos.y * 5.0 + slowT3 * 2.1) * 0.0040;

                    float seg1 = smoothstep(0.15, 0.65, filmHash(vec2(floor((vScreenPos.y + 2.0) * 9.0), frameTick), 51.0));
                    float seg2 = smoothstep(0.25, 0.75, filmHash(vec2(floor((vScreenPos.y + 2.0) * 12.0), frameTick), 57.0));
                    float seg3 = smoothstep(0.30, 0.80, filmHash(vec2(floor((vScreenPos.y + 2.0) * 8.0), frameTick), 63.0));

                    float darkS1 = (1.0 - smoothstep(0.0, 0.0012, abs(vScreenPos.x - x1))) * vis1 * seg1 * 0.48;
                    float darkS2 = (1.0 - smoothstep(0.0, 0.0009, abs(vScreenPos.x - x2))) * vis2 * seg2 * 0.36;
                    float brightS3 = (1.0 - smoothstep(0.0, 0.0010, abs(vScreenPos.x - x3))) * vis3 * seg3 * 0.42;

                    y = clamp(y - (darkS1 + darkS2) * wear + brightS3 * wear, 0.0, 1.0);

                    // B. Organic Circular/Elliptical Single-Frame Micro-Dust & Base Pinholes (integer gatePos grid!)
                    vec2 dustGrid = (gatePos + vec2(2.0, 2.0)) * 72.0;
                    vec2 cellId = floor(dustGrid);
                    vec2 cellUv = fract(dustGrid);
                    float dChance = filmHash(cellId, frameTick + 7.0);
                    if (dChance > 0.9920) {
                        vec2 speckCenter = vec2(
                            0.25 + 0.50 * filmHash(cellId + vec2(11.0, 3.0), frameTick + 13.0),
                            0.25 + 0.50 * filmHash(cellId + vec2(5.0, 19.0), frameTick + 29.0)
                        );
                        vec2 delta = (cellUv - speckCenter) * vec2(1.0, 1.45);
                        float rad = 0.14 + 0.15 * filmHash(cellId + vec2(23.0, 7.0), frameTick + 5.0);
                        float speckMask = 1.0 - smoothstep(rad * 0.55, rad, length(delta));
                        if (dChance > 0.9974) {
                            y = clamp(y + speckMask * 0.56 * wear, 0.0, 1.0);
                        } else {
                            y = clamp(y - speckMask * 0.60 * wear, 0.0, 1.0);
                        }
                    }
                }

                // 7. Optical Corner Falloff
                float vigStrength = mix(0.26, 0.58, wear);
                float vig = 1.0 - vigStrength * smoothstep(mix(0.46, 0.32, wear), 1.10, rDist);
                y *= clamp(vig, 0.0, 1.0);

                // 8. Authentic Stock Base Tint: FRESH = crisp cool-ivory silver gelatin, ARCHIVE = 100-year amber-sepia nitrate!
                vec3 freshTint = vec3(1.01, 1.00, 0.96);
                vec3 archiveTint = vec3(1.11, 0.98, 0.78);
                vec3 stockColor = clamp(y * mix(freshTint, archiveTint, wear), 0.0, 1.0);
                return stockColor * gateMask;
            }

            // Era 2 (1950s Classic Monochrome — Satyajit Ray / Arriflex 35 / Kodak Plus-X & Double-X):
            // - Baked 1.375:1 Academy Sound Ratio Gate with tighter machined 1950s corners
            // - True Panchromatic Silver-Halide + subtle Wratten K2 Yellow Filter (glowing skin, moody skies, rich reds)
            // - Cooke Speed Panchro coated-glass sharpness + soft silver-halide highlight halation
            // - FRESH (uEraWear = 0.0): 1955 pure silver-gelatin premiere print (cool neutral silver, deep blacks, ultra-fine grain, spotless)
            // - ARCHIVE (uEraWear = 1.0): 70-yr acetate vault print (warm cream-selenium base, lifted shadows, gentle micro-weave & flicker,
            //   delicate hairline scratches, single-frame acetate dust & authentic top-right 35mm reel-changeover cue mark!)
            vec3 applyEra1950sMonochrome(vec3 rawRgb) {
                // 1. Baked 1.375:1 AMPAS Academy Sound Gate (crisper machined corners than 1920s brass gate)
                float aspect = max(uViewportAspect, 0.5);
                vec2 gatePos = vec2(vScreenPos.x * aspect, vScreenPos.y);
                float gateHalfH = 0.972;
                float gateHalfW = 1.375 * gateHalfH;
                float cornerR = 0.032;
                vec2 q = abs(gatePos) - vec2(gateHalfW - cornerR, gateHalfH - cornerR);
                float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cornerR;
                float gateMask = 1.0 - smoothstep(-0.006, 0.006, sdf);
                if (gateMask <= 0.001) {
                    return vec3(0.0);
                }

                float wear = clamp(uEraWear, 0.0, 1.0);
                vec2 gateUv = vec2(gatePos.x / gateHalfW, gatePos.y / gateHalfH);
                float rDist = length(gateUv) * 0.7071;
                float r2 = rDist * rDist;

                // Quantize film grain/artifacts to active 1950s FPS cadence (default 24 FPS sync sound)
                float activeFps = max(uEraFps, 12.0);
                float frameTick = floor(uTimeSec * activeFps);

                // 2. Pin-Registered Mitchell/Arriflex Gate (zero weave in FRESH, subtle 70-yr acetate breathing in ARCHIVE)
                vec2 weaveUv = vTexCoord;
                if (wear > 0.2) {
                    float wx = (filmHash(vec2(frameTick, 19.0), 4.0) - 0.5) * 0.0014 * wear;
                    float wy = (filmHash(vec2(frameTick, 37.0), 5.0) - 0.5) * 0.0032 * wear;
                    weaveUv = clamp(vTexCoord + vec2(wx, wy), vec2(0.002), vec2(0.998));
                }

                // 3. Coated 1950s Cooke Speed Panchro Optics: crisp center resolution + gentle corner glow + highlight halation
                vec3 centerSample = (wear > 0.2) ? texture2D(uTexture, weaveUv).rgb : rawRgb;
                float blurSpan = mix(0.75, 1.55, wear) + r2 * mix(1.35, 2.30, wear);
                vec2 dA = (uStepX + uStepY) * blurSpan;
                vec2 dB = (uStepX - uStepY) * blurSpan;
                vec3 softAvg = 0.25 * (
                    texture2D(uTexture, clamp(weaveUv + dA, vec2(0.002), vec2(0.998))).rgb +
                    texture2D(uTexture, clamp(weaveUv - dA, vec2(0.002), vec2(0.998))).rgb +
                    texture2D(uTexture, clamp(weaveUv + dB, vec2(0.002), vec2(0.998))).rgb +
                    texture2D(uTexture, clamp(weaveUv - dB, vec2(0.002), vec2(0.998))).rgb
                );

                // 4. Authentic 1950s Panchromatic Silver-Halide + Wratten K2 Yellow Filter (sculptural skin, moody sky, rich red)
                float yCenter = dot(centerSample, vec3(0.42, 0.46, 0.12));
                float ySoft = dot(softAvg, vec3(0.42, 0.46, 0.12));
                float softMix = clamp(mix(0.05, 0.22, wear) + r2 * mix(0.14, 0.22, wear), 0.0, 0.52);
                float y = mix(yCenter, ySoft, softMix);
                // Delicate silver-halide highlight halation around bright windows, sun-lit faces & lamps
                float halation = max(ySoft - 0.72, 0.0) * mix(0.22, 0.34, wear);
                y = clamp(y + halation, 0.0, 1.0);

                // 5. Chiaroscuro Tone Curve (Subrata Mitra bounce-light midtones, velvety blacks, silky rolloff)
                if (wear > 0.2) {
                    float flicker = 1.0 + (filmHash(vec2(frameTick, 11.0), 6.0) - 0.5) * 2.0 * (0.048 * wear);
                    y = clamp(y * flicker, 0.0, 1.0);
                }
                float contrast = mix(1.26, 1.14, wear);
                float gammaVal = mix(1.02, 0.98, wear);
                float toe = mix(0.014, 0.058, wear);
                float shoulder = mix(0.982, 0.915, wear);
                y = clamp((y - 0.46) * contrast + 0.48, 0.0, 1.0);
                y = pow(y, gammaVal);
                y = clamp((y / (1.0 + 0.10 * y * y)) * 1.10, 0.0, 1.0);
                y = clamp(toe + y * (shoulder - toe), 0.0, 1.0);

                // 6. Fine Organic 35mm Kodak Plus-X / Double-X Silver Grain (true isotropic Gaussian, zero triangles!)
                vec2 gFineCell = floor((gatePos + vec2(2.0, 2.0)) * 340.0);
                float gTotal = organicGrain(gFineCell, frameTick) * mix(0.055, 0.112, wear);
                if (wear > 0.25) {
                    vec2 gMedCell = floor((gatePos + vec2(2.0, 2.0)) * 155.0);
                    gTotal += organicGrain(gMedCell, frameTick + 37.0) * 0.058 * wear;
                }
                float grainMask = 0.40 + 0.60 * clamp(4.0 * y * (1.0 - y), 0.0, 1.0);
                y = clamp(y + gTotal * grainMask, 0.0, 1.0);

                // 7. ARCHIVE-ONLY 1950s Acetate Vault Artifacts (ZERO artifacts in FRESH!)
                if (wear > 0.25) {
                    // Delicate single dark + bright acetate hairline tramline scratches
                    float slowT1 = floor(frameTick * 0.08);
                    float slowT2 = floor(frameTick * 0.15);
                    float vis1 = step(0.46, filmHash(vec2(slowT1, 6.0), 19.0));
                    float vis2 = step(0.58, filmHash(vec2(slowT2, 13.0), 29.0));
                    float x1 = (filmHash(vec2(slowT1, 3.0), 41.0) * 1.30 - 0.65) + sin(vScreenPos.y * 3.8 + slowT1) * 0.0028;
                    float x2 = (filmHash(vec2(slowT2, 9.0), 47.0) * 1.24 - 0.62) + sin(vScreenPos.y * 5.2 - slowT2) * 0.0025;
                    float seg1 = smoothstep(0.22, 0.72, filmHash(vec2(floor((vScreenPos.y + 2.0) * 10.0), frameTick), 53.0));
                    float seg2 = smoothstep(0.32, 0.80, filmHash(vec2(floor((vScreenPos.y + 2.0) * 11.0), frameTick), 59.0));
                    float darkS = (1.0 - smoothstep(0.0, 0.0009, abs(vScreenPos.x - x1))) * vis1 * seg1 * 0.34;
                    float lightS = (1.0 - smoothstep(0.0, 0.0008, abs(vScreenPos.x - x2))) * vis2 * seg2 * 0.30;
                    y = clamp(y - darkS * wear + lightS * wear, 0.0, 1.0);

                    // Fine single-frame circular acetate micro-dust flecks
                    vec2 dustGrid = (gatePos + vec2(2.0, 2.0)) * 86.0;
                    vec2 cellId = floor(dustGrid);
                    vec2 cellUv = fract(dustGrid);
                    float dChance = filmHash(cellId, frameTick + 17.0);
                    if (dChance > 0.9945) {
                        vec2 sc = vec2(
                            0.28 + 0.44 * filmHash(cellId + vec2(13.0, 4.0), frameTick + 31.0),
                            0.28 + 0.44 * filmHash(cellId + vec2(7.0, 21.0), frameTick + 43.0)
                        );
                        vec2 delta = (cellUv - sc) * vec2(1.0, 1.35);
                        float rad = 0.12 + 0.13 * filmHash(cellId + vec2(19.0, 9.0), frameTick + 11.0);
                        float speck = 1.0 - smoothstep(rad * 0.50, rad, length(delta));
                        if (dChance > 0.9980) {
                            y = clamp(y + speck * 0.46 * wear, 0.0, 1.0);
                        } else {
                            y = clamp(y - speck * 0.48 * wear, 0.0, 1.0);
                        }
                    }

                    // Authentic 1950s 35mm Projection Changeover Cue Mark (cigarette burn): top-right corner for 4 frames every ~20s
                    float reelCycle = activeFps * 20.0;
                    float reelMod = mod(frameTick, reelCycle);
                    if (reelMod >= (reelCycle - 8.0) && reelMod < (reelCycle - 4.0)) {
                        vec2 cueDelta = gateUv - vec2(0.84, 0.80);
                        cueDelta.x *= 1.375;
                        float cueD = length(cueDelta);
                        float cueRing = (1.0 - smoothstep(0.032, 0.044, cueD));
                        float cueHole = (1.0 - smoothstep(0.016, 0.025, cueD));
                        y = mix(y, 0.08, cueRing * 0.85 * wear);
                        y = mix(y, 0.92, cueHole * 0.72 * wear);
                    }
                }

                // 8. Subtle Cooke Prime Vignette + Pure Silver-Gelatin (FRESH) vs Aged Cream Acetate (ARCHIVE) Tint
                float vigStrength = mix(0.20, 0.42, wear);
                float vig = 1.0 - vigStrength * smoothstep(mix(0.52, 0.38, wear), 1.12, rDist);
                y *= clamp(vig, 0.0, 1.0);

                vec3 freshSilver = vec3(0.99, 1.00, 1.01);
                vec3 archiveAcetate = vec3(1.05, 1.01, 0.91);
                vec3 stockColor = clamp(y * mix(freshSilver, archiveAcetate, wear), 0.0, 1.0);
                return stockColor * gateMask;
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
                if (uEraMode == 1) {
                    gl_FragColor = vec4(applyEra1920sSilent(rawColor), 1.0);
                    return;
                }
                if (uEraMode == 2) {
                    gl_FragColor = vec4(applyEra1950sMonochrome(rawColor), 1.0);
                    return;
                }

                float rawY = calcLuma(rawColor);
                vec3 baseColor = rawColor;

                // 1. Live GPU Exposure Lift (allows pushing past phone hardware AE +1.0 EV ceiling)
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

                // 5. Phase 7.2B Real 3D .cube LUT (applied after primary balance & before optical vignette)
                if (uHasLut == 1 && uLutIntensity > 0.001) {
                    vec3 lutColor = apply3dCubeLut(baseColor);
                    baseColor = clamp(mix(baseColor, lutColor, uLutIntensity), 0.0, 1.0);
                }

                // 6. Bipolar Cinema Vignette: negative (-0.85..0) = Black optical falloff, positive (0..+0.85) = White dreamy glow
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

                // Phase 7.2.5 Preview-Only Live RAW | LOOK Split-Screen Wipe (NEVER active in Pass 2 encoder!)
                if (uEnableScopes == 1 && uSplitPixelX >= 0.0 && gl_FragCoord.x < uSplitPixelX) {
                    baseColor = rawColor;
                }

                vec3 outColor = baseColor;

                // Monitoring scopes ONLY execute in Preview pass (uEnableScopes == 1)
                if (uEnableScopes == 1) {
                    float y = calcLuma(baseColor);

                    // 1. Full-HD Rec.709 False Color (monitors graded output)
                    if (uShowFalseColor == 1) {
                        outColor = applyFalseColor(y);
                    }

                    // 2. FiLMiC-Grade High-Frequency Laplacian x Coherent-Gradient Focus Peaking
                    // Uses rawY against raw neighbors so color grade/exposure lift never causes false peaking!
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

                    // 3. Razor-Sharp Animated Marching 45-Deg Diagonal Zebras (Screen-Pixel Space)
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
    private var uEraModeLoc: Int = -1
    private var uEraWearLoc: Int = -1
    private var uEraFpsLoc: Int = -1
    private var uViewportAspectLoc: Int = -1

    private var lutTextureId: Int = 0
    private var dummyLutTextureId: Int = 0
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
    @Volatile var splitCompareFraction: Float = -1.0f // < 0.0 = OFF, 0.0..1.0 = Live RAW | LOOK vertical wipe
    @Volatile var eraModeCode: Int = 0 // 0 = SAVIOR, 1 = 1920s Silent 35mm, 2 = 1950s Monochrome 35mm
    @Volatile var eraWearLevel: Float = 0.0f // 0.0 = NITRATE Clean, 1.0 = ARCHIVE Aged
    @Volatile var eraFps: Float = 16.0f
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
        timelapseIntervalMs: Long = 0L
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
                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    oesTextureId = 0
                }
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
        val timeSec = ((System.currentTimeMillis() - startTimeMs) % 60_000L) / 1000f

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
        val glRotDeg = displayRotationToGlDegrees(rot)
        val stSwapped = kotlin.math.abs(stMatrix[1]) > kotlin.math.abs(stMatrix[0])
        val rot0W = if (stSwapped) videoHeight.toFloat() else videoWidth.toFloat()
        val rot0H = if (stSwapped) videoWidth.toFloat() else videoHeight.toFloat()

        val rotSwapped = (glRotDeg == 90f || glRotDeg == -90f)
        val effVideoW = if (rotSwapped) rot0H else rot0W
        val effVideoH = if (rotSwapped) rot0W else rot0H

        val encAspect = encW.toFloat() / encH.coerceAtLeast(1).toFloat()
        val videoAspect = effVideoW / effVideoH.coerceAtLeast(1f)
        val scaleX: Float
        val scaleY: Float
        if (encAspect > videoAspect) {
            scaleX = 1.0f
            scaleY = encAspect / videoAspect
        } else {
            scaleX = videoAspect / encAspect
            scaleY = 1.0f
        }

        // Determinant of upper-left 2x2 of stMatrix: negative = unmirrored (standard GL Y-flip),
        // positive = HAL applied horizontal selfie mirror (front camera). Un-mirror for clean readable MP4.
        val stDet = stMatrix[0] * stMatrix[5] - stMatrix[1] * stMatrix[4]
        val unMirrorX = if (isFrontCamera && stDet > 0f && !mirrorFrontExport) -1.0f else 1.0f

        Matrix.setIdentityM(outMvp, 0)
        Matrix.scaleM(outMvp, 0, scaleX * unMirrorX, scaleY, 1.0f)
        if (glRotDeg != 0f) {
            Matrix.rotateM(outMvp, 0, glRotDeg, 0f, 0f, 1f)
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

    private fun drawQuad(
        enableScopes: Boolean,
        viewportAspect: Float,
        stepXx: Float,
        stepXy: Float,
        stepYx: Float,
        stepYy: Float,
        timeSec: Float
    ) {
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
        // CRITICAL OpenGL ES 2.0 rule: samplerExternalOES (uTexture -> unit 0) and sampler2D (uLutTexture -> unit 1)
        // MUST NEVER share Texture Unit 0, or glDrawArrays() fails with GL_INVALID_OPERATION (black screen)!
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (lutReady) lutTextureId else dummyLutTextureId)
        GLES20.glUniform1i(uLutTextureLoc, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (cs.isBypassed) {
            GLES20.glUniform1f(uExposureMulLoc, 1.0f)
            GLES20.glUniform1f(uContrastLoc, 1.0f)
            GLES20.glUniform1f(uSaturationLoc, 1.0f)
            GLES20.glUniform1f(uShadowsLoc, 0.0f)
            GLES20.glUniform1f(uHighlightsLoc, 0.0f)
            GLES20.glUniform1f(uVignetteLoc, 0.0f)
            GLES20.glUniform1i(uHasLutLoc, 0)
            GLES20.glUniform1f(uLutSizeLoc, 33.0f)
            GLES20.glUniform1f(uLutIntensityLoc, 0.0f)
        } else {
            val expMul = Math.pow(2.0, cs.exposureLift.toDouble()).toFloat()
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
        GLES20.glUniform1i(uEraModeLoc, eraModeCode)
        GLES20.glUniform1f(uEraWearLoc, eraWearLevel.coerceIn(0f, 1f))
        GLES20.glUniform1f(uEraFpsLoc, eraFps.coerceIn(1f, 60f))
        GLES20.glUniform1f(uViewportAspectLoc, viewportAspect.coerceIn(0.4f, 4.0f))

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
            uEraModeLoc = GLES20.glGetUniformLocation(programId, "uEraMode")
            uEraWearLoc = GLES20.glGetUniformLocation(programId, "uEraWear")
            uEraFpsLoc = GLES20.glGetUniformLocation(programId, "uEraFps")
            uViewportAspectLoc = GLES20.glGetUniformLocation(programId, "uViewportAspect")
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

            // Lock sampler units immediately after program link (unit 0 = OES camera, unit 1 = 2D LUT)
            GLES20.glUseProgram(programId)
            GLES20.glUniform1i(uTextureLoc, 0)
            GLES20.glUniform1i(uLutTextureLoc, 1)

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
}
