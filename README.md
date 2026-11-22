# IDGAF CAMERA (`v0.6.1` — Phase 6.1)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli**.

## What's New in `v0.6.1`
- **Button-Controlled Rotation (`LAND / PORT`):** Removed automatic rotation; tap **`LAND / PORT`** in the top bar to switch orientation intentionally when needed.
- **Unstretched Viewfinder Framing:** Fixed proportional aspect-ratio scaling so Portrait and Landscape video never stretch.
- **Toast Cooldown Timer:** Added a `1.8s` cooldown on status toasts (such as fixed-focus lenses) so rapid taps never spam popups.
- **Reliable FPS Lock + Tap-to-Type FPS:** Long-press **`FPS`** to type any frame rate (`15–120 FPS`), and tap to cycle `24 / 30 / 60 FPS` without auto-downgrading.
- **Steady Focus (No Auto-Hunting):** Removed automatic focus hunting/rotation during recording.
