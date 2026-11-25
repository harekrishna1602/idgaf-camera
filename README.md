# IDGAF CAMERA (`v0.7.5.2` — 1920s Optical Audit Fixes, Distinct Workbench/Era Drawers, Left-Rail Clean-Frame Eye & Styled License)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed & Upgraded in `v0.7.5.2`
- **1920s Multi-Pass Optical Audit Fixes (`CinemaGlRenderer.kt`):**
  - **0.000 px Gate-Weave UV Alignment:** Computes gate weave in unified `vRawUv` space and maps through `uFragStMatrix` so sharp camera texture, temporal trail FBO, and optical bloom FBO track together with zero double-vision ghosting in `VAULT` / `FOUND`.
  - **True Circular Petzval Bloom:** Upgraded FBOs to square Power-of-Two (`512×512` & `256×256`) with aspect-compensated `uBlurDir` (`rot0W` / `rot0H`) so optical halation is circular rather than horizontally stretched on portrait-natural sensors.
  - **Crisper Midtones + Zero Indoor Trail Ghosting:** Raised highlight bloom knee (`0.66`) so faces stay punchy while skies/windows/lamps glow, and disabled indoor temporal blending (`motionAmt < 0.05`) while retaining rotary-shutter daylight blur.
- **ASA Double-Selection Bug Fixed & Zebra Toggle Restored:** Dynamically adapts 1920s ASA stock chips to the lens's hardware ISO floor (`ASA 100/200/400/800` when min ISO is 100) so only one chip can ever highlight; restored `btnToolZebra` click listener inside `MON`.
- **Distinct Workbench vs Era Camera Vault Drawers (Through 2008 DSLR):** Inside Era mode, `ERA ▾` opens the Workbench Switcher (`SAVIOR | ERA | PURIST`) while `1920s SILENT ▾` opens the 2-row 6-era Camera Vault (`1920s`, `1950s`, `1970s S8/16`, `1980s VHS`, `2000s DV`, `2008 DSLR`).
- **Left-Rail Clean-Frame Eye (`👁`) + No Auto-Keyboard Input Bar + WB `✎ K` + Styled IDGAF License:** Moved `👁` below the lens switcher on the left rail (with in-place subtle restore eye); Cinema Inline Input Bar keeps keyboard closed until you tap inside the box so `−`/`+` work cleanly; added explicit `✎ K` button next to `AUTO` in White Balance; redesigned `dialog_license.xml` to match IDGAF's dark cinema aesthetic.
