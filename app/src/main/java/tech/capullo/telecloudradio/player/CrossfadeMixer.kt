package tech.capullo.telecloudradio.player

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Indirection point for a player's FIFO/watchdog tee: each ExoPlayer instance's sink chain is
 * fixed at construction (Media3 gives no way to swap an AudioProcessor's destination after
 * build()), but a crossfade needs to repoint "where does this player's audio actually go" at
 * runtime - from a preroll capture (while it's the INCOMING track, decoding ahead of the fade)
 * to the real broadcast FIFO (once it's promoted to primary at handoff). Every player is built
 * with a [RoutableSink] as its fixed tee target; PlaybackService only ever swaps [delegate].
 */
@UnstableApi
class RoutableSink : TeeAudioProcessor.AudioBufferSink {
    @Volatile var delegate: TeeAudioProcessor.AudioBufferSink? = null

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        delegate?.flush(sampleRateHz, channelCount, encoding)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        delegate?.handleBuffer(buffer)
    }
}

/**
 * Captures the post-balance, pre-FIFO PCM of the INCOMING player during a crossfade, instead of
 * that player writing straight to a live AudioTrack's tee/FIFO. [CrossfadeMixer] drains this via
 * [CrossfadeMixer.beginFade]; while a player is still prerolling (not yet fading), its
 * [RoutableSink] delegate points here and nowhere else touches the real broadcast.
 *
 * Bounded + drop-oldest: this is a live-broadcast tap, not a lossless buffer. If the mixer falls
 * behind, dropping the oldest unmixed chunk keeps the fade in sync with real time rather than
 * growing unbounded latency - the alternative (block the incoming player's own pacing thread)
 * would risk stalling ITS AudioTrack, which the broader architecture never allows (see
 * FifoAudioSink.kt's "writes block when the FIFO fills" note for why pacing stalls are treated as
 * a hard failure mode here, not a soft one).
 */
@UnstableApi
class CapturingAudioSink : TeeAudioProcessor.AudioBufferSink {
    val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        queue.clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val bytes = ByteArray(buffer.remaining())
        buffer.duplicate().get(bytes)
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 16
    }
}

/**
 * Mixes the outgoing and incoming PCM streams across a crossfade window and forwards the result
 * to the broadcast FIFO sink; outside a fade, outgoing PCM passes straight through untouched -
 * this is the ONLY cost paid during normal (non-crossfading) playback, matching the previous
 * single-tee path exactly. Implements [TeeAudioProcessor.AudioBufferSink] directly so the current
 * primary player's [RoutableSink] can point straight at an instance of this class.
 *
 * Driven by the OUTGOING (primary) player's tap: every outgoing PCM chunk is the pacing heartbeat
 * (it arrives already real-time-paced by that player's own AudioTrack, exactly like the
 * single-player FIFO tee before crossfade existed - see PlaybackService's sink-chain comment), so
 * mixing never runs faster or slower than real playback. The incoming player's chunks are pulled
 * from its own [CapturingAudioSink] queue, which PlaybackService starts prerolling shortly before
 * the fade window so they are normally already available when needed.
 *
 * All PCM here is guaranteed 48000 Hz / 16-bit / stereo - LOCKSTEP with SnapserverProcess and the
 * Sonic resampler every player runs (see PlaybackService), so no per-chunk resampling is needed,
 * only a sample-by-sample sum.
 */
@UnstableApi
class CrossfadeMixer(
    private val output: TeeAudioProcessor.AudioBufferSink,
) : TeeAudioProcessor.AudioBufferSink {
    @Volatile private var fadeDurationMs: Long = 0L
    @Volatile private var fadeStartElapsedMs: Long = 0L
    @Volatile private var fading = false
    private var incomingQueue: ArrayBlockingQueue<ByteArray>? = null
    private var incomingCarry: ByteArray? = null
    private var incomingCarryOffset = 0

    /** True while a fade is in progress. */
    val isFading: Boolean get() = fading

    /** Starts mixing: [incoming]'s queue is pulled to fade in while the outgoing stream fades out. */
    fun beginFade(incoming: CapturingAudioSink, durationMs: Long, nowElapsedMs: Long) {
        incomingQueue = incoming.queue
        incomingCarry = null
        incomingCarryOffset = 0
        fadeDurationMs = durationMs
        fadeStartElapsedMs = nowElapsedMs
        fading = true
    }

    /** Stops mixing; subsequent outgoing buffers pass straight through unmixed. */
    fun endFade() {
        fading = false
        incomingQueue = null
        incomingCarry = null
        incomingCarryOffset = 0
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        output.flush(sampleRateHz, channelCount, encoding)
    }

    /** The current primary's [RoutableSink] delegate - called from its own tee tap on every PCM buffer. */
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!fading) {
            output.handleBuffer(buffer)
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - fadeStartElapsedMs
        if (elapsed >= fadeDurationMs) {
            // Window elapsed but endFade() hasn't landed yet (race with the handoff running on a
            // different thread) - pass the tail through unmixed rather than drop live audio; the
            // handoff caller is responsible for calling endFade() promptly once it takes over.
            output.handleBuffer(buffer)
            return
        }
        output.handleBuffer(mix(buffer, elapsed))
    }

    private fun mix(outBuf: ByteBuffer, elapsedMs: Long): ByteBuffer {
        val needed = outBuf.remaining()
        val inBytes = fillIncoming(needed)
        val progress = (elapsedMs.toDouble() / fadeDurationMs).coerceIn(0.0, 1.0)
        // Equal-power: gOut^2 + gIn^2 = 1 at every point, so perceived loudness stays constant
        // through the fade instead of dipping the way a plain linear crossfade would.
        val gOut = cos(progress * PI / 2.0)
        val gIn = sin(progress * PI / 2.0)
        val out = outBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val mixed = ByteBuffer.allocate(needed).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (out.remaining() >= 2) {
            val so = out.short.toInt()
            val si = if (i + 1 < inBytes.size) {
                (inBytes[i].toInt() and 0xFF) or (inBytes[i + 1].toInt() shl 8)
            } else {
                0
            }.toShort().toInt()
            i += 2
            val mixedSample = (so * gOut + si * gIn).toInt().coerceIn(-32768, 32767)
            mixed.putShort(mixedSample.toShort())
        }
        mixed.flip()
        return mixed
    }

    // Pulls exactly [needed] bytes from the incoming queue, zero-padding (silence) if it runs dry
    // - a brief underrun here fades in slightly quieter for an instant rather than blocking the
    // outgoing player's pacing thread, which must never stall.
    private fun fillIncoming(needed: Int): ByteArray {
        val out = ByteArray(needed)
        var filled = 0
        incomingCarry?.let { carry ->
            val n = min(carry.size - incomingCarryOffset, needed)
            System.arraycopy(carry, incomingCarryOffset, out, 0, n)
            filled = n
            incomingCarryOffset += n
            if (incomingCarryOffset >= carry.size) {
                incomingCarry = null
                incomingCarryOffset = 0
            }
        }
        val q = incomingQueue ?: return out
        while (filled < needed) {
            val chunk = q.poll(0, TimeUnit.MILLISECONDS) ?: break
            val n = min(chunk.size, needed - filled)
            System.arraycopy(chunk, 0, out, filled, n)
            filled += n
            if (n < chunk.size) {
                incomingCarry = chunk
                incomingCarryOffset = n
            }
        }
        return out
    }
}
