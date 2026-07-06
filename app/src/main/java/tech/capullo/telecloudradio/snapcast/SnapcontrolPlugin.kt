package tech.capullo.telecloudradio.snapcast

/**
 * Implements the Snapcast stream-plugin JSON-RPC 2.0 protocol.
 *
 * Snapserver spawns libsnapcontrol.so as a `controlscript`; that binary is a
 * stdio <-> Unix-abstract-socket proxy and connects to the abstract socket
 * bound here. This class accepts connections, sends Plugin.Stream.Ready,
 * answers Plugin.Stream.Player.GetProperties, and pushes
 * Plugin.Stream.Player.Properties notifications when metadata changes.
 *
 * This is how web players (and remote snapclients) see the currently playing
 * track and send play/pause/next/previous back to the app.
 */

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

private const val TAG = "SnapcontrolPlugin"
private const val SOCKET_NAME = "snapcontrol"
private const val NOTIFY_READY = """{"jsonrpc":"2.0","method":"Plugin.Stream.Ready"}"""

interface SnapcontrolCallbacks {
    val isPlaying: Boolean
    val isPaused: Boolean

    /** True when a playlist is active so next/previous are meaningful. */
    val canSkip: Boolean
    val currentTitle: String
    val currentArtist: String

    /** Station name (the Telegram chat title). */
    val currentStation: String

    /** Embedded/fetched album art bytes for the current track, or null. */
    val currentArtworkBytes: ByteArray?

    fun onPlay()
    fun onPause()
    fun onSkipNext()
    fun onSkipPrev()
}

class SnapcontrolPlugin(
    private val callbacks: SnapcontrolCallbacks,
    parentJob: Job,
) {
    private val pluginJob = SupervisorJob(parentJob)
    private val scope = CoroutineScope(Dispatchers.IO + pluginJob)

    private var listener: LocalServerSocket? = null

    @Volatile private var currentSession: SnapcontrolSession? = null

    // When locked, the broadcaster's stream ignores remote/web transport commands
    // and advertises canPlay/canPause/canGoNext/canGoPrevious = false (web + remote
    // now-playing then hide their transport). Toggled from SnapcastManager.
    @Volatile var isStreamLocked: Boolean = false
        set(value) {
            field = value
            notifyPropertiesChanged()
        }

    fun start() {
        if (listener != null) return
        listener = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind abstract:$SOCKET_NAME — is another instance running?", e)
            return
        }
        Log.d(TAG, "Listening on abstract:$SOCKET_NAME")
        scope.launch { acceptLoop() }
    }

    fun stop() {
        val srv = listener
        listener = null
        try {
            srv?.close()
        } catch (_: IOException) {}
        currentSession?.close()
        currentSession = null
        pluginJob.cancel()
    }

    fun notifyPropertiesChanged() {
        currentSession?.notifyProperties()
    }

    private suspend fun acceptLoop() {
        val srv = listener ?: return
        while (scope.isActive) {
            val sock = try {
                srv.accept()
            } catch (e: IOException) {
                Log.d(TAG, "Accept loop ending: ${e.message}")
                return
            }
            Log.d(TAG, "New session")
            val session = SnapcontrolSession(sock, callbacks, scope) { isStreamLocked }
            currentSession = session
            try {
                session.run()
            } finally {
                currentSession = null
                Log.d(TAG, "Session ended")
            }
        }
    }
}

private class SnapcontrolSession(
    private val socket: LocalSocket,
    private val callbacks: SnapcontrolCallbacks,
    parentScope: CoroutineScope,
    private val getLocked: () -> Boolean,
) {
    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)

    // Serializes artwork refresh + send so a stale encode can't overwrite a
    // newer "art cleared" notification — snapserver stores the last properties,
    // so web clients would keep showing the old track's art indefinitely.
    private val propsMutex = Mutex()

    @Volatile private var cachedArtBytes: ByteArray? = null
    @Volatile private var cachedArtData: JSONObject? = null

    suspend fun run() {
        outbox.trySend(NOTIFY_READY)
        val writerJob = scope.launch(Dispatchers.IO) { writerLoop() }
        try {
            withContext(Dispatchers.IO) { readerLoop() }
        } finally {
            outbox.close()
            writerJob.cancel()
            try {
                socket.close()
            } catch (_: IOException) {}
            sessionJob.cancel()
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: IOException) {}
        outbox.close()
        sessionJob.cancel()
    }

    fun notifyProperties() {
        scope.launch {
            propsMutex.withLock {
                refreshArtwork()
                val notif = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "Plugin.Stream.Player.Properties")
                    .put("params", buildProperties())
                outbox.trySend(notif.toString())
            }
        }
    }

    private suspend fun refreshArtwork() {
        val bytes = callbacks.currentArtworkBytes
        if (bytes === cachedArtBytes) return
        cachedArtBytes = bytes
        cachedArtData = if (bytes == null || bytes.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                // PNG magic: 0x89 'P' 'N' 'G'; everything else embedded in audio
                // tags is effectively JPEG
                val ext = if (bytes.size > 4 &&
                    bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
                ) "png" else "jpg"
                JSONObject()
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("extension", ext)
            }
        }
    }

    private fun buildProperties(): JSONObject {
        val status = when {
            callbacks.isPlaying -> "playing"
            callbacks.isPaused -> "paused"
            else -> "stopped"
        }
        val locked = getLocked()
        val obj = JSONObject()
            .put("playbackStatus", status)
            .put("loopStatus", "none")
            .put("shuffle", false)
            .put("volume", 100)
            .put("mute", false)
            .put("canPlay", !locked)
            .put("canPause", !locked)
            .put("canSeek", false)
            .put("canGoNext", !locked && callbacks.canSkip)
            .put("canGoPrevious", !locked && callbacks.canSkip)
            .put("canControl", true)

        val title = callbacks.currentTitle
        val artist = callbacks.currentArtist
        val station = callbacks.currentStation
        if (title.isNotEmpty() || artist.isNotEmpty() || station.isNotEmpty()) {
            val meta = JSONObject()
            if (title.isNotEmpty()) meta.put("title", title)
            if (artist.isNotEmpty()) meta.put("artist", JSONArray().put(artist))
            if (station.isNotEmpty()) meta.put("station", station)
            cachedArtData?.let { meta.put("artData", it) }
            obj.put("metadata", meta)
        }
        return obj
    }

    private suspend fun readerLoop() {
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        while (scope.isActive) {
            val line = try {
                reader.readLine() ?: return
            } catch (e: IOException) {
                Log.d(TAG, "Reader end: ${e.message}")
                return
            }
            if (line.isBlank()) continue
            handleLine(line)
        }
    }

    private suspend fun writerLoop() {
        val out = socket.outputStream
        try {
            for (line in outbox) {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Writer end: ${e.message}")
        }
    }

    private suspend fun handleLine(line: String) {
        val req = try {
            JSONObject(line)
        } catch (e: JSONException) {
            Log.w(TAG, "JSON parse error: ${e.message}")
            return
        }
        val id: Any? = if (req.has("id") && !req.isNull("id")) req.get("id") else null
        val method = req.optString("method", "")
        if (method.isEmpty()) return

        try {
            val result: Any = when (method) {
                "Plugin.Stream.Player.GetProperties" -> propsMutex.withLock {
                    refreshArtwork()
                    buildProperties()
                }
                "Plugin.Stream.Player.Control" -> {
                    val command = req.optJSONObject("params")?.optString("command") ?: ""
                    Log.d(TAG, "Control command: $command isPlaying=${callbacks.isPlaying} locked=${getLocked()}")
                    if (getLocked()) {
                        Log.d(TAG, "Stream locked — ignoring control command: $command")
                    } else {
                        when (command) {
                            "play" -> callbacks.onPlay()
                            "pause" -> callbacks.onPause()
                            "playPause" -> if (callbacks.isPlaying) callbacks.onPause() else callbacks.onPlay()
                            "stop" -> callbacks.onPause()
                            "next" -> callbacks.onSkipNext()
                            "previous" -> callbacks.onSkipPrev()
                            else -> Log.d(TAG, "Unhandled control command: $command")
                        }
                    }
                    "ok"
                }
                "Plugin.Stream.Player.SetProperty" -> "ok"
                else -> {
                    if (id != null) sendError(id, -32601, "Method not found: $method")
                    return
                }
            }
            if (id != null) sendResult(id, result)
        } catch (e: Throwable) {
            Log.e(TAG, "Dispatch error", e)
            if (id != null) sendError(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun sendResult(id: Any, result: Any) {
        outbox.trySend(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }

    private fun sendError(id: Any, code: Int, message: String) {
        outbox.trySend(
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message)).toString(),
        )
    }
}
