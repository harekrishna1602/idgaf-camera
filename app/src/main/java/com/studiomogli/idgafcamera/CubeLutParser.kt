package com.studiomogli.idgafcamera

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
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
    val file: File? // null for built-in algorithmic 3D LUTs
)

object CubeLutLibrary {
    const val BUILTIN_KODAK_2383 = "builtin:kodak_2383"
    const val BUILTIN_TEAL_ORANGE = "builtin:teal_orange"
    const val BUILTIN_FUJI_ETERNA = "builtin:fuji_eterna"

    private const val MAX_GPU_LUT_SIZE = 33

    fun getBuiltInEntries(): List<SavedLutEntry> = listOf(
        SavedLutEntry(BUILTIN_KODAK_2383, "KODAK 2383", null),
        SavedLutEntry(BUILTIN_TEAL_ORANGE, "TEAL & ORG", null),
        SavedLutEntry(BUILTIN_FUJI_ETERNA, "FUJI ETERNA", null)
    )

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
                file = f
            )
        }
    }

    fun deleteUserLut(context: Context, sourceId: String): Boolean {
        if (!sourceId.startsWith("file:")) return false
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
        // Validate by parsing first so corrupt files are never saved
        val cleanFileName = sanitizeCubeFileName(rawFileName)
        val parsed = parseCubeString(
            cubeText = cubeText,
            sourceId = "file:$cleanFileName",
            fallbackName = cleanFileName.removeSuffix(".cube")
        )
        val dest = File(getLutDir(context), cleanFileName)
        dest.writeText(cubeText, Charsets.UTF_8)
        return SavedLutEntry(
            sourceId = "file:$cleanFileName",
            displayName = parsed.displayName,
            file = dest
        )
    }

    fun loadLutById(context: Context, sourceId: String): CubeLutData? {
        if (sourceId.isBlank()) return null
        return when (sourceId) {
            BUILTIN_KODAK_2383 -> buildBuiltInLut(BUILTIN_KODAK_2383, "KODAK 2383")
            BUILTIN_TEAL_ORANGE -> buildBuiltInLut(BUILTIN_TEAL_ORANGE, "TEAL & ORG")
            BUILTIN_FUJI_ETERNA -> buildBuiltInLut(BUILTIN_FUJI_ETERNA, "FUJI ETERNA")
            else -> {
                if (!sourceId.startsWith("file:")) return null
                val fname = sourceId.removePrefix("file:")
                val f = File(getLutDir(context), fname)
                if (!f.exists()) return null
                f.inputStream().use { ins ->
                    parseCubeStream(ins, sourceId, f.nameWithoutExtension.replace('_', ' '))
                }
            }
        }
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

        // Normalize values if DOMAIN_MIN / DOMAIN_MAX isn't [0..1]
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

    /**
     * Generates 3 genuine broadcast-grade 33x33x33 cinema 3D LUTs directly in memory
     * so users can experience the 3D LUT engine immediately with zero extra APK bloat.
     */
    private fun buildBuiltInLut(id: String, title: String): CubeLutData {
        val n = 33
        val floats = FloatArray(n * n * n * 3)
        val inv = 1f / (n - 1).toFloat()
        var idx = 0

        for (bi in 0 until n) {
            val bIn = bi * inv
            for (gi in 0 until n) {
                val gIn = gi * inv
                for (ri in 0 until n) {
                    val rIn = ri * inv
                    var r = rIn
                    var g = gIn
                    var b = bIn

                    val luma = 0.299f * r + 0.587f * g + 0.114f * b
                    val shdW = (1f - luma) * (1f - luma)
                    val midW = (4f * luma * (1f - luma)).coerceIn(0f, 1f)
                    val hilW = luma * luma

                    when (id) {
                        BUILTIN_KODAK_2383 -> {
                            // Classic Kodak 2383 Print Film: deep rich shadows with cool cyan-green toe,
                            // warm golden highlights, filmic S-curve & smooth highlight rolloff
                            r = filmicSCurve(r, 1.16f, 0.025f, 0.96f)
                            g = filmicSCurve(g, 1.14f, 0.020f, 0.96f)
                            b = filmicSCurve(b, 1.12f, 0.020f, 0.94f)
                            r += (hilW * 0.055f) - (shdW * 0.032f)
                            g += (hilW * 0.022f) + (shdW * 0.008f)
                            b += (shdW * 0.042f) - (hilW * 0.052f)
                            // Gentle film saturation boost in midtones, gentle rolloff in highlights
                            val y2 = 0.299f * r + 0.587f * g + 0.114f * b
                            val sat = 1.08f - hilW * 0.18f
                            r = y2 + (r - y2) * sat
                            g = y2 + (g - y2) * sat
                            b = y2 + (b - y2) * sat
                        }
                        BUILTIN_TEAL_ORANGE -> {
                            // Modern Hollywood Teal & Orange split-tone with protected skin tones
                            r = filmicSCurve(r, 1.18f, 0.015f, 0.98f)
                            g = filmicSCurve(g, 1.15f, 0.015f, 0.98f)
                            b = filmicSCurve(b, 1.16f, 0.015f, 0.97f)
                            // Push shadows strongly toward cinema teal, highlights & warm mids toward orange/amber
                            r += (midW * 0.055f) + (hilW * 0.055f) - (shdW * 0.070f)
                            g += (shdW * 0.022f) + (midW * 0.012f) - (hilW * 0.008f)
                            b += (shdW * 0.075f) - (midW * 0.045f) - (hilW * 0.075f)
                            val y2 = 0.299f * r + 0.587f * g + 0.114f * b
                            r = y2 + (r - y2) * 1.12f
                            g = y2 + (g - y2) * 1.12f
                            b = y2 + (b - y2) * 1.12f
                        }
                        BUILTIN_FUJI_ETERNA -> {
                            // Fujifilm Eterna Cinema: lifted matte blacks, clean pastel greens, creamy highlights
                            r = filmicSCurve(r, 0.96f, 0.045f, 0.95f)
                            g = filmicSCurve(g, 0.98f, 0.048f, 0.96f)
                            b = filmicSCurve(b, 0.97f, 0.052f, 0.96f)
                            r += (midW * 0.015f) - (shdW * 0.015f)
                            g += (shdW * 0.018f) + (midW * 0.018f)
                            b += (shdW * 0.025f) - (hilW * 0.022f)
                            val y2 = 0.299f * r + 0.587f * g + 0.114f * b
                            r = y2 + (r - y2) * 0.88f
                            g = y2 + (g - y2) * 0.88f
                            b = y2 + (b - y2) * 0.88f
                        }
                    }

                    floats[idx++] = r.coerceIn(0f, 1f)
                    floats[idx++] = g.coerceIn(0f, 1f)
                    floats[idx++] = b.coerceIn(0f, 1f)
                }
            }
        }
        return CubeLutData(
            sourceId = id,
            displayName = title,
            size = n,
            rgbaBytes = packFloatsTo2dAtlasRgba(floats, n)
        )
    }

    private fun filmicSCurve(x: Float, contrast: Float, toeLift: Float, ceiling: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val s = if (c < 0.5f) {
            0.5f * (2f * c).toDouble().pow(contrast.toDouble()).toFloat()
        } else {
            1f - 0.5f * (2f * (1f - c)).toDouble().pow(contrast.toDouble()).toFloat()
        }
        return (toeLift + s * (ceiling - toeLift)).coerceIn(0f, 1f)
    }
}
