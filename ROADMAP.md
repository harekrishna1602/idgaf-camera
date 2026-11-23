# IDGAF CAMERA — Phased Roadmap & Logged Notes

- **Phase 1–6.7 (`v0.1.0` – `v0.6.7`) — DONE:** Native Camera2 Engine, Full Manual ISO/Shutter/WB/Focus/EV, 180° Shutter Angle, A/B Rack Focus, Focus Peaking/Zebras/Histogram/Horizon Level, Zoom Engine v2 (`T`/`W`, `LIN/EXP/CRASH` curves, A→B Zoom Pull), Live AWB Kelvin, H.264/HEVC, Smart Bitrate (4–120 Mbps), Presets, Studio Mogli Credits & Silent Updater.
- **Phase 6.8 (`v0.6.8`) — DONE:** Clip Vault v2 (Custom Folders, Multi-Select, Bulk Move/Share/Delete) + Pro Audio Controller Drawer (Hardware Preamp `CAM/RAW/BOOST`, Mute Toggle, Live dBFS Readout & VU Trim).
- **Phase 6.9 (`v0.6.9`) — NEXT (Camera2 Pro-Grade Color, ISO & Lock Overhaul):**
  - **EV / Exposure Respects `LOCK`:** Stop silently unlocking `AE/AF LOCK` when EV is touched; block changes and show a clear notification instead.
  - **Fix Greenish Manual White Balance:** Capture and reuse the sensor's real ISP `COLOR_CORRECTION_TRANSFORM` matrix (never a raw Identity Matrix) + calibrated WB gain math + Tint (`-50..+50`) trim so matching AWB Kelvin matches the exact color without any green cast.
  - **Full Extended Manual ISO (`CONTROL_POST_RAW_SENSITIVITY_BOOST`):** Unlock full brightness above the sensor's analog ISO cap (e.g., `1594` → `6400+`) using Camera2 post-RAW digital gain boost—matching how Blackmagic Camera / FiLMiC Pro / MotionCam reach Auto-level brightness in manual mode.
  - **Stop Auto Focus Slider Jitter:** Keep the Focus slider parked cleanly when in AF-C (no random micro-hunting thumb jumps).
- **Phase 7 (`v0.7.0+`) — ERA MODE & Color Pipeline:**
  - **7.1 (`v0.7.0`):** OpenGL ES Shader Pipeline (bakes looks into preview + `.mp4`), FiLMiC-Style Color Control Map (Saturation, Contrast, Shadows/Highlights, Tint), Expanded LUTs + `.cube` Loader, and `PRO` / `ERA` Mode Switcher.
  - **7.2 (`v0.7.1`):** Satyajit Ray 35mm Monochrome Era (`1.37:1` Academy gate, silver-halide contrast, 35mm grain).
  - **7.3 (`v0.7.2`):** OG 80s/90s VHS Camcorder & 2000s MiniDV CCD look.
  - **7.4 (`v0.7.3`):** 16mm Indie & 70s Warm Film Halation.
- **Phase 8 (`v0.8.0+`) — ARC 3 (PURIST / GATE 65 MODE):**
  - ISP Kill Switch ("No-Lie Mode"), Open-Gate `1.43:1` + Dual-Extraction Frame Lines, 3-Minute Film Mag (`1000 FT → 0 FT`) & Dailies Lock, Committed Film Stocks (`250D / 500T / Double-X`), Whisper-Dialogue Audio, Blind Volume Controls & Inversion Tool.
