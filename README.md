# IDGAF CAMERA (`v0.6.13` — Auto Pilot: 1-Tap Scene Rescue & Stable Lock)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.6.13` (Auto Pilot)
- **Dedicated `PILOT` Button (Right Above REC Shutter):**
  - **Instant Full-System Rescue:** If your ISO is buried at 100 (pitch-black frame), shutter is off, focus is stuck blurry, WB/tint is skewed, EV is dialed down, AE/AF lock is stuck, or Audio is accidentally muted—one tap on **`PILOT`** resets and rescues everything immediately (clears locks/EV/self-timer, restores `AF-C`, enables `STAB`, sets Tone to `STD`, unmutes Audio on balanced `CAM` preamp, closes drawers).
  - **Smart Scene Convergence & Stable Lock:** Lets Camera2's AE & AWB meter the real visible scene (`SYNC…`, ~0.6s), then **locks stable Exposure (`ISO` + `Shutter`) and calibrated White Balance** so the footage stays rock-steady with zero brightness or color pumping—while keeping **continuous autofocus (`AF-C`) live** so moving subjects stay sharp.
  - **You Hit REC When Ready:** Auto Pilot preps and stabilizes the camera without auto-starting recording—just drop your thumb onto the red REC shutter when you're ready to roll.
  - **Tap vs Long-Press & Human-First Handoff:** Tap **`PILOT`** again to exit back to Standard Auto, **long-press `PILOT`** anytime to re-meter a new scene, or drag any manual slider and Auto Pilot cleanly hands control right back to you.
