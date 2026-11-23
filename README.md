# IDGAF CAMERA (`v0.6.9.1` — Hotfix: Camera Open ISO Crash & Steady Audio `-∞dB` Box)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed in `v0.6.9.1`
- **Fixed Camera Open Crash (`empty range: maximum 12800`):** On sensors whose native analog ISO range exceeds `12800`, `coerceIn(analogUpper, 12800)` threw an `IllegalArgumentException` and aborted camera startup. Fixed the upper bound to `maxOf(analogUpper, 12800)` and hardened all Camera2 range clamps so every sensor opens cleanly.
- **Fixed Audio VU Box Glitch & Missing `dB`:** Locked the entire Audio VU pill (`btnAudioPanelToggle`) to a fixed `132dp` container with `9dp` monospace text and changed silent `-∞` to **`-∞dB`** so the box never shifts size and `"dB"` never flickers out.
