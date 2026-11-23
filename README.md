# IDGAF CAMERA (`v0.6.10` — Run 1: `MON` Tab Overhaul)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.6.10` (Run 1 — `MON` Tab Overhaul)
- **Zero-Lag Background Scope Engine:** Moved Focus Peaking, Zebras, Histogram, and False Color analysis onto a dedicated background `HandlerThread` with pre-allocated reusable buffers and double-buffered Bitmaps—zero UI-thread stutter even with all scopes active.
- **Customizable Focus Peaking (`PEAK`):** Tap to toggle ON/OFF; long-press `PEAK` (or tap `CFG`) to choose **Color** (`GREEN / RED / WHITE / CYAN`) and **Sensitivity** (`LOW / MED / HIGH`).
- **Customizable Zebras (`ZEBRA`) + False Color (`FALSE`):**
  - Long-press `ZEBRA` (or tap `CFG`) to switch between **`70% (SKIN)`** (yellow skin-tone exposure band), **`92% (HIGH)`**, and **`100% (CLIP)`**.
  - Tap **`FALSE`** for a real-time cinema **IRE False Color** exposure map with a bottom IRE legend strip (`BLK | SHD | 41% | SKIN | HI | CLIP`).
- **Expanded Cinema Frame Guides (`FRAME`):** Added **`1.85:1`**, **`4:3`**, and **`1:1`** alongside `2.39:1`, `16:9`, and `9:16`.
- **Reverse-Landscape-Safe Horizon Level (`LEVEL`):** Fixed 180° landscape tilt inversion and added a live center degree readout pill (`0.0°` green when level, Safety Orange when tilted).
- **Non-Colliding Luma Histogram (`HIST`):** Relocated to the top-right below the telemetry bar with quarter-stop guides and a red highlight-clipping indicator dot—clear of all drawers and buttons.
