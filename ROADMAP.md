# IDGAF CAMERA — Savior Cinema Engine Roadmap

- **Phase 1–7.6 (`v0.1.0` – `v0.7.6.2`) — DONE:** Complete SAVIOR Pro Cinema Rig (Camera2 Manual ISO/Shutter/WB/Focus, Global 50Hz/60Hz Anti-Flicker, Auto Pilot, 60 FPS GPU Scopes, 12 Cinema 3D LUTs + `.cube` import, Live Split-Screen Wipe, True 9:16 Vertical Recording, Time-Lapse, Custom Self-Timer, Clip Vault v2 + Lossless ZIP Archiver, 100% Dark Cinema UI).
- **Phase 7.7 (`v0.7.7` — Turn 2) — DONE (SAVIOR Core Freeze + Drop-In Plug-In Architecture):** Stripped all hardcoded Era code from core files; installed `EraCameraPlugin` + `EraPluginRegistry` auto-discovery + dynamic GLSL plugin shader slot + `eraPluginContainer` HUD mount.
- **Turn 3 — NEXT:** Drop in standalone `era/Era1920sPlugin.kt` (1920s Silent 35mm — 1 file, 0 core edits).
- **Turn 4 — NEXT:** Drop in standalone `era/Era1950sPlugin.kt` (1950s Monochrome 35mm with truly distinct Wratten optical glass filters — 1 file, 0 core edits).
- **Turn 5+ — PLANNED:** Drop in `Era1970sPlugin.kt`, `Era1980sVhsPlugin.kt`, `Era2000sDvPlugin.kt`, `Era2008DslrPlugin.kt`, `Purist65Plugin.kt` (65mm / 1.43:1 Celluloid Workflow), and `GtaCreatorPlugin.kt` (Stylized Creator Mode + OBS Webcam Bridge) — strictly 1 isolated file per camera.
