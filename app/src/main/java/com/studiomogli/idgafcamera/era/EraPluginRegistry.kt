package com.studiomogli.idgafcamera.era

/**
 * Zero-Touch Drop-In Plug-In Auto-Discovery Registry.
 * Dropping any standalone plugin .kt file into com.studiomogli.idgafcamera.era automatically activates it
 * on the next build with ZERO edits to MainActivity, CinemaCameraEngine, CinemaGlRenderer, or XML!
 */
object EraPluginRegistry {

    private val KNOWN_PLUGIN_CLASSES = listOf(
        "com.studiomogli.idgafcamera.era.Era1920sPlugin",
        "com.studiomogli.idgafcamera.era.Era1950sPlugin",
        "com.studiomogli.idgafcamera.era.Era1970sPlugin",
        "com.studiomogli.idgafcamera.era.Era1980sVhsPlugin",
        "com.studiomogli.idgafcamera.era.Era2000sDvPlugin",
        "com.studiomogli.idgafcamera.era.Era2008DslrPlugin",
        "com.studiomogli.idgafcamera.era.Purist65Plugin",
        "com.studiomogli.idgafcamera.era.GtaCreatorPlugin"
    )

    fun discoverAll(): List<EraCameraPlugin> {
        val loaded = mutableListOf<EraCameraPlugin>()
        for (className in KNOWN_PLUGIN_CLASSES) {
            try {
                val clazz = Class.forName(className)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is EraCameraPlugin) {
                    loaded.add(instance)
                }
            } catch (_: ClassNotFoundException) {
                // Plugin file not dropped in yet — silently skip!
            } catch (_: Throwable) {
                // Isolate broken plugin so SAVIOR Core NEVER crashes!
            }
        }
        return loaded.sortedBy { it.slotOrder }
    }
}
