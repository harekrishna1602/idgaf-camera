# IDGAF CAMERA (`v0.6.7` — Zoom Engine v2, Live Auto Sliders & Live AWB Kelvin)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.6.7`
- **Zoom Engine v2 (`T` / `W` + Curves + Smooth Pulls):**
  - Restored crisp **`T` / `W`** servo zoom rocker with faster **`SLOW / MED / FAST`** speeds.
  - Selectable **Zoom Curves** inside the `1.0x` popout drawer: **`LIN`** (Linear), **`EXP`** (Exponential / Smooth Ease), and **`CRASH`** (high-speed snap crash zoom).
  - **Smooth Preset Zooms (`1x / 2x / 5x / MAX`):** Tapping any preset glides smoothly using your active curve and duration.
  - **A→B Motorized Zoom Pull:** Save Zoom Point **`A`**, save Zoom Point **`B`**, tap **`1.5s`** to set any custom duration (`0.2s–15.0s`), and tap **`A→B`** for repeatable cinema zoom pulls.
- **Live Auto Sliders:** When the ISO, Shutter, Focus, or WB drawer is open in Auto mode, the slider thumb and readout now track live sensor values in real time instead of freezing.
- **Live AWB Kelvin Readout:** The bottom `WB` pill and slider now display the live estimated Kelvin number (e.g., `5400K`) even in Auto White Balance (crisp white for Auto, Safety Orange for Manual).
