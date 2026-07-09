package tech.capullo.telecloudradio.snapcast

import android.content.Context
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.AudioFocusController
import tech.capullo.audio.snapcast.ClientOnConnect
import tech.capullo.audio.snapcast.ClientOnDisconnect
import tech.capullo.audio.snapcast.ClientOnLatencyChanged
import tech.capullo.audio.snapcast.ClientOnNameChanged
import tech.capullo.audio.snapcast.ClientOnVolumeChanged
import tech.capullo.audio.snapcast.Group
import tech.capullo.audio.snapcast.ServerGetStatusResponse
import tech.capullo.audio.snapcast.ServerOnUpdate
import tech.capullo.audio.snapcast.ServerStatusResult
import tech.capullo.audio.snapcast.SnapcastControlClient
import tech.capullo.audio.snapcast.SnapclientProcess
import tech.capullo.audio.snapcast.SnapcontrolPlugin
import tech.capullo.audio.snapcast.SnapserverDiscoveryManager
import tech.capullo.audio.snapcast.SnapserverNsdRegistrar
import tech.capullo.audio.snapcast.SnapserverPorts
import tech.capullo.audio.snapcast.SnapserverProcess
import tech.capullo.audio.snapcast.StreamOnProperties
import tech.capullo.audio.snapcast.StreamPlayerProperties
import tech.capullo.audio.snapcast.Volume
import tech.capullo.telecloudradio.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the Snapcast stack.
 *
 * Broadcast (this device is the source, always on while playing):
 *   ExoPlayer tee → FIFO → snapserver → { local snapclient, LAN clients, web players }
 *
 * Listen-in (this device joins another snapserver):
 *   remote snapserver → local snapclient; metadata + transport via JSON-RPC.
 *
 * PlaybackService drives the broadcast side (it owns the ExoPlayer and the
 * foreground lifecycle); SnapcastViewModel drives listen-in and the client
 * control UI. Both observe [state].
 */
@Singleton
class SnapcastManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    data class SnapcastState(
        /** Broadcast stack running (snapserver + local snapclient live). */
        val isBroadcasting: Boolean = false,
        /** Connected snapcast clients (broadcast or listen-in, whichever is active). */
        val groups: List<Group> = emptyList(),
        /** Remote host when in listen-in mode; empty otherwise. */
        val listenHost: String = "",
        val listenPort: Int = SnapcastPorts.STREAM,
        val listenState: SnapclientProcess.ConnectionState = SnapclientProcess.ConnectionState.STARTING,
        /** Now-playing pushed by the remote stream while listening in. */
        val remoteProps: StreamPlayerProperties? = null,
        /** Decoded artData of the remote track. */
        val remoteArt: ByteArray? = null,
        /** Stream id of the active group (needed for Stream.Control). */
        val streamId: String = "",
        /** Audio channel of THIS device's snapclient: stereo / left / right. */
        val snapclientChannel: String = "stereo",
        /** Broadcaster stream-control lock (blocks web/remote transport). */
        val isStreamLocked: Boolean = false,
        /** This broadcaster's resolved HTTP (web player + control) port - for the listen-in QR URL. */
        val broadcastHttpPort: Int = SnapcastPorts.HTTP,
    ) {
        val isListening: Boolean get() = listenHost.isNotEmpty()
    }

    private val _state = MutableStateFlow(SnapcastState())
    val state: StateFlow<SnapcastState> = _state.asStateFlow()

    val discovery by lazy { SnapserverDiscoveryManager(context) }

    /** This device's persistent snapclient id - marks "this device" in client lists. */
    val localClientId: String get() = SnapclientProcess.localHostId(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Own snapclient vol/latency restore + persist (spatial-role memory). savedVol/savedLat are
    // refreshed from settings at each (re)connect; volLatRestored gates persistence until the server
    // reflects the restored values, so the connect-time default can't be persisted over saved.
    private var savedVol = 100
    private var savedLat = 0
    private var volLatRestored = false
    private var volLatApplied = false
    private var lastPersistedVol = -1
    private var lastPersistedLat = Int.MIN_VALUE

    init {
        // Persist own client's volume/latency on ANY change (slider, knob, remote controller).
        scope.launch {
            _state.collect { s ->
                if (!volLatRestored) return@collect
                val localId = localClientId.takeIf { it.isNotEmpty() } ?: return@collect
                val own = s.groups.flatMap { it.clients }
                    .find { it.id == localId || it.id.contains(localId) } ?: return@collect
                if (own.config.volume.percent != lastPersistedVol || own.config.latency != lastPersistedLat) {
                    lastPersistedVol = own.config.volume.percent
                    lastPersistedLat = own.config.latency
                    settingsRepository.snapclientVolume = lastPersistedVol
                    settingsRepository.snapclientLatency = lastPersistedLat
                }
            }
        }
    }

    // --- Broadcast side ---

    private var snapserverProcess: SnapserverProcess? = null
    private var snapcontrolPlugin: SnapcontrolPlugin? = null
    private var nsdRegistrar: SnapserverNsdRegistrar? = null
    private var snapserverJob: Job? = null

    // --- Shared (broadcast local playback / listen-in) ---

    private var snapclientProcess: SnapclientProcess? = null
    private var snapclientJob: Job? = null
    private var snapclientStateJob: Job? = null
    private var controlClient: SnapcastControlClient? = null
    private var controlJob: Job? = null
    private var localChannelTagSet = false

    // Audio focus governs THIS device's audible snapclient (not the broadcast): the snapserver keeps
    // streaming to web/LAN clients regardless, but the local snapclient is paused when another app
    // takes focus and restarted when focus returns - so two capullo apps don't mix their speaker.
    private var currentSnapclientHost: String? = null
    private var currentSnapclientPort: Int = 0
    private var audioFocus: AudioFocusController? = null

    /**
     * (Re)creates the snapserver process + FIFO. Called by PlaybackService in
     * onCreate BEFORE the tee sink opens the pipe, so the write end always
     * refers to the freshly created FIFO inode.
     */
    fun prepareBroadcast(): String {
        stopBroadcast()
        // OS-assigned ports so multiple capullo apps coexist and the ports aren't a fixed guess.
        // The resolved trio is read back off snapserver.ports to wire the snapclient / NSD / control.
        return SnapserverProcess(context, STREAM_NAME, SnapserverPorts.free())
            .also { snapserverProcess = it }.pipeFilepath
    }

    /**
     * Starts snapserver + local snapclient + metadata plugin + NSD + control
     * socket. Idempotent - called on every "playing" transition.
     */
    fun startBroadcast(nowPlaying: StateFlow<NowPlaying>, controller: PlaybackController) {
        if (_state.value.isBroadcasting) return
        if (_state.value.isListening) return // listen-in owns the snapclient/ports
        // Lazily recreated after a listen-in session tore the stack down; the
        // FIFO file is reused so the tee sink's open write end stays valid.
        val snapserver = snapserverProcess
            ?: SnapserverProcess(context, STREAM_NAME, SnapserverPorts.free())
                .also { snapserverProcess = it }
        val ports = snapserver.ports
        Log.d(TAG, "Starting broadcast stack")
        // Plugin must be listening before snapserver spawns libsnapcontrol.so. The engine's
        // SnapcontrolPlugin is contract-driven: a StateFlow<NowPlaying> (read) + a PlaybackController
        // (transport), replacing Telecloud's former fat SnapcontrolCallbacks.
        snapcontrolPlugin = SnapcontrolPlugin(nowPlaying, controller, scope.coroutineContext[Job]!!)
            .also {
                it.isStreamLocked = _state.value.isStreamLocked
                it.start()
            }
        snapserverJob = scope.launch { snapserver.start() }
        startLocalSnapclient("localhost", ports.streamPort)
        nsdRegistrar = SnapserverNsdRegistrar(context)
            .also { it.start("", ports.streamPort, ports.tcpPort, ports.httpPort) }
        startControl("localhost", ports.httpPort)
        _state.update { it.copy(isBroadcasting = true, broadcastHttpPort = ports.httpPort) }
    }

    fun notifyPropertiesChanged() {
        snapcontrolPlugin?.notifyPropertiesChanged()
    }

    // Written next to the served index.html (snapserver doc_root); the web
    // player fetches webcfg.json on load. Missing file → web defaults apply
    // (debug hidden, no autoplay). Toggles take effect on the page's next reload.
    fun updateWebConfig(debug: Boolean, autoplay: Boolean) {
        scope.launch {
            try {
                val dir = java.io.File(context.filesDir, "webui").apply { mkdirs() }
                java.io.File(dir, "webcfg.json").writeText("""{"debug":$debug,"autoplay":$autoplay}""")
            } catch (e: Exception) {
                Log.w(TAG, "webcfg.json write failed: ${e.message}")
            }
        }
    }

    fun stopBroadcast() {
        if (snapserverProcess == null && snapcontrolPlugin == null) return
        Log.d(TAG, "Stopping broadcast stack")
        nsdRegistrar?.stop()
        nsdRegistrar = null
        snapcontrolPlugin?.stop()
        snapcontrolPlugin = null
        stopControl()
        stopLocalSnapclient()
        snapserverJob?.cancel()
        snapserverJob = null
        snapserverProcess = null
        localChannelTagSet = false
        _state.update {
            it.copy(isBroadcasting = false, groups = emptyList(), streamId = "", isStreamLocked = false)
        }
    }

    // --- Listen-in side ---

    fun connectListen(
        host: String,
        port: Int = SnapcastPorts.STREAM,
        httpPort: Int = SnapcastPorts.HTTP,
    ) {
        Log.d(TAG, "Listen-in → $host:$port (control :$httpPort)")
        stopBroadcast()
        stopLocalSnapclient()
        stopControl()
        _state.update {
            it.copy(
                listenHost = host,
                listenPort = port,
                listenState = SnapclientProcess.ConnectionState.STARTING,
                remoteProps = null,
                remoteArt = null,
                groups = emptyList(),
                streamId = "",
            )
        }
        startLocalSnapclient(host, port)
        startControl(host, httpPort)
    }

    fun disconnectListen() {
        if (!_state.value.isListening) return
        Log.d(TAG, "Listen-in disconnect")
        stopLocalSnapclient()
        stopControl()
        _state.update {
            it.copy(
                listenHost = "",
                remoteProps = null,
                remoteArt = null,
                groups = emptyList(),
                streamId = "",
            )
        }
    }

    // --- Client control (shared) ---

    fun adjustClientVolume(clientId: String, percent: Int, muted: Boolean) {
        // Snapcast sends a Response (not a notification) back to the sender of
        // Client.SetVolume. Apply optimistically so the local UI updates
        // immediately, same as other clients do via ClientOnVolumeChanged.
        _state.update { st ->
            st.copy(
                groups = st.groups.map { group ->
                    group.copy(
                        clients = group.clients.map { client ->
                            if (client.id == clientId) {
                                client.copy(config = client.config.copy(volume = Volume(muted, percent)))
                            } else {
                                client
                            }
                        },
                    )
                },
            )
        }
        scope.launch { controlClient?.sendSetVolume(clientId, muted, percent) }
    }

    fun adjustClientLatency(clientId: String, latencyMs: Int) {
        scope.launch { controlClient?.sendSetLatency(clientId, latencyMs) }
    }

    // Reset controls (broadcaster only). "Reset" = stereo / 100% / 0ms latency.
    private fun resetClientToDefaults(clientId: String) {
        changeClientChannel(clientId, "stereo")
        adjustClientVolume(clientId, 100, false)
        adjustClientLatency(clientId, 0)
    }

    // Reset forgets this device's saved spatial role so it sticks next launch. Only THIS device's
    // persistence is cleared - remote devices restore their own saved config on next reconnect.
    private fun clearOwnPersistence() {
        savedVol = 100
        savedLat = 0
        lastPersistedVol = 100
        lastPersistedLat = 0
        settingsRepository.snapclientChannel = "stereo"
        settingsRepository.snapclientVolume = 100
        settingsRepository.snapclientLatency = 0
        _state.update { it.copy(snapclientChannel = "stereo") }
    }

    fun resetSelf() {
        clearOwnPersistence()
        val localId = localClientId.takeIf { it.isNotEmpty() } ?: return
        resetClientToDefaults(localId)
    }

    fun resetAll() {
        clearOwnPersistence()
        _state.value.groups.flatMap { it.clients }.filter { it.connected }.forEach {
            resetClientToDefaults(it.id)
        }
    }

    /** Change this device's own audio channel (stereo/left/right). */
    fun setLocalChannel(channel: String) {
        _state.update { it.copy(snapclientChannel = channel) }
        snapclientProcess?.setChannel(channel)
        settingsRepository.snapclientChannel = channel
        val localId = localClientId.takeIf { it.isNotEmpty() } ?: return
        renameClientWithChannel(localId, channel)
    }

    /** Change any client's channel by re-tagging its name; if it's us, also
     *  switch the live audio channel. */
    fun changeClientChannel(clientId: String, channel: String) {
        renameClientWithChannel(clientId, channel)
        if (clientId == localClientId || clientId.contains(localClientId) || localClientId.contains(clientId)) {
            _state.update { it.copy(snapclientChannel = channel) }
            snapclientProcess?.setChannel(channel)
            settingsRepository.snapclientChannel = channel
        }
    }

    private fun renameClientWithChannel(clientId: String, channel: String) {
        val tag = when (channel) {
            "left" -> "[L]"
            "right" -> "[R]"
            else -> "[S]"
        }
        val client = _state.value.groups.flatMap { it.clients }
            .find { it.id == clientId || it.id.contains(clientId) || clientId.contains(it.id) }
        val base = client?.config?.name?.replace(Regex("\\s*\\[[LRS]]$"), "")?.trim()
            ?.ifBlank { client.host.name.ifBlank { client.host.ip } }
            ?: return
        val newName = "$base $tag"
        // Optimistic update so the badge refreshes immediately (sender gets a
        // Response, not an OnNameChanged echo).
        _state.update { st ->
            st.copy(
                groups = st.groups.map { group ->
                    group.copy(
                        clients = group.clients.map { c ->
                            if (c.id == clientId || c.id.contains(clientId) || clientId.contains(c.id)) {
                                c.copy(config = c.config.copy(name = newName))
                            } else {
                                c
                            }
                        },
                    )
                },
            )
        }
        scope.launch {
            controlClient?.sendSetClientName(clientId, newName)
            controlClient?.sendGetStatus()
        }
    }

    /** Broadcaster-only: toggle the stream-control lock. */
    fun toggleStreamLock() {
        val locked = !_state.value.isStreamLocked
        _state.update { it.copy(isStreamLocked = locked) }
        snapcontrolPlugin?.isStreamLocked = locked
    }

    /** Transport control of the remote stream while listening in. */
    fun sendStreamControl(command: String) {
        val streamId = _state.value.streamId.ifEmpty { return }
        scope.launch { controlClient?.sendStreamControl(streamId, command) }
    }

    // --- Internals ---

    private fun startLocalSnapclient(host: String, port: Int) {
        destroySnapclientProcess()
        currentSnapclientHost = host
        currentSnapclientPort = port
        localChannelTagSet = false
        // Restore this device's saved spatial role for the new connection: channel seeds the initial
        // tag; volume/latency are re-applied once the client appears (restoreOwnVolLat).
        volLatRestored = false
        volLatApplied = false
        savedVol = settingsRepository.snapclientVolume
        savedLat = settingsRepository.snapclientLatency
        _state.update { it.copy(snapclientChannel = settingsRepository.snapclientChannel) }
        launchSnapclientProcess(host, port)
        // Request audio focus so the foreground app owns the speaker. On loss the local snapclient is
        // stopped (broadcast continues); on regain it's restarted with the same host/port.
        val focus = audioFocus ?: AudioFocusController(
            context,
            onPause = { scope.launch { destroySnapclientProcess() } },
            onResume = {
                scope.launch {
                    val h = currentSnapclientHost
                    if (h != null && snapclientProcess == null) launchSnapclientProcess(h, currentSnapclientPort)
                }
            },
        ).also { audioFocus = it }
        focus.request()
    }

    /**
     * Re-assert audio focus for the local snapclient - call when the app returns to the foreground so
     * the focused app reclaims the speaker. Delegates to the shared controller: a no-op unless the
     * local snapclient was paused by a focus loss, in which case it reclaims focus and relaunches the
     * snapclient (via the onResume callback wired in [startLocalSnapclient]).
     */
    fun refocusLocalAudio() {
        audioFocus?.refocus()
    }

    private fun launchSnapclientProcess(host: String, port: Int) {
        val channel = _state.value.snapclientChannel
        val sc = SnapclientProcess(context).also { snapclientProcess = it }
        snapclientStateJob = scope.launch {
            sc.connectionState.collect { s -> _state.update { it.copy(listenState = s) } }
        }
        snapclientJob = scope.launch { sc.start(host, port, channel) }
    }

    private fun destroySnapclientProcess() {
        snapclientStateJob?.cancel()
        snapclientStateJob = null
        snapclientJob?.cancel()
        snapclientJob = null
        snapclientProcess?.destroy()
        snapclientProcess = null
    }

    private fun stopLocalSnapclient() {
        destroySnapclientProcess()
        currentSnapclientHost = null
        audioFocus?.abandon()
    }

    private fun startControl(host: String, httpPort: Int) {
        stopControl()
        val client = SnapcastControlClient(host, httpPort).also { controlClient = it }
        controlJob = scope.launch {
            client.initialize()
            client.notifications.collect { notif ->
                when (notif) {
                    is ServerGetStatusResponse -> applyServer(notif.result)
                    is ServerOnUpdate -> applyServer(notif.params)
                    is StreamOnProperties -> applyRemoteProps(notif.params.properties)
                    is ClientOnVolumeChanged -> {
                        _state.update { st ->
                            st.copy(
                                groups = st.groups.map { group ->
                                    group.copy(
                                        clients = group.clients.map { client ->
                                            if (client.id == notif.params.clientId) {
                                                client.copy(config = client.config.copy(volume = notif.params.volume))
                                            } else {
                                                client
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    is ClientOnLatencyChanged -> {
                        _state.update { st ->
                            st.copy(
                                groups = st.groups.map { group ->
                                    group.copy(
                                        clients = group.clients.map { client ->
                                            if (client.id == notif.params.clientId) {
                                                client.copy(config = client.config.copy(latency = notif.params.latency))
                                            } else {
                                                client
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    is ClientOnConnect,
                    is ClientOnDisconnect,
                    is ClientOnNameChanged,
                    -> {
                        controlClient?.sendGetStatus()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun stopControl() {
        controlJob?.cancel()
        controlJob = null
        runCatching { controlClient?.close() }
        controlClient = null
    }

    private suspend fun applyServer(result: ServerStatusResult) {
        val groups = result.server.groups
        _state.update {
            it.copy(
                groups = groups,
                streamId = groups.firstOrNull()?.streamId ?: it.streamId,
            )
        }
        mergeGroupsIfNeeded(groups)
        maybeSetInitialChannelTag(groups)
        syncLocalChannelFromName(groups)
        restoreOwnVolLat(groups)
        // Read the active stream's properties on connect so listen-in now-playing
        // shows immediately instead of waiting for the next OnProperties push
        if (_state.value.isListening) {
            val activeStreamId = groups.firstOrNull()?.streamId
            result.server.streams.find { it.id == activeStreamId }?.properties?.let { sp ->
                applyRemoteProps(
                    StreamPlayerProperties(
                        playbackStatus = sp.playbackStatus ?: "",
                        canPlay = sp.canPlay,
                        canPause = sp.canPause,
                        canGoNext = sp.canGoNext,
                        canGoPrevious = sp.canGoPrevious,
                        canControl = sp.canControl,
                        metadata = sp.metadata,
                    ),
                )
            }
        }
    }

    private fun applyRemoteProps(props: StreamPlayerProperties) {
        val art = props.metadata?.artData?.let { ad ->
            runCatching { Base64.decode(ad.data, Base64.DEFAULT) }.getOrNull()
        }
        _state.update {
            it.copy(
                remoteProps = props,
                // Keep previous art when a play/pause-only event carries no metadata
                remoteArt = if (props.metadata != null) art else it.remoteArt,
            )
        }
    }

    // Give this device's local snapclient an initial channel tag ([S]/[L]/[R])
    // once it appears in the group, so the control sheet shows a channel badge
    // and can cycle it. Runs once per (re)connect.
    private fun maybeSetInitialChannelTag(groups: List<Group>) {
        if (localChannelTagSet) return
        val localId = localClientId.takeIf { it.isNotEmpty() } ?: return
        val client = groups.flatMap { it.clients }
            .find { it.id == localId || it.id.contains(localId) } ?: return
        localChannelTagSet = true
        if (Regex("\\s*\\[[LRS]]$").containsMatchIn(client.config.name)) return // already tagged
        val tag = when (_state.value.snapclientChannel) {
            "left" -> "[L]"
            "right" -> "[R]"
            else -> "[S]"
        }
        val base = client.config.name.ifBlank { client.host.name.ifBlank { client.host.ip } }
        scope.launch {
            controlClient?.sendSetClientName(client.id, "$base $tag")
            controlClient?.sendGetStatus()
        }
    }

    // Adopt a channel change made from another controller (web/app) to our own
    // client - keeps the live audio channel in step with the [L/R/S] name tag.
    private fun syncLocalChannelFromName(groups: List<Group>) {
        val localId = localClientId.takeIf { it.isNotEmpty() } ?: return
        val client = groups.flatMap { it.clients }
            .find { it.id == localId || it.id.contains(localId) } ?: return
        val tag = Regex("\\s*\\[([LRS])]$").find(client.config.name)?.groupValues?.get(1) ?: return
        val newChannel = when (tag) {
            "L" -> "left"
            "R" -> "right"
            else -> "stereo"
        }
        if (newChannel != _state.value.snapclientChannel) {
            _state.update { it.copy(snapclientChannel = newChannel) }
            snapclientProcess?.setChannel(newChannel)
            settingsRepository.snapclientChannel = newChannel
        }
    }

    // Restore this device's saved volume/latency onto its own snapclient on connect; persistence is
    // handled by the init collector. Gated by volLatRestored - set only once the server reflects the
    // saved values - so the transient connect-time default can't be persisted over saved.
    private fun restoreOwnVolLat(groups: List<Group>) {
        if (volLatRestored) return
        val localId = localClientId.takeIf { it.isNotEmpty() } ?: return
        val own = groups.flatMap { it.clients }
            .find { it.id == localId || it.id.contains(localId) } ?: return
        val vol = own.config.volume.percent
        val lat = own.config.latency
        if (vol == savedVol && lat == savedLat) {
            volLatRestored = true
            lastPersistedVol = vol
            lastPersistedLat = lat
        } else if (!volLatApplied) {
            volLatApplied = true
            scope.launch {
                controlClient?.sendSetVolume(own.id, own.config.volume.muted, savedVol)
                controlClient?.sendSetLatency(own.id, savedLat)
                controlClient?.sendGetStatus()
            }
        }
    }

    private fun mergeGroupsIfNeeded(groups: List<Group>) {
        // Do NOT delete disconnected clients: web players briefly disconnect when
        // backgrounded, then reconnect with the same id - deleting wipes their
        // stored name and latency calibration. Merge ONLY when CONNECTED clients
        // are split across multiple groups; groups holding only disconnected
        // clients are ignored (avoids a SetClients/OnUpdate storm).
        val groupsWithConnected = groups.filter { g -> g.clients.any { it.connected } }
        if (groupsWithConnected.size <= 1) return
        val targetGroupId = groupsWithConnected.first().id
        val connectedIds = groups.flatMap { it.clients }.filter { it.connected }.map { it.id }
        scope.launch { controlClient?.sendGroupSetClients(targetGroupId, connectedIds) }
        Log.d(TAG, "Merging ${groupsWithConnected.size} groups → $targetGroupId (${connectedIds.size} clients)")
    }

    companion object {
        private const val TAG = "SnapcastManager"

        /** The Snapcast stream `name=` identity for this app (web player + snapclients). */
        private const val STREAM_NAME = "TelecloudRadio"
    }
}
