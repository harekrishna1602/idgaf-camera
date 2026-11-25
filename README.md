# IDGAF CAMERA (`v0.7.7` — SAVIOR Core Stable + Drop-In Era Plug-In Slot)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.7` (Turn 2 — Modular Core Freeze)
- **Clean, Lean SAVIOR Core (`0` Hardcoded Era Code):** Stripped all inline 1920s/1950s shaders, views, and state out of `MainActivity.kt`, `CinemaGlRenderer.kt`, `CinemaCameraEngine.kt`, and `activity_main.xml`.
- **Zero-Touch Drop-In Plug-In Architecture (`era/EraCameraPlugin.kt` + `era/EraPluginRegistry.kt`):**
  - Dynamic GPU shader loader on `IdgafGlThread` (compiles any plugin's OpenGL ES 2.0 fragment shader on-demand with shared grain atlas, monochrome or full-color optical bloom/trail FBOs, and 64-bit transport engine).
  - Self-contained plugin HUD mount (`eraPluginContainer`) + clean `EraHostBridge` API.
  - Adding future cameras (**1920s**, **1950s**, **1970s**, **1980s VHS**, **2000s DV**, **2008 DSLR**, **PURIST 65mm**, **GTA Creator**) requires dropping **only 1 new `.kt` file** into `era/` — **zero frozen core files are ever touched again**.
- **All SAVIOR Core Features Included & Intact:** Global `50Hz/60Hz/OFF` Anti-Flicker engine, Quick Shutter presets + `✎`, EV manual lock + GPU overdrive past `+1.0`, custom Clean-Frame vector icon, orange `ERA ▾` pill, green `IDGAF CAM ↑` badge, 12 3D LUTs + `.cube` import, live Split-Screen wipe, True `9:16` Vertical Recording, Clip Vault v2 + Lossless `STORED` ZIP Archiver.
