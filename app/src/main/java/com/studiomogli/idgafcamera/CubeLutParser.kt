package com.studiomogli.idgafcamera

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Packed 2D Atlas representation of an N x N x N 3D LUT ready for OpenGL ES 2.0 GL_RGBA upload.
 * Atlas width = size * size, height = size (row = green, col = blue * size + red).
 */
data class CubeLutData(
    val sourceId: String,
    val displayName: String,
    val size: Int,
    val rgbaBytes: ByteArray
)

data class SavedLutEntry(
    val sourceId: String,
    val displayName: String,
    val subtitle: String,
    val file: File? // null for built-in world-class 3D LUTs
)

private data class HueLobe(val centerDeg: Float, val amount: Float, val widthDeg: Float)

private data class FilmStockProfile(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val matrix: FloatArray,
    val hueShifts: List<HueLobe>,
    val satMults: List<HueLobe>,
    val lumaShifts: List<HueLobe>,
    val density: Float,
    val contrast: Float,
    val pivot: Float,
    val toe: Float,
    val shoulder: Float,
    val gamma: Float,
    val shdTint: FloatArray,
    val midTint: FloatArray,
    val hilTint: FloatArray,
    val sat: Float,
    val hilDesat: Float,
    val bwWeights: FloatArray? = null
)

object CubeLutLibrary {
    private const val MAX_GPU_LUT_SIZE = 33
    private val ramCache = ConcurrentHashMap<String, CubeLutData>()

    // 12 World-Class Cinema 3D LUTs — True 7-Stage Film Color-Science Emulation (33x33x33)
    private val WORLD_CLASS_STOCKS: List<FilmStockProfile> = listOf(
        FilmStockProfile(
            id = "wc:2383_print_d60",
            displayName = "2383 PRINT D60",
            subtitle = "Hollywood 35mm Print • Deep Blacks & Cyan/Amber",
            matrix = floatArrayOf(1.05f, -0.03f, -0.02f, -0.02f, 1.02f, 0.00f, -0.03f, 0.06f, 0.97f),
            hueShifts = listOf(
                HueLobe(0f, 3f, 42f),
                HueLobe(30f, -1f, 35f),
                HueLobe(120f, 12f, 50f),
                HueLobe(225f, -24f, 65f),
                HueLobe(310f, 12f, 50f)
            ),
            satMults = listOf(
                HueLobe(0f, 1.12f, 42f),
                HueLobe(30f, 0.95f, 35f),
                HueLobe(120f, 0.84f, 55f),
                HueLobe(220f, 0.98f, 65f)
            ),
            lumaShifts = listOf(
                HueLobe(0f, -0.06f, 45f),
                HueLobe(120f, -0.05f, 50f),
                HueLobe(220f, -0.08f, 65f)
            ),
            density = 0.22f,
            contrast = 1.22f, pivot = 0.45f, toe = 0.012f, shoulder = 0.965f, gamma = 1.01f,
            shdTint = floatArrayOf(-0.038f, 0.014f, 0.048f),
            midTint = floatArrayOf(0.012f, 0.002f, -0.014f),
            hilTint = floatArrayOf(0.038f, 0.016f, -0.040f),
            sat = 1.03f, hilDesat = 0.28f
        ),
        FilmStockProfile(
            id = "wc:fuji_3513d",
            displayName = "FUJI 3513D",
            subtitle = "Cinema Print • Lush Emerald & Silky Skin",
            matrix = floatArrayOf(1.03f, -0.02f, -0.01f, -0.03f, 1.06f, -0.03f, -0.02f, 0.07f, 0.95f),
            hueShifts = listOf(
                HueLobe(0f, 5f, 40f),
                HueLobe(30f, 1f, 35f),
                HueLobe(115f, 16f, 55f),
                HueLobe(225f, -26f, 65f)
            ),
            satMults = listOf(
                HueLobe(0f, 1.06f, 45f),
                HueLobe(30f, 0.98f, 35f),
                HueLobe(120f, 0.90f, 55f),
                HueLobe(215f, 1.02f, 65f)
            ),
            lumaShifts = listOf(
                HueLobe(120f, -0.04f, 50f),
                HueLobe(220f, -0.06f, 60f)
            ),
            density = 0.18f,
            contrast = 1.15f, pivot = 0.46f, toe = 0.024f, shoulder = 0.960f, gamma = 0.98f,
            shdTint = floatArrayOf(-0.048f, 0.022f, 0.046f),
            midTint = floatArrayOf(0.016f, 0.004f, -0.012f),
            hilTint = floatArrayOf(0.026f, 0.016f, -0.024f),
            sat = 1.02f, hilDesat = 0.24f
        ),
        FilmStockProfile(
            id = "wc:arri_alf2",
            displayName = "ARRI ALF-2",
            subtitle = "Alexa Cinema Science • Natural Highlight Roll-Off",
            matrix = floatArrayOf(1.02f, -0.01f, -0.01f, -0.01f, 1.02f, -0.01f, 0.00f, 0.02f, 0.98f),
            hueShifts = listOf(
                HueLobe(0f, 4f, 40f),
                HueLobe(115f, 12f, 50f),
                HueLobe(230f, -12f, 55f)
            ),
            satMults = listOf(
                HueLobe(30f, 1.02f, 35f),
                HueLobe(120f, 0.86f, 50f),
                HueLobe(230f, 0.92f, 55f)
            ),
            lumaShifts = listOf(
                HueLobe(0f, -0.04f, 45f),
                HueLobe(230f, -0.04f, 55f)
            ),
            density = 0.16f,
            contrast = 1.12f, pivot = 0.44f, toe = 0.018f, shoulder = 0.975f, gamma = 0.97f,
            shdTint = floatArrayOf(-0.012f, 0.004f, 0.014f),
            midTint = floatArrayOf(0.008f, 0.002f, -0.006f),
            hilTint = floatArrayOf(0.008f, 0.004f, -0.006f),
            sat = 0.98f, hilDesat = 0.30f
        ),
        FilmStockProfile(
            id = "wc:portra_400",
            displayName = "PORTRA 400",
            subtitle = "Golden Skin & Creamy Pastel Negative",
            matrix = floatArrayOf(1.04f, 0.02f, -0.06f, 0.00f, 1.01f, -0.01f, -0.02f, 0.04f, 0.98f),
            hueShifts = listOf(
                HueLobe(0f, 8f, 45f),
                HueLobe(30f, 2f, 35f),
                HueLobe(120f, -10f, 55f),
                HueLobe(220f, -16f, 60f)
            ),
            satMults = listOf(
                HueLobe(30f, 1.04f, 38f),
                HueLobe(120f, 0.72f, 55f),
                HueLobe(220f, 0.80f, 60f)
            ),
            lumaShifts = listOf(
                HueLobe(30f, 0.04f, 38f),
                HueLobe(120f, 0.02f, 50f)
            ),
            density = 0.10f,
            contrast = 0.95f, pivot = 0.47f, toe = 0.046f, shoulder = 0.960f, gamma = 0.94f,
            shdTint = floatArrayOf(0.012f, 0.014f, 0.018f),
            midTint = floatArrayOf(0.024f, 0.012f, -0.020f),
            hilTint = floatArrayOf(0.036f, 0.022f, -0.026f),
            sat = 0.92f, hilDesat = 0.22f
        ),
        FilmStockProfile(
            id = "wc:cinestill_800t",
            displayName = "CINESTILL 800T",
            subtitle = "Tungsten Night • Cool Cyan Shadows & Amber Lamps",
            matrix = floatArrayOf(1.03f, -0.03f, 0.00f, -0.01f, 1.02f, -0.01f, -0.03f, 0.07f, 0.96f),
            hueShifts = listOf(
                HueLobe(0f, -3f, 38f),
                HueLobe(45f, -5f, 35f),
                HueLobe(120f, 22f, 55f),
                HueLobe(225f, -18f, 65f)
            ),
            satMults = listOf(
                HueLobe(0f, 1.18f, 40f),
                HueLobe(30f, 0.96f, 35f),
                HueLobe(120f, 0.78f, 55f),
                HueLobe(210f, 1.04f, 65f)
            ),
            lumaShifts = listOf(
                HueLobe(0f, -0.04f, 45f),
                HueLobe(220f, -0.06f, 60f)
            ),
            density = 0.20f,
            contrast = 1.20f, pivot = 0.46f, toe = 0.016f, shoulder = 0.970f, gamma = 1.02f,
            shdTint = floatArrayOf(-0.064f, 0.018f, 0.068f),
            midTint = floatArrayOf(-0.022f, 0.006f, 0.028f),
            hilTint = floatArrayOf(0.052f, 0.016f, -0.044f),
            sat = 1.02f, hilDesat = 0.18f
        ),
        FilmStockProfile(
            id = "wc:classic_chrome",
            displayName = "CLASSIC CHROME",
            subtitle = "Editorial Documentary • Muted Earth & Deep Tone",
            matrix = floatArrayOf(1.02f, -0.03f, 0.01f, -0.01f, 1.01f, 0.00f, -0.02f, 0.05f, 0.97f),
            hueShifts = listOf(
                HueLobe(0f, 9f, 45f),
                HueLobe(120f, -14f, 55f),
                HueLobe(225f, -22f, 65f)
            ),
            satMults = listOf(
                HueLobe(0f, 0.78f, 45f),
                HueLobe(30f, 0.88f, 35f),
                HueLobe(120f, 0.62f, 55f),
                HueLobe(220f, 0.72f, 65f)
            ),
            lumaShifts = listOf(
                HueLobe(0f, -0.07f, 45f),
                HueLobe(220f, -0.09f, 65f)
            ),
            density = 0.24f,
            contrast = 1.18f, pivot = 0.48f, toe = 0.022f, shoulder = 0.945f, gamma = 1.02f,
            shdTint = floatArrayOf(-0.024f, 0.008f, 0.022f),
            midTint = floatArrayOf(0.006f, 0.002f, -0.008f),
            hilTint = floatArrayOf(0.022f, 0.016f, -0.014f),
            sat = 0.78f, hilDesat = 0.28f
        ),
        FilmStockProfile(
            id = "wc:bleach_bypass",
            displayName = "BLEACH BYPASS",
            subtitle = "Lab Silver Retention • Gritty Steely Contrast",
            matrix = floatArrayOf(1.00f, 0.00f, 0.00f, 0.00f, 1.00f, 0.00f, 0.00f, 0.00f, 1.00f),
            hueShifts = listOf(
                HueLobe(220f, -12f, 60f),
                HueLobe(120f, -16f, 55f)
            ),
            satMults = listOf(
                HueLobe(30f, 0.78f, 38f),
                HueLobe(120f, 0.45f, 55f),
                HueLobe(220f, 0.55f, 60f)
            ),
            lumaShifts = listOf(
                HueLobe(220f, -0.10f, 60f)
            ),
            density = 0.26f,
            contrast = 1.42f, pivot = 0.48f, toe = 0.006f, shoulder = 0.985f, gamma = 1.06f,
            shdTint = floatArrayOf(-0.024f, -0.004f, 0.022f),
            midTint = floatArrayOf(-0.006f, 0.002f, 0.008f),
            hilTint = floatArrayOf(0.010f, 0.014f, 0.018f),
            sat = 0.46f, hilDesat = 0.42f
        ),
        FilmStockProfile(
            id = "wc:kodachrome_64",
            displayName = "KODACHROME 64",
            subtitle = "Archival Slide • Velvety Brick Reds & Rich Warmth",
            matrix = floatArrayOf(1.07f, -0.04f, -0.03f, -0.02f, 1.03f, -0.01f, -0.03f, 0.03f, 1.00f),
            hueShifts = listOf(
                HueLobe(0f, -4f, 38f),
                HueLobe(30f, -2f, 32f),
                HueLobe(120f, -12f, 55f),
                HueLobe(225f, -14f, 60f)
            ),
            satMults = listOf(
                HueLobe(0f, 1.22f, 40f),
                HueLobe(30f, 0.94f, 34f),
                HueLobe(120f, 0.78f, 55f),
                HueLobe(225f, 0.96f, 60f)
            ),
            lumaShifts = listOf(
                HueLobe(0f, -0.10f, 42f),
                HueLobe(225f, -0.08f, 60f)
            ),
            density = 0.24f,
            contrast = 1.24f, pivot = 0.46f, toe = 0.008f, shoulder = 0.960f, gamma = 1.03f,
            shdTint = floatArrayOf(-0.018f, 0.002f, 0.016f),
            midTint = floatArrayOf(0.020f, 0.008f, -0.022f),
            hilTint = floatArrayOf(0.034f, 0.020f, -0.032f),
            sat = 1.04f, hilDesat = 0.22f
        ),
        FilmStockProfile(
            id = "wc:neon_jade",
            displayName = "NEON JADE",
            subtitle = "Wong Kar-wai Nocturne • Emerald Shadows & Amber",
            matrix = floatArrayOf(1.03f, -0.05f, 0.02f, -0.03f, 1.07f, -0.04f, -0.03f, 0.05f, 0.98f),
            hueShifts = listOf(
                HueLobe(0f, -4f, 42f),
                HueLobe(50f, -10f, 42f),
                HueLobe(120f, 26f, 60f),
                HueLobe(220f, -32f, 70f)
            ),
            satMults = listOf(
                HueLobe(0f, 1.18f, 42f),
                HueLobe(30f, 0.96f, 35f),
                HueLobe(140f, 0.92f, 60f),
                HueLobe(200f, 1.02f, 60f)
            ),
            lumaShifts = listOf(
                HueLobe(130f, -0.06f, 55f),
                HueLobe(220f, -0.07f, 60f)
            ),
            density = 0.22f,
            contrast = 1.20f, pivot = 0.45f, toe = 0.020f, shoulder = 0.955f, gamma = 1.02f,
            shdTint = floatArrayOf(-0.062f, 0.042f, 0.020f),
            midTint = floatArrayOf(-0.024f, 0.024f, -0.008f),
            hilTint = floatArrayOf(0.050f, 0.022f, -0.050f),
            sat = 1.02f, hilDesat = 0.20f
        ),
        FilmStockProfile(
            id = "wc:desert_gold",
            displayName = "DESERT GOLD",
            subtitle = "Epic Theatrical Amber • Golden Ochre & Bronze",
            matrix = floatArrayOf(1.06f, 0.02f, -0.08f, 0.00f, 1.01f, -0.01f, -0.04f, -0.02f, 1.06f),
            hueShifts = listOf(
                HueLobe(0f, 10f, 45f),
                HueLobe(120f, -34f, 65f),
                HueLobe(220f, -20f, 65f)
            ),
            satMults = listOf(
                HueLobe(35f, 1.06f, 45f),
                HueLobe(120f, 0.56f, 60f),
                HueLobe(220f, 0.62f, 65f)
            ),
            lumaShifts = listOf(
                HueLobe(120f, -0.06f, 55f),
                HueLobe(220f, -0.06f, 60f)
            ),
            density = 0.20f,
            contrast = 1.18f, pivot = 0.46f, toe = 0.024f, shoulder = 0.955f, gamma = 1.02f,
            shdTint = floatArrayOf(-0.016f, -0.006f, 0.012f),
            midTint = floatArrayOf(0.038f, 0.015f, -0.046f),
            hilTint = floatArrayOf(0.062f, 0.034f, -0.068f),
            sat = 0.92f, hilDesat = 0.24f
        ),
        FilmStockProfile(
            id = "wc:nordic_slate",
            displayName = "NORDIC SLATE",
            subtitle = "Fincher / Cold Thriller • Steel Blue Shadows",
            matrix = floatArrayOf(0.96f, 0.02f, 0.02f, 0.00f, 1.00f, 0.00f, -0.02f, 0.06f, 0.96f),
            hueShifts = listOf(
                HueLobe(120f, 18f, 60f),
                HueLobe(225f, -18f, 65f)
            ),
            satMults = listOf(
                HueLobe(30f, 0.88f, 36f),
                HueLobe(120f, 0.52f, 60f),
                HueLobe(215f, 1.04f, 65f)
            ),
            lumaShifts = listOf(
                HueLobe(220f, -0.06f, 60f)
            ),
            density = 0.22f,
            contrast = 1.20f, pivot = 0.47f, toe = 0.018f, shoulder = 0.950f, gamma = 1.05f,
            shdTint = floatArrayOf(-0.056f, 0.008f, 0.064f),
            midTint = floatArrayOf(-0.034f, 0.006f, 0.042f),
            hilTint = floatArrayOf(-0.016f, 0.008f, 0.022f),
            sat = 0.76f, hilDesat = 0.30f
        ),
        FilmStockProfile(
            id = "wc:trix_400_bw",
            displayName = "TRI-X 400 BW",
            subtitle = "Panchromatic Silver B&W • Dark Skies & Luminous Skin",
            matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            hueShifts = emptyList(),
            satMults = emptyList(),
            lumaShifts = emptyList(),
            density = 0.0f,
            contrast = 1.36f, pivot = 0.45f, toe = 0.014f, shoulder = 0.975f, gamma = 1.02f,
            shdTint = floatArrayOf(0f, 0f, 0f),
            midTint = floatArrayOf(0f, 0f, 0f),
            hilTint = floatArrayOf(0f, 0f, 0f),
            sat = 0.0f, hilDesat = 0.0f,
            bwWeights = floatArrayOf(0.46f, 0.44f, 0.10f)
        )
    )

    private val STOCK_BY_ID: Map<String, FilmStockProfile> = WORLD_CLASS_STOCKS.associateBy { it.id }

    fun getBuiltInEntries(): List<SavedLutEntry> = WORLD_CLASS_STOCKS.map { stock ->
        SavedLutEntry(
            sourceId = stock.id,
            displayName = stock.displayName,
            subtitle = stock.subtitle,
            file = null
        )
    }

    fun getLutDir(context: Context): File {
        val dir = File(context.filesDir, "luts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSavedUserLuts(context: Context): List<SavedLutEntry> {
        val dir = getLutDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".cube", ignoreCase = true) }
            ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { f ->
            SavedLutEntry(
                sourceId = "file:${f.name}",
                displayName = f.nameWithoutExtension.replace('_', ' ').take(22),
                subtitle = "Imported .cube 3D LUT",
                file = f
            )
        }
    }

    fun deleteUserLut(context: Context, sourceId: String): Boolean {
        if (!sourceId.startsWith("file:")) return false
        ramCache.remove(sourceId)
        val fname = sourceId.removePrefix("file:")
        val target = File(getLutDir(context), fname)
        return target.exists() && target.delete()
    }

    fun sanitizeCubeFileName(rawName: String): String {
        val base = rawName.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".cube")
            .removeSuffix(".CUBE")
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(40)
            .ifEmpty { "Custom_LUT_${System.currentTimeMillis() % 10000}" }
        return "$base.cube"
    }

    fun saveCubeTextToLibrary(context: Context, rawFileName: String, cubeText: String): SavedLutEntry {
        val cleanFileName = sanitizeCubeFileName(rawFileName)
        val srcId = "file:$cleanFileName"
        val parsed = parseCubeString(
            cubeText = cubeText,
            sourceId = srcId,
            fallbackName = cleanFileName.removeSuffix(".cube")
        )
        val dest = File(getLutDir(context), cleanFileName)
        dest.writeText(cubeText, Charsets.UTF_8)
        ramCache[srcId] = parsed
        return SavedLutEntry(
            sourceId = srcId,
            displayName = parsed.displayName,
            subtitle = "Imported .cube 3D LUT",
            file = dest
        )
    }

    fun loadLutById(context: Context, sourceId: String): CubeLutData? {
        if (sourceId.isBlank()) return null
        ramCache[sourceId]?.let { return it }

        val builtInProfile = STOCK_BY_ID[sourceId]
        if (builtInProfile != null) {
            val built = synthesizeWorldClassLut(builtInProfile)
            ramCache[sourceId] = built
            return built
        }

        if (!sourceId.startsWith("file:")) return null
        val fname = sourceId.removePrefix("file:")
        val f = File(getLutDir(context), fname)
        if (!f.exists()) return null
        val parsed = f.inputStream().use { ins ->
            parseCubeStream(ins, sourceId, f.nameWithoutExtension.replace('_', ' '))
        }
        ramCache[sourceId] = parsed
        return parsed
    }

    fun parseCubeStream(stream: InputStream, sourceId: String, fallbackName: String): CubeLutData {
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        return parseCubeString(text, sourceId, fallbackName)
    }

    fun parseCubeString(cubeText: String, sourceId: String, fallbackName: String): CubeLutData {
        var title = fallbackName.trim().ifEmpty { "Custom LUT" }
        var lutSize = 0
        var has1dLut = false
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)

        val lines = cubeText.lineSequence()
        var floats: FloatArray? = null
        var floatIdx = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val upper = line.uppercase()
            when {
                upper.startsWith("TITLE") -> {
                    val t = line.substring(5).trim().trim('"').trim()
                    if (t.isNotEmpty()) title = t.take(24)
                }
                upper.startsWith("LUT_1D_SIZE") -> {
                    has1dLut = true
                }
                upper.startsWith("LUT_3D_SIZE") -> {
                    val parts = line.split(Regex("\\s+"))
                    val parsedSize = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    if (parsedSize < 2 || parsedSize > 128) {
                        throw IllegalArgumentException("Unsupported LUT_3D_SIZE ($parsedSize). Expected 2..128.")
                    }
                    lutSize = parsedSize
                    floats = FloatArray(lutSize * lutSize * lutSize * 3)
                }
                upper.startsWith("DOMAIN_MIN") -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        domainMin[0] = parts[1].toFloatOrNull() ?: 0f
                        domainMin[1] = parts[2].toFloatOrNull() ?: 0f
                        domainMin[2] = parts[3].toFloatOrNull() ?: 0f
                    }
                }
                upper.startsWith("DOMAIN_MAX") -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        domainMax[0] = parts[1].toFloatOrNull() ?: 1f
                        domainMax[1] = parts[2].toFloatOrNull() ?: 1f
                        domainMax[2] = parts[3].toFloatOrNull() ?: 1f
                    }
                }
                else -> {
                    val c0 = line[0]
                    if (c0 == '-' || c0 == '+' || c0 == '.' || c0.isDigit()) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                val arr = floats ?: throw IllegalArgumentException("Missing LUT_3D_SIZE header before data.")
                                if (floatIdx + 2 < arr.size) {
                                    arr[floatIdx++] = r
                                    arr[floatIdx++] = g
                                    arr[floatIdx++] = b
                                }
                            }
                        }
                    }
                }
            }
        }

        if (lutSize == 0) {
            if (has1dLut) {
                throw IllegalArgumentException("1D .cube LUTs are not supported — please import a 3D .cube LUT.")
            }
            throw IllegalArgumentException("Invalid .cube file: missing LUT_3D_SIZE header.")
        }

        val data = floats ?: throw IllegalArgumentException("Empty .cube LUT data.")
        val expectedFloats = lutSize * lutSize * lutSize * 3
        if (floatIdx < expectedFloats) {
            throw IllegalArgumentException(
                "Incomplete .cube LUT: found ${floatIdx / 3} of ${expectedFloats / 3} RGB entries."
            )
        }

        val dR = (domainMax[0] - domainMin[0]).takeIf { it > 0.0001f } ?: 1f
        val dG = (domainMax[1] - domainMin[1]).takeIf { it > 0.0001f } ?: 1f
        val dB = (domainMax[2] - domainMin[2]).takeIf { it > 0.0001f } ?: 1f
        val needsDomainNorm = (domainMin[0] != 0f || domainMin[1] != 0f || domainMin[2] != 0f ||
            dR != 1f || dG != 1f || dB != 1f)
        if (needsDomainNorm) {
            var i = 0
            while (i < expectedFloats) {
                data[i] = ((data[i] - domainMin[0]) / dR).coerceIn(0f, 1f)
                data[i + 1] = ((data[i + 1] - domainMin[1]) / dG).coerceIn(0f, 1f)
                data[i + 2] = ((data[i + 2] - domainMin[2]) / dB).coerceIn(0f, 1f)
                i += 3
            }
        }

        // Resample > 33 (e.g. 64^3 or 65^3) cleanly down to 33x33x33 so atlas width = 1089 <= 4096 on EVERY GPU!
        val finalSize: Int
        val finalFloats: FloatArray
        if (lutSize > MAX_GPU_LUT_SIZE) {
            finalSize = MAX_GPU_LUT_SIZE
            finalFloats = resample3dLut(data, lutSize, MAX_GPU_LUT_SIZE)
        } else {
            finalSize = lutSize
            finalFloats = data
        }

        val rgba = packFloatsTo2dAtlasRgba(finalFloats, finalSize)
        return CubeLutData(
            sourceId = sourceId,
            displayName = title,
            size = finalSize,
            rgbaBytes = rgba
        )
    }

    private fun resample3dLut(src: FloatArray, srcN: Int, dstN: Int): FloatArray {
        val dst = FloatArray(dstN * dstN * dstN * 3)
        val scale = (srcN - 1).toFloat() / (dstN - 1).toFloat()
        val srcStrideB = srcN * srcN * 3
        val srcStrideG = srcN * 3

        var outIdx = 0
        for (b in 0 until dstN) {
            val sb = b * scale
            val b0 = floor(sb).toInt().coerceIn(0, srcN - 1)
            val b1 = (b0 + 1).coerceAtMost(srcN - 1)
            val fb = sb - b0
            for (g in 0 until dstN) {
                val sg = g * scale
                val g0 = floor(sg).toInt().coerceIn(0, srcN - 1)
                val g1 = (g0 + 1).coerceAtMost(srcN - 1)
                val fg = sg - g0
                for (r in 0 until dstN) {
                    val sr = r * scale
                    val r0 = floor(sr).toInt().coerceIn(0, srcN - 1)
                    val r1 = (r0 + 1).coerceAtMost(srcN - 1)
                    val fr = sr - r0

                    val i000 = b0 * srcStrideB + g0 * srcStrideG + r0 * 3
                    val i100 = b0 * srcStrideB + g0 * srcStrideG + r1 * 3
                    val i010 = b0 * srcStrideB + g1 * srcStrideG + r0 * 3
                    val i110 = b0 * srcStrideB + g1 * srcStrideG + r1 * 3
                    val i001 = b1 * srcStrideB + g0 * srcStrideG + r0 * 3
                    val i101 = b1 * srcStrideB + g0 * srcStrideG + r1 * 3
                    val i011 = b1 * srcStrideB + g1 * srcStrideG + r0 * 3
                    val i111 = b1 * srcStrideB + g1 * srcStrideG + r1 * 3

                    for (ch in 0..2) {
                        val c00 = src[i000 + ch] * (1f - fr) + src[i100 + ch] * fr
                        val c10 = src[i010 + ch] * (1f - fr) + src[i110 + ch] * fr
                        val c01 = src[i001 + ch] * (1f - fr) + src[i101 + ch] * fr
                        val c11 = src[i011 + ch] * (1f - fr) + src[i111 + ch] * fr
                        val c0 = c00 * (1f - fg) + c10 * fg
                        val c1 = c01 * (1f - fg) + c11 * fg
                        dst[outIdx++] = (c0 * (1f - fb) + c1 * fb).coerceIn(0f, 1f)
                    }
                }
            }
        }
        return dst
    }

    /**
     * Packs standard .cube order (r fastest, g middle, b slowest) into 2D RGBA atlas:
     * width = N * N, height = N; pixel(x = b * N + r, y = g).
     */
    private fun packFloatsTo2dAtlasRgba(floats: FloatArray, n: Int): ByteArray {
        val atlasW = n * n
        val rgba = ByteArray(atlasW * n * 4)
        var srcIdx = 0
        for (b in 0 until n) {
            for (g in 0 until n) {
                val rowBase = g * atlasW
                for (r in 0 until n) {
                    val outR = (floats[srcIdx++].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                    val outG = (floats[srcIdx++].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                    val outB = (floats[srcIdx++].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                    val dstOffset = (rowBase + b * n + r) * 4
                    rgba[dstOffset] = outR.toByte()
                    rgba[dstOffset + 1] = outG.toByte()
                    rgba[dstOffset + 2] = outB.toByte()
                    rgba[dstOffset + 3] = 255.toByte()
                }
            }
        }
        return rgba
    }

    // ==================== 7-STAGE LINEAR-LIGHT FILM COLOR-SCIENCE ENGINE ====================

    private fun synthesizeWorldClassLut(cfg: FilmStockProfile): CubeLutData {
        val n = 33
        val floats = FloatArray(n * n * n * 3)
        val inv = 1f / (n - 1).toFloat()
        val linTable = FloatArray(n) { i -> (i * inv).toDouble().pow(2.2).toFloat() }
        val m = cfg.matrix
        val bw = cfg.bwWeights
        val shdT = cfg.shdTint
        val midT = cfg.midTint
        val hilT = cfg.hilTint

        var idx = 0
        for (bi in 0 until n) {
            val bLinIn = linTable[bi]
            for (gi in 0 until n) {
                val gLinIn = linTable[gi]
                for (ri in 0 until n) {
                    val rLinIn = linTable[ri]

                    if (bw != null) {
                        val yLin = (bw[0] * rLinIn + bw[1] * gLinIn + bw[2] * bLinIn).coerceIn(0f, 1f)
                        var y = yLin.toDouble().pow(1.0 / 2.2).toFloat()
                        y = filmCurve(y, cfg.contrast, cfg.pivot, cfg.toe, cfg.shoulder, cfg.gamma)
                        val shd = (1f - y) * (1f - y)
                        val hil = y * y
                        floats[idx++] = (y + hil * 0.010f - shd * 0.006f).coerceIn(0f, 1f)
                        floats[idx++] = (y + hil * 0.004f).coerceIn(0f, 1f)
                        floats[idx++] = (y - hil * 0.010f + shd * 0.010f).coerceIn(0f, 1f)
                        continue
                    }

                    // Stage 1: Linear-light 3x3 spectral dye-layer crosstalk matrix
                    val lr = (m[0] * rLinIn + m[1] * gLinIn + m[2] * bLinIn).coerceIn(0f, 1f)
                    val lg = (m[3] * rLinIn + m[4] * gLinIn + m[5] * bLinIn).coerceIn(0f, 1f)
                    val lb = (m[6] * rLinIn + m[7] * gLinIn + m[8] * bLinIn).coerceIn(0f, 1f)

                    var r = lr.toDouble().pow(1.0 / 2.2).toFloat()
                    var g = lg.toDouble().pow(1.0 / 2.2).toFloat()
                    var b = lb.toDouble().pow(1.0 / 2.2).toFloat()

                    // Stage 2 & 3: 6-Vector HSV Hue/Sat/Luma Warping + Subtractive Film Density
                    val mx = maxOf(r, maxOf(g, b))
                    val mn = minOf(r, minOf(g, b))
                    val d = mx - mn
                    val sRaw = if (mx < 1e-6f) 0f else d / mx
                    if (sRaw > 0.01f) {
                        var h = when {
                            d < 1e-6f -> 0f
                            mx == r -> 60f * (((g - b) / d) % 6f)
                            mx == g -> 60f * (((b - r) / d) + 2f)
                            else -> 60f * (((r - g) / d) + 4f)
                        }
                        if (h < 0f) h += 360f

                        var dh = 0f
                        for (lobe in cfg.hueShifts) {
                            dh += lobe.amount * hueWeight(h, lobe.centerDeg, lobe.widthDeg)
                        }
                        var sm = 1f
                        for (lobe in cfg.satMults) {
                            sm += (lobe.amount - 1f) * hueWeight(h, lobe.centerDeg, lobe.widthDeg)
                        }
                        var dl = 0f
                        for (lobe in cfg.lumaShifts) {
                            dl += lobe.amount * hueWeight(h, lobe.centerDeg, lobe.widthDeg)
                        }

                        h = (h + dh) % 360f
                        if (h < 0f) h += 360f
                        val sNew = (sRaw * sm).coerceIn(0f, 1f)
                        val midBell = (4f * mx * (1f - mx)).coerceIn(0f, 1f)
                        val densityDrop = cfg.density * sNew.toDouble().pow(1.35).toFloat() * midBell
                        val vNew = (mx * (1f + dl * sNew - densityDrop)).coerceIn(0f, 1f)

                        // Convert HSV back to RGB inline (zero object allocations!)
                        val c = vNew * sNew
                        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
                        val mOff = vNew - c
                        when {
                            h < 60f -> { r = c + mOff; g = x + mOff; b = mOff }
                            h < 120f -> { r = x + mOff; g = c + mOff; b = mOff }
                            h < 180f -> { r = mOff; g = c + mOff; b = x + mOff }
                            h < 240f -> { r = mOff; g = x + mOff; b = c + mOff }
                            h < 300f -> { r = x + mOff; g = mOff; b = c + mOff }
                            else -> { r = c + mOff; g = mOff; b = x + mOff }
                        }
                    }

                    // Stage 4: Pivot-centered filmic S-curve with toe & soft highlight shoulder roll-off
                    r = filmCurve(r, cfg.contrast, cfg.pivot, cfg.toe, cfg.shoulder, cfg.gamma)
                    g = filmCurve(g, cfg.contrast, cfg.pivot, cfg.toe, cfg.shoulder, cfg.gamma)
                    b = filmCurve(b, cfg.contrast, cfg.pivot, cfg.toe, cfg.shoulder, cfg.gamma)

                    // Stage 5: Zone Split-Toning (Shadows / Midtones / Highlights) with pure-black protection
                    val y = 0.299f * r + 0.587f * g + 0.114f * b
                    val blkGuard = (y / 0.10f).coerceIn(0f, 1f)
                    val shdW = (1f - y).toDouble().pow(2.2).toFloat() * blkGuard
                    val midW = (4f * y * (1f - y)).coerceIn(0f, 1f)
                    val hilW = y * y

                    r = (r + shdT[0] * shdW + midT[0] * midW + hilT[0] * hilW).coerceIn(0f, 1f)
                    g = (g + shdT[1] * shdW + midT[1] * midW + hilT[1] * hilW).coerceIn(0f, 1f)
                    b = (b + shdT[2] * shdW + midT[2] * midW + hilT[2] * hilW).coerceIn(0f, 1f)

                    // Stage 6: Global Saturation + Filmic Specular Highlight Desaturation
                    val y2 = 0.299f * r + 0.587f * g + 0.114f * b
                    val hilRoll = ((y2 - 0.65f) / 0.35f).coerceIn(0f, 1f)
                    val effSat = cfg.sat * (1f - cfg.hilDesat * hilRoll.toDouble().pow(1.5).toFloat())
                    floats[idx++] = (y2 + (r - y2) * effSat).coerceIn(0f, 1f)
                    floats[idx++] = (y2 + (g - y2) * effSat).coerceIn(0f, 1f)
                    floats[idx++] = (y2 + (b - y2) * effSat).coerceIn(0f, 1f)
                }
            }
        }
        return CubeLutData(
            sourceId = cfg.id,
            displayName = cfg.displayName,
            size = n,
            rgbaBytes = packFloatsTo2dAtlasRgba(floats, n)
        )
    }

    private fun hueWeight(h: Float, center: Float, width: Float): Float {
        var diff = (h - center + 180f) % 360f
        if (diff < 0f) diff += 360f
        val d = abs(diff - 180f)
        if (d >= width) return 0f
        return (0.5 * (1.0 + cos(PI * (d / width).toDouble()))).toFloat()
    }

    private fun filmCurve(
        x: Float,
        contrast: Float,
        pivot: Float,
        toe: Float,
        shoulder: Float,
        gamma: Float
    ): Float {
        var c = x.coerceIn(0f, 1f)
        if (abs(gamma - 1f) > 0.0001f) {
            c = c.toDouble().pow(gamma.toDouble()).toFloat()
        }
        var s = if (c < pivot) {
            pivot * (c / pivot.coerceAtLeast(0.0001f)).toDouble().pow(contrast.toDouble()).toFloat()
        } else {
            1f - (1f - pivot) * ((1f - c) / (1f - pivot).coerceAtLeast(0.0001f)).toDouble().pow(contrast.toDouble()).toFloat()
        }
        // Soft hyperbolic highlight roll-off (matches DaVinci / Filmic shoulder)
        s = ((s / (1f + 0.08f * s * s)) * 1.08f).coerceIn(0f, 1f)
        return (toe + s * (shoulder - toe)).coerceIn(0f, 1f)
    }
}
