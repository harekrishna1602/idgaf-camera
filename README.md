# IDGAF CAMERA (`v0.7.0.1` — Hotfix: OpenGL ES Viewfinder & Encoder Rotation Alignment)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 + OpenGL ES 2.0** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed in `v0.7.0.1`
- **Fixed Sideways / Stretched Camera Preview & Recordings:** `SurfaceTexture.getTransformMatrix(stMatrix)` already applies Camera2's `sensorOrientation` to align frames to natural portrait (`ROTATION_0`). `v0.7.0` subtracted `sensorOrientation` a second time (producing `0°` rotation instead of `±90°`). `v0.7.0.1` now rotates cleanly from `ROTATION_0` to the active display orientation (`+90°` CCW in `ROTATION_90`, `-90°` CW in `ROTATION_270`) and computes exact center-crop aspect ratio from `stMatrix` for 1:1 unstretched square pixels.
- **Fixed Recorded MP4 Orientation & Front-Camera Un-Mirroring:** `buildEncoderMvp` now applies exact upright `±90°` landscape rotation on the `MediaRecorder` GL pass and checks `det2x2(stMatrix) > 0` to cleanly un-mirror front-camera MP4 exports horizontally.
- **Exact 1-Pixel GPU Sobel Peaking Steps & Frame-1 Viewport Size:** Fixed `uStepX` / `uStepY` to true raw OES pixel units `(1/W, 0)` & `(0, 1/H)` and passed exact `(width, height)` from `onSurfaceTextureAvailable`.
