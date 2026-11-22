package com.studiomogli.idgafcamera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CameraPreset(
    val name: String,
    val resolutionName: String = CinemaResolution.FHD_1080P.name,
    val fps: Int = 30,
    val codecName: String = CinemaCodec.H264.name,
    val bitrateMbps: Int = 35,
    val micPrefName: String = MicPreference.AUTO.name,
    val toneProfileName: String = ToneProfile.STANDARD.name,
    val isManualExposure: Boolean = false,
    val manualIso: Int = 200,
    val manualShutterNs: Long = 16_666_666L,
    val isShutter180Locked: Boolean = false,
    val isManualWb: Boolean = false,
    val manualKelvin: Int = 5600,
    val isManualFocus: Boolean = false,
    val manualFocusDistance: Float = 0f,
    val focusPullMs: Long = 1200L,
    val gridModeName: String = GridMode.OFF.name,
    val aspectGuideName: String = AspectGuide.OFF.name
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("resolutionName", resolutionName)
        put("fps", fps)
        put("codecName", codecName)
        put("bitrateMbps", bitrateMbps)
        put("micPrefName", micPrefName)
        put("toneProfileName", toneProfileName)
        put("isManualExposure", isManualExposure)
        put("manualIso", manualIso)
        put("manualShutterNs", manualShutterNs)
        put("isShutter180Locked", isShutter180Locked)
        put("isManualWb", isManualWb)
        put("manualKelvin", manualKelvin)
        put("isManualFocus", isManualFocus)
        put("manualFocusDistance", manualFocusDistance.toDouble())
        put("focusPullMs", focusPullMs)
        put("gridModeName", gridModeName)
        put("aspectGuideName", aspectGuideName)
    }

    companion object {
        fun fromJson(obj: JSONObject): CameraPreset = CameraPreset(
            name = obj.optString("name", "Preset"),
            resolutionName = obj.optString("resolutionName", CinemaResolution.FHD_1080P.name),
            fps = obj.optInt("fps", 30),
            codecName = obj.optString("codecName", CinemaCodec.H264.name),
            bitrateMbps = obj.optInt("bitrateMbps", 35),
            micPrefName = obj.optString("micPrefName", MicPreference.AUTO.name),
            toneProfileName = obj.optString("toneProfileName", ToneProfile.STANDARD.name),
            isManualExposure = obj.optBoolean("isManualExposure", false),
            manualIso = obj.optInt("manualIso", 200),
            manualShutterNs = obj.optLong("manualShutterNs", 16_666_666L),
            isShutter180Locked = obj.optBoolean("isShutter180Locked", false),
            isManualWb = obj.optBoolean("isManualWb", false),
            manualKelvin = obj.optInt("manualKelvin", 5600),
            isManualFocus = obj.optBoolean("isManualFocus", false),
            manualFocusDistance = obj.optDouble("manualFocusDistance", 0.0).toFloat(),
            focusPullMs = obj.optLong("focusPullMs", 1200L),
            gridModeName = obj.optString("gridModeName", GridMode.OFF.name),
            aspectGuideName = obj.optString("aspectGuideName", AspectGuide.OFF.name)
        )
    }
}

class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("idgaf_camera_presets_v1", Context.MODE_PRIVATE)

    fun getAll(): MutableList<CameraPreset> {
        val raw = prefs.getString("presets_json", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<CameraPreset>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(CameraPreset.fromJson(obj))
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(preset: CameraPreset) {
        val list = getAll()
        val idx = list.indexOfFirst { it.name.equals(preset.name, ignoreCase = true) }
        if (idx >= 0) list[idx] = preset else list.add(0, preset)
        persist(list)
    }

    fun delete(name: String) {
        val list = getAll()
        list.removeAll { it.name.equals(name, ignoreCase = true) }
        persist(list)
    }

    private fun persist(list: List<CameraPreset>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("presets_json", arr.toString()).apply()
    }
}
