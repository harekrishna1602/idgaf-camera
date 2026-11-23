# IDGAF CAMERA (`v0.6.11` — Run 2: Scope Alignment, RGB Histogram & Full UI/Engine Glitch Sweep)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed & Upgraded in `v0.6.11` (Run 2)
- **Fixed `onPause()` Crash:** Eliminated the recursive `stopAnalysisThread()` bug from `v0.6.10` and wired clean worker teardown in `onPause()`.
- **Pixel-Locked False Color, Peaking & Zebras:** Synced `TextureView`'s exact transform matrix into `CinemaOverlayView` and switched to a symmetric `144×144` off-thread sample buffer so `FALSE`, `PEAK`, and `ZEBRA` align 100% with the live image (no more 90° rotation mismatch).
- **3-Channel RGB + Luma Histogram (`HIST`) & `⚙` Settings Pill:** Upgraded the single-color orange histogram to a true **RGB + Luma** overlay scope (translucent Red, Green, Blue channels + crisp white Luma outline + channel clip indicator), and renamed `CFG` → **`⚙`**.
- **EV Tap-to-Type in Real Stops:** Typing EV now accepts real decimal stops (e.g., `-1.5`, `0`, `+2.0`) instead of raw hardware step indices.
- **Clean Mutually Exclusive Drawers:** Opening `MON`, `PRO`, or the Audio VU drawer closes the others automatically, and tapping the viewfinder dismisses every open drawer cleanly.
- **Simultaneous Rack Focus + Zoom Pull:** Removed stray zoom-cancel calls inside Focus controls so you can execute dolly-zoom / simultaneous focus + zoom pulls cleanly.
- **Zero Ghost `A/B` Highlights on Lens Switch:** Switching lenses now resets both the saved Focus/Zoom `A/B` marks and their orange button highlights.
- **180° Reverse Landscape Flip Support:** Added `DisplayManager.DisplayListener` + `onConfigurationChanged()` so flipping the phone 180° immediately updates the viewfinder and scopes.
- **Scrollable `PRO` Bar, External Mic Preview VU, Full Presets & `32000` ISO Cap:** Wrapped `proSettingsBar` in a `HorizontalScrollView`, routed external USB/wired mics to the idle VU meter, expanded `PresetStore` to save all pro controls (EV, Tint, Preamp, Mute, Stab, Zoom Curve/Duration, Peak/Zebra settings), and lifted the extended ISO ceiling to `32000`.
