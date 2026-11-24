# IDGAF CAMERA (`v0.7.2.1` — Savior Cinema Engine)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed in `v0.7.2.1`
- **Black Screen on Boot — Fixed (`CinemaGlRenderer.kt`):** Fixed an OpenGL ES 2.0 sampler-unit collision where `uTexture` (`samplerExternalOES`, unit `0`) and `uLutTexture` (`sampler2D`, unassigned → defaulted to unit `0`) collided before a LUT was selected, causing `glDrawArrays()` to drop frames with `GL_INVALID_OPERATION`. Now binds `uLutTexture` unconditionally to `GL_TEXTURE1` with a permanent valid 2D dummy texture fallback so the viewfinder boots live 100% of the time—whether LUT is `NONE`, active, or cleared back to `NONE`.
- **LUT Mix Slider Visibility Fix (`MainActivity.kt`):** Synced `rowColorLutMix` cleanly whenever `LOOK` opens after loading a Preset with a saved 3D LUT.
- **The IDGAF License (`LICENSE.md` + Credits Dialog):** Added a tappable **`IDGAF LICENSE`** badge inside the Credits dialog.

## The IDGAF License (Redemption Edition)
- **Humans (indie filmmakers, students, creators, literally any person):** **$0 / ₹0 forever.** Optional **$1 USD global Human Lifetime License** if you want to own it officially—otherwise free forever.
- **Corporations & any XYZ company selling monthly subscriptions for basic features:** **$9,999 USD / month per device** *(billed annually)*. **Corporations are permanently banned from buying the Lifetime License.**
