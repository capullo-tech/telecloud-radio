package tech.capullo.telecloudradio.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A [TeeAudioProcessor.AudioBufferSink] that is told the end of its stream apart from a flush.
 * [TeeAudioProcessor] reports both as a flush, but a crossfade lane has to keep the audio it delays
 * across an end of stream and drop it at a flush.
 */
@UnstableApi
interface StreamEndSink : TeeAudioProcessor.AudioBufferSink {
    /**
     * The stream ended: everything before it was real audio. A flush may follow, and then the next
     * item of a gapless join in the same format (DefaultAudioSink drains and re-flushes its
     * processors at every item boundary).
     */
    fun endOfStream(sampleRateHz: Int, channelCount: Int, encoding: Int)
}

/**
 * A tee (like [TeeAudioProcessor]: passes its input on unchanged and hands a read-only view of it to
 * a sink) whose sink can change at runtime. Media3 fixes every AudioProcessor in the chain when the
 * sink is built, so each player's FIFO and silence-watchdog tees are one of these and
 * PlaybackService only ever repoints [attach].
 *
 * A newly attached sink is first flushed with the current format, on the playback thread just
 * before the first buffer it sees, so it is configured exactly as if it had been attached from the
 * start. Doing that there rather than in [attach] keeps every call into the sink on the playback
 * thread, where the sink's own locking expects it.
 *
 * An end of stream reaches a [StreamEndSink] as such; any other sink gets it as a flush, as from
 * [TeeAudioProcessor].
 */
@UnstableApi
class RoutableTee : BaseAudioProcessor() {
    private class Route(val sink: TeeAudioProcessor.AudioBufferSink) {
        // Playback thread only.
        var configured = false
    }

    @Volatile private var route: Route? = null

    // The read-only view handed to the sink, playback thread only. The chain passes the tee the
    // same buffer every time unless the processor before it grows it, so the view is only rebuilt
    // then rather than allocated per buffer.
    private var viewSource: ByteBuffer? = null
    private var view: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /**
     * Routes this tee to [sink] from the next buffer on; null drops everything. [replayFormat] false
     * skips the format replay, for a re-route between sinks that never need it: replaying into the
     * FIFO sink would discard whatever its pause gate still holds.
     */
    fun attach(sink: TeeAudioProcessor.AudioBufferSink?, replayFormat: Boolean = true) {
        route = sink?.let { Route(it).apply { configured = !replayFormat } }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val r = route
        if (r != null) {
            if (!r.configured) {
                r.configured = true
                r.sink.flush(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, inputAudioFormat.encoding)
            }
            r.sink.handleBuffer(viewOf(inputBuffer))
        }
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onQueueEndOfStream() = announce(endOfStream = true)

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = announce(endOfStream = false)

    // After a reset nothing is configured, so there is nothing to announce (as in TeeAudioProcessor).

    private fun announce(endOfStream: Boolean) {
        if (!isActive) return
        val r = route ?: return
        r.configured = true
        val format = inputAudioFormat
        val sink = r.sink
        if (endOfStream && sink is StreamEndSink) {
            sink.endOfStream(format.sampleRate, format.channelCount, format.encoding)
        } else {
            sink.flush(format.sampleRate, format.channelCount, format.encoding)
        }
    }

    private fun viewOf(input: ByteBuffer): ByteBuffer {
        if (input !== viewSource) {
            viewSource = input
            view = input.asReadOnlyBuffer().order(input.order())
        }
        view.limit(input.limit())
        view.position(input.position())
        return view
    }
}

/**
 * Measures how far the PCM entering this sink runs ahead of what it has played. The tees inside it
 * see each buffer as it enters, while the player's position follows the AudioTrack's playback head,
 * up to its buffer later. Both values are in the renderer's timeline, so their difference holds
 * across seeks and item boundaries. PlaybackService schedules a crossfade's end with it instead of
 * assuming the largest AudioTrack buffer any device might have.
 */
@UnstableApi
class InputLeadAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    // Playback thread only.
    private var inputFrameBytes = 0
    private var inputSampleRateHz = 0
    private var lastPresentationTimeUs = C.TIME_UNSET

    // Written on the playback thread, read on the main thread.
    @Volatile private var inputEndUs = C.TIME_UNSET

    @Volatile private var playedUs = C.TIME_UNSET

    /** How far the input runs ahead of the playback position, or null until both are known. */
    fun inputLeadMs(): Long? {
        val end = inputEndUs
        val played = playedUs
        if (end == C.TIME_UNSET || played == C.TIME_UNSET) return null
        return maxOf(0L, end - played) / 1000
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        // Decoded PCM only; anything else leaves the lead unknown.
        val pcm = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW && Util.isEncodingLinearPcm(inputFormat.pcmEncoding)
        inputFrameBytes = if (pcm) Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount) else 0
        inputSampleRateHz = inputFormat.sampleRate
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // The sink is handed the rest of a buffer it couldn't take at once again: only a new
        // buffer moves the end.
        if (presentationTimeUs != lastPresentationTimeUs && inputFrameBytes > 0 && inputSampleRateHz > 0) {
            lastPresentationTimeUs = presentationTimeUs
            inputEndUs = presentationTimeUs + buffer.remaining() / inputFrameBytes * 1_000_000L / inputSampleRateHz
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = super.getCurrentPositionUs(sourceEnded).also {
        if (it != AudioSink.CURRENT_POSITION_NOT_SET) playedUs = it
    }

    override fun flush() {
        forget()
        super.flush()
    }

    override fun reset() {
        forget()
        super.reset()
    }

    private fun forget() {
        lastPresentationTimeUs = C.TIME_UNSET
        inputEndUs = C.TIME_UNSET
        playedUs = C.TIME_UNSET
    }
}

/**
 * Crossfades between two players' PCM into one broadcast [output] (the snapserver FIFO sink).
 *
 * Every player that can be part of a crossfade writes its tee into its own [Lane]. One lane is the
 * lead: outside a fade its PCM goes on to [output]. The incoming player's lane
 * ([prepareIncoming]) captures into a preallocated ring from its very first sample. During a fade
 * ([beginFade]) every lead buffer is mixed, frame for frame, with the same number of frames taken
 * from that ring, with equal-power gains (cos/sin, so loudness holds steady through the window).
 * Equal-power gains sum to up to √2 at mid-fade, so two loud or correlated tracks can add up past
 * full scale: the mix goes through a soft limiter that leaves everything below [LIMIT_KNEE]
 * untouched and bends the rest smoothly towards full scale instead of clipping it.
 *
 * Progress is counted in lead frames, not wall time: the lead's tee is already paced in real time
 * by its AudioTrack, so the window is exactly as long as the PCM it covers. When the count reaches
 * the window length, frames past it in the same buffer come from the ring alone, and the incoming
 * lane becomes the lead: the old lead's later buffers are dropped (nothing of the outgoing track is
 * heard after the window). The handoff callback then lets the service promote the player itself.
 * If the lead's own track ends inside the window, its later buffers carry its player's gapless
 * next item: from then on they only pace the window, and the outgoing side is silent.
 *
 * At the handoff the new lead's ring still holds a backlog: what its player decoded during preroll
 * (its AudioTrack's prefill, up to ~0.75 s), which a frame-for-frame mix never catches up on.
 * Written out at once, it would hit the FIFO as one burst (the pipe holds ~0.34 s): once the pipe
 * is full the write blocks the new lead's playback thread while its AudioTrack drains, and as
 * nothing ever takes that extra back out of the pipe, every later handoff stacks another prefill on
 * top. Instead the lead keeps the backlog as a fixed delay: each buffer it delivers joins the back
 * of its ring and as many frames leave the front, so the incoming stream continues sample for
 * sample, paced by its AudioTrack exactly like the outgoing one was, only behind its tee by the
 * backlog. That delay never bursts out:
 * - A flush of the lead (seek, skip, stop) drops it: it belongs to a position the player has left.
 * - An end of stream keeps it: it is the end of the track. The next item of a gapless join
 *   pushes it out like any other buffer (the flush between the two only reconfigures the chain).
 *   While no buffer follows (the end of the playlist, or a slow reconfiguration), a tail writer
 *   thread writes it out at real-time pace.
 *
 * Threading: lanes are fed on their players' playback threads; control calls come from the main
 * thread. [stateLock] guards the lane roles and the rings and is never held across an output
 * write. [outputLock] serializes output writes so the outgoing lane's last mixed buffer is always
 * written before the incoming lane's first; only lead lanes and the tail writer take it, so the
 * incoming player's capture never waits on the FIFO. The front of the lead's ring is written
 * straight to [output] outside [stateLock]: every append to a lead's ring holds [outputLock] too,
 * so the bytes stay put until the write returns. Nothing on the audio path allocates: rings are
 * sized up front and mixed PCM goes through a reused scratch buffer that only grows if a buffer
 * ever exceeds it.
 *
 * PCM is always 48000 Hz / 16-bit / stereo (LOCKSTEP with SnapserverProcess and the Sonic
 * resampler in every player's chain), so mixing is a plain per-sample sum. A lane fails fast on any
 * other format.
 */
@UnstableApi
class CrossfadeMixer(
    private val output: TeeAudioProcessor.AudioBufferSink,
    laneCapacityBytes: Int = DEFAULT_LANE_CAPACITY_BYTES,
    // Runs the tail writer. Tests pass one that keeps the task, to run or step it themselves.
    private val tailExecutor: Executor = newTailExecutor(),
) {
    private val laneCapacity = laneCapacityBytes - laneCapacityBytes % BYTES_PER_FRAME

    /** One player's way into the mixer; set as that player's FIFO tee destination. */
    inner class Lane internal constructor() : StreamEndSink {
        // Ring, guarded by stateLock: the capture while this lane is incoming (read by the mix),
        // and the delay it took over at the handoff while it is the lead.
        private val ring = ByteArray(laneCapacity)
        private val ringView: ByteBuffer = ByteBuffer.wrap(ring)
        internal var readPos = 0
        internal var size = 0

        // Guarded by stateLock. Set at an end of stream, cleared by the next buffer. Until then a
        // flush is the chain reconfiguring for the next item of a gapless join, not the player
        // leaving its position, and keeps what the ring holds.
        internal var ended = false

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            checkFormat(sampleRateHz, channelCount, encoding)
            laneFlush(this, endOfStream = false, sampleRateHz, channelCount, encoding)
        }

        override fun endOfStream(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            checkFormat(sampleRateHz, channelCount, encoding)
            laneFlush(this, endOfStream = true, sampleRateHz, channelCount, encoding)
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            laneBuffer(this, buffer)
        }

        internal fun clear() {
            readPos = 0
            size = 0
        }

        // Copies [buffer] in without consuming it. What doesn't fit is dropped: the ring holds
        // seconds while the incoming player only runs ahead by its AudioTrack buffer.
        internal fun append(buffer: ByteBuffer) {
            var n = minOf(buffer.remaining(), laneCapacity - size)
            n -= n % BYTES_PER_FRAME
            if (n <= 0) return
            val start = buffer.position()
            val write = (readPos + size) % laneCapacity
            val first = minOf(n, laneCapacity - write)
            buffer.get(ring, write, first)
            if (n > first) buffer.get(ring, 0, n - first)
            buffer.position(start)
            size += n
        }

        // Next 16-bit sample, or silence if the ring has run dry.
        internal fun pollSample(): Int {
            if (size < BYTES_PER_SAMPLE) return 0
            // readPos is always even and the capacity a multiple of 4, so the pair never wraps.
            val sample = (ring[readPos + 1].toInt() shl 8) or (ring[readPos].toInt() and 0xFF)
            consume(BYTES_PER_SAMPLE)
            return sample
        }

        // The front of the ring, up to [maxBytes] and never across its wrap, already consumed.
        // A view into the ring itself: valid until the next append.
        internal fun takeFront(maxBytes: Int): ByteBuffer? {
            val len = minOf(maxBytes, size, laneCapacity - readPos)
            if (len <= 0) return null
            ringView.clear()
            ringView.position(readPos)
            ringView.limit(readPos + len)
            consume(len)
            return ringView
        }

        private fun consume(bytes: Int) {
            readPos = (readPos + bytes) % laneCapacity
            size -= bytes
        }
    }

    private val stateLock = Any()
    private val outputLock = Any()

    // --- guarded by stateLock ---
    private var lead: Lane? = null
    private var incoming: Lane? = null
    private var fadeFrames = 0L // 0 = not fading
    private var fadeDone = 0L
    private var fadeLeadEnded = false // the lead's own track ended inside the window
    private var onHandoff: Runnable? = null

    // A short linear ramp that brings the lead back to full level after a fade is abandoned,
    // while whatever had faded in fades back out of [rampSource]'s ring.
    private var rampSource: Lane? = null
    private var rampFrames = 0 // 0 = not ramping
    private var rampDone = 0
    private var rampOutFrom = 1.0
    private var rampInFrom = 0.0

    private var tailWriting = false // the tail writer is queued or running

    // --- guarded by outputLock ---
    private var scratch: ByteBuffer = ByteBuffer.allocate(INITIAL_SCRATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    // The tail writer: an ended lead's delay at real-time pace, never ahead of it (chunk n goes
    // out no earlier than n chunk lengths after the start, later when a write blocks on a full
    // pipe). It waits a chunk before the first write, so the next item of a gapless join, which
    // arrives right behind the end of stream, carries the delay on and nothing is written here.
    private val tailTask = Runnable {
        var due = System.nanoTime()
        do {
            due += TAIL_CHUNK_NANOS
            while (true) {
                val wait = due - System.nanoTime()
                if (wait <= 0) break
                LockSupport.parkNanos(wait)
            }
        } while (writeTailChunk())
    }

    /** A new lane with its own preallocated capture ring. Call off the audio thread. */
    fun newLane(): Lane = Lane()

    /**
     * Makes [lane] the one passed through to the output, ending any fade; null mutes every lane.
     * Whatever the previous lead still delayed is never written.
     */
    fun setLead(lane: Lane?) {
        synchronized(stateLock) {
            lead = lane
            incoming = null
            clearFade()
            clearRamp()
        }
    }

    /** Starts capturing [lane] into its ring. Call before its player is prepared, so the ring
     *  holds the incoming track from its first sample. */
    fun prepareIncoming(lane: Lane) {
        synchronized(stateLock) {
            lane.clear()
            incoming = lane
        }
    }

    /**
     * Fades from the lead into the prepared incoming lane over [frames] lead frames. [onHandoff]
     * runs on the outgoing playback thread once the incoming lane has become the lead. False if
     * there is nothing to fade between or a fade is already running.
     */
    fun beginFade(frames: Long, onHandoff: Runnable): Boolean {
        synchronized(stateLock) {
            if (lead == null || incoming == null || fadeFrames > 0 || frames <= 0) return false
            clearRamp()
            fadeFrames = frames
            fadeDone = 0
            this.onHandoff = onHandoff
            return true
        }
    }

    /**
     * Drops the incoming lane while [keep] is still the lead. A fade already under way doesn't
     * snap back: [keep] ramps back to full level and the incoming audio ramps out over
     * [RAMP_FRAMES]. False if [keep] is no longer the lead, i.e. the handoff already happened.
     */
    fun abortFade(keep: Lane): Boolean {
        synchronized(stateLock) {
            if (lead !== keep) return false
            val source = incoming
            if (fadeFrames > 0 && fadeDone > 0 && source != null) {
                val angle = fadeAngle(fadeDone)
                startRamp(source, outFrom = cos(angle), inFrom = sin(angle))
            }
            incoming = null
            clearFade()
            return true
        }
    }

    /** Takes the lead back for [lane] after a handoff, ramping it in from silence. */
    fun revertTo(lane: Lane) {
        synchronized(stateLock) {
            lead = lane
            incoming = null
            clearFade()
            startRamp(source = null, outFrom = 0.0, inFrom = 0.0)
        }
    }

    /** True once [lane] carries the broadcast, i.e. a fade into it has handed off. */
    fun isLead(lane: Lane?): Boolean = synchronized(stateLock) { lane != null && lead === lane }

    /**
     * True once [lane] has nothing left that only the mixer can render: no fade, no ramp and no
     * delay. Routed straight to the output from its next buffer on, it then sounds exactly as it
     * does through here.
     */
    fun isDrained(lane: Lane): Boolean = synchronized(stateLock) {
        lead !== lane || (incoming == null && fadeFrames == 0L && rampFrames == 0 && lane.size == 0)
    }

    /** How far the broadcast runs behind the lead's tee: the backlog it took over at a handoff. */
    fun leadDelayMs(): Long = synchronized(stateLock) {
        (lead?.size ?: 0).toLong() / BYTES_PER_FRAME * 1000 / SAMPLE_RATE_HZ
    }

    /**
     * One step of the tail writer: the next [TAIL_CHUNK_FRAMES] of an ended lead's delay. False,
     * ending the writer, once there is nothing (left) for it: the lead's stream went on, a fade or
     * ramp has to render the delay from the lead's own buffers, or the delay is gone.
     */
    internal fun writeTailChunk(): Boolean {
        synchronized(outputLock) {
            val lane = synchronized(stateLock) {
                val lane = lead?.takeIf { it.ended && it.size > 0 && fadeFrames == 0L && rampFrames == 0 }
                if (lane == null) tailWriting = false
                lane
            } ?: return false
            writeDelayed(lane, TAIL_CHUNK_FRAMES * BYTES_PER_FRAME)
            return true
        }
    }

    private fun startTail() {
        synchronized(stateLock) {
            if (tailWriting) return
            tailWriting = true
        }
        tailExecutor.execute(tailTask)
    }

    private fun laneBuffer(lane: Lane, buffer: ByteBuffer) {
        synchronized(stateLock) {
            lane.ended = false
            if (lane === incoming) {
                lane.append(buffer)
                return
            }
            if (lane !== lead) return
        }
        synchronized(outputLock) { writeAsLead(lane, buffer) }
    }

    private fun laneFlush(lane: Lane, endOfStream: Boolean, sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(stateLock) {
            if (lane === incoming) {
                // A flush with no end of stream before it: the incoming player left the position
                // it was capturing. Its own end keeps the ring, which the mix still plays out.
                if (endOfStream) {
                    lane.ended = true
                } else if (!lane.ended) {
                    lane.clear()
                }
                return
            }
            if (lane !== lead) return
        }
        var tail = false
        synchronized(outputLock) {
            synchronized(stateLock) {
                if (lane !== lead) return
                if (endOfStream) {
                    // The lead's track ended. What it still delays is that track's real end and
                    // stays (see the class doc). Inside a fade, its later buffers only pace the
                    // window from here (see writeAsLead).
                    lane.ended = true
                    if (fadeFrames > 0) fadeLeadEnded = true
                    clearRamp()
                    tail = lane.size > 0 && fadeFrames == 0L
                } else if (!lane.ended) {
                    // The player left this position (seek, skip, stop): what the lane still
                    // delays must not air, and a fade or ramp across the old position no longer
                    // lines up with the new one.
                    lane.clear()
                    if (fadeFrames > 0) {
                        incoming = null
                        clearFade()
                    }
                    clearRamp()
                }
                // Otherwise the chain is reconfiguring right after an end of stream: all carries on.
            }
            output.flush(sampleRateHz, channelCount, encoding)
        }
        if (tail) startTail()
    }

    // outputLock held.
    private fun writeAsLead(lane: Lane, buffer: ByteBuffer) {
        var out: ByteBuffer? = buffer
        var delayedBytes = 0
        var handoff: Runnable? = null
        var tail = false
        synchronized(stateLock) {
            if (lane !== lead) return
            val source = incoming.takeIf { fadeFrames > 0 }
            // Once its own track has ended inside the fade, the lead's buffers carry its player's
            // next item: its samples come from its ring alone, silence once that runs dry.
            val leadEnded = source != null && fadeLeadEnded
            // Delayed since the handoff into this lane: the buffer joins the back of its ring and
            // as many frames leave the front, so the output keeps this buffer's size and timing.
            val ring = if (lane.size > 0 || leadEnded) lane else null
            if (ring != null && !leadEnded) ring.append(buffer)
            if (source != null) {
                out = mixFade(buffer, ring, source)
                if (fadeDone >= fadeFrames) {
                    handoff = onHandoff
                    // Whatever this lane still delays is past the window: never heard.
                    lane.clear()
                    lead = source
                    incoming = null
                    clearFade()
                    // The new lead's track already ended inside the window: no buffer of its own
                    // will push its backlog out.
                    tail = source.ended && source.size > 0
                }
            } else if (rampFrames > 0) {
                out = mixRamp(buffer, ring)
            } else if (ring != null) {
                out = null
                delayedBytes = buffer.remaining() - buffer.remaining() % BYTES_PER_FRAME
            }
        }
        val mixed = out
        if (mixed != null) output.handleBuffer(mixed) else writeDelayed(lane, delayedBytes)
        handoff?.run()
        if (tail) startTail()
    }

    // outputLock held. Writes up to [bytes] from the front of the lead's ring, straight from the
    // ring (see the class doc for why the bytes stay put).
    private fun writeDelayed(lane: Lane, bytes: Int) {
        var left = bytes
        while (left > 0) {
            val front = synchronized(stateLock) {
                if (lane !== lead) return
                lane.takeFront(left) ?: return
            }
            left -= front.remaining()
            output.handleBuffer(front)
        }
    }

    // stateLock + outputLock held. The lead's samples come from [ring] (its own) when it has one,
    // else straight from [outgoing].
    private fun mixFade(outgoing: ByteBuffer, ring: Lane?, source: Lane): ByteBuffer {
        val frames = outgoing.remaining() / BYTES_PER_FRAME
        val dst = scratchFor(frames * BYTES_PER_FRAME)
        var index = outgoing.position()
        for (i in 0 until frames) {
            val frame = fadeDone + i
            val gOut: Double
            val gIn: Double
            if (frame < fadeFrames) {
                val angle = fadeAngle(frame)
                gOut = cos(angle)
                gIn = sin(angle)
            } else {
                gOut = 0.0
                gIn = 1.0
            }
            repeat(CHANNELS) {
                val leadSample = if (ring != null) ring.pollSample() else readSample(outgoing, index)
                dst.putShort(mix(leadSample, gOut, source.pollSample(), gIn))
                index += BYTES_PER_SAMPLE
            }
        }
        fadeDone += frames
        dst.flip()
        return dst
    }

    // stateLock + outputLock held. [ring] as in mixFade.
    private fun mixRamp(outgoing: ByteBuffer, ring: Lane?): ByteBuffer {
        val frames = outgoing.remaining() / BYTES_PER_FRAME
        val dst = scratchFor(frames * BYTES_PER_FRAME)
        val source = rampSource
        var index = outgoing.position()
        for (i in 0 until frames) {
            val step = rampDone + i
            val ramping = step < rampFrames
            val t = if (ramping) step.toDouble() / rampFrames else 1.0
            val gOut = rampOutFrom + (1.0 - rampOutFrom) * t
            val gIn = rampInFrom * (1.0 - t)
            repeat(CHANNELS) {
                val leadSample = if (ring != null) ring.pollSample() else readSample(outgoing, index)
                val incomingSample = if (ramping && source != null) source.pollSample() else 0
                dst.putShort(mix(leadSample, gOut, incomingSample, gIn))
                index += BYTES_PER_SAMPLE
            }
        }
        rampDone += frames
        if (rampDone >= rampFrames) clearRamp()
        dst.flip()
        return dst
    }

    private fun scratchFor(bytes: Int): ByteBuffer {
        if (scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocate(maxOf(bytes, scratch.capacity() * 2)).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        return scratch
    }

    private fun fadeAngle(frame: Long): Double = frame.toDouble() / fadeFrames * (PI / 2)

    private fun startRamp(source: Lane?, outFrom: Double, inFrom: Double) {
        rampSource = source
        rampFrames = RAMP_FRAMES
        rampDone = 0
        rampOutFrom = outFrom
        rampInFrom = inFrom
    }

    private fun clearFade() {
        fadeFrames = 0
        fadeDone = 0
        fadeLeadEnded = false
        onHandoff = null
    }

    private fun clearRamp() {
        rampSource = null
        rampFrames = 0
        rampDone = 0
    }

    companion object {
        /** LOCKSTEP with SnapserverProcess and the Sonic resampler in every player's chain. */
        const val SAMPLE_RATE_HZ = 48_000
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE

        /** 4 s per lane: the incoming player runs ahead by at most its AudioTrack buffer (≤ 0.75 s). */
        const val DEFAULT_LANE_CAPACITY_BYTES = SAMPLE_RATE_HZ * BYTES_PER_FRAME * 4

        /** Length of the ramp that undoes an abandoned fade (50 ms). */
        const val RAMP_FRAMES = SAMPLE_RATE_HZ / 20

        /** What the tail writer writes per step (10 ms). */
        const val TAIL_CHUNK_FRAMES = SAMPLE_RATE_HZ / 100
        private const val TAIL_CHUNK_NANOS = TAIL_CHUNK_FRAMES * 1_000_000_000L / SAMPLE_RATE_HZ

        /**
         * Where the soft limiter starts bending the mix (0.9 of full scale). Below it the gains
         * stay exactly equal-power; above it the level approaches full scale without reaching it.
         */
        const val LIMIT_KNEE = 0.9 * Short.MAX_VALUE
        private const val LIMIT_HEADROOM = Short.MAX_VALUE - LIMIT_KNEE

        private const val INITIAL_SCRATCH_BYTES = 64 * 1024

        // One daemon thread, started up front and kept: creating it on demand would run
        // Thread.start() on the playback thread at a track boundary.
        private fun newTailExecutor(): Executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, "CrossfadeTail").apply { isDaemon = true }
        }.apply { prestartCoreThread() }

        private fun checkFormat(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            check(sampleRateHz == SAMPLE_RATE_HZ && channelCount == CHANNELS && encoding == C.ENCODING_PCM_16BIT) {
                "crossfade lane got $sampleRateHz Hz / $channelCount ch / encoding $encoding; " +
                    "the mixer sums $SAMPLE_RATE_HZ Hz / $CHANNELS ch / 16-bit PCM only"
            }
        }

        private fun readSample(buffer: ByteBuffer, index: Int): Int = (buffer.get(index + 1).toInt() shl 8) or (buffer.get(index).toInt() and 0xFF)

        private fun mix(a: Int, gainA: Double, b: Int, gainB: Double): Short = softLimit(a * gainA + b * gainB).roundToInt().toShort()

        // Identity up to LIMIT_KNEE; above it x/(1 + x/headroom) of the excess, which meets the
        // knee with the same value and slope and stays below full scale for any input.
        private fun softLimit(x: Double): Double {
            val magnitude = abs(x)
            if (magnitude <= LIMIT_KNEE) return x
            val over = magnitude - LIMIT_KNEE
            val bent = LIMIT_KNEE + over / (1.0 + over / LIMIT_HEADROOM)
            return if (x < 0) -bent else bent
        }
    }
}
