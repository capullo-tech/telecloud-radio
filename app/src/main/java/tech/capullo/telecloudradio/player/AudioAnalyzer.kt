package tech.capullo.telecloudradio.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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

        // Full-track cap for computeIntegratedLufs (volume normalization), not the stats-sheet
        // analyze() above. 3 minutes is enough content for a stable gated-integrated-loudness
        // estimate for virtually all music (ITU-R BS.1770 / EBU R128 practice uses far shorter
        // excerpts routinely) while bounding worst-case decode memory for a background per-track
        // pass. The decode buffer GROWS toward this cap rather than being pre-sized to it (see
        // decodePcmUnboxed) - even so, device testing showed concurrent gain computations (the
        // prefetch window can trigger several close together) still produced repeated
        // OutOfMemoryError at the original 600s/~230MB cap on this low-end test device; lowering
        // the cap AND serializing computations (DownloadManager.gainSemaphore) together bring
        // worst-case single-decode memory down to ~69MB. Tracks longer than this cap get a gain
        // computed from their first 3 minutes rather than the true full-track loudness - an
        // acceptable approximation, and most tracks' loudness is stable well before this point.
        private const val MAX_GAIN_ANALYZE_SECONDS = 180

        // Starting size for computeIntegratedLufs's growable decode buffer - ~30s stereo at 48kHz,
        // doubled as needed up to MAX_GAIN_ANALYZE_SECONDS. Most tracks need only a few doublings.
        private const val INITIAL_GAIN_BUFFER_SAMPLES = 48_000 * 30 * 2
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
    // analyze() above, which stays capped at MAX_ANALYZE_SECONDS/uses the ungated computeLufs
    // below - that's an on-demand UI feature where a fast approximate read is fine; a playback
    // gain needs the real full-track loudness) ───────────────────────────────────────────────

    /**
     * Full-track integrated loudness per ITU-R BS.1770-4 / EBU R128: 400ms blocks at 100ms step
     * (75% overlap), absolute gate at -70 LUFS, relative gate at (ungated mean - 10 LU). The
     * un-gated [computeLufs] above skews quiet intros/outros and silence into the result; proper
     * gating is what makes this value comparable across tracks for normalization.
     *
     * Decodes up to [MAX_GAIN_ANALYZE_SECONDS] of audio (not [analyze]'s 30s - a representative
     * playback gain needs the real mix, not just the intro) using a pre-sized primitive
     * FloatArray rather than [decodePcm]'s ArrayList<Float> - at this scale the latter would box
     * tens of millions of Float objects (gigabytes of garbage), the same class of GC-pressure bug
     * already found and fixed once in this codebase's FLAC decoder path. Meant to run once per
     * track in the background (DownloadManager, at download-completion time), never synchronously
     * on the UI thread. Returns null if the file can't be decoded.
     */
    fun computeIntegratedLufs(path: String, mimeType: String?): Float? {
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
            val maxSamples = sampleRate * MAX_GAIN_ANALYZE_SECONDS * channelCount

            val pcm: FloatArray = if (mime == "audio/raw") {
                val encoding = runCatching { format.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                    .getOrElse { AudioFormat.ENCODING_PCM_16BIT }
                readRawPcmDirectUnboxed(extractor, maxSamples, encoding)
            } else {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                decodePcmUnboxed(extractor, codec, maxSamples)
            }
            if (pcm.isEmpty()) return null
            gatedIntegratedLufs(pcm, sampleRate, channelCount)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            // Defense-in-depth: the growable buffer in decodePcmUnboxed/readRawPcmDirectUnboxed
            // is sized to avoid this, but this call runs unattended in the background
            // (DownloadManager.triggerGainComputation, never awaited by playback) - an uncaught
            // Error here would crash the whole app, not just skip one track's gain.
            null
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            extractor.release()
        }
    }

    private fun gatedIntegratedLufs(samples: FloatArray, sampleRate: Int, channelCount: Int): Float {
        val n = samples.size / channelCount.coerceAtLeast(1)
        if (n == 0) return FLOOR_DB
        val chsToProcess = channelCount.coerceAtMost(2) // L + R only (weight = 1.0 each), matches computeLufs
        val weightedChannels = (0 until chsToProcess).map { ch ->
            val chSamples = if (channelCount > 1) FloatArray(n) { i -> samples[i * channelCount + ch] } else samples
            applyKWeighting(chSamples, sampleRate)
        }
        val blockSize = (sampleRate * 0.4).toInt()
        val stepSize = (sampleRate * 0.1).toInt()
        if (blockSize <= 0 || n < blockSize) return singleBlockLufs(weightedChannels, n)

        val blockPowers = ArrayList<Double>()
        var start = 0
        while (start + blockSize <= n) {
            var sumSq = 0.0
            for (chArr in weightedChannels) {
                for (i in start until start + blockSize) {
                    val s = chArr[i].toDouble()
                    sumSq += s * s
                }
            }
            blockPowers += sumSq / (blockSize * chsToProcess)
            start += stepSize
        }
        // Absolute gate: -70 LUFS
        val absoluteThreshold = 10.0.pow((-70.0 + 0.691) / 10.0)
        val absoluteGated = blockPowers.filter { it > absoluteThreshold }
        if (absoluteGated.isEmpty()) return -70f
        val ungatedMean = absoluteGated.average()
        if (ungatedMean <= 0.0) return -70f
        // Relative gate: (ungated mean) - 10 LU
        val relativeThresholdLufs = (-0.691 + 10 * log10(ungatedMean)) - 10.0
        val relativeThreshold = 10.0.pow((relativeThresholdLufs + 0.691) / 10.0)
        val relativeGated = absoluteGated.filter { it > relativeThreshold }
        val finalMean = if (relativeGated.isNotEmpty()) relativeGated.average() else ungatedMean
        return (-0.691 + 10 * log10(finalMean)).toFloat()
    }

    private fun singleBlockLufs(weightedChannels: List<FloatArray>, n: Int): Float {
        if (n == 0 || weightedChannels.isEmpty()) return FLOOR_DB
        var sumSq = 0.0
        for (chArr in weightedChannels) {
            for (i in 0 until n) sumSq += chArr[i].toDouble() * chArr[i]
        }
        val meanSq = sumSq / (n * weightedChannels.size)
        return if (meanSq > 1e-12) (-0.691f + 10f * log10(meanSq.toFloat())) else FLOOR_DB
    }

    // Same decode loops as decodePcm/readRawPcmDirect below, but writing into a growable primitive
    // FloatArray (doubling capacity as needed, starting small and capped at maxSamples) with a
    // cursor instead of an ArrayList<Float> - see computeIntegratedLufs's doc for why boxing is
    // unacceptable at full-track scale. Growable rather than pre-sized to maxSamples: eagerly
    // allocating the full MAX_GAIN_ANALYZE_SECONDS (600s * 48kHz * 2ch * 4B ~= 230MB) regardless
    // of the actual track length crashed with OutOfMemoryError on-device for an ordinary ~3 minute
    // track (confirmed: this app's heap growth limit is ~384MB) - most tracks need a small
    // fraction of the worst-case buffer. The stats-sheet analyze() path keeps using the boxed
    // versions unchanged (fine at its much smaller 30s cap).
    private fun decodePcmUnboxed(extractor: MediaExtractor, codec: MediaCodec, maxSamples: Int): FloatArray {
        var out = FloatArray(minOf(maxSamples, INITIAL_GAIN_BUFFER_SAMPLES))
        var cursor = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone && cursor < maxSamples) {
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
                    val shortBuf = buf.asShortBuffer()
                    var i = 0
                    val remaining = shortBuf.remaining()
                    while (i < remaining && cursor < maxSamples) {
                        if (cursor >= out.size) out = out.copyOf(minOf(out.size * 2, maxSamples))
                        out[cursor++] = shortBuf.get(i) / 32768f
                        i++
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        return if (cursor == out.size) out else out.copyOf(cursor)
    }

    private fun readRawPcmDirectUnboxed(extractor: MediaExtractor, maxSamples: Int, encoding: Int): FloatArray {
        var out = FloatArray(minOf(maxSamples, INITIAL_GAIN_BUFFER_SAMPLES))
        var cursor = 0
        val buf = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder())
        while (cursor < maxSamples) {
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) break
            buf.position(0).limit(n)
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    while (buf.remaining() >= 4 && cursor < maxSamples) {
                        if (cursor >= out.size) out = out.copyOf(minOf(out.size * 2, maxSamples))
                        out[cursor++] = buf.float
                    }
                }
                else -> {
                    while (buf.remaining() >= 2 && cursor < maxSamples) {
                        if (cursor >= out.size) out = out.copyOf(minOf(out.size * 2, maxSamples))
                        out[cursor++] = buf.short / 32768f
                    }
                }
            }
            buf.clear()
            extractor.advance()
        }
        return if (cursor == out.size) out else out.copyOf(cursor)
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
        // Stage 1: pre-filter (high-shelf ~1682 Hz, +4 dB) - 48 kHz coefficients are
        // a close approximation for 44.1–96 kHz; error < 0.1 LUFS for typical content
        val b1 = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
        val a1 = doubleArrayOf(-1.69065929318241, 0.73248077421585)

        // Stage 2: RLB high-pass at 38.13506 Hz - computed exactly for this sample rate
        val (b2, a2) = rlbHighPassCoeffs(sampleRate)

        val stage1 = biquadFilter(x, b1, a1)
        return biquadFilter(stage1, b2, a2)
    }

    private fun rlbHighPassCoeffs(sampleRate: Int): Pair<DoubleArray, DoubleArray> {
        val k = tan(PI * 38.13506 / sampleRate)
        val q = 1.0 / sqrt(2.0)
        val d = 1.0 + k / q + k * k
        val b = doubleArrayOf(1.0 / d, -2.0 / d, 1.0 / d)
        val a = doubleArrayOf(2.0 * (k * k - 1.0) / d, (1.0 - k / q + k * k) / d)
        return b to a
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
