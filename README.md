# IDGAF CAMERA (`v0.7.4.1` — Zero-Triangle Film Grain, 1950s FPS Control & Mid-Recording Lock Audit)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed & Upgraded in `v0.7.4.1`
- **Eliminated Repeated Triangle / Diamond Grain Pattern (Both `1920s` & `1950s`):**
  - Enabled `highp` float precision in fragment shaders (`#ifdef GL_FRAGMENT_PRECISION_HIGH`) and replaced `hash12` with a strictly bounded, non-tiling split-modulo **`filmHash()`** + dual-tap Gaussian **`organicGrain()`** that stays 100% artifact-free even under strict mobile FP16 (`mediump`) GPUs.
- **Full Mid-Recording Film Magazine Lock (`1920s` & `1950s`):**
  - Locked **`PRINT` (`FRESH` ↔ `ARCHIVE`)**, **`FPS` / `CRANK`**, **`TURRET`**, and camera switches while rolling (with subtle visual dimming) so film stock and optical audio DSP stay 100% consistent from start to cut, while **`IRIS`** and **`FOCUS`** remain live for real-time pulls.
- **Dedicated `FPS` Control in `1950s Classic Monochrome`:**
  - `1950s` now displays **both `FPS`** (`18` / `24` [Sync] / `25` [Telecine] / `30 FPS`) **and `TURRET`** (`28mm` / `35mm` / `50mm`) in a clean 5-pill bottom deck, with dynamic footage counting (`FPS / 16 ft/s`), dynamic shader frame ticks, and live **`DEVELOPING...`** status while the 1950s Optical Mono track finalizes.
