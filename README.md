# IDGAF CAMERA (`v0.5.0` — Phase 5: Presets, Flat Profile, Shutter Angle, Golden Ratio & Updater)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli**.

## What's New in Phase 5 (`v0.5.0`)
- **Save & Load Shooting Templates (`PRO → PRESETS`):** Save all current camera settings (Resolution, FPS, Codec, Bitrate, Mic, Tone Curve, Manual ISO/Shutter/WB/Focus, Focus Pull Time, Grid & Frame Guides) as named templates and reload them in 1 tap.
- **Flat & Cinema Tone Curves (`PRO → STD / FLAT / CINE`):** Real-time Camera2 `TONEMAP_MODE_CONTRAST_CURVE` profiles with lifted shadows and rolled-off highlights for easier color grading.
- **Editable Shutter Angle & Shutter Speed:** Toggle **`SPD` / `ANG°`** inside the Shutter drawer to dial or tap-to-type exact Shutter Speed (`1/48s`) or Shutter Angle (`180°`, `90°`, `270°`, `360°`).
- **Custom Focus Pull Time:** Tap **`1.2s`** in the Focus drawer to set any A→B rack focus duration (`0.2s` to `15.0s`).
- **Golden Ratio Grid (`φ`):** `GRID` now cycles `OFF → 3x3 → φ (Golden Ratio)`.
- **Hidden Silent In-App Updater:** Checks `harekrishna1602/idgaf-camera` Releases silently and only reveals the **`UPDATE`** pill when a newer APK is live.
