# IDGAF CAMERA (`v0.6.4` — Core Engine Fixes: Resolutions, WB, Auto-Tap & mm Lenses)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.6.4`
- **Hardware-Verified Resolution Picker:** Tap the Resolution pill to choose from **`480P`**, **`720P`**, **`1080P`**, **`2K`**, and **`4K`**. Automatically checks your active lens's real `MediaRecorder` hardware capabilities and warns you if `4K` (or another resolution) is not supported.
- **Fixed Kelvin White Balance:** Passes an explicit identity `ColorSpaceTransform` alongside RGGB gains so Android ISPs apply manual Kelvin (`2500K–8500K`) accurately in real time.
- **Fixed Auto Tap-to-Focus & Tap-to-Meter:** Tapping anywhere on the viewfinder now fires `CONTROL_AE_PRECAPTURE_TRIGGER_START` + `CONTROL_AF_TRIGGER_START` so Auto Exposure and Auto Focus react immediately in preview—no longer waiting for REC—and smoothly resumes continuous video tracking.
- **Focal-Length `mm` Lens Labels:** Lenses on the left rail are now labeled by their 35mm-equivalent focal length (e.g., `24mm`, `13mm`, `52mm`).
