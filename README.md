# IDGAF CAMERA (`v0.7.2.5` — Part 2: Live 60 FPS GPU `RAW | LOOK` Split-Screen Wipe Slider)

*A love letter to indie filmmakers — by Savior San.*

Free, open Android cinema camera powered by the **Savior Cinema Engine** (native **Kotlin + Camera2 + OpenGL ES 2.0**) by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.7.2.5` (Part 2 of 2)
- **Live 60 FPS GPU `RAW | LOOK` Split-Screen Wipe (`CinemaGlRenderer.kt` + `CinemaOverlayView.kt`):**
  - **2 Ways to Trigger:**
    1. **Dedicated `SPLIT` Button (`LOOK` Header):** Tap **`SPLIT`** right beside **`RAW`** (`RAW | SPLIT | RESET | ✕`) to drop an interactive `50/50` vertical wipe line across the viewfinder, then grab and drag the **`◂▸`** handle anywhere across the screen.
    2. **Hold `RAW` & Slide Finger:** Press & hold **`RAW`** for an instant full-screen RAW peek—or **slide your finger left/right while holding `RAW`** to pull out the vertical wipe line and scrub live across the entire frame. Release in the frame to keep it interactive, or swipe to either edge to snap back to full graded view.
  - **100% Preview-Only & Zero Black-Screen Risk:** Runs via a single `uSplitPixelX` float uniform (zero extra texture units) and is strictly bypassed on Pass 2 (`MediaRecorder` encoder), so recorded `.mp4` clips always stay 100% full-frame graded.
  - **Full-Screen Inspection (`SPLIT ✕`):** Close the `LOOK` drawer with **`✕`** and keep dragging the vertical divider across the unobstructed viewfinder—dismiss anytime with the floating top-left **`SPLIT ✕`** pill (or `PILOT` / `RESET`).
  - **Live Dynamic Tagging:** Displays **`RAW`** on the left and the active **3D LUT name** (e.g., `2383 PRINT D60`, `CINESTILL 800T`) or active **`LOOK`** (`LOG`, `FILM`, `VIVID`, `MONO`) on the right.
