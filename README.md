# IDGAF CAMERA (`v0.7.6.1` — Global 50Hz/60Hz Anti-Flicker Shutter Engine, Quick Shutter Presets & Centered 9:16 Portrait Dialog)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed in `v0.7.6.1`
- **Global Anti-Flicker Shutter Engine (`50Hz` / `60Hz` / `OFF`) across SAVIOR, Auto Pilot, 1920s & 1950s:**
  - Auto-defaults to **`50Hz`** in 50Hz regions (India/EU/Asia/AU/Africa) and **`60Hz`** in 60Hz regions (US/Canada/etc.), persisted across sessions.
  - Sends explicit **`CONTROL_AE_ANTIBANDING_MODE_50HZ` / `60HZ`** directly to Camera2 hardware AE (fixing Auto Exposure indoor banding & erratic ISO spikes).
  - **Auto Pilot, `180°` Lock, and Era `ASA` / `LOCK 180°`** now snap strictly to flicker-free AC multiples (**`1/50s`**, **`1/100s`**, **`1/33s`**, **`1/25s`** on `50Hz`; **`1/60s`**, **`1/120s`**, **`1/30s`** on `60Hz`) and re-solve exact ISO so brightness stays identical with zero rolling-shutter banding.
- **Upgraded Shutter Drawer (`SHUTTER`):**
  - Added **`50Hz`/`60Hz`/`OFF`** Anti-Flicker toggle and **`✎`** (pencil button for exact custom shutter speed/angle) in the header bar.
  - Added 1-tap **Quick Shutter Preset Chips** below the slider (`1/25 | 1/50 | 1/100 | 1/200 | 1/500 | 1/1000` in `50Hz`, or `45° | 90° | 144° | 172.8° | 180° | 270° | 360°` in `ANG°` mode), plus `50Hz`/`60Hz` toggle inside Era's `ASA` drawer.
- **Dead-Center Rotated `9:16 FULL PORTRAIT` Dialog:** Replaced the clipped `AlertDialog` container with a full-screen translucent `Dialog` sized cleanly to 68% of landscape height so the upright grip-cue card sits dead-center with zero cut-off.
