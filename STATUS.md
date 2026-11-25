# IDGAF CAMERA — Module Freeze & Verification Tracker

| Module | Location | Status | Last Verified Build | Rule |
| :--- | :--- | :--- | :--- | :--- |
| **SAVIOR Cinema Core** | `MainActivity.kt`, `CinemaCameraEngine.kt`, `CinemaGlRenderer.kt`, `activity_main.xml` | **CORE READY FOR FREEZE** | `v0.7.7` | Once phone-tested, **FROZEN** — never modified when adding Eras. |
| **Plug-In Slot & Registry** | `era/EraCameraPlugin.kt`, `era/EraPluginRegistry.kt` | **INSTALLED** | `v0.7.7` | Auto-discovers drop-in `Era*.kt` files with zero core edits. |
| **Era 1: 1920s Silent 35mm** | `era/Era1920sPlugin.kt` | **NEXT (Turn 3)** | — | 1 standalone drop-in file. |
| **Era 2: 1950s Mono 35mm** | `era/Era1950sPlugin.kt` | **NEXT (Turn 4)** | — | 1 standalone drop-in file. |
| **Era 3–6 / PURIST / Creator** | `era/Era1970sPlugin.kt` … `era/GtaCreatorPlugin.kt` | **PLANNED (Turn 5+)** | — | 1 standalone drop-in file each. |
