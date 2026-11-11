package tech.capullo.telecloudradio.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [CrossfadeMixer] driven by sample count the way the two players' tees drive it, and
 * [RoutableTee]'s routing. Left and right carry different streams where that lets one run check
 * both gains at once.
 */
class CrossfadeMixerTest {

    /** Records the PCM written to it, consuming each buffer like the FIFO sink does. */
    private open class RecordingSink : TeeAudioProcessor.AudioBufferSink {
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

    private class StreamEndRecordingSink :
        RecordingSink(),
        StreamEndSink {
        override fun endOfStream(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            events += "end $sampleRateHz/$channelCount/$encoding"
        }
    }

    // The mixer's tail writer runs, or is stepped, by the test itself.
    private val tailTasks = mutableListOf<Runnable>()

    private fun newMixer(out: RecordingSink) = CrossfadeMixer(out, tailExecutor = { tailTasks += it })

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

    // The incoming stream from frame 1000 on, as it should reach the output after a handoff.
    private fun assertIncomingContinues(out: RecordingSink, until: Int) {
        assertEquals(until, out.left.size)
        for (j in 1000 until until) {
            assertEquals("left $j", ramp(j), out.left[j])
            assertEquals("right $j", -ramp(j), out.right[j])
        }
    }

    private data class Handoff(val out: RecordingSink, val mixer: CrossfadeMixer, val outgoing: CrossfadeMixer.Lane, val incoming: CrossfadeMixer.Lane)

    // The incoming lane prerolls frames 0-2999 (ramp left, -ramp right), more than the fade will
    // take from it. The 1000-frame window ends partway through the outgoing's second 768-frame
    // buffer: 1536 frames out, the last 536 the incoming's alone, 1464 prerolled frames left over.
    private fun handOffWithBacklog(): Handoff {
        val out = RecordingSink()
        val mixer = newMixer(out)
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
        val mixer = newMixer(out)
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
    fun `a loud correlated fade bends below full scale instead of clipping, a quieter one stays equal power`() {
        // The same signal on both tracks, the worst case: cos + sin peaks at √2 mid-fade.
        val window = 4800
        fun fadeOf(level: Int): RecordingSink {
            val out = RecordingSink()
            val mixer = newMixer(out)
            val outgoing = mixer.newLane().also(mixer::setLead)
            val incoming = mixer.newLane().also(mixer::prepareIncoming)
            feed(incoming, 0, window, 960, { level }, { -level })
            assertTrue(mixer.beginFade(window.toLong(), Runnable {}))
            feed(outgoing, 0, window, 480, { level }, { -level })
            return out
        }

        val loud = fadeOf(30_000) // sums to 42 426 mid-fade
        val peak = loud.left.max()
        assertTrue("peak $peak: close to full scale, never held at it", peak in 32_000 until Short.MAX_VALUE.toInt())
        // Rises to the middle of the window and falls after it, like the sum it bends.
        for (j in 1..window / 2) assertTrue("rises at $j", loud.left[j] >= loud.left[j - 1])
        for (j in window / 2 + 1 until window) assertTrue("falls at $j", loud.left[j] <= loud.left[j - 1])
        for (j in 0 until window) assertEquals("frame $j", -loud.left[j].toDouble(), loud.right[j].toDouble(), 1.0)

        val quiet = fadeOf(10_000) // peaks at 14 142, below the knee
        for (j in 0 until window) {
            val angle = j.toDouble() / window * PI / 2
            assertEquals("frame $j", 10_000 * (cos(angle) + sin(angle)), quiet.left[j].toDouble(), 1.0)
        }
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
        assertIncomingContinues(out, until = 2036)
    }

    @Test
    fun `a flush of the lead drops what it still delays, then passes buffers straight through`() {
        val (out, mixer, _, lead) = handOffWithBacklog()
        feed(lead, 3000, 500, 250, ramp) { -ramp(it) }
        assertFalse("the backlog still delays the lane", mixer.isDrained(lead))

        lead.flush(48_000, 2, PCM_16BIT) // a seek, skip or stop

        // What it delayed belongs to the position the player left: it never airs.
        assertEquals("flush 48000/2/2", out.events.last())
        assertIncomingContinues(out, until = 2036)
        assertTrue(mixer.isDrained(lead))
        assertTrue(tailTasks.isEmpty())
        feed(lead, 0, 300, 300, { 7 }, { -7 })
        assertEquals("buffer 300", out.events.last())
        assertEquals(2336, out.left.size)
        assertEquals(7, out.left.last())
    }

    @Test
    fun `an end of stream keeps the delay, the tail writer fills a gap, and the next item carries the rest on`() {
        val (out, mixer, _, lead) = handOffWithBacklog()
        feed(lead, 3000, 500, 250, ramp) { -ramp(it) }
        val atEnd = out.events.size

        // DefaultAudioSink at an item boundary: drain (end of stream), re-flush, next item. Here
        // the next item is slow to come, and one tail chunk airs meanwhile.
        lead.endOfStream(48_000, 2, PCM_16BIT)
        assertEquals(1, tailTasks.size)
        assertTrue(mixer.writeTailChunk())
        lead.flush(48_000, 2, PCM_16BIT)
        feed(lead, 3500, 500, 250, ramp) { -ramp(it) }

        val chunk = CrossfadeMixer.TAIL_CHUNK_FRAMES
        assertEquals(listOf("flush 48000/2/2", "buffer $chunk", "flush 48000/2/2", "buffer 250", "buffer 250"), out.events.drop(atEnd))
        assertIncomingContinues(out, until = 2036 + chunk + 500)
        // The stream went on, so the tail writer stops, and what is left stays a delay.
        assertFalse(mixer.writeTailChunk())
        assertEquals((1464L - chunk) * 1000 / 48_000, mixer.leadDelayMs())
    }

    @Test
    fun `with nothing after an end of stream, the tail writer airs the delay at real-time pace`() {
        val (out, mixer, _, lead) = handOffWithBacklog()
        feed(lead, 3000, 500, 250, ramp) { -ramp(it) }
        lead.endOfStream(48_000, 2, PCM_16BIT)
        val atEnd = out.events.size

        val started = System.nanoTime()
        tailTasks.single().run()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // 1464 delayed frames: three full 10 ms chunks and the rest, chunk n no earlier than n
        // chunk lengths in.
        val chunk = CrossfadeMixer.TAIL_CHUNK_FRAMES
        assertEquals(listOf("buffer $chunk", "buffer $chunk", "buffer $chunk", "buffer ${1464 - 3 * chunk}"), out.events.drop(atEnd))
        assertTrue("took $elapsedMs ms", elapsedMs >= 4L * chunk * 1000 / 48_000)
        assertIncomingContinues(out, until = 3500)
        assertTrue(mixer.isDrained(lead))
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
    fun `once the outgoing track ends inside the window, its player's next item only paces the fade`() {
        val out = RecordingSink()
        val mixer = newMixer(out)
        val outgoing = mixer.newLane().also(mixer::setLead)
        val incoming = mixer.newLane().also(mixer::prepareIncoming)
        val window = 4800
        feed(incoming, 0, window, 960, { 0 }, { 10_000 })
        assertTrue(mixer.beginFade(window.toLong(), Runnable {}))
        feed(outgoing, 0, window / 2, 480, { 10_000 }, { 0 })

        // Its track ends halfway; after the chain's re-flush the next item follows gaplessly.
        outgoing.endOfStream(48_000, 2, PCM_16BIT)
        outgoing.flush(48_000, 2, PCM_16BIT)
        feed(outgoing, window / 2, window / 2, 480, { MARKER }, { MARKER })

        assertTrue(mixer.isLead(incoming))
        assertEquals(window, out.left.size)
        // The outgoing side goes silent at its end instead of mixing in what its player plays
        // next, while the incoming side keeps rising on the same curve.
        assertTrue(out.left.drop(window / 2).all { it == 0 })
        for (j in 0 until window) {
            assertEquals("frame $j", 10_000 * sin(j.toDouble() / window * PI / 2), out.right[j].toDouble(), 1.0)
        }
    }

    @Test
    fun `aborting mid-fade ramps back instead of jumping, and the lane drains once it has`() {
        val out = RecordingSink()
        val mixer = newMixer(out)
        val outgoing = mixer.newLane().also(mixer::setLead)
        val incoming = mixer.newLane().also(mixer::prepareIncoming)
        val window = 4800
        feed(incoming, 0, window, 960, { 0 }, { 10_000 })
        assertTrue(mixer.beginFade(window.toLong(), Runnable {}))
        feed(outgoing, 0, window / 2, 480, { 10_000 }, { 0 })
        val atAbort = out.left.size

        assertTrue(mixer.abortFade(outgoing))
        // Routed straight to the FIFO now, the ramp would never render: the output would jump.
        assertFalse(mixer.isDrained(outgoing))
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
        assertTrue(mixer.isDrained(outgoing))
    }

    @Test
    fun `a lane fails fast on anything but 48 kHz 16-bit stereo`() {
        val lane = newMixer(RecordingSink()).let { mixer -> mixer.newLane().also(mixer::setLead) }
        assertThrows(IllegalStateException::class.java) { lane.flush(44_100, 2, PCM_16BIT) }
        assertThrows(IllegalStateException::class.java) { lane.endOfStream(48_000, 1, PCM_16BIT) }
        assertThrows(IllegalStateException::class.java) { lane.flush(48_000, 2, C.ENCODING_PCM_FLOAT) }
    }

    @Test
    fun `a sink attached to a running tee is configured before its first buffer, and told an end of stream apart from a flush`() {
        val tee = RoutableTee()
        tee.configure(AudioProcessor.AudioFormat(48_000, 2, PCM_16BIT))
        tee.flush(AudioProcessor.StreamMetadata.DEFAULT)
        tee.queueInput(pcm(0 until 10, { 1 }, { 1 })) // nowhere to go yet
        val sink = StreamEndRecordingSink()

        tee.attach(sink)
        tee.queueInput(pcm(0 until 10, { 1 }, { 1 }))
        tee.queueInput(pcm(0 until 10, { 1 }, { 1 }))
        tee.queueEndOfStream()
        tee.configure(AudioProcessor.AudioFormat(44_100, 2, PCM_16BIT))
        tee.flush(AudioProcessor.StreamMetadata.DEFAULT)
        tee.queueInput(pcm(0 until 10, { 1 }, { 1 }))
        // Any other sink gets an end of stream as a flush, as from TeeAudioProcessor.
        val plain = RecordingSink()
        tee.attach(plain, replayFormat = false)
        tee.queueEndOfStream()

        assertEquals(
            listOf("flush 48000/2/2", "buffer 10", "buffer 10", "end 48000/2/2", "flush 44100/2/2", "buffer 10"),
            sink.events,
        )
        assertEquals(listOf("flush 44100/2/2"), plain.events)
    }

    private companion object {
        const val PCM_16BIT = C.ENCODING_PCM_16BIT

        // The outgoing stream in the handoff tests: any of it after the window is a bug.
        const val MARKER = 12_345
    }
}
