package tech.capullo.telecloudradio.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * [CrossfadeMixer] driven by sample count the way the two players' tees drive it, and
 * [RoutableSink]'s flush replay. Left and right carry different streams where that lets one run
 * check both gains at once.
 */
class CrossfadeMixerTest {

    /** Records the PCM written to it, consuming each buffer like the FIFO sink does. */
    private class RecordingSink : TeeAudioProcessor.AudioBufferSink {
        val left = mutableListOf<Int>()
        val right = mutableListOf<Int>()
        val events = mutableListOf<String>()

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            events += "flush $sampleRateHz/$channelCount/$encoding"
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            events += "buffer ${b.remaining() / 4}"
            while (b.remaining() >= 4) {
                left += b.short.toInt()
                right += b.short.toInt()
            }
            buffer.position(buffer.limit())
        }
    }

    // A read-only stereo buffer, as TeeAudioProcessor hands it to its sink.
    private fun pcm(frames: IntRange, left: (Int) -> Int, right: (Int) -> Int): ByteBuffer {
        val b = ByteBuffer.allocate(frames.count() * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in frames) {
            b.putShort(left(f).toShort())
            b.putShort(right(f).toShort())
        }
        b.flip()
        return b.asReadOnlyBuffer()
    }

    // Feeds [total] frames to [sink] in chunks of [chunk], numbering frames from [from].
    private fun feed(sink: TeeAudioProcessor.AudioBufferSink, from: Int, total: Int, chunk: Int, left: (Int) -> Int, right: (Int) -> Int) {
        var f = from
        while (f < from + total) {
            val end = minOf(f + chunk, from + total)
            sink.handleBuffer(pcm(f until end, left, right))
            f = end
        }
    }

    private val ramp = { f: Int -> f % 30_000 }

    private data class Handoff(val out: RecordingSink, val mixer: CrossfadeMixer, val outgoing: CrossfadeMixer.Lane, val incoming: CrossfadeMixer.Lane)

    // The incoming lane prerolls frames 0-2999 (ramp left, -ramp right), more than the fade will
    // take from it. The 1000-frame window ends partway through the outgoing's second 768-frame
    // buffer: 1536 frames out, the last 536 the incoming's alone, 1464 prerolled frames left over.
    private fun handOffWithBacklog(): Handoff {
        val out = RecordingSink()
        val mixer = CrossfadeMixer(out)
        val outgoing = mixer.newLane().also(mixer::setLead)
        val incoming = mixer.newLane().also(mixer::prepareIncoming)
        feed(incoming, 0, 3000, 700, ramp) { -ramp(it) }
        assertTrue(mixer.beginFade(1000L, Runnable {}))
        feed(outgoing, 0, 1536, 768, { MARKER }, { MARKER })
        assertTrue(mixer.isLead(incoming))
        assertEquals(1536, out.left.size)
        return Handoff(out, mixer, outgoing, incoming)
    }

    @Test
    fun `fade is equal power from full outgoing to full incoming`() {
        val out = RecordingSink()
        val mixer = CrossfadeMixer(out)
        val outgoing = mixer.newLane().also(mixer::setLead)
        val incoming = mixer.newLane().also(mixer::prepareIncoming)
        val window = 4800
        feed(incoming, 0, window, 960, { 0 }, { 20_000 })
        var handoffs = 0
        assertTrue(mixer.beginFade(window.toLong(), Runnable { handoffs++ }))

        feed(outgoing, 0, window, 480, { 20_000 }, { 0 })

        assertEquals(window, out.left.size)
        assertEquals(20_000, out.left.first())
        assertEquals(0, out.right.first())
        for (j in 0 until window) {
            val power = sqrt(out.left[j].toDouble() * out.left[j] + out.right[j].toDouble() * out.right[j])
            assertEquals("frame $j", 20_000.0, power, 1.5)
        }
        // Last frame of the window: the outgoing is all but gone, the incoming all but full.
        val lastOut = 20_000 * cos((window - 1).toDouble() / window * PI / 2)
        assertEquals(lastOut, out.left.last().toDouble(), 1.0)
        assertTrue(out.right.last() >= 19_999)
        assertEquals(1, handoffs)
        assertTrue(mixer.isLead(incoming))
    }

    @Test
    fun `after the window the incoming stream continues sample for sample, one buffer out per buffer in`() {
        val (out, mixer, outgoing, incoming) = handOffWithBacklog()
        assertFalse("the handoff already happened", mixer.abortFade(outgoing))
        // The outgoing player keeps decoding until it is released: none of it may be heard.
        feed(outgoing, 1536, 768, 768, { MARKER }, { MARKER })
        assertEquals(1536, out.left.size)
        val eventsAtHandoff = out.events.size

        // The leftover preroll stays behind: each live buffer brings out exactly as much, so the
        // broadcast keeps the incoming player's pace instead of taking the backlog in one burst.
        feed(incoming, 3000, 500, 250, ramp) { -ramp(it) }

        assertEquals(listOf("buffer 250", "buffer 250"), out.events.drop(eventsAtHandoff))
        assertEquals(MARKER, out.left[0])
        for (j in 1000 until out.left.size) {
            assertEquals("left $j", ramp(j), out.left[j])
            assertEquals("right $j", -ramp(j), out.right[j])
        }
    }

    @Test
    fun `a flush of the lead writes what it still delays first, then passes buffers straight through`() {
        val (out, _, _, incoming) = handOffWithBacklog()
        feed(incoming, 3000, 500, 250, ramp) { -ramp(it) }

        incoming.flush(48_000, 2, C.ENCODING_PCM_16BIT)

        // The end of the item: everything decoded before the flush airs, in order, ahead of it.
        assertEquals("flush 48000/2/2", out.events.last())
        assertEquals(3500, out.left.size)
        for (j in 1000 until 3500) {
            assertEquals("left $j", ramp(j), out.left[j])
            assertEquals("right $j", -ramp(j), out.right[j])
        }
        // Nothing left to delay: the next item's buffers go out as they come.
        feed(incoming, 0, 300, 300, { 7 }, { -7 })
        assertEquals("buffer 300", out.events.last())
        assertEquals(3800, out.left.size)
        assertEquals(7, out.left.last())
    }

    @Test
    fun `a delayed lead fades out from where its output was, and its leftover is never heard`() {
        val (out, mixer, _, lead) = handOffWithBacklog()
        feed(lead, 3000, 500, 250, ramp) { -ramp(it) }
        val next = mixer.newLane().also(mixer::prepareIncoming)
        feed(next, 0, 2000, 500, { 0 }, { 10_000 })
        val window = 1000
        assertTrue(mixer.beginFade(window.toLong(), Runnable {}))
        val atFade = out.left.size

        feed(lead, 3500, window, 500, ramp) { -ramp(it) }

        assertTrue(mixer.isLead(next))
        // The lead fades from the next frame it had delayed, not from its newest buffer.
        for (j in 0 until window) {
            val expected = ramp(atFade + j) * cos(j.toDouble() / window * PI / 2)
            assertEquals("frame ${atFade + j}", expected, out.left[atFade + j].toDouble(), 1.0)
        }
        // What the old lead still held, and anything it decodes later, stays off the air.
        feed(lead, 4500, 500, 500, ramp) { -ramp(it) }
        feed(next, 2000, 500, 250, { 0 }, { 10_000 })
        assertEquals(atFade + window + 500, out.left.size)
        assertTrue(out.left.drop(atFade + window).all { it == 0 })
        assertTrue(out.right.drop(atFade + window).all { it == 10_000 })
    }

    @Test
    fun `aborting mid-fade ramps back instead of jumping`() {
        val out = RecordingSink()
        val mixer = CrossfadeMixer(out)
        val outgoing = mixer.newLane().also(mixer::setLead)
        val incoming = mixer.newLane().also(mixer::prepareIncoming)
        val window = 4800
        feed(incoming, 0, window, 960, { 0 }, { 10_000 })
        assertTrue(mixer.beginFade(window.toLong(), Runnable {}))
        feed(outgoing, 0, window / 2, 480, { 10_000 }, { 0 })
        val atAbort = out.left.size

        assertTrue(mixer.abortFade(outgoing))
        feed(outgoing, window / 2, 2 * CrossfadeMixer.RAMP_FRAMES, 480, { 10_000 }, { 0 })

        // Picks up where the fade left off (cos 45°), not at full level.
        assertEquals(7071.0, out.left[atAbort].toDouble(), 2.0)
        assertEquals(7071.0, out.right[atAbort].toDouble(), 2.0)
        for (j in atAbort + 1 until out.left.size) {
            assertTrue("outgoing rises at $j", out.left[j] >= out.left[j - 1])
            assertTrue("incoming falls at $j", out.right[j] <= out.right[j - 1])
        }
        val afterRamp = atAbort + CrossfadeMixer.RAMP_FRAMES
        assertTrue(out.left.drop(afterRamp).all { it == 10_000 })
        assertTrue(out.right.drop(afterRamp).all { it == 0 })
        assertTrue(mixer.isLead(outgoing))
    }

    @Test
    fun `a sink attached after the format flush is configured before its first buffer`() {
        val route = RoutableSink()
        route.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        route.handleBuffer(pcm(0 until 10, { 1 }, { 1 })) // nowhere to go yet
        val sink = RecordingSink()

        route.attach(sink)
        route.handleBuffer(pcm(0 until 10, { 1 }, { 1 }))
        route.handleBuffer(pcm(0 until 10, { 1 }, { 1 }))
        route.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        route.handleBuffer(pcm(0 until 10, { 1 }, { 1 }))

        assertEquals(
            listOf("flush 48000/2/2", "buffer 10", "buffer 10", "flush 44100/2/2", "buffer 10"),
            sink.events,
        )
    }

    private companion object {
        // The outgoing stream in the handoff tests: any of it after the window is a bug.
        const val MARKER = 12_345
    }
}
