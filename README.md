# IDGAF CAMERA (`v0.7.2` — Phase 7.2B: Real `.cube` 3D LUT Engine & Zero-Clunk Updater)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.2` (Phase 7.2B)
- **Real Adobe `.cube` 3D LUT Engine (`CubeLutParser.kt` + `CinemaGlRenderer.kt`):**
  - Full trilinear 3D LUT lookup packed into a hardware OpenGL ES 2.0 2D texture atlas (`33×33×33` cinema broadcast standard) running at **60 FPS** and baking live into both the viewfinder **and** recorded `.mp4`.
  - Automatically validates and trilinearly resamples `64³` / `65³` `.cube` files down to `33³` so every `.cube` file on the internet runs smoothly on 100% of Android GPUs.
  - **Import `.cube` from Anywhere on the Internet:**
    - **From Phone / Downloads (`+ .CUBE`):** Download any `.cube` file from Chrome, Google Drive, Telegram, DaVinci Resolve, or LUT sites and load it via Android's document picker.
    - **Direct URL Download:** Paste any direct `.cube` URL (including GitHub `.cube` links) right inside the app to fetch, verify, and save it permanently to your in-app LUT library.
    - **3 Built-In Cinema 3D LUTs Included:** **`KODAK 2383`**, **`TEAL & ORG`**, and **`FUJI ETERNA`** ready to test immediately.
  - **Peek-Through `LUT` Intensity Slider (`0%–100%`):** Drag to dial your 3D LUT strength cleanly over the unobstructed camera frame, press-and-hold **`HOLD RAW`** to compare against raw ungraded sensor output, and save/load LUTs in Shooting Presets.
- **Zero-Clunk Top-Bar Update Notification:** Removed the extra `UPDATE` pill from the right top bar so `LOOK / PRO / MON / 1080P / 30 FPS` never crowd or shift when an update arrives. Instead, **`IDGAF CAM ↑`** glows cleanly on the left and opens a direct **`INSTALL vX.X.X`** button inside Credits.
