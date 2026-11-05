package tech.capullo.telecloudradio.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import tech.capullo.audio.player.KWeighting
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class AudioAnalyzer @Inject constructor() {

    companion object {
        const val FFT_SIZE = 4096
        const val SPECTRUM_BINS = 256
        const val FLOOR_DB = -120f
        private const val MAX_ANALYZE_SECONDS = 30

        private const val SPECT_TIME_BINS = 200
        private const val SPECT_FREQ_BINS = 256
    }

    data class ChannelStats(val peakDb: Float, val rmsDb: Float, val drDb: Float)

    data class Result(
        val peakDb: Float,
        val rmsDb: Float,
        val dynamicRange: Float,
        val spectralCutoffHz: Int,
        val nyquistHz: Int,
        val likelyTrueLossless: Boolean,
        val spectrumMagnitudesDb: FloatArray,
        val spectrogramFile: File?,
        // Extended metrics
        val lufs: Float,
        val truePeakDb: Float,
        val clipping: Boolean,
        val totalSamples: Long,
        val channelStats: List<ChannelStats>,
    )

    fun analyze(path: String, mimeType: String?, spectrogramFile: File): Result? {
        if (!File(path).exists()) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(path)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            val maxSamples = sampleRate * MAX_ANALYZE_SECONDS * channelCount

            val pcm: FloatArray = if (mime == "audio/raw") {
                val encoding = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                    .getOrElse { AudioFormat.ENCODING_PCM_16BIT }
                readRawPcmDirect(extractor, maxSamples, encoding)
            } else {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                decodePcm(extractor, codec, maxSamples)
            }

            if (pcm.isEmpty()) return null

            val totalSamples = (pcm.size / channelCount).toLong()

            // Per-channel stats (before mono mixdown)
            val chStats = (0 until channelCount).map { ch ->
                var chPeak = 0f
                var chSumSq = 0.0
                val n = pcm.size / channelCount
                for (i in 0 until n) {
                    val s = pcm[i * channelCount + ch]
                    val a = abs(s)
                    if (a > chPeak) chPeak = a
                    chSumSq += s.toDouble() * s
                }
                val chPeakDb = if (chPeak > 1e-9f) 20f * log10(chPeak) else FLOOR_DB
                val chRmsDb = if (chSumSq > 0.0) 20f * log10(sqrt(chSumSq / n).toFloat()) else FLOOR_DB
                ChannelStats(chPeakDb, chRmsDb, chPeakDb - chRmsDb)
            }

            // Clipping: any sample at or above FS
            val clipping = pcm.any { abs(it) >= 0.9997f }

            // Mix down to mono for global stats + FFT
            val mono = if (channelCount > 1) {
                FloatArray(pcm.size / channelCount) { i ->
                    var sum = 0f
                    for (ch in 0 until channelCount) sum += pcm[i * channelCount + ch]
                    sum / channelCount
                }
            } else {
                pcm
            }

            // Global Peak & RMS
            var peak = 0f
            var sumSq = 0.0
            for (s in mono) {
                val a = abs(s)
                if (a > peak) peak = a
                sumSq += s.toDouble() * s
            }
            val peakDb = if (peak > 1e-9f) 20f * log10(peak) else FLOOR_DB
            val rmsDb = if (sumSq > 0.0) 20f * log10(sqrt(sumSq / mono.size).toFloat()) else FLOOR_DB
            val dynamicRange = peakDb - rmsDb

            // True Peak via 4× linear oversampling
            var tp = peak
            for (i in 0 until mono.size - 1) {
                val a = mono[i]
                val b = mono[i + 1]
                for (t in 1..3) {
                    val v = abs(a + (b - a) * t / 4f)
                    if (v > tp) tp = v
                }
            }
            val truePeakDb = if (tp > 1e-9f) 20f * log10(tp) else FLOOR_DB

            // LUFS via K-weighting (ITU-R BS.1770, ungated)
            val lufs = computeLufs(pcm, sampleRate, channelCount)

            // Hann window
            val hann = FloatArray(FFT_SIZE) { i ->
                (0.5 * (1 - cos(2 * PI * i / (FFT_SIZE - 1)))).toFloat()
            }

            // STFT frames
            val specBins = FFT_SIZE / 2 + 1
            val hop = FFT_SIZE / 2
            val allFrames = ArrayList<FloatArray>()
            val accumAvg = DoubleArray(specBins)
            var pos = 0
            while (pos + FFT_SIZE <= mono.size) {
                val windowed = FloatArray(FFT_SIZE) { i -> mono[pos + i] * hann[i] }
                val mag = fftMagnitudes(windowed)
                allFrames.add(mag)
                for (i in 0 until specBins) accumAvg[i] += mag[i].toDouble()
                pos += hop
            }

            val totalFrames = allFrames.size
            val freqRes = sampleRate.toFloat() / FFT_SIZE
            val nyquistHz = sampleRate / 2

            val avgMagDb = FloatArray(specBins) { i ->
                if (totalFrames == 0) {
                    FLOOR_DB
                } else {
                    val avg = (accumAvg[i] / totalFrames).toFloat()
                    if (avg > 1e-12f) maxOf(20f * log10(avg / FFT_SIZE.toFloat()), FLOOR_DB) else FLOOR_DB
                }
            }

            val minBin = (4000f / freqRes).toInt().coerceIn(0, specBins - 1)
            var cutoffBin = minBin
            for (i in specBins - 1 downTo minBin) {
                if (avgMagDb[i] > -80f) {
                    cutoffBin = i
                    break
                }
            }
            val spectralCutoffHz = (cutoffBin * freqRes).toInt()
            val lossless = isLosslessContainer(path, mimeType)
            val likelyTrueLossless = lossless && spectralCutoffHz >= (nyquistHz * 0.88).toInt()

            val spectFile = runCatching {
                writeSpectrogram(allFrames, specBins, spectrogramFile)
                spectrogramFile
            }.getOrNull()

            Result(
                peakDb = peakDb,
                rmsDb = rmsDb,
                dynamicRange = dynamicRange,
                spectralCutoffHz = spectralCutoffHz,
                nyquistHz = nyquistHz,
                likelyTrueLossless = likelyTrueLossless,
                spectrumMagnitudesDb = downsample(avgMagDb, SPECTRUM_BINS),
                spectrogramFile = spectFile,
                lufs = lufs,
                truePeakDb = truePeakDb,
                clipping = clipping,
                totalSamples = totalSamples,
                channelStats = chStats,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            extractor.release()
        }
    }

    // ── Gated integrated LUFS, for volume-normalization gain (separate from the stats-sheet
    // analyze() above, which stays capped at MAX_ANALYZE_SECONDS) ──────────────────────────────

    /** Whole-track loudness and sample peak for normalization; see [measureLoudness]. */
    data class Loudness(val integratedLufs: Float, val samplePeak: Float)

    /**
     * Full-track integrated loudness (ITU-R BS.1770-4 / EBU R128, gated) and sample peak, measured
     * while decoding: each decoder output buffer is read in place straight into a [LoudnessMeter],
     * so memory stays constant (~80 KB of sub-block energies per hour) however long the track is,
     * and nothing is allocated per buffer. That matters because this runs in the background during
     * a broadcast, and allocation churn in this process is exactly what once drove the snapserver
     * into dryouts (see the FLAC decoder note in PlaybackService).
     *
     * FLAC: the platform's c2.android.flac.decoder is the one whose buffer-pool churn caused that,
     * so the standalone OMX.google.flac.decoder is tried first by name; where it is only an alias
     * of the c2 component (or missing) the default decoder is used, still drained in place.
     *
     * Returns null if the file can't be decoded or has no gated loudness (e.g. digital silence).
     */
    fun measureLoudness(path: String): Loudness? {
        if (!File(path).exists()) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(path)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    format = f
                    break
                }
            }
            if (format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            // Mono is measured as the chain broadcasts it: ChannelMixingAudioProcessor copies it at
            // unity into L and R, so it is metered as two identical channels.
            var monoUpmix = channels == 1
            var meter = LoudnessMeter(rate, if (monoUpmix) 2 else channels)
            if (mime == "audio/raw") {
                val encoding = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                    .getOrElse { AudioFormat.ENCODING_PCM_16BIT }
                val buf = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())
                while (true) {
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    buf.position(0).limit(n)
                    feed(meter, buf, encoding, monoUpmix)
                    buf.clear()
                    extractor.advance()
                }
            } else {
                val c = createDecoder(mime).also { codec = it }
                c.configure(format, null, null, 0)
                c.start()
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val idx = c.dequeueInputBuffer(10_000L)
                        if (idx >= 0) {
                            val n = extractor.readSampleData(c.getInputBuffer(idx)!!, 0)
                            if (n < 0) {
                                c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val out = c.dequeueOutputBuffer(info, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // The decoder's real output can differ from the container (HE-AAC SBR
                            // doubles the rate, PS turns mono into stereo). Always delivered before
                            // the first output buffer, so nothing has been fed yet.
                            val of = c.outputFormat
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            val outRate = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) of.getInteger(MediaFormat.KEY_SAMPLE_RATE) else rate
                            val outChannels = if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels
                            monoUpmix = outChannels == 1
                            meter = LoudnessMeter(outRate, if (monoUpmix) 2 else outChannels)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        else -> if (out >= 0) {
                            val buf = c.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset).limit(info.offset + info.size)
                            feed(meter, buf, encoding, monoUpmix)
                            c.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
            val r = meter.result()
            r.integratedLufs?.let { Loudness(it, r.samplePeak) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            extractor.release()
        }
    }

    private fun feed(meter: LoudnessMeter, buf: ByteBuffer, encoding: Int, monoUpmix: Boolean) {
        val copies = if (monoUpmix) 2 else 1
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            while (buf.remaining() >= 4) {
                val s = buf.float
                repeat(copies) { meter.accept(s) }
            }
        } else {
            while (buf.remaining() >= 2) {
                val s = buf.short / 32768f
                repeat(copies) { meter.accept(s) }
            }
        }
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (mime == MediaFormat.MIMETYPE_AUDIO_FLAC) {
            runCatching { MediaCodec.createByCodecName("OMX.google.flac.decoder") }
                .getOrNull()
                ?.let {
                    // getName() echoes the alias; only the canonical name reveals a c2 component
                    // behind it (aliases exist from API 29).
                    val component = if (Build.VERSION.SDK_INT >= 29) it.canonicalName else it.name
                    if (!component.startsWith("c2.")) return it else it.release()
                }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    // ── LUFS (ITU-R BS.1770, ungated) ────────────────────────────────────────

    private fun computeLufs(samples: FloatArray, sampleRate: Int, channelCount: Int): Float {
        val n = samples.size / channelCount.coerceAtLeast(1)
        if (n == 0) return FLOOR_DB
        val chsToProcess = channelCount.coerceAtMost(2) // L + R only (weight = 1.0 each)
        var sumMs = 0.0
        for (ch in 0 until chsToProcess) {
            val chSamples = if (channelCount > 1) {
                FloatArray(n) { i -> samples[i * channelCount + ch] }
            } else {
                samples
            }
            val kw = applyKWeighting(chSamples, sampleRate)
            var ms = 0.0
            for (s in kw) ms += s.toDouble() * s
            sumMs += ms / kw.size
        }
        return if (sumMs > 1e-12) (-0.691f + 10f * log10(sumMs.toFloat())) else FLOOR_DB
    }

    private fun applyKWeighting(x: FloatArray, sampleRate: Int): FloatArray {
        // Same BS.1770 coefficients as the normalization path (KWeighting, derived from the rate),
        // so the stats sheet and the playback gain agree on a track's loudness.
        val shelf = KWeighting.shelf(sampleRate)
        val highPass = KWeighting.highPass(sampleRate)
        val stage1 = biquadFilter(x, doubleArrayOf(shelf.b0, shelf.b1, shelf.b2), doubleArrayOf(shelf.a1, shelf.a2))
        return biquadFilter(stage1, doubleArrayOf(highPass.b0, highPass.b1, highPass.b2), doubleArrayOf(highPass.a1, highPass.a2))
    }

    private fun biquadFilter(x: FloatArray, b: DoubleArray, a: DoubleArray): FloatArray {
        val y = FloatArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val yi = b[0] * xi + b[1] * x1 + b[2] * x2 - a[0] * y1 - a[1] * y2
            y[i] = yi.toFloat()
            x2 = x1
            x1 = xi
            y2 = y1
            y1 = yi
        }
        return y
    }

    // ── Raw PCM (audio/raw from demuxer) ─────────────────────────────────────

    private fun readRawPcmDirect(extractor: MediaExtractor, maxSamples: Int, encoding: Int): FloatArray {
        val out = ArrayList<Float>(minOf(maxSamples, 2_000_000))
        val buf = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())
        while (out.size < maxSamples) {
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) break
            buf.position(0).limit(n)
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    while (buf.remaining() >= 4 && out.size < maxSamples) out.add(buf.float)
                }
                else -> {
                    while (buf.remaining() >= 2 && out.size < maxSamples) out.add(buf.short / 32768f)
                }
            }
            buf.clear()
            extractor.advance()
        }
        return out.toFloatArray()
    }

    // ── Spectrogram ──────────────────────────────────────────────────────────

    private fun writeSpectrogram(frames: List<FloatArray>, srcFreqBins: Int, outFile: File) {
        val numT = SPECT_TIME_BINS
        val numF = SPECT_FREQ_BINS
        val tRatio = frames.size.toFloat() / numT
        val fRatio = srcFreqBins.toFloat() / numF

        outFile.parentFile?.mkdirs()
        val buf = ByteBuffer.allocate(8 + numT * numF * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(numT)
        buf.putInt(numF)

        for (t in 0 until numT) {
            val tLo = (t * tRatio).toInt()
            val tHi = ((t + 1) * tRatio).toInt().coerceAtMost(frames.size)
            for (f in 0 until numF) {
                val fLo = (f * fRatio).toInt()
                val fHi = ((f + 1) * fRatio).toInt().coerceAtMost(srcFreqBins)
                var sum = 0.0
                var count = 0
                for (ti in tLo until tHi.coerceAtLeast(tLo + 1)) {
                    val frame = frames.getOrNull(ti) ?: continue
                    for (fi in fLo until fHi.coerceAtLeast(fLo + 1)) {
                        sum += frame.getOrElse(fi) { 0f }.toDouble()
                        count++
                    }
                }
                val mag = if (count > 0) (sum / count).toFloat() else 0f
                // Normalize by FFT_SIZE so full-scale sine ≈ -6 dB; without this all bins clamp to white
                val db = if (mag > 1e-12f) maxOf(20f * log10(mag / FFT_SIZE.toFloat()), FLOOR_DB) else FLOOR_DB
                val encoded = (((db - FLOOR_DB) / (-FLOOR_DB)) * 65535f).toInt()
                    .coerceIn(0, 65535).toShort()
                buf.putShort(encoded)
            }
        }
        outFile.writeBytes(buf.array())
    }

    // ── MediaCodec decoder ────────────────────────────────────────────────────

    private fun decodePcm(extractor: MediaExtractor, codec: MediaCodec, maxSamples: Int): FloatArray {
        val out = ArrayList<Float>(minOf(maxSamples, 2_000_000))
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone && out.size < maxSamples) {
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outIdx = codec.dequeueOutputBuffer(info, 10_000L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                else -> if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!.apply { order(ByteOrder.nativeOrder()) }
                    val shorts = ShortArray(info.size / 2)
                    buf.asShortBuffer().get(shorts)
                    for (s in shorts) {
                        out.add(s / 32768f)
                        if (out.size >= maxSamples) break
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        return out.toFloatArray()
    }

    // ── FFT & helpers ─────────────────────────────────────────────────────────

    private fun fftMagnitudes(x: FloatArray): FloatArray {
        val n = x.size
        val re = x.copyOf()
        val im = FloatArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var s = 2
        while (s <= n) {
            val half = s ushr 1
            val ang = -2.0 * PI / s
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var k = 0
            while (k < n) {
                var tRe = 1f
                var tIm = 0f
                for (m in 0 until half) {
                    val uRe = re[k + m]
                    val uIm = im[k + m]
                    val vRe = re[k + m + half] * tRe - im[k + m + half] * tIm
                    val vIm = re[k + m + half] * tIm + im[k + m + half] * tRe
                    re[k + m] = uRe + vRe
                    im[k + m] = uIm + vIm
                    re[k + m + half] = uRe - vRe
                    im[k + m + half] = uIm - vIm
                    val newTRe = tRe * wRe - tIm * wIm
                    tIm = tRe * wIm + tIm * wRe
                    tRe = newTRe
                }
                k += s
            }
            s = s shl 1
        }
        return FloatArray(n / 2 + 1) { i -> sqrt(re[i] * re[i] + im[i] * im[i]) }
    }

    private fun downsample(src: FloatArray, targetBins: Int): FloatArray {
        if (src.size <= targetBins) return src
        val ratio = src.size.toFloat() / targetBins
        return FloatArray(targetBins) { i ->
            val lo = (i * ratio).toInt()
            val hi = ((i + 1) * ratio).toInt().coerceAtMost(src.size)
            if (hi > lo) src.slice(lo until hi).average().toFloat() else src[lo]
        }
    }

    private fun isLosslessContainer(path: String, mimeType: String?): Boolean {
        val ext = File(path).extension.lowercase()
        return ext in setOf("flac", "wav", "wave", "aiff", "aif", "alac") ||
            mimeType?.lowercase() in setOf("audio/flac", "audio/wav", "audio/x-wav", "audio/aiff")
    }
}
