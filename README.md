# IDGAF CAMERA (`v0.1.0` — Phase 1)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli**.

## Phase 1 Features (Live Now)
- **Native Camera2 Cinema Engine (`CinemaCameraEngine.kt`):** Direct hardware pipeline built for manual cinema controls in upcoming phases.
- **Landscape Pro HUD:** Full-screen immersive landscape viewfinder with Safety Orange (`#FF5F1F`) telemetry, live `00:00:00` timecode, and tap-to-focus reticle.
- **Multi-Lens Switcher:** Automatically detects hardware focal lengths (`0.5x` Ultra-Wide, `1x` Wide, `2x` Tele, `FR` Front).
- **Resolution & FPS Control:** Switch between `1080P` (24 Mbps) and `4K` (50 Mbps) at `24 / 30 / 60 FPS` with automatic hardware fallback.
- **Direct MediaStore Save:** Saves crisp H.264 `.mp4` + 48kHz AAC audio directly to `Movies/IDGAF_Camera` with 1-tap **CLIP** playback.
