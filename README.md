# IDGAF CAMERA (`v0.7.2.2` — Bipolar Vignette & Pro Time-Lapse Engine)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New & Fixed in `v0.7.2.2`
- **Bipolar Black ↔ White Optical Vignette (`LOOK → VIG`):**
  - Fixed Vignette so it works in both directions just like Lightroom & Premiere: slide left (**`-85%` to `0%`**) for classic dark/black cinema lens falloff, center (**`0%`**) for neutral, and slide right (**`0%` to `+85%`**) for dreamy bright/white corner fade.
  - Built-in looks (`CINE`, `PUNCH`, `NOIR`) use dark black cinema vignette (`-28%`, `-14%`, `-42%`), and tapping the value readout snaps cleanly back to `0%`.
- **Pro Time-Lapse & Intervalometer Engine (`PRO → TL`):**
  - Capture 1 frame every **`0.5s / 1s / 2s / 5s / 10s / 30s`** (or any custom interval from `0.5s` to `60s`) and bake compressed-timestamp frames directly into a silky-smooth **`24 FPS` or `30 FPS` `.mp4`** (video-only, zero audio drift).
  - Live top-bar counter displays captured frames and exact output playback duration (**`TL 45f • 00:01.5`**).
  - Full live **`LOOK`** grading, Bipolar Vignette, and **`.cube` 3D LUTs** bake automatically into every Time-Lapse frame.
  - Tapping **`PILOT`** cleanly resets Time-Lapse back to `OFF`.
