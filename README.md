# IDGAF CAMERA (`v0.7.2.3` — World-Class 12-LUT Cinema Color-Science Suite)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.2.3` (LUT-Only Color-Science Upgrade)
- **Removed Old Basic LUTs → Replaced with 12 World-Class Cinema 3D LUTs (`CubeLutParser.kt`):**
  - Powered by a true **7-Stage Linear-Light Film Color-Science Engine** (3×3 dye-layer spectral matrix, 6-vector HSV Hue/Sat/Luma warping, subtractive film density, pivot S-curve with soft highlight shoulder roll-off, black-protected zone split-toning, specular highlight desaturation, and orange-filtered panchromatic B&W):
    1. **`2383 PRINT D60`** — Hollywood 35mm Print Film (rich deep blacks, warm D60 cream highlights, cyan-teal blues, protected golden skin).
    2. **`FUJI 3513D`** — Fujifilm Eterna-CP 3513DI Print Stock (subtle teal/orange separation, lush emerald foliage, silky skin).
    3. **`ARRI ALF-2`** — ARRI Alexa Cinema Science (neutral balanced highlights, organic skin, effortless filmic dynamic range).
    4. **`PORTRA 400`** — Kodak Portra 400 Negative (golden peach skin, lifted matte blacks, soft pastel skies).
    5. **`CINESTILL 800T`** — Tungsten Night Cinema (cool cyan shadows, glowing warm amber practical lights).
    6. **`CLASSIC CHROME`** — Fujifilm Classic Chrome (editorial muted documentary palette, deep shadows).
    7. **`BLEACH BYPASS`** — Lab Silver-Retention ENR (*Se7en* / gritty steely high-contrast drama).
    8. **`KODACHROME 64`** — Archival Slide Film (velvety brick reds, dense blacks, golden warmth).
    9. **`NEON JADE`** — Wong Kar-wai Nocturne (lush emerald-jade shadows, glowing amber highlights).
    10. **`DESERT GOLD`** — *Dune* / *Blade Runner 2049* (warm golden ochre highlights, smoky bronze shadows).
    11. **`NORDIC SLATE`** — Fincher / Cold Thriller (desaturated steel-blue shadows, restrained tension).
    12. **`TRI-X 400 BW`** — Kodak Tri-X 400 Panchromatic B&W (orange-filter channel mix for dark skies & luminous skin).
- **1-Tap Instant LUT Cycling (`‹` / `›`) + RAM Cache:** Step cleanly through all 12 World-Class LUTs + your imported `.cube` files right from the `LOOK` drawer with `< 1 ms` cached switching.
- **Zero Risk to Core Camera:** `CinemaGlRenderer.kt`, `CinemaCameraEngine.kt`, audio, and Time-Lapse remain 100% untouched from `v0.7.2.2`.
