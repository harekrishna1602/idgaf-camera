package com.studiomogli.idgafcamera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

enum class EraAudioProfile {
    NONE,
    OPTICAL_1950S
}

/**
 * Lossless-Video / Authentic-Era-Audio MP4 Processor (v0.7.4.3 Westrex R&D Engine):
 * - Video Track: 100% zero-reencode bit-for-bit passthrough via MediaExtractor -> MediaMuxer.
 * - Audio Track (OPTICAL_1950S): Decodes AAC -> 16-bit PCM, applies historically accurate
 *   1950s Variable-Area 35mm Optical Sound-on-Film DSP:
 *   1. Steep dual-stage AMPAS Academy Curve HPF (cuts modern sub-bass rumble below 100 Hz)
 *   2. RCA 44BX Ribbon Mic lower-mid chest warmth (360 Hz) + studio dialogue articulation (2.1 kHz)
 *   3. 24 dB/oct Optical Slit-Loss HF Roll-off (5.4-6.2 kHz FRESH / 4.4-5.1 kHz ARCHIVE)
 *   4. Westrex RA-1231 Optical Light-Valve Compressor/Limiter (smooth 8ms/160ms dialogue leveling)
 *   5. Asymmetric 2nd-order Vacuum-Tube Saturation + 20 Hz DC Blocker
 *   6. Signal-dependent Variable-Area Optical Modulation Hiss + ARCHIVE 24Hz sprocket micro-flutter & rare dust ticks
 */
object EraAudioProcessor {

    private class BiquadFilter(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            fun highPass(fc: Double, q: Double, fs: Double): BiquadFilter {
                val w0 = 2.0 * PI * fc.coerceIn(20.0, fs * 0.45) / fs
                val alpha = sin(w0) / (2.0 * q)
                val c = cos(w0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = ((1.0 + c) * 0.5) / a0,
                    b1 = -(1.0 + c) / a0,
                    b2 = ((1.0 + c) * 0.5) / a0,
                    a1 = (-2.0 * c) / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }

            fun lowPass(fc: Double, q: Double, fs: Double): BiquadFilter {
                val w0 = 2.0 * PI * fc.coerceIn(100.0, fs * 0.45) / fs
                val alpha = sin(w0) / (2.0 * q)
                val c = cos(w0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = ((1.0 - c) * 0.5) / a0,
                    b1 = (1.0 - c) / a0,
                    b2 = ((1.0 - c) * 0.5) / a0,
                    a1 = (-2.0 * c) / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }

            fun peakingEq(fc: Double, q: Double, gainDb: Double, fs: Double): BiquadFilter {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * fc.coerceIn(100.0, fs * 0.45) / fs
                val alpha = sin(w0) / (2.0 * q)
                val c = cos(w0)
                val a0 = 1.0 + alpha / a
                return BiquadFilter(
                    b0 = (1.0 + alpha * a) / a0,
                    b1 = (-2.0 * c) / a0,
                    b2 = (1.0 - alpha * a) / a0,
                    a1 = (-2.0 * c) / a0,
                    a2 = (1.0 - alpha / a) / a0
                )
            }
        }
    }

    private class Optical1950sDsp(sampleRate: Int, private val archiveWear: Boolean) {
        private val fs = sampleRate.coerceIn(16000, 96000).toDouble()

        // 1. Steep 1950s AMPAS Academy Curve + RCA 44BX Ribbon Mic Voicing
        private val hpf1 = BiquadFilter.highPass(if (archiveWear) 140.0 else 115.0, 0.707, fs)
        private val hpf2 = BiquadFilter.highPass(if (archiveWear) 120.0 else 95.0, 0.707, fs)
        private val ribbonWarmth = BiquadFilter.peakingEq(360.0, 0.9, if (archiveWear) 1.8 else 2.2, fs)
        private val dialogueArt = BiquadFilter.peakingEq(2100.0, 1.1, if (archiveWear) 4.0 else 3.4, fs)
        private val slitLpf1 = BiquadFilter.lowPass(if (archiveWear) 4400.0 else 5400.0, 0.707, fs)
        private val slitLpf2 = BiquadFilter.lowPass(if (archiveWear) 5100.0 else 6200.0, 0.707, fs)

        // Optical emulsion noise shaper (band-limited 450 Hz .. 3.4 kHz like real silver-halide optical track)
        private val hissLpf = BiquadFilter.lowPass(3400.0, 0.707, fs)
        private val hissHpf = BiquadFilter.highPass(420.0, 0.707, fs)

        // 2. Westrex RA-1231 Optical Light-Valve Compressor Envelope
        private val atkCoeff = exp(-1.0 / (0.008 * fs))
        private val relCoeff = exp(-1.0 / (0.160 * fs))
        private var env = 0.0

        // 3. Asymmetric 2nd-Order Vacuum-Tube Saturation + 20 Hz DC Blocker
        private val tubeDrive = if (archiveWear) 1.85 else 1.48
        private val tubeNorm = 1.0 / tanh(tubeDrive)
        private var dcX1 = 0.0
        private var dcY1 = 0.0
        private val dcR = 0.9965

        // 4. Deterministic 64-bit PRNG + 24Hz Sprocket Flutter & 50Hz Exciter-Lamp Warmth
        private var rngState: Long = 0x5DEECE66DL
        private var sampleIndex: Long = 0L
        private val baseHissAmp = if (archiveWear) 0.0115 else 0.0038
        private var tickEnv = 0.0
        private var tickPol = 1.0

        private fun nextSignedNoise(): Double {
            rngState = (rngState * 6364136223846793005L + 1442695040888963407L)
            val bits = ((rngState ushr 33) and 0x7FFFFFFFL).toDouble() / 2147483647.0
            return bits * 2.0 - 1.0
        }

        fun processSample(monoIn: Double): Short {
            sampleIndex++
            // A. Academy Curve + RCA Ribbon Mic Frequency Shaping
            var s = hpf1.process(monoIn)
            s = hpf2.process(s)
            s = ribbonWarmth.process(s)
            s = dialogueArt.process(s)
            s = slitLpf1.process(s)
            s = slitLpf2.process(s)

            // B. Westrex Optical Light-Valve Auto-Leveling Compressor (gentle 3:1 above -16 dBFS + makeup gain)
            val level = abs(s)
            env = if (level > env) {
                atkCoeff * env + (1.0 - atkCoeff) * level
            } else {
                relCoeff * env + (1.0 - relCoeff) * level
            }
            val threshold = 0.14
            val compGain = if (env > threshold) {
                (threshold / env).pow(0.65)
            } else {
                1.0
            }
            s *= compGain * 1.45

            // C. Asymmetric Vacuum-Tube Harmonics (adds authentic warm 2nd-order even harmonics) + DC Blocker
            val asym = s + 0.14 * s * s
            val sat = tanh(asym * tubeDrive) * tubeNorm * 0.90
            val dcBlocked = sat - dcX1 + dcR * dcY1
            dcX1 = sat
            dcY1 = dcBlocked
            s = dcBlocked

            // D. Authentic Variable-Area Optical Modulation Noise (hiss breathes subtly with dialogue!)
            val rawNoise = nextSignedNoise()
            val bandHiss = hissHpf.process(hissLpf.process(rawNoise))
            val modNoiseMul = 1.0 + (env * 1.65).coerceAtMost(1.2)
            s += bandHiss * baseHissAmp * modNoiseMul

            // E. ARCHIVE-ONLY 35mm Projector Transport Character (24 Hz sprocket micro-flutter + 50 Hz exciter warmth + rare optical dust ticks)
            if (archiveWear) {
                val tSec = sampleIndex.toDouble() / fs
                // Subtle 24 Hz sprocket amplitude breathing (~0.8%) + faint 50 Hz exciter lamp warmth (-52 dB)
                val sprocketMod = 1.0 + 0.008 * sin(2.0 * PI * 24.0 * tSec)
                val exciterHum = 0.0022 * sin(2.0 * PI * 50.0 * tSec)
                s = s * sprocketMod + exciterHum

                // Rare crisp optical-slit dust ticks (~1.5/sec average, damped impulse)
                val r = nextSignedNoise()
                if (r > 0.99992) {
                    tickEnv = (0.11 + (r - 0.99992) * 800.0).coerceAtMost(0.20)
                    tickPol = if (nextSignedNoise() > 0.0) 1.0 else -1.0
                }
                if (tickEnv > 0.001) {
                    s += tickEnv * tickPol * (0.4 + 0.6 * rawNoise)
                    tickEnv *= 0.78
                }
            }

            val clamped = (s * 32767.0).toInt().coerceIn(-32768, 32767)
            return clamped.toShort()
        }
    }

    fun processMp4Audio(
        inputFile: File,
        outputFile: File,
        profile: EraAudioProfile,
        archiveWear: Boolean
    ): Boolean {
        if (profile == EraAudioProfile.NONE || !inputFile.exists() || inputFile.length() < 1024L) {
            return false
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }

            var videoTrackIdx = -1
            var audioTrackIdx = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIdx < 0) {
                    videoTrackIdx = i
                    videoFormat = fmt
                } else if (mime.startsWith("audio/") && audioTrackIdx < 0) {
                    audioTrackIdx = i
                    audioFormat = fmt
                }
            }

            if (videoTrackIdx < 0 || audioTrackIdx < 0 || videoFormat == null || audioFormat == null) {
                return false
            }

            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val srcSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(16000, 48000)

            // 1. Decode source audio track completely into mono processed 16-bit PCM bytes (fast & zero deadlock!)
            audioExtractor.selectTrack(audioTrackIdx)
            decoder = MediaCodec.createDecoderByType(audioMime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            var decodedSampleRate = srcSampleRate
            var decodedChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            } else 1
            var dsp = Optical1950sDsp(decodedSampleRate, archiveWear)

            val pcmOut = java.io.ByteArrayOutputStream(262_144)
            val decInfo = MediaCodec.BufferInfo()
            var decInputDone = false
            var decOutputDone = false

            while (!decOutputDone) {
                if (!decInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = audioExtractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decInputDone = true
                            } else {
                                val pts = audioExtractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                audioExtractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val ofmt = decoder.outputFormat
                        val newRate = if (ofmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            ofmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(16000, 96000)
                        } else decodedSampleRate
                        val newCh = if (ofmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            ofmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                        } else decodedChannels
                        if (newRate != decodedSampleRate) {
                            decodedSampleRate = newRate
                            dsp = Optical1950sDsp(decodedSampleRate, archiveWear)
                        }
                        decodedChannels = newCh
                    }
                    outIdx >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && decInfo.size > 0) {
                            outBuf.position(decInfo.offset)
                            outBuf.limit(decInfo.offset + decInfo.size)
                            val chunk = ByteArray(decInfo.size)
                            outBuf.get(chunk)

                            val shortBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val totalShorts = shortBuf.remaining()
                            val frameCount = totalShorts / decodedChannels
                            val monoBytes = ByteArray(frameCount * 2)
                            val monoWr = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                            if (decodedChannels == 1) {
                                for (i in 0 until frameCount) {
                                    val x = shortBuf.get(i).toDouble() / 32768.0
                                    monoWr.put(i, dsp.processSample(x))
                                }
                            } else {
                                for (i in 0 until frameCount) {
                                    val base = i * decodedChannels
                                    val l = shortBuf.get(base).toDouble() / 32768.0
                                    val r = shortBuf.get(base + 1).toDouble() / 32768.0
                                    monoWr.put(i, dsp.processSample((l + r) * 0.5))
                                }
                            }
                            pcmOut.write(monoBytes)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decOutputDone = true
                        }
                    }
                }
            }

            val processedPcm = pcmOut.toByteArray()
            if (processedPcm.size < 4096) {
                return false
            }

            // 2. Encode processed Mono PCM to AAC-LC & mux alongside bit-for-bit lossless original video track!
            val encFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                decodedSampleRate,
                1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(0)
            val muxVideoTrack = muxer.addTrack(videoFormat)
            var muxAudioTrack = -1

            val encInfo = MediaCodec.BufferInfo()
            var pcmOffset = 0
            var totalSamplesFed = 0L
            var encInputDone = false
            var encOutputDone = false

            while (!encOutputDone) {
                if (!encInputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val remaining = processedPcm.size - pcmOffset
                            if (remaining <= 0) {
                                val eosPts = (totalSamplesFed * 1_000_000L) / decodedSampleRate.toLong()
                                encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encInputDone = true
                            } else {
                                val toCopy = minOf(remaining, minOf(inBuf.capacity(), 2048))
                                val evenCopy = toCopy - (toCopy % 2)
                                val ptsUs = (totalSamplesFed * 1_000_000L) / decodedSampleRate.toLong()
                                inBuf.put(processedPcm, pcmOffset, evenCopy)
                                encoder.queueInputBuffer(inIdx, 0, evenCopy, ptsUs, 0)
                                pcmOffset += evenCopy
                                totalSamplesFed += (evenCopy / 2).toLong()
                            }
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(encInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxAudioTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (muxerStarted && muxAudioTrack >= 0 && !isConfig && outBuf != null && encInfo.size > 0) {
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxAudioTrack, outBuf, encInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encOutputDone = true
                        }
                    }
                }
            }

            if (!muxerStarted) {
                return false
            }

            // 3. Copy original video track bit-for-bit (0% re-encoding, 100% original 24fps GPU quality!)
            videoExtractor.selectTrack(videoTrackIdx)
            val maxVideoBufSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(512 * 1024)
            } else {
                1024 * 1024
            }
            val videoBuf = ByteBuffer.allocateDirect(maxVideoBufSize)
            val vidInfo = MediaCodec.BufferInfo()

            while (true) {
                videoBuf.clear()
                val size = videoExtractor.readSampleData(videoBuf, 0)
                if (size < 0) break
                vidInfo.offset = 0
                vidInfo.size = size
                vidInfo.presentationTimeUs = videoExtractor.sampleTime.coerceAtLeast(0L)
                val extFlags = videoExtractor.sampleFlags
                vidInfo.flags = if ((extFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(muxVideoTrack, videoBuf, vidInfo)
                videoExtractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            return outputFile.exists() && outputFile.length() > 1024L
        } catch (_: Exception) {
            try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
            return false
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }
}
