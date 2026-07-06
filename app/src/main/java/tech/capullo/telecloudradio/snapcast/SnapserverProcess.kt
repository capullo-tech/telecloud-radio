package tech.capullo.telecloudradio.snapcast

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Runs `libsnapserver.so` as a child process: reads 44100:16:2 PCM from a named
 * FIFO (fed by the ExoPlayer tee sink) and broadcasts time-synced audio on the
 * LAN (stream port [SnapcastPorts.STREAM]) + serves the bundled web player on
 * the HTTP/WebSocket port [SnapcastPorts.HTTP].
 */
class SnapserverProcess(private val context: Context) {

    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir
    private val cacheDir: File = context.cacheDir
    private val confFile: String = getSnapserverConfPath()
    val pipeFilepath: String = createFifo()

    companion object {
        private const val PIPE_NAME = "telecloud_fifo"
        private const val STREAM_NAME = "name=TelecloudRadio"
        private const val CODEC = "codec=pcm"
        private const val PIPE_MODE = "mode=read"
        private const val DRYOUT_MS = "dryout_ms=10000"
        private const val SAMPLE_FORMAT = "sampleformat=44100:16:2"
        private val TAG = SnapserverProcess::class.java.simpleName
    }

    init {
        copyWebUiAsset()
    }

    private fun copyWebUiAsset() {
        try {
            val webuiDir = File(context.filesDir, "webui").apply { mkdirs() }
            context.assets.open("webui/index.html").use { input ->
                File(webuiDir, "index.html").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy WebUI: ${e.message}")
        }
    }

    private val pipeArgs = listOf(
        STREAM_NAME,
        CODEC,
        PIPE_MODE,
        DRYOUT_MS,
        SAMPLE_FORMAT,
        "controlscript=$nativeLibDir/libsnapcontrol.so",
    ).joinToString("&")

    private fun createFifo(): String {
        val pipeFile = File(cacheDir, PIPE_NAME)
        // Must be a named pipe (FIFO). With a regular file, Snapserver crashes
        // when it hits EOF trying to encode the first chunk. With a FIFO:
        //   - the tee sink opens O_RDWR → no blocking, holds the write end
        //   - Snapserver opens O_RDONLY|O_NONBLOCK → read() blocks/EAGAIN, not EOF
        // An existing FIFO is reused, NOT recreated: the tee sink may already
        // hold its write end open (broadcast stack restart after listen-in) and
        // delete+mkfifo would leave that fd pointing at an orphaned inode.
        val isFifo = try {
            pipeFile.exists() && OsConstants.S_ISFIFO(Os.stat(pipeFile.absolutePath).st_mode)
        } catch (_: Exception) {
            false
        }
        if (isFifo) {
            Log.d(TAG, "FIFO reused: ${pipeFile.absolutePath}")
            return pipeFile.absolutePath
        }
        if (pipeFile.exists()) pipeFile.delete()
        try {
            Os.mkfifo(
                pipeFile.absolutePath,
                OsConstants.S_IRUSR or OsConstants.S_IWUSR or
                    OsConstants.S_IRGRP or OsConstants.S_IWGRP,
            )
        } catch (e: Exception) {
            Log.e(TAG, "mkfifo failed: ${e.message}")
        }
        Log.d(TAG, "FIFO created: ${pipeFile.absolutePath}")
        return pipeFile.absolutePath
    }

    private fun getSnapserverConfPath(): String {
        val confFile = File(cacheDir, "snapserver.conf")
        val webUiPath = File(context.filesDir, "webui").absolutePath
        try {
            // Always rewrite so doc_root stays current.
            confFile.writeText("[http]\ndoc_root = $webUiPath\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write snapserver.conf: ${e.message}")
        }
        // Snapserver skips chunk delivery to clients persisted with muted=true in
        // server.json, so audio dies on reconnect. Clear every muted flag (client and
        // group) but keep the rest - notably per-client latency calibration and volumes.
        clearMutedFlags(File(cacheDir, "server.json"))
        return confFile.absolutePath
    }

    private fun clearMutedFlags(file: File) {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            clearMuted(root)
            file.writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear muted flags, deleting server.json: ${e.message}")
            file.delete()
        }
    }

    private fun clearMuted(node: Any?) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                for (key in keys) {
                    if (key == "muted") {
                        node.put("muted", false)
                    } else {
                        clearMuted(node.opt(key))
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) clearMuted(node.opt(i))
        }
    }

    suspend fun start() = coroutineScope {
        val pb = ProcessBuilder()
            .command(
                "$nativeLibDir/libsnapserver.so",
                "--config", confFile,
                "--server.datadir=$cacheDir",
                "--stream.source", "pipe://$pipeFilepath?$pipeArgs",
                "--stream.port=${SnapcastPorts.STREAM}",
                "--tcp.port=${SnapcastPorts.TCP}",
                "--http.port=${SnapcastPorts.HTTP}",
                "--http.doc_root=${File(context.filesDir, "webui").absolutePath}",
                "--server.name=${android.os.Build.MODEL}",
            )
            .redirectErrorStream(true)

        val process = pb.start()
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                ensureActive()
                Log.d(TAG, line!!)
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "Snapserver cancelled")
            process.destroy()
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Snapserver error", e)
        }
    }
}
