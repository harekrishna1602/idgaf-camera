# IDGAF CAMERA (`v0.6.14` — Final `0.6.x` Production Milestone)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New & Fixed in `v0.6.14` (Final `0.6.x` Polish Before Phase 7)
- **FiLMiC-Style 2D White Balance × Tint Gradient Map (`WbGradientPadView`):** Replaced the separate WB & Tint sliders with a tactile 2D color map (`2500K–8500K` cool-to-warm horizontal axis × `-50..+50` green-to-magenta vertical axis) backed by an atomic Camera2 `setManualWhiteBalance(kelvin, tint)` call, plus 1-tap cinema presets (**`3200K` / `4300K` / `5600K` / `6500K`**), 1-tap **`TINT 0`** reset, and exact **Tap-to-Type** Kelvin.
- **Isotropic `MON` Scope Sampling + Animated Marching Zebras:** Switched the off-thread scope buffer to aspect-matched **`108×192`** (`9:16` matching the raw Camera2 `SurfaceTexture`) so rotated landscape Peaking, Zebras, and False Color render with **1:1 square pixels** instead of stretched stripes—plus animated marching diagonal Zebra stripes.
- **Auto Pilot Recording Guards (`PILOT` Audit Fixes):**
  - **Instant `SYNC…` → `REC` Snap-Lock:** Tapping REC while `PILOT` is still converging immediately locks Exposure + WB before `MediaRecorder` starts—zero mid-take brightness or color jumps.
  - **Mid-Recording Protection:** Tapping or long-pressing `PILOT` while actively rolling is cleanly blocked (with dimmed visual cue) so an accidental thumb bump can never flash a live take.
  - **Framing & Light Safe:** `PILOT` keeps your active Zoom framing and dark-room Torch intact while rescuing Exposure, WB, Tint, EV, Focus (`AF-C`), Lock, Tone, Stabilization, Timer, and Audio Mute.
