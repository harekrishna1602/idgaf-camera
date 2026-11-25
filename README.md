# IDGAF CAMERA (`v0.7.4.3` — Physical Film Atlas R&D, Manual Film ASA/Shutter Control, Era Torch Lamp & Westrex Optical Audio)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Rebuilt & Added in `v0.7.4.3`
- **Real 256×256 4-Channel Physical Film Emulsion & Debris Atlas (`CinemaGlRenderer.kt`):**
  - Replaced repeated procedural GPU noise with a synthesized seamless **4-channel physical film atlas** (`R`: 1920s coarse clumped silver-nitrate crystals, `G`: 1950s fine acutance Kodak Double-X grain, `B`: real irregular curved micro-fibers & asymmetric gate dust, `A`: 1920s hand-bath developer unevenness).
  - Sampled via **37° cross-rotated irrational dual-tap coordinates** driven by a **64-bit CPU SplitMix64 film-transport engine** (weaves, shutter flicker, and natural multi-frame drifting scratch bursts that appear briefly and vanish)—**zero 89-frame loops, zero grid dots, zero triangles**.
- **Night-and-Day Separation Between `1920s` and `1950s`:**
  - **1920s Silent (`Pathé / Bell & Howell 2709`):** True red-blind Orthochromatic (`0.04 R`), dreamy uncoated Petzval radial edge swirl & heavy highlight bloom, thick rounded `1.33:1` brass corners, heavy barrel vignette, warm antique ivory/amber-sepia base, coarse clumped grain, and chemical bath unevenness.
  - **1950s Monochrome (`Satyajit Ray / Arriflex 35`):** Full-spectrum Panchromatic (`Yellow K2` / `Clear` / `Red 25A` / `Green X1`), crisp coated Cooke Speed Panchro edge-to-edge acutance, wide 10-zone Subrata Mitra shadow latitude, razor-clean pin-registered `1.375:1` Academy gate, cool platinum-silver gelatin base, and ultra-fine ASA-reactive grain.
- **True Manual Film Stock `ASA / ISO` & Mechanical Rotary Shutter (`CLASSIC` Mode):**
  - Tap **`ASA/IRIS`** to load authentic period film stocks (**1920s:** `ASA 50 / 100 / 400 / 800`; **1950s:** `PX 80 / XX 250 / TX 400 / P 800 / 1600`), tap **`LOCK 180°`** to freeze exact ISO + rotary shutter (eliminating Android Auto-ISO glitches completely), ride mechanical shutter smoothly with the slider, tap the readout to type **any exact ASA**, or switch back to **`AUTO`**. Higher ASA naturally scales silver-halide grain density!
- **Live `LAMP` (Torch / Studio Sun-Gun) in `CLASSIC` Mode:**
  - Dedicated **`LAMP`** toggle pill right on the Classic top bar (works live during recording).
- **Upgraded 1950s Westrex Optical Mono Audio Engine (`EraAudioProcessor.kt`):**
  - Dual-pole AMPAS Academy Curve HPF, RCA 44BX ribbon mic chest warmth (`360 Hz`), dialogue articulation (`2.1 kHz`), 24 dB/oct optical slit HF roll-off, **Westrex RA-1231 optical light-valve compressor**, asymmetric 2nd-order vacuum-tube harmonics + DC blocker, signal-modulated optical emulsion hiss, and `ARCHIVE` 24 Hz sprocket micro-flutter.
