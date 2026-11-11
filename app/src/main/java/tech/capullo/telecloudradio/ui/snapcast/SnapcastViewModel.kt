package tech.capullo.telecloudradio.ui.snapcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.capullo.audio.snapcast.DiscoveredSnapserver
import tech.capullo.audio.snapcast.SnapserverListenInfo
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.telecloudradio.snapcast.SnapcastManager
import tech.capullo.telecloudradio.snapcast.SnapcastPorts
import javax.inject.Inject

@HiltViewModel
class SnapcastViewModel @Inject constructor(
    private val snapcastManager: SnapcastManager,
    tunnelManager: TunnelManager,
) : ViewModel() {

    val state: StateFlow<SnapcastManager.SnapcastState> = snapcastManager.state
    val discoveredServers: StateFlow<List<DiscoveredSnapserver>> = snapcastManager.discovery.discoveredServers
    val tunnelState: StateFlow<TunnelManager.TunnelState> = tunnelManager.state
    val localClientId: String get() = snapcastManager.localClientId

    fun startDiscovery() = snapcastManager.discovery.startDiscovery()
    fun stopDiscovery() = snapcastManager.discovery.stopDiscovery()

    fun connect(
        host: String,
        port: Int = SnapcastPorts.STREAM,
        httpPort: Int = SnapcastPorts.HTTP,
    ) = snapcastManager.connectListen(host, port, httpPort)

    // Manual listen-in: the user types whichever port they know, usually the HTTP port from the
    // web-player URL. A broadcaster's ports are one consecutive stream/tcp/http block, so resolve()
    // probes the typed port and the two above it for its listen.json and learns all three. If nothing
    // resolves (stock/legacy server, or the typed port really was a stream port), fall back to
    // treating the typed port as a direct stream port with the default control port.
    fun connectManually(host: String, typedPort: Int?) {
        viewModelScope.launch {
            val ports = SnapserverListenInfo.resolve(host, typedPort, defaultHttpPort = SnapcastPorts.HTTP)
            if (ports != null) {
                snapcastManager.connectListen(host, ports.streamPort, ports.httpPort)
            } else {
                snapcastManager.connectListen(host, typedPort ?: SnapcastPorts.STREAM, SnapcastPorts.HTTP)
            }
        }
    }

    fun disconnect() = snapcastManager.disconnectListen()

    fun setClientVolume(clientId: String, percent: Int, muted: Boolean) = snapcastManager.adjustClientVolume(clientId, percent, muted)

    fun setClientLatency(clientId: String, latencyMs: Int) = snapcastManager.adjustClientLatency(clientId, latencyMs)

    fun setLocalChannel(channel: String) = snapcastManager.setLocalChannel(channel)

    fun changeClientChannel(clientId: String, channel: String) = snapcastManager.changeClientChannel(clientId, channel)

    fun toggleStreamLock() = snapcastManager.toggleStreamLock()

    fun resetSelf() = snapcastManager.resetSelf()

    fun resetAll() = snapcastManager.resetAll()

    /** Transport control of the remote stream while listening in. */
    fun streamControl(command: String) = snapcastManager.sendStreamControl(command)
}
