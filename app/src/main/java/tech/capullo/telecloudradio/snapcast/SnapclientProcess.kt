package tech.capullo.telecloudradio.snapcast

import android.content.Context
import android.media.AudioManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Runs `libsnapclient.so` as a child process — the audible endpoint. Connects to
 * a snapserver (localhost when broadcasting, remote host when listening in) and
 * plays time-synced audio through oboe/opensl.
 */
class SnapclientProcess(private val context: Context) {

    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    private val androidPlayer = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) "opensl" else "oboe"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val rate: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
    private val fpb: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
    private val sampleFormat = "$rate:16:*"

    enum class ConnectionState { STARTING, CONNECTED, ERROR }

    private val _connectionState = MutableStateFlow(ConnectionState.STARTING)
    val connectionState = _connectionState.asStateFlow()

    val storedHostId: String
        get() = localHostId(context)

    private var currentProcess: Process? = null

    fun destroy() {
        currentProcess?.destroyForcibly()
        currentProcess = null
    }

    /** Switch the live audio channel (stereo / left / right) without restarting
     *  the process — the snapclient listens on an abstract socket for this. */
    fun setChannel(channel: String) {
        try {
            val sock = LocalSocket()
            sock.connect(LocalSocketAddress("snapclient_channel", LocalSocketAddress.Namespace.ABSTRACT))
            sock.outputStream.write("$channel\n".toByteArray())
            sock.outputStream.flush()
            sock.close()
            Log.d(TAG, "Channel → $channel (via socket)")
        } catch (e: Exception) {
            Log.w(TAG, "setChannel failed: ${e.message}")
        }
    }

    private fun loadHostId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREFS_KEY, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit { putString(PREFS_KEY, id) }
            Log.d(TAG, "Generated new hostId: $id")
            id
        }
    }

    suspend fun start(
        snapserverAddress: String = "localhost",
        snapserverPort: Int = SnapcastPorts.STREAM,
        audioChannel: String = "stereo",
    ) = coroutineScope {
        val hostId = loadHostId()
        val pb = ProcessBuilder().command(
            "$nativeLibDir/libsnapclient.so",
            "--hostID", hostId,
            "--player", androidPlayer,
            "--sampleformat", sampleFormat,
            "--logfilter", "*:info",
            "--channel", audioChannel,
            "tcp://$snapserverAddress:$snapserverPort",
        )

        val env = pb.environment()
        if (rate != null) env["SAMPLE_RATE"] = rate
        if (fpb != null) env["FRAMES_PER_BUFFER"] = fpb

        val process = pb.start().also { currentProcess = it }
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                ensureActive()
                line?.let {
                    when {
                        it.contains("[Error] (Connection)") -> _connectionState.update { ConnectionState.ERROR }
                        it.contains("[Notice] (Connection) Connected to") -> _connectionState.update { ConnectionState.CONNECTED }
                    }
                    Log.d(TAG, it)
                }
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "Snapclient cancelled")
            process.destroy()
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Snapclient error", e)
        }
    }

    companion object {
        private val TAG = SnapclientProcess::class.java.simpleName
        private const val PREFS_NAME = "SNAPCAST_CLIENT_HOST_ID"
        private const val PREFS_KEY = "SNAPCAST_CLIENT_HOST_ID_PREFERENCE"

        /** The persistent --hostID this device's snapclient registers with —
         *  equals its client id on any snapserver (used to identify self in
         *  client lists). Empty until the first client run. */
        fun localHostId(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY, null) ?: ""
    }
}
