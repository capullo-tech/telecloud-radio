package tech.capullo.telecloudradio.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A player's fixed tee target whose real destination can change at runtime. Media3 fixes every
 * AudioProcessor's destination when the sink is built, so each player's FIFO and silence-watchdog
 * tees point at one of these and PlaybackService only ever repoints [attach].
 *
 * A newly attached sink first receives the most recent [flush] (the tee's format announcement),
 * replayed on the playback thread just before the first buffer it sees, so it is configured exactly
 * as if it had been attached from the start. Replaying there rather than in [attach] keeps every
 * call into the delegate on the playback thread, where the delegate's own locking expects it.
 */
@UnstableApi
class RoutableSink : TeeAudioProcessor.AudioBufferSink {
    private class Route(val sink: TeeAudioProcessor.AudioBufferSink) {
        // Playback thread only.
        var configured = false
    }

    @Volatile private var route: Route? = null

    // Most recent flush, playback thread only.
    private var hasFormat = false
    private var sampleRateHz = 0
    private var channelCount = 0
    private var encoding = 0

    /** Routes this tee to [sink] from the next buffer on; null drops everything. */
    fun attach(sink: TeeAudioProcessor.AudioBufferSink?) {
        route = sink?.let(::Route)
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        hasFormat = true
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.encoding = encoding
        val r = route ?: return
        r.configured = true
        r.sink.flush(sampleRateHz, channelCount, encoding)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val r = route ?: return
        if (!r.configured) {
            r.configured = true
            if (hasFormat) r.sink.flush(sampleRateHz, channelCount, encoding)
        }
        r.sink.handleBuffer(buffer)
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
 *
 * Progress is counted in lead frames, not wall time: the lead's tee is already paced in real time
 * by its AudioTrack, so the window is exactly as long as the PCM it covers. When the count reaches
 * the window length, frames past it in the same buffer come from the ring alone, and the incoming
 * lane becomes the lead: the old lead's later buffers are dropped (nothing of the outgoing track is
 * heard after the window). The handoff callback then lets the service promote the player itself.
 *
 * At that point the new lead's ring still holds a backlog: what its player decoded during preroll
 * (its AudioTrack's prefill, up to ~0.75 s), which a frame-for-frame mix never catches up on.
 * Written out at once, it would hit the FIFO as one burst (the pipe holds ~0.34 s): once the pipe
 * is full the write blocks the new lead's playback thread while its AudioTrack drains, and as
 * nothing ever takes that extra back out of the pipe, every later handoff stacks another prefill on
 * top. Instead the lead keeps the backlog as a fixed delay: each buffer it delivers joins the back
 * of its ring and as many frames leave the front, so the incoming stream continues sample for
 * sample, paced by its AudioTrack exactly like the outgoing one was, only behind its tee by the
 * backlog. A flush of the lead (end of item, seek, stop) writes the backlog out first: the tail of
 * a finished item still airs, and after a seek or skip the FIFO sink, paused by then, discards it
 * with the rest of what it holds.
 *
 * Threading: lanes are fed on their players' playback threads; control calls come from the main
 * thread. [stateLock] guards the lane roles and the rings and is never held across an output
 * write. [outputLock] serializes output writes so the outgoing lane's last mixed buffer is always
 * written before the incoming lane's first; only lead lanes take it, so the incoming player's
 * capture never waits on the FIFO. A lead writes the front of its own ring straight to [output]
 * outside [stateLock]: only its own playback thread appends to that ring, so the bytes stay put
 * until the write returns. Nothing on the audio path allocates: rings are sized up front and mixed
 * PCM goes through a reused scratch buffer that only grows if a buffer ever exceeds it.
 *
 * PCM is always 48000 Hz / 16-bit / stereo (LOCKSTEP with SnapserverProcess and the Sonic
 * resampler in every player's chain), so mixing is a plain per-sample sum.
 */
@UnstableApi
class CrossfadeMixer(
    private val output: TeeAudioProcessor.AudioBufferSink,
    laneCapacityBytes: Int = DEFAULT_LANE_CAPACITY_BYTES,
) {
    private val laneCapacity = laneCapacityBytes - laneCapacityBytes % BYTES_PER_FRAME

    /** One player's way into the mixer; set as that player's FIFO tee destination. */
    inner class Lane internal constructor() : TeeAudioProcessor.AudioBufferSink {
        // Ring, guarded by stateLock: the capture while this lane is incoming (read by the mix),
        // and the delay it took over at the handoff while it is the lead.
        private val ring = ByteArray(laneCapacity)
        internal val ringView: ByteBuffer = ByteBuffer.wrap(ring)
        internal var readPos = 0
        internal var size = 0

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            laneFlush(this, sampleRateHz, channelCount, encoding)
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

        internal fun consume(bytes: Int) {
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
    private var onHandoff: Runnable? = null

    // A short linear ramp that brings the lead back to full level after a fade is abandoned,
    // while whatever had faded in fades back out of [rampSource]'s ring.
    private var rampSource: Lane? = null
    private var rampFrames = 0 // 0 = not ramping
    private var rampDone = 0
    private var rampOutFrom = 1.0
    private var rampInFrom = 0.0

    // --- guarded by outputLock ---
    private var scratch: ByteBuffer = ByteBuffer.allocate(INITIAL_SCRATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN)

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

    /** How far the broadcast runs behind the lead's tee: the backlog it took over at a handoff. */
    fun leadDelayMs(): Long = synchronized(stateLock) {
        (lead?.size ?: 0).toLong() / BYTES_PER_FRAME * 1000 / SAMPLE_RATE_HZ
    }

    private fun laneBuffer(lane: Lane, buffer: ByteBuffer) {
        synchronized(stateLock) {
            if (lane === incoming) {
                lane.append(buffer)
                return
            }
            if (lane !== lead) return
        }
        synchronized(outputLock) { writeAsLead(lane, buffer) }
    }

    private fun laneFlush(lane: Lane, sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(stateLock) {
            if (lane === incoming) {
                lane.clear()
                return
            }
            if (lane !== lead) return
        }
        synchronized(outputLock) {
            // The lead's stream ended or restarted (end of item, seek, stop, new item). What it
            // still delays was delivered before the flush, so it goes out first: the tail of a
            // finished item still airs, and after a seek or skip the FIFO sink, paused by then,
            // discards it with the rest of what it holds.
            writeDelayed(lane, Int.MAX_VALUE)
            synchronized(stateLock) {
                if (lane !== lead) return
                // A fade or ramp across the old position no longer lines up with the new one.
                if (fadeFrames > 0) {
                    incoming = null
                    clearFade()
                }
                clearRamp()
            }
            output.flush(sampleRateHz, channelCount, encoding)
        }
    }

    // outputLock held.
    private fun writeAsLead(lane: Lane, buffer: ByteBuffer) {
        var out: ByteBuffer? = buffer
        var delayedBytes = 0
        var handoff: Runnable? = null
        synchronized(stateLock) {
            if (lane !== lead) return
            // Delayed since the handoff into this lane: the buffer joins the back of its ring and
            // as many frames leave the front, so the output keeps this buffer's size and timing.
            val delay = if (lane.size > 0) lane else null
            delay?.append(buffer)
            val source = incoming
            if (fadeFrames > 0 && source != null) {
                out = mixFade(buffer, delay, source)
                if (fadeDone >= fadeFrames) {
                    handoff = onHandoff
                    // Whatever this lane still delays is past the window: never heard.
                    lane.clear()
                    lead = source
                    incoming = null
                    clearFade()
                }
            } else if (rampFrames > 0) {
                out = mixRamp(buffer, delay)
            } else if (delay != null) {
                out = null
                delayedBytes = buffer.remaining() - buffer.remaining() % BYTES_PER_FRAME
            }
        }
        val mixed = out
        if (mixed != null) output.handleBuffer(mixed) else writeDelayed(lane, delayedBytes)
        handoff?.run()
    }

    // outputLock held. Writes up to [bytes] from the front of the lead's ring, straight from the
    // ring: consumed before the write, but only this lane's own playback thread (the caller)
    // appends to it, so the bytes stay put until the write returns.
    private fun writeDelayed(lane: Lane, bytes: Int) {
        var left = bytes
        while (left > 0) {
            val view = synchronized(stateLock) {
                if (lane !== lead) return
                val len = minOf(left, lane.size, laneCapacity - lane.readPos)
                if (len <= 0) return
                lane.ringView.clear()
                lane.ringView.position(lane.readPos)
                lane.ringView.limit(lane.readPos + len)
                lane.consume(len)
                left -= len
                lane.ringView
            }
            output.handleBuffer(view)
        }
    }

    // stateLock + outputLock held. The lead's samples come from [delay] (its own ring) when it has
    // one, else straight from [outgoing].
    private fun mixFade(outgoing: ByteBuffer, delay: Lane?, source: Lane): ByteBuffer {
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
                val leadSample = if (delay != null) delay.pollSample() else readSample(outgoing, index)
                dst.putShort(mix(leadSample, gOut, source.pollSample(), gIn))
                index += BYTES_PER_SAMPLE
            }
        }
        fadeDone += frames
        dst.flip()
        return dst
    }

    // stateLock + outputLock held. [delay] as in mixFade.
    private fun mixRamp(outgoing: ByteBuffer, delay: Lane?): ByteBuffer {
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
                val leadSample = if (delay != null) delay.pollSample() else readSample(outgoing, index)
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

        private const val INITIAL_SCRATCH_BYTES = 64 * 1024

        private fun readSample(buffer: ByteBuffer, index: Int): Int = (buffer.get(index + 1).toInt() shl 8) or (buffer.get(index).toInt() and 0xFF)

        private fun mix(a: Int, gainA: Double, b: Int, gainB: Double): Short = (a * gainA + b * gainB).roundToInt().coerceIn(-32768, 32767).toShort()
    }
}
