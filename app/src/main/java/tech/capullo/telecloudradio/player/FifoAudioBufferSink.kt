package tech.capullo.telecloudradio.player

import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Receives the decoded PCM from the TeeAudioProcessor (already forced to
 * 44100 Hz / 16-bit / stereo by the processor chain in PlaybackService)
 * and writes it into the snapserver FIFO.
 *
 * The FIFO is opened O_RDWR: that succeeds immediately without a reader, so
 * the write end can be held open before snapserver starts (snapserver's read
 * then sees EAGAIN, not EOF). A plain FileOutputStream (O_WRONLY) would block
 * until snapserver opens the read end → deadlock, since snapserver is only
 * started once playback begins.
 *
 * Writes stay disabled until playback actually starts: the FIFO holds only
 * ~370ms of PCM and snapserver (the reader) isn't running yet during preroll -
 * writing early fills the pipe and BLOCKS the playback thread before
 * STATE_READY can fire: deadlock, loading stuck forever.
 */
@UnstableApi
class FifoAudioBufferSink(private val fifoPath: String) : TeeAudioProcessor.AudioBufferSink {

    // Opened on the caller's thread (main), written on the playback thread
    @Volatile private var fd: FileDescriptor? = null

    @Volatile private var out: FileOutputStream? = null

    @Volatile private var writeEnabled = false

    // A released ExoPlayer flushes its sink asynchronously; without this flag
    // the dying player's flush() would reopen the closed FIFO and dribble
    // stale buffers into the next session's fresh pipe.
    @Volatile private var closed = false

    fun enableWrites() {
        writeEnabled = true
    }

    fun open() {
        if (closed || out != null) return
        try {
            val descriptor = Os.open(fifoPath, OsConstants.O_RDWR, 0)
            fd = descriptor
            out = FileOutputStream(descriptor)
            Log.d(TAG, "FIFO write end open (O_RDWR): $fifoPath")
        } catch (e: Exception) {
            Log.e(TAG, "FIFO open failed: ${e.message}")
        }
    }

    fun close() {
        closed = true
        writeEnabled = false
        try {
            out?.close()
        } catch (_: Exception) {}
        out = null
        fd = null // closed via the stream
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        // Called on (re)configure - the chain guarantees 44100/2ch/16-bit here.
        Log.d(TAG, "FIFO sink format: ${sampleRateHz}Hz ${channelCount}ch enc=$encoding")
        open()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!writeEnabled) return // preroll PCM is dropped, see writeEnabled
        val o = out ?: return
        try {
            val ch = o.channel
            while (buffer.hasRemaining()) ch.write(buffer)
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "FIFO write failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FifoAudioSink"
    }
}
