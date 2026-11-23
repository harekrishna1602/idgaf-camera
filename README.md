# IDGAF CAMERA (`v0.6.9` — Pro Color/WB Fix, Extended ISO, Lock Guard & Fixed Audio Box)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.6.9`
- **Fixed Audio VU Box Width Jitter:** Locked `txtAudioDb` to a fixed `36dp` monospace slot so the top-bar Audio VU pill never expands or shrinks when switching between 1-digit (`-5dB`), 2-digit (`-24dB`), `CLIP`, and `-∞`.
- **Calibrated Manual White Balance + Tint Slider (`-50` to `+50`):** Captures and reuses your sensor's real ISP `COLOR_CORRECTION_TRANSFORM` 3×3 color matrix (instead of a greenish raw identity matrix) and anchors Kelvin gains to your lens's live AWB calibration. Matching AWB's Kelvin number now matches the exact color with zero green cast, plus you get a dedicated Green/Magenta **TINT** trim inside the WB drawer.
- **Extended Manual ISO (`CONTROL_POST_RAW_SENSITIVITY_BOOST`):** Unlocks brightness above the sensor's analog ISO cap (e.g., `1594` → `6400+`) using hardware post-RAW boost on a logarithmic Blackmagic-style ISO slider, so manual ISO matches Auto ISO's full low-light brightness.
- **Strict AE/AF Lock Protection:** Adjusting `EV`, `ISO`, or `SHUTTER` while `LOCK` is active no longer silently unlocks it—instead it stays locked and alerts you to turn off `LOCK` first.
- **Zero Focus Slider Jitter in `AF-C`:** The Focus slider now stays parked cleanly during continuous autofocus instead of jumping randomly on micro-hunts.
