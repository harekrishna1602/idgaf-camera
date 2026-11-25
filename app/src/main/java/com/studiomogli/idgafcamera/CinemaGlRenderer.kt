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

        // Offscreen Pass 1 Fragment Shader: Era-Adaptive Spectral Extraction (1920s Ortho OR 1950s Panchromatic + Wratten Glass Filters)
        // Rendered into 512x512 trailFbo with hardware GL_SRC_ALPHA/GL_ONE_MINUS_SRC_ALPHA temporal motion-blur blending!
        private const val ORTHO_TRAIL_FRAG_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTexture;
            uniform float uTrailAlpha;
            uniform vec3 uEraWeights;
            uniform float uBloomKnee;
            varying vec2 vTexCoord;
            void main() {
                vec3 rgb = texture2D(uTexture, vTexCoord).rgb;
                float ySpectral = clamp(dot(rgb, uEraWeights), 0.0, 1.0);
                // Soft-knee optical highlight excess (1920s Petzval knee=0.66; 1950s Cooke Speed Panchro specular halation knee=0.78)
                float hi = smoothstep(uBloomKnee, 0.99, ySpectral) * ySpectral;
                gl_FragColor = vec4(ySpectral, hi, ySpectral, uTrailAlpha);
            }
        """

        // Offscreen Pass 2 & 3 Fragment Shader: 9-Tap Separable Wide Gaussian Optical Bloom (sigma=2.15, stride=2 -> +-64px on 1080p!)
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

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform samplerExternalOES uTexture;
            uniform mat4 uFragStMatrix;
            uniform int uEnableScopes;
            uniform int uShowFalseColor;
            uniform int uShowPeaking;
            uniform vec3 uPeakingColor;
            uniform float uPeakingThreshold;
            uniform int uZebraMode;
            uniform vec2 uStepX;
            uniform vec2 uStepY;
            uniform float uTimeSec;

            // Phase 7.2A/7.2B Live GPU Color Grade + 3D .cube LUT Atlas uniforms
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

            // Phase 7.5 1920s Silent 35mm Multi-Pass Optical Engine uniforms
            // When uEraMode == 1:
            //   uLutTexture (unit 1)   = 256x256 Pure Seamless Silver-Nitrate Grain & Hand-Bath Atlas (ZERO tiled dots!)
            //   uBloomTexture (unit 2) = 256x128 True Wide 9-Tap 2D Separable Optical Bloom & MTF Softness FBO
            //   uTrailTexture (unit 3) = 512x256 Hardware Alpha-Blended Temporal Motion-Blur Ortho FBO
            uniform sampler2D uBloomTexture;
            uniform sampler2D uTrailTexture;
            uniform int uHasOpticalFbo;
            uniform int uEraMode;
            uniform float uEraWear;      // 0.0 = PREMIERE, 0.5 = VAULT, 1.0 = FOUND
            uniform float uEraFps;
            uniform int uEraFilter;      // 0 = SILVER, 1 = AMBER, 2 = NIGHT (Nosferatu), 3 = SEPIA, 4 = ROSE
            uniform float uEraGrainMul;
            uniform float uMotionBlend;  // 0.0..0.55 temporal rotary-shutter blend in bright daylight
            uniform vec4 uFilmSeed;      // 64-bit CPU non-repeating Weyl grain UV offsets
            uniform vec4 uFilmTransport; // .xy = gate weave, .z = slow organic breathing, .w = drifting scratch X (<-1.5 = none)
            uniform vec4 uDefectA;       // Event-driven single-frame focal-plane speck/hair A: .xy=pos, .z=rad, .w=polarity+angle (x<-5 = inactive)
            uniform vec4 uDefectB;       // Event-driven single-frame focal-plane speck/hair B: .xy=pos, .z=rad, .w=polarity+angle (x<-5 = inactive)
            uniform float uViewportAspect;
            uniform float uEraGateAspect;

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

            vec2 rot37(vec2 v) {
                return vec2(0.798635 * v.x - 0.601815 * v.y, 0.601815 * v.x + 0.798635 * v.y);
            }

            // Renders an authentic non-circular, irregular 1-frame focal-plane dust flake or curved micro-hair
            // using organic silhouette perturbation from the grain atlas (NEVER a smooth button/circle, NEVER repeating!)
            float evalOrganicDefect(vec2 gatePos, vec4 ev, float grainJitter) {
                if (ev.x < -5.0) return 0.0;
                vec2 d = gatePos - ev.xy;
                // Quick bounding rejection
                float maxR = ev.z * 4.5;
                if (abs(d.x) > maxR || abs(d.y) > maxR) return 0.0;

                float pol = (ev.w >= 0.0) ? 1.0 : -1.0;
                float code = abs(ev.w);
                float ang = fract(code) * 6.283185;
                float elong = 1.35 + floor(code) * 0.75; // 1.35 (irregular flake) .. 3.60 (delicate hair sliver)
                float ca = cos(ang);
                float sa = sin(ang);
                vec2 rd = vec2(ca * d.x - sa * d.y, sa * d.x + ca * d.y);
                // Slight organic bend for micro-fibers
                rd.y += (rd.x * rd.x) * 32.0 * pol;
                rd.x /= elong;
                float dist = length(rd) + grainJitter * ev.z * 0.72;
                float mask = 1.0 - smoothstep(ev.z * 0.65, ev.z, dist);
                return mask * pol * ((pol > 0.0) ? 0.52 : 0.72);
            }

            // True Beer-Lambert 1920s Lab Chemical Tinting & Toning:
            // Metallic silver shadows remain deeply dark/charcoal while translucent gelatin midtones & highlights absorb lab dye!
            vec3 apply1920sLabBath(float yIn, int bathCode, float wear) {
                float y = yIn;
                if (bathCode == 2) {
                    // NIGHT (Nosferatu 1922 Day-for-Night Prussian Blue-Teal):
                    // Pulls midtones down & deepens shadows so sunlit exteriors transform into moonlit night!
                    y = pow(clamp(y, 0.0, 1.0), 1.42);
                    y = clamp((y - 0.36) * 1.38 + 0.32, 0.0, 1.0);
                } else {
                    float contrast = mix(1.40, 1.26, wear);
                    y = clamp((y - 0.45) * contrast + 0.47, 0.0, 1.0);
                }

                // Authentic silver-halide highlight shoulder roll-off + real D-max toe
                y = clamp((y / (1.0 + 0.12 * y * y)) * 1.12, 0.0, 1.0);
                float toe = mix(0.007, 0.029, wear);
                float shoulder = mix(0.990, 0.935, wear);
                y = clamp(toe + y * (shoulder - toe), 0.0, 1.0);

                vec3 shd = vec3(0.008, 0.008, 0.008);
                vec3 mid = vec3(0.52, 0.51, 0.48);
                vec3 hil = vec3(0.99, 0.97, 0.92);

                if (bathCode == 1) {
                    // AMBER (The General 1926 Sunlit Daylight Straw-Amber)
                    shd = vec3(0.014, 0.010, 0.004);
                    mid = vec3(0.62, 0.47, 0.24);
                    hil = vec3(1.00, 0.89, 0.58);
                } else if (bathCode == 2) {
                    // NIGHT (Nosferatu 1922 Moonlit Prussian Blue-Teal)
                    shd = vec3(0.004, 0.012, 0.028);
                    mid = vec3(0.14, 0.40, 0.64);
                    hil = vec3(0.60, 0.86, 0.99);
                } else if (bathCode == 3) {
                    // SEPIA (Lantern Interior Burnt-Umber & Antique Gold)
                    shd = vec3(0.018, 0.010, 0.005);
                    mid = vec3(0.56, 0.41, 0.25);
                    hil = vec3(1.00, 0.90, 0.72);
                } else if (bathCode == 4) {
                    // ROSE (Pathé Dawn / Melodrama Warm Rose-Cream)
                    shd = vec3(0.016, 0.009, 0.012);
                    mid = vec3(0.62, 0.44, 0.44);
                    hil = vec3(1.00, 0.86, 0.84);
                }

                vec3 col = (y < 0.5)
                    ? mix(shd, mid, y * 2.0)
                    : mix(mid, hil, (y - 0.5) * 2.0);

                // Subtle 100-yr warm aging patina in VAULT/FOUND for SILVER print (never contaminates NIGHT blue!)
                if (bathCode == 0 && wear > 0.05) {
                    col *= vec3(1.0 + 0.05 * wear, 1.0 - 0.01 * wear, 1.0 - 0.10 * wear);
                }
                return clamp(col, 0.0, 1.0);
            }

            // Era 1 (1920s Silent 35mm — Nosferatu 1922 / The General 1926 Standard):
            // 1. Sharp 90-degree rectangular 1.333:1 Silent Full-Aperture Gate (NO fake rounded CRT-TV corners!)
            // 2. Multi-pass Temporal Motion-Blur FBO (liquid 180-deg shutter motion even in bright daylight)
            // 3. Multi-pass 9-tap Separable Wide Optical Bloom FBO (authentic ~128px diameter uncoated Petzval glow & optical MTF)
            // 4. True Beer-Lambert Chemical Tinting & Toning across 5 authentic 1920s Lab Baths
            // 5. Pure non-repeating 256x256 Silver-Nitrate Grain + Sparse Event-Driven Archive Physics (ZERO tiled dots!)
            vec3 applyEra1920sSilent(vec3 rawRgb) {
                float aspect = max(uViewportAspect, 0.5);
                float wear = clamp(uEraWear, 0.0, 1.0);

                // 1. Subtle physical intermittent-claw gate weave (computed in unified RawUv space & mapped via uFragStMatrix -> 0px bloom ghosting!)
                vec2 weaveRawUv = clamp(vRawUv + uFilmTransport.xy * wear, vec2(0.002), vec2(0.998));
                vec2 weaveUv = clamp((uFragStMatrix * vec4(weaveRawUv, 0.0, 1.0)).xy, vec2(0.002), vec2(0.998));
                vec2 weaveScreen = uFilmTransport.xy * wear * vec2(0.65, 0.85);

                // 2. Authentic SHARP 90-Degree Rectangular 1.333:1 Silent 35mm Camera Aperture Gate (1-2px matte feather only)
                vec2 gatePos = vec2((vScreenPos.x + weaveScreen.x) * aspect, vScreenPos.y + weaveScreen.y);
                float gateHalfH = 0.992;
                float gateHalfW = 1.3333 * gateHalfH;
                float cornerR = 0.008;
                vec2 q = abs(gatePos) - vec2(gateHalfW - cornerR, gateHalfH - cornerR);
                float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cornerR;
                float gateMask = 1.0 - smoothstep(-0.005, 0.004, sdf);
                if (gateMask <= 0.001) {
                    return vec3(0.0);
                }

                vec2 gateUv = vec2(gatePos.x / gateHalfW, gatePos.y / gateHalfH);
                float rDist = length(gateUv) * 0.7071;
                float r2 = rDist * rDist;

                // 3. Sharp Orthochromatic Base + Multi-Pass Temporal Motion-Blur FBO + Wide Optical Bloom FBO!
                vec3 orthoW = vec3(0.04, 0.56, 0.40);
                vec3 c0 = (wear > 0.1) ? texture2D(uTexture, weaveUv).rgb : rawRgb;
                float ySharp = dot(c0, orthoW);

                float y;
                float wideBloom = 0.0;
                if (uHasOpticalFbo == 1) {
                    vec3 trailSample = texture2D(uTrailTexture, weaveRawUv).rgb;
                    vec3 bloomSample = texture2D(uBloomTexture, weaveRawUv).rgb;
                    // Blend sharp sensor with temporal 512x256 optical MTF + daylight motion-blur trail:
                    // Even at center, mixing 28% of 512x256 MTF strips clinical 4K phone crispness into real ~800-line 1920s glass!
                    float mtfAndTrail = clamp(0.24 + uMotionBlend * 0.52 + r2 * 0.36, 0.0, 0.86);
                    y = mix(ySharp, trailSample.r, mtfAndTrail);
                    // Peripheral uncoated Petzval optical softness toward corners (reads from wide circular FBO .r)
                    y = mix(y, bloomSample.r, clamp(r2 * mix(0.24, 0.42, wear), 0.0, 0.52));
                    // True circular uncoated Petzval highlight bloom & veiling glare on speculars/windows/skies!
                    wideBloom = bloomSample.g * mix(0.36, 0.50, wear);
                } else {
                    // Fallback if FBO unavailable
                    vec2 dA = (uStepX + uStepY) * (1.8 + r2 * 4.0);
                    float ySoft = dot(texture2D(uTexture, clamp(weaveUv + dA, vec2(0.002), vec2(0.998))).rgb, orthoW);
                    y = mix(ySharp, ySoft, clamp(0.25 + r2 * 0.45, 0.0, 0.75));
                    wideBloom = max(ySoft - 0.62, 0.0) * 0.35;
                }
                y = clamp(y + wideBloom, 0.0, 1.0);

                // 4. Slow Organic Hand-Crank Exposure Breathing (low-pass smoothed in CPU, NOT harsh strobe!)
                y = clamp(y * (1.0 + uFilmTransport.z), 0.0, 1.0);

                // 5. Pure Non-Repeating Silver-Nitrate Emulsion Grain + Subtle Chemical Bath Unevenness (ZERO tiled dots!)
                vec2 uvG1 = gatePos * 2.25 + uFilmSeed.xy;
                vec2 uvG2 = rot37(gatePos) * 3.18 + uFilmSeed.zw;
                vec4 gTap1 = texture2D(uLutTexture, uvG1);
                vec4 gTap2 = texture2D(uLutTexture, uvG2);
                float nitrateGrain = (gTap1.r - 0.5) * 0.62 + (gTap2.r - 0.5) * 0.38;

                // Subtle slow hand-developed chemical unevenness in FOUND print (and faint in VAULT)
                if (wear > 0.25) {
                    float mottle = texture2D(uLutTexture, gatePos * 0.28 + uFilmSeed.xy * 0.12).a - 0.5;
                    y = clamp(y + mottle * 0.075 * wear, 0.0, 1.0);
                }

                float grainAmp = mix(0.115, 0.210, wear) * uEraGrainMul;
                float grainEnv = 0.24 + 0.76 * clamp(4.0 * y * (1.0 - y), 0.0, 1.0);
                y = clamp(y + nitrateGrain * grainAmp * grainEnv, 0.0, 1.0);

                // 6. Sparse Event-Driven Archive Physics (PREMIERE = 100% spotless; VAULT ~76% spotless; FOUND ~48% spotless)
                if (wear > 0.15) {
                    float dA = evalOrganicDefect(gatePos, uDefectA, gTap1.g - 0.5);
                    float dB = evalOrganicDefect(gatePos, uDefectB, gTap2.g - 0.5);
                    y = clamp(y + dA + dB, 0.0, 1.0);

                    // Rare, razor-thin 1px drifting emulsion hairline scratch (CPU drives rare bursts & long clean intervals!)
                    if (uFilmTransport.w > -1.4) {
                        float sx = uFilmTransport.w + sin(gatePos.y * 1.9 + uFilmSeed.x * 6.28) * 0.0018;
                        float dx = abs(vScreenPos.x - sx);
                        float scratchMask = (1.0 - smoothstep(0.0, 0.00075, dx)) * clamp(wear * 1.15, 0.0, 1.0);
                        y = clamp(y - scratchMask * 0.38, 0.0, 1.0);
                    }
                }

                // 7. Natural 1920s Brass Petzval Barrel Falloff (subtle in PREMIERE, rich in VAULT/FOUND)
                float vigStrength = mix(0.26, 0.48, wear);
                float vig = 1.0 - vigStrength * smoothstep(mix(0.46, 0.34, wear), 1.14, rDist);
                y *= clamp(vig, 0.0, 1.0);

                // 8. True Beer-Lambert Chemical Tinting & Toning across all 5 1920s Lab Baths!
                vec3 stockColor = apply1920sLabBath(y, uEraFilter, wear);
                return stockColor * gateMask;
            }

            // Spectral RGB -> Panchromatic Silver-Halide weights for 1950s Eastman Plus-X / Double-X + Optical Wratten Glass Filters
            vec3 get1950sPanchromaticWeights(int filterCode) {
                if (filterCode == 1) return vec3(0.40, 0.54, 0.06); // Y8: Wratten #8 Yellow (Subrata Mitra / Pather Panchali outdoor standard)
                if (filterCode == 2) return vec3(0.62, 0.36, 0.02); // O21: Wratten #21 Orange (Kurosawa dramatic skies & sculpted skin)
                if (filterCode == 3) return vec3(0.84, 0.15, 0.01); // R25: Wratten #25A Red (Bergman / Film Noir gothic dark skies)
                if (filterCode == 4) return vec3(0.14, 0.78, 0.08); // G11: Wratten #11 Green (Classic 1950s studio portrait & lush foliage)
                return vec3(0.28, 0.58, 0.14);                      // CLEAR: Authentic Eastman 5231 Plus-X / 5222 Double-X Panchromatic
            }

            // Era 2 (1950s Monochrome 35mm — Satyajit Ray / Kurosawa / Bergman / Mitchell BNC + Cooke Speed Panchro):
            // 1. Sharp 90-degree Cinema Gate: 1.375:1 Academy OR 1.85:1 1950s Widescreen Flat (handles pillarbox & letterbox cleanly)
            // 2. True Panchromatic Silver-Halide + 5 Real Optical Wratten Glass Contrast Filters (CLEAR / Y8 / O21 / R25 / G11)
            // 3. Single-Coated Cooke Speed Panchro Optics: crisp resolving center, gentle corner micro-softness, tight specular halation
            // 4. Zone III-VII Silver-Gelatin Tone Curve: deep velvety D-max blacks, rich 3D midtones, silver highlights (NO fake sepia!)
            // 5. Fine Tri-Acetate 35mm Safety Film Grain (high-freq G-channel dominant) + Pin-Registered STUDIO / CRITERION / ARCHIVE print
            vec3 applyEra1950sMono(vec3 rawRgb) {
                float aspect = max(uViewportAspect, 0.5);
                float gateAspect = max(uEraGateAspect, 1.0);
                float wear = clamp(uEraWear, 0.0, 1.0);

                // 1. Mitchell BNC pin-registered movement: 0 weave in STUDIO, whisper-subtle in CRITERION/ARCHIVE
                float weaveScale = wear * 0.42;
                vec2 weaveRawUv = clamp(vRawUv + uFilmTransport.xy * weaveScale, vec2(0.002), vec2(0.998));
                vec2 weaveUv = clamp((uFragStMatrix * vec4(weaveRawUv, 0.0, 1.0)).xy, vec2(0.002), vec2(0.998));
                vec2 weaveScreen = uFilmTransport.xy * weaveScale * vec2(0.55, 0.75);

                // 2. Authentic 1950s Cinema Aperture Gate (1.375:1 Academy OR 1.85:1 Widescreen Flat)
                vec2 gatePos = vec2((vScreenPos.x + weaveScreen.x) * aspect, vScreenPos.y + weaveScreen.y);
                float gateHalfH = (gateAspect > aspect) ? ((aspect * 0.992) / gateAspect) : 0.992;
                float gateHalfW = gateAspect * gateHalfH;
                float cornerR = 0.006;
                vec2 q = abs(gatePos) - vec2(gateHalfW - cornerR, gateHalfH - cornerR);
                float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cornerR;
                float gateMask = 1.0 - smoothstep(-0.004, 0.003, sdf);
                if (gateMask <= 0.001) {
                    return vec3(0.0);
                }

                vec2 gateUv = vec2(gatePos.x / gateHalfW, gatePos.y / gateHalfH);
                float rDist = length(gateUv) * 0.7071;
                float r2 = rDist * rDist;

                // 3. True Panchromatic Silver-Halide + Real Optical Wratten Glass Filter
                vec3 panW = get1950sPanchromaticWeights(uEraFilter);
                vec3 c0 = (wear > 0.1) ? texture2D(uTexture, weaveUv).rgb : rawRgb;
                float ySharp = clamp(dot(c0, panW), 0.0, 1.0);

                float y;
                float silverHalation = 0.0;
                if (uHasOpticalFbo == 1) {
                    vec3 trailSample = texture2D(uTrailTexture, weaveRawUv).rgb;
                    vec3 bloomSample = texture2D(uBloomTexture, weaveRawUv).rgb;
                    // Cooke Speed Panchro glass: MUCH sharper center than 1920s Petzval (only 12% center MTF smoothing),
                    // while engaging silky 180-deg rotary motion trail in bright daylight + delicate edge softening
                    float mtfAndTrail = clamp(0.11 + uMotionBlend * 0.44 + r2 * 0.16, 0.0, 0.68);
                    y = mix(ySharp, trailSample.r, mtfAndTrail);
                    // Gentle Cooke Speed Panchro wide-open edge character (zero corner mush!)
                    y = mix(y, bloomSample.r, clamp(r2 * mix(0.08, 0.16, wear), 0.0, 0.22));
                    // Tight single-coated silver-halide specular halation on bright highlights, lamps & sunlit rims
                    silverHalation = bloomSample.g * mix(0.24, 0.34, wear);
                } else {
                    y = ySharp;
                    silverHalation = max(ySharp - 0.78, 0.0) * 0.22;
                }
                y = clamp(y + silverHalation, 0.0, 1.0);

                // 4. Crystal-Sync Motor Exposure (0 flicker in STUDIO/CRITERION, tiny tungsten carbon-arc shimmer in ARCHIVE)
                y = clamp(y * (1.0 + uFilmTransport.z), 0.0, 1.0);

                // 5. Fine Tri-Acetate 1950s Safety Film Grain (high-frequency G-channel dominant -> crisp 35mm Plus-X/Double-X!)
                vec2 uvG1 = gatePos * 3.65 + uFilmSeed.xy;
                vec2 uvG2 = rot37(gatePos) * 4.90 + uFilmSeed.zw;
                vec4 gTap1 = texture2D(uLutTexture, uvG1);
                vec4 gTap2 = texture2D(uLutTexture, uvG2);
                float fineGrain = ((gTap1.g - 0.5) * 0.56 + (gTap2.g - 0.5) * 0.30) + ((gTap1.r - 0.5) * 0.14);

                float grainAmp = mix(0.072, 0.132, wear) * uEraGrainMul;
                // Silver-halide grain concentrates naturally in Zone III-VII midtones, stays velvety clean in deep blacks & bright speculars
                float grainEnv = 0.18 + 0.82 * clamp(4.0 * y * (1.0 - y), 0.0, 1.0);
                y = clamp(y + fineGrain * grainAmp * grainEnv, 0.0, 1.0);

                // 6. Sparse Archive Print Defects (STUDIO = 100% spotless; CRITERION = ~94% spotless; ARCHIVE = subtle theatre reel patina)
                if (wear > 0.15) {
                    float dA = evalOrganicDefect(gatePos, uDefectA, gTap1.g - 0.5) * 0.72;
                    float dB = evalOrganicDefect(gatePos, uDefectB, gTap2.g - 0.5) * 0.65;
                    y = clamp(y + dA + dB, 0.0, 1.0);

                    if (uFilmTransport.w > -1.4) {
                        float sx = uFilmTransport.w + sin(gatePos.y * 1.4 + uFilmSeed.x * 6.28) * 0.0010;
                        float dx = abs(vScreenPos.x - sx);
                        float scratchMask = (1.0 - smoothstep(0.0, 0.00055, dx)) * clamp(wear * 0.85, 0.0, 1.0);
                        y = clamp(y - scratchMask * 0.26, 0.0, 1.0);
                    }
                }

                // 7. Subtle Cooke Speed Panchro optical falloff (far cleaner & more open than 1920s Petzval barrel!)
                float vigStrength = mix(0.14, 0.28, wear);
                float vig = 1.0 - vigStrength * smoothstep(0.52, 1.18, rDist);
                y *= clamp(vig, 0.0, 1.0);

                // 8. True 1950s Silver-Gelatin Film S-Curve (deep D-max blacks, luminous Zone III-VII skin/fabric separation, soft silver shoulder!)
                // Midtone presence boost + crisp contrast without crushing shadow texture
                float yLog = pow(clamp(y, 0.0, 1.0), 0.96);
                float pivot = 0.44;
                float con = mix(1.30, 1.22, wear);
                float sCurve = (yLog < pivot)
                    ? pivot * pow(clamp(yLog / pivot, 0.0, 1.0), con)
                    : 1.0 - (1.0 - pivot) * pow(clamp((1.0 - yLog) / (1.0 - pivot), 0.0, 1.0), con);
                // Smooth silver-halide highlight shoulder roll-off
                sCurve = clamp((sCurve / (1.0 + 0.10 * sCurve * sCurve)) * 1.10, 0.0, 1.0);
                float toe = mix(0.006, 0.020, wear);
                float shoulder = mix(0.992, 0.958, wear);
                float yOut = clamp(toe + sCurve * (shoulder - toe), 0.0, 1.0);

                // Authentic museum Silver-Gelatin fiber paper tone (crisp cool-neutral D-max shadows + whisper-warm baryta silver highlights — NEVER sepia!)
                float shdW = (1.0 - yOut) * (1.0 - yOut);
                float hilW = yOut * yOut;
                vec3 paperTint = vec3(
                    yOut + hilW * 0.011 - shdW * 0.005,
                    yOut + hilW * 0.005,
                    yOut - hilW * 0.012 + shdW * 0.008
                );
                // Ultra-faint warm baryta aging only in ARCHIVE print
                if (wear > 0.65) {
                    float a = (wear - 0.65) / 0.35;
                    paperTint *= vec3(1.0 + 0.025 * a, 1.0, 1.0 - 0.045 * a);
                }
                return clamp(paperTint, 0.0, 1.0) * gateMask;
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
                    gl_FragColor = vec4(applyEra1950sMono(rawColor), 1.0);
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
    private var uFragStMatrixLoc: Int = -1
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
    private var uEraFilterLoc: Int = -1
    private var uBloomTextureLoc: Int = -1
    private var uTrailTextureLoc: Int = -1
    private var uHasOpticalFboLoc: Int = -1
    private var uEraGrainMulLoc: Int = -1
    private var uMotionBlendLoc: Int = -1
    private var uFilmSeedLoc: Int = -1
    private var uFilmTransportLoc: Int = -1
    private var uDefectALoc: Int = -1
    private var uDefectBLoc: Int = -1
    private var uViewportAspectLoc: Int = -1
    private var uEraGateAspectLoc: Int = -1

    // Phase 7.5 Offscreen Multi-Pass Optical Bloom + Temporal Motion-Blur FBO resources
    private var trailProgramId: Int = 0
    private var trailAPosLoc: Int = -1
    private var trailATexLoc: Int = -1
    private var trailUStLoc: Int = -1
    private var trailUTexLoc: Int = -1
    private var trailUAlphaLoc: Int = -1
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
    @Volatile var splitCompareFraction: Float = -1.0f // < 0.0 = OFF, 0.0..1.0 = Live RAW | LOOK vertical wipe
    @Volatile var eraModeCode: Int = 0 // 0 = SAVIOR, 1 = 1920s Silent 35mm, 2 = 1950s Monochrome 35mm
    @Volatile var eraWearLevel: Float = 0.0f // 0.0 = NITRATE Clean, 1.0 = ARCHIVE Aged
    @Volatile var eraFps: Float = 16.0f
    @Volatile var eraFilterCode: Int = 0
    @Volatile var eraGrainMultiplier: Float = 1.0f
    @Volatile var eraGateAspect: Float = 1.3333f

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
        if (eraModeCode > 0) {
            updateFilmTransportState(elapsedMs)
            if (opticalFbosReady) {
                runOpticalPrePassesOnGlThread()
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
        GLES20.glUniformMatrix4fv(uFragStMatrixLoc, 1, false, stMatrix, 0)

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
        val tex1Id = when {
            eraModeCode > 0 && filmAtlasTextureId != 0 -> filmAtlasTextureId
            lutReady -> lutTextureId
            else -> dummyLutTextureId
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex1Id)
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
        val useOptFbo = ((eraModeCode == 1 || eraModeCode == 2) && opticalFbosReady)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (useOptFbo) bloomTexAId else dummyLutTextureId)
        GLES20.glUniform1i(uBloomTextureLoc, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (useOptFbo) trailTexId else dummyLutTextureId)
        GLES20.glUniform1i(uTrailTextureLoc, 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform1i(uHasOpticalFboLoc, if (useOptFbo) 1 else 0)
        GLES20.glUniform1i(uEraFilterLoc, eraFilterCode)
        GLES20.glUniform1f(uEraGrainMulLoc, eraGrainMultiplier.coerceIn(0.45f, 2.2f))
        GLES20.glUniform1f(uMotionBlendLoc, computeDaylightMotionBlend())
        GLES20.glUniform4f(uFilmSeedLoc, filmSeedX1, filmSeedY1, filmSeedX2, filmSeedY2)
        GLES20.glUniform4f(uFilmTransportLoc, filmWeaveX, filmWeaveY, filmFlicker, filmScratchX)
        GLES20.glUniform4f(uDefectALoc, defectAx, defectAy, defectAr, defectAw)
        GLES20.glUniform4f(uDefectBLoc, defectBx, defectBy, defectBr, defectBw)
        GLES20.glUniform1f(uViewportAspectLoc, viewportAspect.coerceIn(0.4f, 4.0f))
        GLES20.glUniform1f(uEraGateAspectLoc, eraGateAspect.coerceIn(1.0f, 2.6f))

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
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
            uFragStMatrixLoc = GLES20.glGetUniformLocation(programId, "uFragStMatrix")
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
            uBloomTextureLoc = GLES20.glGetUniformLocation(programId, "uBloomTexture")
            uTrailTextureLoc = GLES20.glGetUniformLocation(programId, "uTrailTexture")
            uHasOpticalFboLoc = GLES20.glGetUniformLocation(programId, "uHasOpticalFbo")
            uEraFilterLoc = GLES20.glGetUniformLocation(programId, "uEraFilter")
            uEraGrainMulLoc = GLES20.glGetUniformLocation(programId, "uEraGrainMul")
            uMotionBlendLoc = GLES20.glGetUniformLocation(programId, "uMotionBlend")
            uFilmSeedLoc = GLES20.glGetUniformLocation(programId, "uFilmSeed")
            uFilmTransportLoc = GLES20.glGetUniformLocation(programId, "uFilmTransport")
            uDefectALoc = GLES20.glGetUniformLocation(programId, "uDefectA")
            uDefectBLoc = GLES20.glGetUniformLocation(programId, "uDefectB")
            uViewportAspectLoc = GLES20.glGetUniformLocation(programId, "uViewportAspect")
            uEraGateAspectLoc = GLES20.glGetUniformLocation(programId, "uEraGateAspect")
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

            // Lock sampler units immediately after program link (unit 0=OES, 1=LUT/Grain, 2=BloomFBO, 3=TrailFBO)
            GLES20.glUseProgram(programId)
            GLES20.glUniform1i(uTextureLoc, 0)
            GLES20.glUniform1i(uLutTextureLoc, 1)
            GLES20.glUniform1i(uBloomTextureLoc, 2)
            GLES20.glUniform1i(uTrailTextureLoc, 3)

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

    private fun updateFilmTransportState(elapsedMs: Long) {
        val fps = eraFps.coerceIn(12f, 60f).toDouble()
        val tick = ((elapsedMs / 1000.0) * fps).toLong()
        if (tick == lastFilmTick) return
        lastFilmTick = tick

        val wear = eraWearLevel.coerceIn(0f, 1f)

        // 1. Non-repeating 64-bit SplitMix64 texture shifts for silver-nitrate grain (period 2^64, never repeats!)
        filmSeedX1 = nextFilmRand01()
        filmSeedY1 = nextFilmRand01()
        filmSeedX2 = nextFilmRand01()
        filmSeedY2 = nextFilmRand01()

        // 2. Smooth intermittent-claw gate weave (0 in PREMIERE, gentle in VAULT, pronounced in FOUND)
        if (wear > 0.1f) {
            val targetWx = (nextFilmRand01() - 0.5f) * 0.0024f
            val targetWy = (nextFilmRand01() - 0.5f) * 0.0056f
            filmWeaveX = filmWeaveX * 0.40f + targetWx * 0.60f
            filmWeaveY = filmWeaveY * 0.35f + targetWy * 0.65f
        } else {
            filmWeaveX = 0f
            filmWeaveY = 0f
        }

        // 3. Exposure Transport: Hand-Crank breathing in 1920s vs Crystal-Sync Mitchell BNC motor in 1950s!
        if (eraModeCode == 2) {
            // 1950s motor-driven sync sound: 0 flicker in STUDIO/CRITERION, tiny ±0.6% carbon-arc lamp shimmer in ARCHIVE
            if (wear >= 0.75f) {
                filmBreathPhase += 0.22
                val arc = (kotlin.math.sin(filmBreathPhase).toFloat() * 0.004f) + ((nextFilmRand01() - 0.5f) * 0.006f)
                filmFlicker = filmFlicker * 0.70f + arc * 0.30f
            } else {
                filmFlicker = 0.0f
            }
        } else {
            filmBreathPhase += 0.28 + nextFilmRand01() * 0.14
            val crankSine = kotlin.math.sin(filmBreathPhase).toFloat() * 0.018f
            val crankJitter = (nextFilmRand01() - 0.5f) * 0.032f
            val breathAmp = when {
                wear < 0.15f -> 0.45f  // PREMIERE: tiny ±1.2% hand-crank life
                wear < 0.75f -> 1.00f  // VAULT: authentic ±3.2% breathing
                else -> 1.65f          // FOUND: ±5.2% aged projector/crank breathing
            }
            val targetFlicker = (crankSine + crankJitter) * breathAmp
            filmFlicker = filmFlicker * 0.55f + targetFlicker * 0.45f
        }

        // 4. Sparse Event-Driven Physical Film Defects (ZERO tiled dots!)
        defectAx = -9.0f
        defectBx = -9.0f
        if (wear < 0.15f) {
            // PREMIERE (1920s) / STUDIO (1950s): 100% spotless fresh lab release print
            filmScratchLife = 0
            filmScratchX = -2.0f
        } else {
            val isHeavy = wear >= 0.75f
            val is1950s = (eraModeCode == 2)
            val pDustA = if (is1950s) (if (isHeavy) 0.18f else 0.06f) else (if (isHeavy) 0.34f else 0.16f)
            val pDustB = if (is1950s) (if (isHeavy) 0.06f else 0.01f) else (if (isHeavy) 0.16f else 0.04f)
            val pScratch = if (is1950s) (if (isHeavy) 0.0042f else 0.0f) else (if (isHeavy) 0.0090f else 0.0035f)

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

            // Rare multi-frame drifting emulsion hairline scratch (12-26 frames, then 10-20s of clean footage!)
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
            trailProgramId = createProgram(OFFSCREEN_VERT_SHADER, ORTHO_TRAIL_FRAG_SHADER)
            blurProgramId = createProgram(OFFSCREEN_VERT_SHADER, GAUSS_BLUR_FRAG_SHADER)
            if (trailProgramId == 0 || blurProgramId == 0) return

            trailAPosLoc = GLES20.glGetAttribLocation(trailProgramId, "aPosition")
            trailATexLoc = GLES20.glGetAttribLocation(trailProgramId, "aTexCoord")
            trailUStLoc = GLES20.glGetUniformLocation(trailProgramId, "uStMatrix")
            trailUTexLoc = GLES20.glGetUniformLocation(trailProgramId, "uTexture")
            trailUAlphaLoc = GLES20.glGetUniformLocation(trailProgramId, "uTrailAlpha")
            trailUWeightsLoc = GLES20.glGetUniformLocation(trailProgramId, "uEraWeights")
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

    /**
     * Executes our 3 lightweight offscreen optical passes ONCE per camera frame (shared by Preview & MP4 Encoder):
     * 1. 512x256 Temporal Ortho + Daylight Rotary-Shutter Motion-Blur FBO (hardware alpha blend)
     * 2. 256x128 Horizontal 9-Tap Wide Gaussian Blur (stride 2 -> +-60px horizontal on 1080p)
     * 3. 256x128 Vertical 9-Tap Wide Gaussian Blur (stride 2 -> +-67px vertical on 1080p -> true circular Petzval glow!)
     */
    private fun runOpticalPrePassesOnGlThread() {
        // Offscreen Pass 1: OES Camera -> 512x512 trailFbo (Orthochromatic + Highlight Map + Daylight Rotary Trail)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, trailFboId)
        GLES20.glViewport(0, 0, 512, 512)
        val motionAmt = computeDaylightMotionBlend()
        val alpha: Float
        if (trailNeedsReset || motionAmt < 0.05f) {
            // Indoors / slow shutter: disable blending completely (zero 8-bit quantization ghost tail!)
            GLES20.glDisable(GLES20.GL_BLEND)
            alpha = 1.0f
            trailNeedsReset = false
        } else {
            // Bright daylight fast shutter (<1/120s): engage silky hardware rotary-shutter motion blend
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
        if (eraModeCode == 2) {
            when (eraFilterCode) {
                1 -> GLES20.glUniform3f(trailUWeightsLoc, 0.40f, 0.54f, 0.06f) // Y8 Yellow
                2 -> GLES20.glUniform3f(trailUWeightsLoc, 0.62f, 0.36f, 0.02f) // O21 Orange
                3 -> GLES20.glUniform3f(trailUWeightsLoc, 0.84f, 0.15f, 0.01f) // R25 Red
                4 -> GLES20.glUniform3f(trailUWeightsLoc, 0.14f, 0.78f, 0.08f) // G11 Green
                else -> GLES20.glUniform3f(trailUWeightsLoc, 0.28f, 0.58f, 0.14f) // CLEAR Panchromatic
            }
            GLES20.glUniform1f(trailUKneeLoc, 0.78f)
        } else {
            GLES20.glUniform3f(trailUWeightsLoc, 0.04f, 0.56f, 0.40f) // 1920s Red-Blind Orthochromatic
            GLES20.glUniform1f(trailUKneeLoc, 0.66f)
        }

        GLES20.glEnableVertexAttribArray(trailAPosLoc)
        GLES20.glVertexAttribPointer(trailAPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        GLES20.glEnableVertexAttribArray(trailATexLoc)
        GLES20.glVertexAttribPointer(trailATexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(trailAPosLoc)
        GLES20.glDisableVertexAttribArray(trailATexLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        // Compute true physical aspect-compensated blur steps so Petzval glow is 100% circular on every sensor!
        val stSwapped = kotlin.math.abs(stMatrix[1]) > kotlin.math.abs(stMatrix[0])
        val rot0W = (if (stSwapped) videoHeight else videoWidth).coerceAtLeast(16).toFloat()
        val rot0H = (if (stSwapped) videoWidth else videoHeight).coerceAtLeast(16).toFloat()
        val minDim = minOf(rot0W, rot0H)
        // 1920s Uncoated Petzval = wide 4.4% short-edge bloom; 1950s Single-Coated Cooke Speed Panchro = tight 2.2% silver halation!
        val stepPx = minDim * (if (eraModeCode == 2) 0.0055f else 0.0110f)
        val blurStepU = stepPx / rot0W
        val blurStepV = stepPx / rot0H

        // Offscreen Pass 2: U-axis 9-Tap Wide Gaussian -> 256x256 bloomFboB
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

        // Offscreen Pass 3: V-axis 9-Tap Wide Gaussian -> 256x256 bloomFboA
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
