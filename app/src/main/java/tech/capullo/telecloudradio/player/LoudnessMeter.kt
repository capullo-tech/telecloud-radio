package tech.capullo.telecloudradio.player

import tech.capullo.audio.player.KWeighting
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Streaming ITU-R BS.1770-4 / EBU R128 integrated loudness plus absolute sample peak, fed one
 * interleaved sample at a time so a whole track can be measured while it decodes.
 *
 * Each sample is K-weighted with [KWeighting] (both stages derived from [sampleRate], per-channel
 * filter state carried across calls) and its square accumulated into the current 100 ms sub-block.
 * A finished sub-block stores one number - the SUM over channels of their mean squares - so memory
 * is O(sub-blocks), ~80 KB per hour of audio. [result] then forms the spec's 400 ms gating blocks
 * at 75% overlap as the mean of 4 consecutive sub-blocks (equal-length sub-blocks make that exact)
 * and applies the -70 LUFS absolute gate and the (ungated mean - 10 LU) relative gate.
 *
 * Only the first two channels contribute to loudness (L and R, weight 1.0 each); surround layouts
 * need per-position weights this does not model. Every channel contributes to the peak. Free of
 * Android types so the math is unit-testable on the JVM.
 */
class LoudnessMeter(sampleRate: Int, private val channelCount: Int) {

    data class Result(
        // Gated integrated loudness; null when no 400 ms block passes the gates (digital silence,
        // or less than 400 ms of audio), where there is no meaningful loudness to normalize.
        val integratedLufs: Float?,
        // Largest absolute sample value seen on any channel, linear full scale (1.0 = 0 dBFS).
        val samplePeak: Float,
    )

    private val shelf = KWeighting.shelf(sampleRate)
    private val highPass = KWeighting.highPass(sampleRate)
    private val measuredChannels = channelCount.coerceAtMost(2)

    // Biquad state per measured channel, flattened: [x1, x2, y1, y2] for stage 1 then stage 2.
    private val state = DoubleArray(measuredChannels * 8)

    private val subBlockFrames = (sampleRate / 10).coerceAtLeast(1)
    private var channel = 0
    private var frameInSubBlock = 0
    private var subBlockSumSq = 0.0

    // Per-sub-block energy (sum over channels of mean square), grown by doubling - ~7 minutes
    // before the first growth.
    private var subBlocks = DoubleArray(4096)
    private var subBlockCount = 0

    private var peak = 0f

    /** Feeds the next interleaved sample, full scale = 1.0. */
    fun accept(sample: Float) {
        val a = abs(sample)
        if (a > peak) peak = a
        if (channel < measuredChannels) {
            val y = kWeight(channel * 8, sample.toDouble())
            subBlockSumSq += y * y
        }
        if (++channel < channelCount) return
        channel = 0
        if (++frameInSubBlock < subBlockFrames) return
        if (subBlockCount == subBlocks.size) subBlocks = subBlocks.copyOf(subBlocks.size * 2)
        subBlocks[subBlockCount++] = subBlockSumSq / subBlockFrames
        frameInSubBlock = 0
        subBlockSumSq = 0.0
    }

    /** Loudness of everything fed so far; a trailing partial 100 ms sub-block is not counted. */
    fun result(): Result {
        val absoluteThreshold = 10.0.pow((ABSOLUTE_GATE_LUFS - KWeighting.LOUDNESS_OFFSET_DB) / 10.0)
        var sum = 0.0
        var count = 0
        for (i in 3 until subBlockCount) {
            val z = blockEnergy(i)
            if (z > absoluteThreshold) {
                sum += z
                count++
            }
        }
        if (count == 0) return Result(null, peak)
        val relativeThreshold = sum / count * 10.0.pow(RELATIVE_GATE_LU / 10.0)
        sum = 0.0
        count = 0
        for (i in 3 until subBlockCount) {
            val z = blockEnergy(i)
            if (z > absoluteThreshold && z > relativeThreshold) {
                sum += z
                count++
            }
        }
        val lufs = KWeighting.LOUDNESS_OFFSET_DB + 10.0 * log10(sum / count)
        return Result(lufs.toFloat(), peak)
    }

    // The 400 ms block ending with sub-block [last].
    private fun blockEnergy(last: Int): Double = (subBlocks[last - 3] + subBlocks[last - 2] + subBlocks[last - 1] + subBlocks[last]) / 4.0

    private fun kWeight(base: Int, x: Double): Double = biquad(base + 4, highPass, biquad(base, shelf, x))

    private fun biquad(at: Int, f: KWeighting.Biquad, x: Double): Double {
        val s = state
        val y = f.b0 * x + f.b1 * s[at] + f.b2 * s[at + 1] - f.a1 * s[at + 2] - f.a2 * s[at + 3]
        s[at + 1] = s[at]
        s[at] = x
        s[at + 3] = s[at + 2]
        s[at + 2] = y
        return y
    }

    private companion object {
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = -10.0
    }
}
