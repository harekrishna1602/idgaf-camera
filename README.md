# IDGAF CAMERA (`v0.7.5.3` — Format Bitrate Integration, 9:16 Vertical Rec in Landscape HUD, 5 Cinema Grids, Top-Left Histogram & 100% Custom Studio UI)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.5.3`
- **Live Green Update Refresh:** Tapping `CHECK FOR UPDATES` inside Credits immediately updates the open button to bright emerald green (`INSTALL vX.X.X`, matching `PILOT`) as soon as a release is detected—no closing/reopening required.
- **Top-Left RGB Histogram:** Moved the 3-channel RGB + Luma Histogram to the top-left opposite `PILOT` so the right rail stays clean.
- **Bitrate Inside `VIDEO FORMAT` Drawer + Rebalanced `PRO` Drawer:** Bitrate (`8M – 100M` + custom `✎ Mbps`) now lives right inside `VIDEO FORMAT` beneath Resolution & FPS (auto-updating visibly whenever you switch resolution), while `PRO` rebalances cleanly into `CODEC | STAB | TORCH | MIRROR` and `TMR | TL | PRESETS`.
- **5 Cinema Grids + True `9:16` Vertical Recording in Landscape HUD:** Expanded `GRID` to `3x3`, `Golden φ`, `4x4`, and `Diagonals (✕+)`, and added `9:16 VERT` recording mode (via `VIDEO FORMAT` or `MON → FRAME: 9:16`) that keeps all menus in your current orientation while encoding and saving true vertical `9:16` MP4s (`1080×1920`).
- **100% Custom Studio Mogli UI (Zero Old Android Popups):** Replaced low-light ISO jargon with an inline `LOW LIGHT • Turn light on?` (`LIGHT ON` / `KEEP OFF`) card, and replaced all remaining Android `AlertDialog` popups (Presets, 3D LUT Library, `+ .CUBE`, Clip Vault `+ FOLDER` / `MOVE` / `ZIP`) with custom dark Studio Mogli drawers & modals.
