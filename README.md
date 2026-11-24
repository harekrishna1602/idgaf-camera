# IDGAF CAMERA (`v0.7.4` — Era 2: 1950s Classic Monochrome [Satyajit Ray 35mm] + Optical Mono Sound Engine)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.4`
- **Era 2 — 1950s Classic Monochrome (*Satyajit Ray / Arriflex 35 / Kodak Plus-X & Double-X*):**
  - **Authentic 1950s Controls Only:** Locked **`24 FPS`** sync sound (true `90 ft/min` 35mm footage counter), **3-Lens Rotating Prime `TURRET` (`28mm` / `35mm` / `50mm`, zero zoom)**, mechanical **`IRIS` (`f/2.0`–`f/16`)**, **`FOCUS`** ring, and **`PRINT` (`FRESH` ↔ `ARCHIVE`)**.
  - **True 1950s Optical & Panchromatic Pipeline (`CinemaGlRenderer.kt`):**
    - Baked **`1.375:1` AMPAS Academy Sound Gate** with crisp machined corners.
    - Genuine **Panchromatic Silver-Halide + Wratten K2 Yellow Filter** tonal response (sculptural luminous skin, rich reds, moody textured skies) and **Cooke Speed Panchro** coated-glass sharpness + delicate highlight halation.
    - **`FRESH` (1955 Premiere Release Print):** Pure cool-neutral silver-gelatin, deep velvety chiaroscuro blacks, ultra-fine 24 FPS 35mm grain, rock-steady pin-registered gate, zero artifacts.
    - **`ARCHIVE` (70-Year Acetate Vault Print):** Warm cream-selenium acetate base, lifted projection shadows, subtle micro-weave & flicker, delicate hairline scratches, micro-dust, and an authentic **top-right 35mm changeover cue mark** every ~20 seconds.
- **Authentic Era Audio DSP Engine (`EraAudioProcessor.kt`):**
  - Committed authentic **`OPTICAL MONO`** sound (zero modern audio toggle) using lossless zero-reencode video passthrough + **1950s Academy Curve Optical Sound-on-Film DSP**: `125 Hz` rumble HPF, `4.8–5.8 kHz` optical slit rolloff, `1.65 kHz` warm ribbon-mic vocal peak, Westrex vacuum-tube soft saturation, and subtle 35mm optical emulsion noise floor (`FRESH` clean hiss vs `ARCHIVE` dustier hiss + micro ticks), with ironclad fallback.
- **Classic Workbench Naming & Instant Battery Receiver Fix:**
  - Kept the top-bar button named **`ERA`** while updating the 3-Workbench dropdown (`SAVIOR` / **`CLASSIC`** / `PURIST`) and inner top-left pill (`CLASSIC ▾`).
  - Fixed `ACTION_BATTERY_CHANGED` BroadcastReceiver lifecycle so charging **`⚡`** and **`≤ 15%`** low-battery plug-in alerts respond instantaneously.
