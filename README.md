# IDGAF CAMERA (`v0.6.12` — Run 3: Clip Vault `ZIP` Archiver)

Free, open, community-built Android cinema camera built on native **Kotlin + Camera2 API** by **Studio Mogli** ([www.studiomogli.com](https://www.studiomogli.com)).

## What's New in `v0.6.12` (Run 3 — Clip Vault `ZIP` Archiver)
- **Fast Lossless `ZIP` Packaging (`ZipEntry.STORED`):** Select multiple takes in Clip Vault and tap **`ZIP`** to bundle them at full original bitrate into `Downloads/IDGAF_Archives/` in seconds (zero video re-compression, preserving subfolder paths inside the archive) with live MB progress and clean cancellation.
- **Two Offload Modes:**
  - **`ZIP & KEEP`:** Packages selected clips into a `.zip` and leaves all originals untouched in Clip Vault.
  - **`ZIP & CLEAR` (Verified Storage Saver):** Builds the `.zip`, runs a full **read-back integrity verification** (verifying every entry and exact byte count), and **only if 100% verified** deletes the original `.mp4` files immediately to reclaim space.
- **Persistent Post-Export `SHARE ZIP` & `DELETE ZIP` Sheet:** Tapping **`SHARE ZIP`** sends the archive via WhatsApp Document / Telegram / Drive without closing the dialog—so when you return, **`DELETE ZIP`** is waiting in 1 tap to wipe the archive too.
- **`ZIPS` Archive Manager Pill:** Tap **`ZIPS`** in the Clip Vault top bar anytime to browse, share, or delete saved archives in `Downloads/IDGAF_Archives/`.
