# IDGAF CAMERA (`v0.7.0.3` — Precision Laplacian Focus Peaking)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 + OpenGL ES 2.0** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.0.3`
- **Precision Laplacian × Coherent-Gradient Focus Peaking (`CinemaGlRenderer.kt`):**
  - Replaced basic Sobel edge detection with an 8-neighbor High-Frequency Laplacian sharpness filter gated by coherent gradient energy—using the exact same 9-tap GPU texture reads at full 60 FPS.
  - **Out-of-focus objects go completely clean** (zero lingering blurry outlines), **high-ISO wall noise is filtered out**, and tack-sharp focus planes snap brightly into view as you rack focus.
  - Recalibrated `LOW` (`0.24`), `MED` (`0.15`), and `HIGH` (`0.095`) sensitivity presets in `CinemaOverlayView.kt`.
- **Zero Risk to Recording Pipeline:** Recording, clock sync, audio handoff, and rotation remain untouched from verified `v0.7.0.2`.
