package tech.capullo.telecloudradio.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Taps the post-decode PCM (via its own [TeeAudioProcessor]) and records the last time *audible*
 * audio flowed through the sink, in [SystemClock.elapsedRealtime]. PlaybackService's watchdog polls
 * [lastAudibleElapsedMs] to decide whether a track that claims to be playing is actually producing
 * sound.
 *
 * Why a "last audible" timestamp rather than a self-contained silence counter: a track can be
 * inaudible in two different ways, and a robust check has to cover both -
 *  - it decodes to zero-valued PCM (silence buffers *do* arrive), or
 *  - it produces no PCM at all (e.g. a corrupt FLAC-in-MP4 that FfmpegAudioRenderer can't decode, so
 *    it drops every frame and never calls handleBuffer) - yet ExoPlayer's clock keeps advancing.
 * A counter living in this sink only sees the first case (it needs buffers to count). A timestamp the
 * service compares against wall-clock time catches both: if no audible buffer has updated it since the
 * track started, the elapsed gap grows regardless of whether silent buffers arrive or none do.
 *
 * Only near-zero PCM counts as silence ([SILENCE_AMPLITUDE]); genuine quiet passages carry
 * dither/noise well above it, so real audio updates the timestamp continuously. If the format is not
 * the 16-bit PCM the sink chain guarantees, any buffer is treated as audible (fail-safe: never skip a
 * track we can't measure).
 */
@OptIn(UnstableApi::class)
class SilenceWatchdogSink : TeeAudioProcessor.AudioBufferSink {

    @Volatile
    var lastAudibleElapsedMs: Long = 0L
        private set

    private var canMeasure = false
    private var bytesPerFrame = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        canMeasure = encoding == C.ENCODING_PCM_16BIT && channelCount > 0
        bytesPerFrame = channelCount * BYTES_PER_16BIT_SAMPLE
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        // Mark audio as flowing when the buffer carries any audible sample - or whenever we can't
        // measure the format, so an unexpected encoding never causes a false "silent" skip.
        if (!canMeasure || bytesPerFrame == 0 || !isSilent(buffer)) {
            lastAudibleElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    // Scans a read-only duplicate (leaves the caller's buffer untouched) for any sample above the
    // silence floor. Bails on the first audible sample, so real audio costs ~one read.
    private fun isSilent(buffer: ByteBuffer): Boolean {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (b.remaining() >= BYTES_PER_16BIT_SAMPLE) {
            val sample = b.short.toInt()
            if (sample > SILENCE_AMPLITUDE || sample < -SILENCE_AMPLITUDE) return false
        }
        return true
    }

    private companion object {
        const val BYTES_PER_16BIT_SAMPLE = 2

        // |sample| at or under this (of a 16-bit full scale of 32768) counts as digital silence.
        const val SILENCE_AMPLITUDE = 16
    }
}
