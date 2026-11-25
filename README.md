# IDGAF CAMERA (`v0.7.5` — True 1920s Silent 35mm Multi-Pass Optical Engine, Beer-Lambert Lab Baths & Unified Inline Drawers)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Rebuilt in `v0.7.5` (Master One Era at a Time — 1920s Silent 35mm)
- **Multi-Pass GPU Optical Bloom & Daylight Motion-Blur FBOs (`CinemaGlRenderer.kt`):**
  - **`512×256` Temporal Ortho & Daylight Rotary-Shutter Motion-Blur FBO:** Reads live sensor shutter speed (`liveShutterNs`) and automatically blends a silky `180°` rotary-shutter trail when shooting bright outdoor sunlight (`< 1/120s`), turning jittery smartphone daylight into liquid mechanical 1920s motion.
  - **`256×128` 9-Tap Separable Wide Optical Bloom FBO:** True 2D circular uncoated Petzval highlight glow (`~128 px` diameter on 1080p) + authentic ~800-line 1920s optical MTF softening (paired with Camera2 `EDGE_MODE_OFF` & EIS off).
- **Authentic Sharp `1.333:1` Silent 35mm Full-Aperture Gate:**
  - Removed fake rounded CRT-TV corners completely—matches real 1920s *The General* (1926) and *Nosferatu* (1922) sharp rectangular 4:3 camera gates.
- **5 True Beer-Lambert 1920s Lab Chemical Tint Baths (`BATH`):**
  - Metallic silver shadows stay deeply dark while translucent gelatin highlights absorb real dye: **`SILVER`** (*The General* Orthochromatic), **`AMBER`** (Sunlit Daylight Straw-Gold), **`NIGHT`** (*Nosferatu* 1922 Day-for-Night Prussian Blue-Teal with automatic shadow/midtone exposure pull), **`SEPIA`** (Lantern Interior), and **`ROSE`** (Pathé Dawn).
- **Zero-Repeat Event-Driven Archive Engine (`PRINT`):**
  - Deleted tiled black-dot confetti completely. Three honest print conditions: **`PREMIERE`** (`100%` spotless 1926 lab release print), **`VAULT`** (`~76%` spotless Criterion/Kino 4K restoration feel), and **`FOUND`** (`~48%` spotless 100-yr discovered reel) with individual 1-frame non-circular irregular specks/hairs, slow organic hand-crank exposure breathing, and rare drifting hairline scratches.
- **Unified Inline Stacked Drawer UX:**
  - Every Classic bottom pill (**`CRANK` \| `BATH` \| `ASA` \| `FOCUS` \| `PRINT`**) opens a clean inline drawer directly above the dock—zero hidden long-presses, zero system popups, plus live **`LAMP`** torch and manual **`ASA` + `LOCK 180°`**.
