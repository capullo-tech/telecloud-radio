package tech.capullo.telecloudradio.snapcast

import tech.capullo.audio.snapcast.JoinTarget
import tech.capullo.audio.snapcast.SnapserverPorts

/**
 * Where a listen-in session started from a scanned join URL connects: [SnapcastManager.connectListen]'s
 * arguments for a [JoinTarget].
 *
 * @property streamPort the port the snapclient dials with [scheme].
 * @property httpPort snapserver's HTTP port (JSON-RPC control, art); through the tunnel only its 443.
 * @property scheme `tcp` to the raw stream port on the LAN, `wss` to the tunnel's `/stream` websocket.
 */
data class JoinEndpoint(val host: String, val streamPort: Int, val httpPort: Int, val scheme: String) {
    companion object {
        /**
         * [listenInfo] is the broadcaster's `listen.json` (`SnapserverListenInfo.fetch(host, httpPort)`),
         * null when unreachable or not fetched. On the LAN it wins over [JoinTarget.ports], which only
         * derives the block from the URL's port and misses a broadcaster on OS-assigned fallback ports.
         * Through the tunnel only the HTTPS port is reachable, so it is ignored there.
         */
        fun of(target: JoinTarget, listenInfo: SnapserverPorts?): JoinEndpoint {
            if (target.secure) return JoinEndpoint(target.host, target.port, target.httpPort, target.scheme)
            val ports = listenInfo ?: target.ports
            return JoinEndpoint(
                host = target.host,
                streamPort = ports?.streamPort ?: target.port,
                httpPort = ports?.httpPort ?: target.httpPort,
                scheme = target.scheme,
            )
        }
    }
}
