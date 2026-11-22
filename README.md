# IDGAF CAMERA (`v0.6.0` — Phase 6: Broadcast Servo Zoom Rocker, Clip Gallery & Auto-Rotation)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli**.

## What's New in Phase 6 (`v0.6.0`)
- **Broadcast Servo Zoom Rocker (`ZOOM`):** Tactile hold-to-zoom **`W −`** and **`T +`** servo rocker buttons with selectable **`SLOW / MED / FAST`** motor speed, 1-tap **`1x / 2x / 5x`** presets, precision slider, and pinch-to-zoom—powered by hardware `CONTROL_ZOOM_RATIO` (with `SCALER_CROP_REGION` fallback).
- **In-App Clip Gallery (`CLIPS`):** Browse all clips saved in `Movies/IDGAF_Camera` with video thumbnails, duration, file size, and 1-tap **PLAY**, **SHARE**, and **DEL**.
- **Full Portrait + Landscape Auto-Rotation:** Smoothly adapts the Camera2 viewfinder transform and HUD when rotating between Portrait (Reels/Shorts) and Landscape (Cinema).
- **Build Fix Baked In:** Fixed `toneProfile` JVM setter clash in `CinemaCameraEngine.kt`.
