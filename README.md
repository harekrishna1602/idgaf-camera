# IDGAF CAMERA (`v0.4.0` — Phase 4: Pro Audio, Codec, Bitrate & Tap-to-Type)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli**.

## What's New in Phase 4 (`v0.4.0`)
- **Live Audio VU Meter:** Real-time 14-segment green/yellow/red audio meter in the top HUD bar (monitors live mic when idle and MediaRecorder amplitude while recording).
- **Pro Audio & Codec Bar (`PRO`):**
  - **Codec Toggle:** Switch between `H.264` and `HEVC (H.265)`.
  - **Custom Bitrate:** Cycle `24 / 35 / 50 / 75 / 100 / 120 Mbps` for clean, artifact-free footage.
  - **External Mic Routing:** Toggle `MIC: AUTO / INT / EXT` to route wired lavs or USB condenser mics (API 28+).
- **Tap-to-Type Exact Values:** Tap the orange value readout in the ISO, Shutter, WB, or Focus drawer to type exact numbers (e.g., `800` ISO, `1/48`, `5600K`).
- **Fixed Tap-to-Focus:** Properly maps landscape touch coordinates to Camera2 sensor coordinates and executes `AF_TRIGGER_CANCEL → START` on the background camera thread.
