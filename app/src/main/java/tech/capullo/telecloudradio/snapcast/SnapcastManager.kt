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
    ) {
        val isListening: Boolean get() = listenHost.isNotEmpty()
    }

    private val _state = MutableStateFlow(SnapcastState())
    val state: StateFlow<SnapcastState> = _state.asStateFlow()

    val discovery by lazy { SnapserverDiscoveryManager(context) }

    /** This device's persistent snapclient id — marks "this device" in client lists. */
    val localClientId: String get() = SnapclientProcess.localHostId(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /**
     * (Re)creates the snapserver process + FIFO. Called by PlaybackService in
     * onCreate BEFORE the tee sink opens the pipe, so the write end always
     * refers to the freshly created FIFO inode.
     */
    fun prepareBroadcast(): String {
        stopBroadcast()
        return SnapserverProcess(context).also { snapserverProcess = it }.pipeFilepath
    }

    /**
     * Starts snapserver + local snapclient + metadata plugin + NSD + control
     * socket. Idempotent — called on every "playing" transition.
     */
    fun startBroadcast(callbacks: SnapcontrolCallbacks) {
        if (_state.value.isBroadcasting) return
        if (_state.value.isListening) return // listen-in owns the snapclient/ports
        // Lazily recreated after a listen-in session tore the stack down; the
        // FIFO file is reused so the tee sink's open write end stays valid.
        val snapserver = snapserverProcess ?: SnapserverProcess(context).also { snapserverProcess = it }
        Log.d(TAG, "Starting broadcast stack")
        // Plugin must be listening before snapserver spawns libsnapcontrol.so
        snapcontrolPlugin = SnapcontrolPlugin(callbacks, scope.coroutineContext[Job]!!).also { it.start() }
        snapserverJob = scope.launch { snapserver.start() }
        startLocalSnapclient("localhost", SnapcastPorts.STREAM)
        nsdRegistrar = SnapserverNsdRegistrar(context).also { it.start() }
        startControl("localhost")
        _state.update { it.copy(isBroadcasting = true) }
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

    fun connectListen(host: String, port: Int = SnapcastPorts.STREAM) {
        Log.d(TAG, "Listen-in → $host:$port")
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
        startControl(host)
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
                            } else client
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

    /** Change this device's own audio channel (stereo/left/right). */
    fun setLocalChannel(channel: String) {
        _state.update { it.copy(snapclientChannel = channel) }
        snapclientProcess?.setChannel(channel)
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
        }
    }

    private fun renameClientWithChannel(clientId: String, channel: String) {
        val tag = when (channel) { "left" -> "[L]"; "right" -> "[R]"; else -> "[S]" }
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
                            } else c
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
        stopLocalSnapclient()
        localChannelTagSet = false
        val channel = _state.value.snapclientChannel
        val sc = SnapclientProcess(context).also { snapclientProcess = it }
        snapclientStateJob = scope.launch {
            sc.connectionState.collect { s -> _state.update { it.copy(listenState = s) } }
        }
        snapclientJob = scope.launch { sc.start(host, port, channel) }
    }

    private fun stopLocalSnapclient() {
        snapclientStateJob?.cancel()
        snapclientStateJob = null
        snapclientJob?.cancel()
        snapclientJob = null
        snapclientProcess?.destroy()
        snapclientProcess = null
    }

    private fun startControl(host: String) {
        stopControl()
        val client = SnapcastControlClient(host).also { controlClient = it }
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
                                            } else client
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
                                            } else client
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
        runCatching { controlClient?.client?.close() }
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
        val tag = when (_state.value.snapclientChannel) { "left" -> "[L]"; "right" -> "[R]"; else -> "[S]" }
        val base = client.config.name.ifBlank { client.host.name.ifBlank { client.host.ip } }
        scope.launch {
            controlClient?.sendSetClientName(client.id, "$base $tag")
            controlClient?.sendGetStatus()
        }
    }

    // Adopt a channel change made from another controller (web/app) to our own
    // client — keeps the live audio channel in step with the [L/R/S] name tag.
    private fun syncLocalChannelFromName(groups: List<Group>) {
        val localId = localClientId.takeIf { it.isNotEmpty() } ?: return
        val client = groups.flatMap { it.clients }
            .find { it.id == localId || it.id.contains(localId) } ?: return
        val tag = Regex("\\s*\\[([LRS])]$").find(client.config.name)?.groupValues?.get(1) ?: return
        val newChannel = when (tag) { "L" -> "left"; "R" -> "right"; else -> "stereo" }
        if (newChannel != _state.value.snapclientChannel) {
            _state.update { it.copy(snapclientChannel = newChannel) }
            snapclientProcess?.setChannel(newChannel)
        }
    }

    private fun mergeGroupsIfNeeded(groups: List<Group>) {
        // Do NOT delete disconnected clients: web players briefly disconnect when
        // backgrounded, then reconnect with the same id — deleting wipes their
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
    }
}
