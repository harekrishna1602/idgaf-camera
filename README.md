# IDGAF CAMERA (`v0.7.1.1` — Audit Release: GPU Frame Pacer, Peek-Through LOOK Grading & Muted REC Guard)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 + OpenGL ES 2.0** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed & Upgraded in `v0.7.1.1`
- **True GPU Frame-Pacer for Custom / Low FPS (`1–60 FPS`):**
  - `CinemaGlRenderer` now gates Pass 2 (encoder) to exact `1_000_000_000L / targetFps` cadence—so `1 FPS` records **1 frame/sec** (5 frames in a 5-second clip) with real-time synced audio while keeping the live viewfinder smooth at 30 FPS.
  - Automatically checks active lens `maxHardwareFps` (clamping `60 FPS` cleanly if the hardware lens caps at `30 FPS`) and aligns `180°` shutter math with `targetFps`.
- **Peek-Through `LOOK` Grading Mode:** Holding/dragging any slider inside `LOOK` hides the panel background, header, presets, and all other rows cleanly (`View.INVISIBLE` so touch position never jumps), leaving **only that single slider + name + live value** floating over the full unobstructed camera frame. Releasing brings the studio drawer right back.
- **`PILOT` Full Look Reset:** Auto Pilot now resets `LOOK` completely back to `NONE` (clearing `NOIR`, `CINE`, `LOG`, and all 6 sliders) alongside exposure, WB, focus, and audio.
- **Unified `LOOK` Studio & Clean `PRO` Bar:** Removed redundant `STD/FLAT/CINE` from `PRO` (`LOOK → LOG` now auto-engages hardware `ToneProfile.FLAT`), renamed `EXP` → `LIFT` inside `LOOK` to distinguish from Bottom Dock hardware `EV`.
- **Unified Pro Audio Drawer & Muted REC Guard:** Moved `MIC` input selector (`AUTO / INT / EXT`) from `PRO` into the VU Meter Audio Drawer, added a red-bordered `MUTED` alert state to the top-bar VU pill, and added a pre-record **`UNMUTE & REC` / `REC SILENT` / `Cancel`** safety prompt when tapping `REC` while muted.
- **Preset Restore Fixes:** Loading a template now clears `AE/AF LOCK` first and restores `180°` shutter lock state accurately.
