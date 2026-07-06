package tech.capullo.telecloudradio.ui.snapcast

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import tech.capullo.telecloudradio.snapcast.DiscoveredSnapserver
import tech.capullo.telecloudradio.snapcast.SnapcastManager
import javax.inject.Inject

@HiltViewModel
class SnapcastViewModel @Inject constructor(
    private val snapcastManager: SnapcastManager,
) : ViewModel() {

    val state: StateFlow<SnapcastManager.SnapcastState> = snapcastManager.state
    val discoveredServers: StateFlow<List<DiscoveredSnapserver>> = snapcastManager.discovery.discoveredServers
    val localClientId: String get() = snapcastManager.localClientId

    fun startDiscovery() = snapcastManager.discovery.startDiscovery()
    fun stopDiscovery() = snapcastManager.discovery.stopDiscovery()

    fun connect(host: String, port: Int = tech.capullo.telecloudradio.snapcast.SnapcastPorts.STREAM) = snapcastManager.connectListen(host, port)
    fun disconnect() = snapcastManager.disconnectListen()

    fun setClientVolume(clientId: String, percent: Int, muted: Boolean) = snapcastManager.adjustClientVolume(clientId, percent, muted)

    fun setClientLatency(clientId: String, latencyMs: Int) = snapcastManager.adjustClientLatency(clientId, latencyMs)

    fun setLocalChannel(channel: String) = snapcastManager.setLocalChannel(channel)

    fun changeClientChannel(clientId: String, channel: String) = snapcastManager.changeClientChannel(clientId, channel)

    fun toggleStreamLock() = snapcastManager.toggleStreamLock()

    /** Transport control of the remote stream while listening in. */
    fun streamControl(command: String) = snapcastManager.sendStreamControl(command)
}
