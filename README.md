# IDGAF CAMERA (`v0.7.1` — Phase 7.2A: Live GPU Color Control Studio & Front Mirror Toggle)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 + OpenGL ES 2.0** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.1` (Phase 7.2A)
- **Live 60 FPS GPU Color Studio (`LOOK` Pill):**
  - **6 Real-Time GPU Grading Sliders** baked simultaneously into the viewfinder **and** recorded `.mp4`:
    - `EXP` (`-1.5` to `+1.5` EV GPU Exposure Lift — pushes brightness cleanly beyond hardware video AE caps)
    - `CON` (`0.50` to `1.60` Midpoint Contrast)
    - `SAT` (`0.00` Monochrome to `1.80` Vivid Saturation)
    - `SHD` (`-50` to `+50` Smooth Quadratic Shadows)
    - `HIL` (`-50` to `+50` Smooth Quadratic Highlights)
    - `VIG` (`0%` to `85%` Elliptical Cinema Lens Vignette)
  - **5 Built-In Starter Looks:** `NONE`, `LOG` (flat grading base), `CINE`, `PUNCH`, and `NOIR` (high-contrast monochrome)—all tweakable live with the sliders below.
  - **Hold-to-Compare (`HOLD RAW`):** Press and hold to compare raw ungraded camera vs. your live grade; releasing always snaps back to graded so you never ruin a take by accident. Tap any readout to reset that single slider, or `RESET` to zero all.
  - **Scope-Smart Pipeline:** Laplacian Focus Peaking (`PEAK`) always reads raw ungraded optical sharpness, while `ZEBRA`, `FALSE`, and `HIST` accurately monitor your live graded output.
- **Front-Camera Mirror Export Toggle (`PRO` → `MIRROR: OFF/ON`):** Choose natural un-mirrored front MP4s (default) or mirrored selfie MP4s.
- **Full Preset Persistence:** Saved shooting templates now store and restore all 6 GPU color sliders and your front mirror preference.
