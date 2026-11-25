# IDGAF CAMERA (`v0.7.5.3` — True 9:16 Vertical Recording, Unified Format Drawer, Top-Left Histogram, 6 Cinema Grids & 100% Dark Cinema UI)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Upgraded in `v0.7.5.3`
- **True 9:16 Vertical Video Recording (UI Stays Locked in Landscape):**
  - **`9:16 CROP` (Format Drawer):** Hold the phone normally in landscape, frame inside the live `9:16 CROP REC` vertical box, and the GPU crops and saves a genuine upright `9:16` vertical MP4.
  - **`9:16 PORT` (Format Drawer):** Hold the phone upright while the HUD stays locked cleanly — records the full camera sensor natively as a full-resolution `9:16` portrait MP4 (with automatic hardware-safe encoder fallback for MediaTek/Snapdragon devices).
  - **`FRAME: 9:16` (MON Drawer):** Guide-only framing overlay when you want to keep a full `16:9` master file.
- **Smarter Control Grouping (`VIDEO FORMAT` vs `PRO`):**
  - Moved **Bitrate (`4M–100M`)** and **Codec (`H.264/HEVC`)** into the **VIDEO FORMAT** drawer so changing resolution visibly updates bitrate right in the same panel.
  - Streamlined **PRO** into 2 clean 3-button rows (`STAB | TORCH | MIRROR` + `TMR | TL | PRESETS`).
- **Top-Left Histogram & 6 Composition Grids:** Moved the RGB+Luma Histogram to the empty top-left corner (opposite the Pilot button), and expanded `MON → GRID` to **6 grids** (`OFF`, `3x3`, `φ`, `DIAG`, `SAFE` 90%/80%, `4x4`).
- **Live Green Update Indicator:** Checking for updates inside Credits refreshes the button immediately into light-green `INSTALL vX.X.X` (and turns the top-left pill green `UPDATE ↑`) without closing the dialog.
- **100% Dark Cinema Modals (`IdgafUiDialogs.kt`):** Eliminated every remaining stock Android `AlertDialog` across both the camera and Clip Vault (simple `DARK SCENE` light prompt, Presets, 3D LUT Library, `+ .CUBE`, URL Downloader, `+ FOLDER`, Move, and Lossless ZIP Archiver).
