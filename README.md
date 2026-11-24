# IDGAF CAMERA (`v0.7.4.2` — True Celluloid Optics Overhaul, Hardware ISP De-Sharpening, Period Filters & Full Audit)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Upgraded in `v0.7.4.2`
- **Real Scanned-35mm Celluloid Feel (Zero "Fake Filter" Look):**
  - **Hardware ISP De-Sharpening:** Automatically disables harsh smartphone digital edge sharpening (`EDGE_MODE_OFF`), plastic AI smoothing (`NOISE_REDUCTION_MODE_MINIMAL`), and digital EIS warping inside **`CLASSIC`** mode so footage starts genuinely optical at the sensor level.
  - **Removed Fake Cue-Mark Button:** Deleted the artificial concentric circle cigarette-burn button completely.
  - **True 2-Octave Smooth Bilinear Silver-Halide Grain:** Replaced hard square grain pixels with optically smooth bilinear emulsion crystals (zero blocks, zero triangles, zero TV static).
  - **8-Tap Two-Ring Vintage Lens Optics + D-Max Split-Toning:** Uncoated Petzval & Cooke Speed Panchro wide highlight halation, deep neutral D-max blacks (no muddy flat fade), warm celluloid base patina in midtones/highlights, plus subtle focal-plane gate dust & rare ultra-fine hairlines in **`ARCHIVE`**.
- **Period-Authentic Accessories (Long-Press `PRINT`):**
  - **1920s Nitrate Lab Tint Baths:** `SILVER` (Default), `AMBER` (Candlelight), `COBALT` (*Nosferatu* Silent Night Blue), `SEPIA`.
  - **1950s Screw-On Glass Contrast Filters:** `Yellow K2` (Satyajit Ray Default), `Clear` (Neutral Double-X), `Red 25A` (Film Noir Black Skies), `Green X1` (Sculptural Portraits).
- **Period-Accurate Speed Menus:**
  - **1920s (`CRANK`):** `16 FPS` (Early Silent) / `18 FPS` (Classic 1920s) / `20 FPS` (Late Cinema Palace).
  - **1950s (`FPS`):** `18 FPS` (Undercrank) / `24 FPS` (Sync Sound) / `25 FPS` (50Hz Telecine) / `30 FPS` (`1955 Todd-AO / TV Kinescope`).
- **Full Clash Audit Fixes:** Low-battery warning banner yields immediately when `LOOK`/`PRO`/`MON` drawers open, idle VU mic monitor pauses inside `CLASSIC` to save battery & cut mic handoff latency, flipping lenses in `CLASSIC` no longer overwrites `SAVIOR` FPS state, and stale Era temp files auto-clean on startup.
