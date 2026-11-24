# IDGAF CAMERA (`v0.7.0` — Phase 7.1: Dual-Surface OpenGL ES GPU Pipeline & 60 FPS Native GPU Scopes)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 + OpenGL ES 2.0** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.0` (Phase 7.1 — GPU Engine)
- **Dual-Surface OpenGL ES 2.0 GPU Renderer (`CinemaGlRenderer.kt`):**
  - Camera2 streams a single continuous `TEMPLATE_RECORD` feed into a hardware OES texture on a dedicated `IdgafGlThread`.
  - **Pass 1 (Preview Surface):** Renders full-bleed center-cropped viewfinder directly to `TextureView` at full display resolution with zero stretching and instant 180° reverse-landscape rotation.
  - **Pass 2 (Hardware Video Encoder):** Attaches `MediaRecorder.surface` directly as a second EGL window surface with exact hardware timestamps (`EGLExt.eglPresentationTimeANDROID`)—enabling **zero-flicker instant REC start/stop** with no Camera2 session teardown and guaranteed clean `.mp4` recordings (zero scopes baked into video).
- **60 FPS Native-Resolution GPU Monitoring Scopes (FiLMiC Pro / Blackmagic Sharpness):**
  - **1-Pixel 3×3 Sobel Focus Peaking (`PEAK`):** Razor-sharp, zero-lag focus edges at native display resolution (Green / Red / White / Cyan + Low / Med / High sensitivity).
  - **Animated Marching 45° Diagonal Zebras (`ZEBRA`):** Crisp screen-space diagonal stripes scrolling smoothly at 60 FPS (`70% SKIN` yellow, `92% HIGH` red, `100% CLIP` hot red).
  - **Full-HD Rec.709 False Color (`FALSE`):** Instant, zero-lag ARRI/SmallHD IRE exposure mapping across every native pixel with zero CPU load.
- **0% CPU Scope Overhead:** CPU polling now runs ONLY when `HIST` (RGB + Luma Histogram) is active, using a tiny `64×36` sample.
