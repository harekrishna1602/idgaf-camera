# IDGAF CAMERA (`v0.7.7.2` — Pre-v1.0 Step 1)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.7.2` (Step 1 of 2)
- **Unified Frame Ratio + True `CROP: REC` in `VIDEO FORMAT`:**
  - Moved Frame Ratio out of `MON` into `VIDEO FORMAT` (`16:9 | 2.39:1 | 1.85:1 | 4:3 | 1:1 | 9:16`) and removed redundant landscape crop buttons.
  - Toggle **`GUIDE ONLY`** (framing lines, records full `16:9`) or **`CROP: REC`** (crops and saves the MP4 directly in your exact chosen ratio — Mod-16 hardware-safe with automatic burned-matte fallback on strict encoders).
  - Dedicated **`9:16 PORT`** button stays right alongside for full-resolution upright portrait shooting.
- **Live Output Monitor (`MON → OUT`) v2:**
  - **Upright Portrait Rotation:** Automatically rotates `-90° / +90°` when `9:16 PORT` is active so it stands upright when holding the phone vertically.
  - **Exact Cropped Aspect:** Matches your exact `CROP: REC` aspect ratio live (`2.39:1`, `1.85:1`, `4:3`, `1:1`, `9:16`, or `16:9`).
  - **Resizable Corner (`⤡`):** Drag the card body to move anywhere, or drag the bottom-right `⤡` handle to scale the monitor smoothly (`0.75x – 1.85x`).
- **Stealth Roadmap Slot:** Locked future workflow labeled **`CLASSIFIED`** in the workbench drawer.
