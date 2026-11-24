# IDGAF CAMERA (`v0.7.0.2` — Hotfix: MP4 Duration Clock Sync + Encoder & Audio Start Audit)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 + OpenGL ES 2.0** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's Fixed in `v0.7.0.2`
- **Fixed `2013:49` Wrong MP4 Duration Bug (`CLOCK_BOOTTIME` vs `CLOCK_MONOTONIC`):** Camera2 `SurfaceTexture.timestamp` uses `CLOCK_BOOTTIME` (`REALTIME`, which counts deep sleep hours), while `MediaRecorder` Audio uses `CLOCK_MONOTONIC` (`System.nanoTime()`). Feeding raw `camTex.timestamp` into `eglPresentationTimeANDROID()` created a multi-hour gap between Video and Audio tracks. `CinemaGlRenderer` now anchors the first encoded video frame to `System.nanoTime()` and advances using verified hardware deltas + monotonic guard so a 5-second clip is always **`00:05`** with tight lip-sync.
- **Fixed Pre-Start Encoder Frame Race:** Added an `isEncoderRecording` gate so OpenGL never swaps frames into `MediaRecorder.surface` until `MediaRecorder.start()` has completed.
- **Fixed Mic Contention Race on REC Start:** `MainActivity` now synchronously pauses and releases the preview VU `AudioRecord` (max 160ms wait) **before** `MediaRecorder.prepare()`/`start()` opens the mic, eliminating audio start delay or silent tracks.
- **Aspect-Safe Encoder Scaling & Thread Safety:** Added aspect-matched center-crop protection inside `buildEncoderMvp()` for non-16:9 lenses, marked shared recording/audio state `@Volatile`, and removed dead legacy orientation code.
