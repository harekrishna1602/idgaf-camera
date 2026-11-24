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
    val audioPreampModeName: String = AudioPreampMode.CAMCORDER.name,
    val isAudioMuted: Boolean = false,
    val isStabilizationEnabled: Boolean = true,
    val toneProfileName: String = ToneProfile.STANDARD.name,
    val evCompensationIndex: Int = 0,
    val isManualExposure: Boolean = false,
    val manualIso: Int = 200,
    val manualShutterNs: Long = 16_666_666L,
    val isShutter180Locked: Boolean = false,
    val isManualWb: Boolean = false,
    val manualKelvin: Int = 5600,
    val manualTint: Int = 0,
    val isManualFocus: Boolean = false,
    val manualFocusDistance: Float = 0f,
    val focusPullMs: Long = 1200L,
    val zoomCurveName: String = ZoomCurve.EXPONENTIAL.name,
    val zoomPullDurationMs: Long = 1500L,
    val gridModeName: String = GridMode.OFF.name,
    val aspectGuideName: String = AspectGuide.OFF.name,
    val peakingColorName: String = PeakingColor.GREEN.name,
    val peakingSensitivityName: String = PeakingSensitivity.MED.name,
    val zebraModeName: String = ZebraMode.IRE_92.name,
    val gpuExposureLift: Float = 0.0f,
    val gpuContrast: Float = 1.0f,
    val gpuSaturation: Float = 1.0f,
    val gpuShadows: Float = 0.0f,
    val gpuHighlights: Float = 0.0f,
    val gpuVignette: Float = 0.0f,
    val lutSourceId: String = "",
    val lutIntensity: Float = 1.0f,
    val mirrorFrontExport: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("resolutionName", resolutionName)
        put("fps", fps)
        put("codecName", codecName)
        put("bitrateMbps", bitrateMbps)
        put("micPrefName", micPrefName)
        put("audioPreampModeName", audioPreampModeName)
        put("isAudioMuted", isAudioMuted)
        put("isStabilizationEnabled", isStabilizationEnabled)
        put("toneProfileName", toneProfileName)
        put("evCompensationIndex", evCompensationIndex)
        put("isManualExposure", isManualExposure)
        put("manualIso", manualIso)
        put("manualShutterNs", manualShutterNs)
        put("isShutter180Locked", isShutter180Locked)
        put("isManualWb", isManualWb)
        put("manualKelvin", manualKelvin)
        put("manualTint", manualTint)
        put("isManualFocus", isManualFocus)
        put("manualFocusDistance", manualFocusDistance.toDouble())
        put("focusPullMs", focusPullMs)
        put("zoomCurveName", zoomCurveName)
        put("zoomPullDurationMs", zoomPullDurationMs)
        put("gridModeName", gridModeName)
        put("aspectGuideName", aspectGuideName)
        put("peakingColorName", peakingColorName)
        put("peakingSensitivityName", peakingSensitivityName)
        put("zebraModeName", zebraModeName)
        put("gpuExposureLift", gpuExposureLift.toDouble())
        put("gpuContrast", gpuContrast.toDouble())
        put("gpuSaturation", gpuSaturation.toDouble())
        put("gpuShadows", gpuShadows.toDouble())
        put("gpuHighlights", gpuHighlights.toDouble())
        put("gpuVignette", gpuVignette.toDouble())
        put("lutSourceId", lutSourceId)
        put("lutIntensity", lutIntensity.toDouble())
        put("mirrorFrontExport", mirrorFrontExport)
    }

    companion object {
        fun fromJson(obj: JSONObject): CameraPreset = CameraPreset(
            name = obj.optString("name", "Preset"),
            resolutionName = obj.optString("resolutionName", CinemaResolution.FHD_1080P.name),
            fps = obj.optInt("fps", 30),
            codecName = obj.optString("codecName", CinemaCodec.H264.name),
            bitrateMbps = obj.optInt("bitrateMbps", 35),
            micPrefName = obj.optString("micPrefName", MicPreference.AUTO.name),
            audioPreampModeName = obj.optString("audioPreampModeName", AudioPreampMode.CAMCORDER.name),
            isAudioMuted = obj.optBoolean("isAudioMuted", false),
            isStabilizationEnabled = obj.optBoolean("isStabilizationEnabled", true),
            toneProfileName = obj.optString("toneProfileName", ToneProfile.STANDARD.name),
            evCompensationIndex = obj.optInt("evCompensationIndex", 0),
            isManualExposure = obj.optBoolean("isManualExposure", false),
            manualIso = obj.optInt("manualIso", 200),
            manualShutterNs = obj.optLong("manualShutterNs", 16_666_666L),
            isShutter180Locked = obj.optBoolean("isShutter180Locked", false),
            isManualWb = obj.optBoolean("isManualWb", false),
            manualKelvin = obj.optInt("manualKelvin", 5600),
            manualTint = obj.optInt("manualTint", 0),
            isManualFocus = obj.optBoolean("isManualFocus", false),
            manualFocusDistance = obj.optDouble("manualFocusDistance", 0.0).toFloat(),
            focusPullMs = obj.optLong("focusPullMs", 1200L),
            zoomCurveName = obj.optString("zoomCurveName", ZoomCurve.EXPONENTIAL.name),
            zoomPullDurationMs = obj.optLong("zoomPullDurationMs", 1500L),
            gridModeName = obj.optString("gridModeName", GridMode.OFF.name),
            aspectGuideName = obj.optString("aspectGuideName", AspectGuide.OFF.name),
            peakingColorName = obj.optString("peakingColorName", PeakingColor.GREEN.name),
            peakingSensitivityName = obj.optString("peakingSensitivityName", PeakingSensitivity.MED.name),
            zebraModeName = obj.optString("zebraModeName", ZebraMode.IRE_92.name),
            gpuExposureLift = obj.optDouble("gpuExposureLift", 0.0).toFloat(),
            gpuContrast = obj.optDouble("gpuContrast", 1.0).toFloat(),
            gpuSaturation = obj.optDouble("gpuSaturation", 1.0).toFloat(),
            gpuShadows = obj.optDouble("gpuShadows", 0.0).toFloat(),
            gpuHighlights = obj.optDouble("gpuHighlights", 0.0).toFloat(),
            gpuVignette = obj.optDouble("gpuVignette", 0.0).toFloat(),
            lutSourceId = obj.optString("lutSourceId", ""),
            lutIntensity = obj.optDouble("lutIntensity", 1.0).toFloat(),
            mirrorFrontExport = obj.optBoolean("mirrorFrontExport", false)
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
