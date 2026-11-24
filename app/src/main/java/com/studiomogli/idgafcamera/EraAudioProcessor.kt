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
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

enum class EraAudioProfile {
    NONE,
    OPTICAL_1950S
}

/**
 * Lossless-Video / Authentic-Era-Audio MP4 Processor:
 * - Video Track: 100% zero-reencode bit-for-bit passthrough via MediaExtractor -> MediaMuxer.
 * - Audio Track: Decodes AAC -> 16-bit PCM, applies historically accurate Era DSP (e.g., 1950s
 *   Academy-Curve Optical Sound-on-Film Mono: 125Hz HPF, 4.8-5.8kHz optical slit LPF, 1.65kHz
 *   warm ribbon-mic vocal presence, Westrex vacuum-tube soft saturation & subtle optical emulsion
 *   noise floor), then re-encodes cleanly to Mono AAC.
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
        // Authentic 1950s AMPAS Academy Curve Optical Sound-on-Film chain
        private val hpf = BiquadFilter.highPass(if (archiveWear) 145.0 else 120.0, 0.707, fs)
        private val lpfSlit1 = BiquadFilter.lowPass(if (archiveWear) 4200.0 else 4900.0, 0.707, fs)
        private val lpfSlit2 = BiquadFilter.lowPass(if (archiveWear) 5100.0 else 5900.0, 0.707, fs)
        private val vocalPeak = BiquadFilter.peakingEq(1650.0, 1.0, if (archiveWear) 4.0 else 3.2, fs)
        private val hissLpf = BiquadFilter.lowPass(3600.0, 0.707, fs)

        // Fast deterministic LCG PRNG for authentic optical track grain hiss & micro dust ticks
        private var rngState: Long = 0x5DEECE66DL
        private val drive = if (archiveWear) 1.65 else 1.38
        private val normScale = 1.0 / tanh(drive)
        private val hissAmp = if (archiveWear) 0.0135 else 0.0048
        private var crackleDecay = 0.0

        private fun nextSignedNoise(): Double {
            rngState = (rngState * 6364136223846793005L + 1442695040888963407L)
            val bits = ((rngState ushr 33) and 0x7FFFFFFFL).toDouble() / 2147483647.0
            return bits * 2.0 - 1.0
        }

        fun processSample(monoIn: Double): Short {
            // 1. Academy Curve optical bandwidth & warm ribbon-mic vocal voicing
            var s = hpf.process(monoIn)
            s = vocalPeak.process(s)
            s = lpfSlit1.process(s)
            s = lpfSlit2.process(s)

            // 2. Westrex / RCA Photophone vacuum-tube soft saturation & automatic dialogue leveling
            s = tanh(s * drive) * normScale * 0.92

            // 3. Band-limited 35mm optical emulsion hiss floor
            val rawNoise = nextSignedNoise()
            val opticalHiss = hissLpf.process(rawNoise) * hissAmp
            s += opticalHiss

            // 4. Subtle single-grain optical soundtrack dust pops in ARCHIVE print mode (~2-3 gentle ticks/sec)
            if (archiveWear) {
                val r = nextSignedNoise()
                if (r > 0.99986) {
                    crackleDecay = (0.14 + (r - 0.99986) * 500.0).coerceAtMost(0.24)
                }
                if (crackleDecay > 0.001) {
                    s += crackleDecay * rawNoise
                    crackleDecay *= 0.82
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
