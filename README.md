# IDGAF CAMERA (`v0.2.0` — Phase 2: Full Manual Pro Controls)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli**.

## What's New in Phase 2 (`v0.2.0`)
- **Full Manual Cinema Dock:** Live bottom telemetry bar with tactile floating sliders for **ISO**, **SHUTTER**, **WB (2500K–8500K)**, **FOCUS**, and **AE/AF LOCK**.
- **180° Shutter Rule Lock:** 1-tap `180°` button locks shutter speed to `2x FPS` (`1/48s` at 24fps, `1/60s` at 30fps, `1/120s` at 60fps) for natural cinema motion blur.
- **Smooth A/B Rack Focus:** Save Focus Point **A**, Save Focus Point **B**, and tap **PULL A→B** for a smooth 1.2s motorized cinema focus pull.
- **Fixed Record Exposure Burst:** Captures live sensor ISO & shutter before switching to `TEMPLATE_RECORD`, eliminating the auto-brightness flash when hitting REC.
