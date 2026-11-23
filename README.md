# IDGAF CAMERA (`v0.6.6` — WB Fix, Smart Bitrate, EV Fix & Lock Alerts)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.6.6`
- **Fixed Reversed White Balance:** `2500K` is now cool/blue and `8500K` is now warm/amber, matching real cinema cameras.
- **Smart Bitrate Scaling (4–120 Mbps):** Bitrate now starts at **`4 Mbps`** (`4 / 8 / 12 / 24 / 35 / 50 / 75 / 100 / 120 Mbps`) and auto-scales when you switch resolutions (`480P → 4 Mbps`, `720P → 12 Mbps`, `1080P → 24 Mbps`, `2K → 50 Mbps`, `4K → 75 Mbps`).
- **Fixed EV Exposure Compensation & Build:** Fixed the `v0.6.5` compile error, removed the duplicate EV branch, and ensured EV unlocks AE when adjusted.
- **No More Fixed-Focus Toast Spam:** Dims the `FOCUS` pill on fixed-focus lenses and cancels duplicate toasts immediately.
- **AE/AF Lock Alerts:** Tapping `AUTO` or the viewfinder while `LOCK` is enabled now clearly alerts you to turn off `LOCK` first.
