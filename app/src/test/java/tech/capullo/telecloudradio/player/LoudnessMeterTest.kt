package tech.capullo.telecloudradio.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [LoudnessMeter] against the BS.1770 calibration point: a 997 Hz sine at -20 dBFS peak on both
 * channels of a stereo signal measures -20.0 LUFS. Averaging the channels instead of summing them
 * reads -23.0 and a fixed 48 kHz shelf drifts at 44.1 kHz, so both rates are pinned.
 */
class LoudnessMeterTest {

    private val amplitude = 0.1f // -20 dBFS peak

    private fun LoudnessMeter.feedSine(sampleRate: Int, seconds: Int, channels: Int = 2) {
        for (n in 0 until sampleRate * seconds) {
            val s = (amplitude * sin(2.0 * PI * 997.0 * n / sampleRate)).toFloat()
            repeat(channels) { accept(s) }
        }
    }

    private fun LoudnessMeter.feedSilence(sampleRate: Int, seconds: Int, channels: Int = 2) {
        repeat(sampleRate * seconds * channels) { accept(0f) }
    }

    private fun assertLufs(expected: Float, result: LoudnessMeter.Result) {
        val lufs = result.integratedLufs
        assertNotNull(lufs)
        assertEquals(expected, lufs!!, 0.1f)
    }

    @Test
    fun `stereo -20 dBFS 997 Hz sine reads -20 LUFS at 48 kHz`() {
        val meter = LoudnessMeter(48_000, 2).apply { feedSine(48_000, 20) }
        assertLufs(-20f, meter.result())
    }

    @Test
    fun `stereo -20 dBFS 997 Hz sine reads -20 LUFS at 44_1 kHz`() {
        val meter = LoudnessMeter(44_100, 2).apply { feedSine(44_100, 20) }
        assertLufs(-20f, meter.result())
    }

    /** One channel carries half the energy of two, so mono at the same level is 3 dB quieter. */
    @Test
    fun `mono sine reads 3 dB below the same sine on both stereo channels`() {
        val meter = LoudnessMeter(48_000, 1).apply { feedSine(48_000, 20, channels = 1) }
        assertLufs(-23.01f, meter.result())
    }

    /** The point of gating: a long silent tail must not drag the integrated value down. */
    @Test
    fun `half sine half digital silence still reads -20 LUFS`() {
        val meter = LoudnessMeter(44_100, 2).apply {
            feedSine(44_100, 30)
            feedSilence(44_100, 30)
        }
        assertLufs(-20f, meter.result())
    }

    @Test
    fun `sample peak is the largest absolute sample on any channel`() {
        val meter = LoudnessMeter(48_000, 2)
        meter.feedSine(48_000, 1)
        meter.accept(0f)
        meter.accept(-0.5f)
        assertEquals(0.5f, meter.result().samplePeak, 0f)
    }

    @Test
    fun `digital silence has no integrated loudness`() {
        val meter = LoudnessMeter(48_000, 2).apply { feedSilence(48_000, 5) }
        assertNull(meter.result().integratedLufs)
    }
}
