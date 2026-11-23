# IDGAF CAMERA (`v0.6.8` — Clip Vault v2 + Pro Audio Controller)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.6.8`
- **Clip Vault v2 (Folders + Multi-Select):**
  - **Custom Folders:** Tap **`+ FOLDER`** to create shoot folders (e.g., `Scene 01`, `B-Roll`) inside `Movies/IDGAF_Camera/`, and filter clips using the horizontal folder strip (`ALL`, `ROOT`, or any custom folder).
  - **Multi-Select Mode:** Tap **`SELECT`** (or long-press any clip card) to pick multiple clips and **`MOVE TO FOLDER`**, bulk **`SHARE`**, or bulk **`DELETE`** in one tap.
- **Pro Audio Controller Drawer (Tap the Live VU Meter):**
  - **Hardware Preamp Stage:** Switch between **`CAM`** (Camcorder), **`RAW`** (`UNPROCESSED` zero-AGC flat feed), and **`BOOST`** (`VOICE_RECOGNITION` high-gain mic stage).
  - **Live dBFS Readout + `CLIP` Warning:** Real-time decibel readout right beside the VU meter.
  - **Hardware Mute Toggle (`LIVE` / `MUTED`):** Records clean silent video-only `.mp4` takes when muted.
  - **VU Trim Slider (`-12 dB` to `+12 dB`):** Calibrate monitor headroom with 1-tap `0 dB` reset.
