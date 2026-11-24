# IDGAF CAMERA (`v0.7.2.4` — Part 1: Fixed LOG, World-Class Look Presets, Smart Pilot v2 & Google Exemption)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.2.4` (Part 1 of 2)
- **Fixed Double-`LOG` Bug in `LOOK`:** Removed hidden hardware `ToneProfile.FLAT` stacking so `LOG` applies cleanly once in the GPU pipeline—eliminating milky washout and making **`RAW`** compare 100% accurately against true unmodified sensor output.
- **Upgraded Quick `LOOK` Buttons (`NONE / LOG / FILM / VIVID / MONO`):** Replaced basic starter presets with calibrated cinema grading foundations designed to stack cleanly with the 12 World-Class 3D LUTs.
- **Sleeker `LOOK` Header (`RAW | RESET | ✕`):** Renamed `HOLD RAW` to compact **`RAW`** and added a dedicated **`✕`** Close button side-by-side (ready for **`SPLIT`** in Part 2 `v0.7.2.5`).
- **Smart Auto Pilot v2 (`PILOT`) + Low-Light Flashlight Prompt:**
  - Automatically re-balances metered brightness toward the **180° cinema shutter rule** (`1/60s` at `30 FPS`, `1/48s` at `24 FPS`) with lowest-noise ISO, base-ISO daylight lock, and smooth `1/fps` low-light protection (zero exposure jump).
  - Detects dark scenes (`ISO ≥ 1600`) and asks **"Low Light Detected — Turn on Flashlight?"** with 1-tap **`TORCH ON & RE-METER`** so exposure re-balances automatically for the LED light.
  - Warns if free storage is below `0.5 GB`.
- **Updated IDGAF License (`LICENSE.md` + Credits):** Added a permanent **FREE FOREVER** exemption for **Alphabet Inc.**, **Google LLC**, and all of their wholly owned subsidiaries worldwide.
