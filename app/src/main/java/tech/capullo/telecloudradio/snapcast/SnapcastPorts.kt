package tech.capullo.telecloudradio.snapcast

import tech.capullo.audio.snapcast.SnapserverPorts

/**
 * Telecloud's fixed Snapcast ports — non-standard (standard snapcast is 1704/1705/1780, and
 * QuantumCast/capullo-audio default to 1604/1605/1680) so Telecloud's snapserver never clashes
 * with another capullo app or a stock Snapcast on the same device / LAN.
 *
 * Passed to the shared [tech.capullo.audio.snapcast.SnapserverProcess] as [asSnapserverPorts];
 * app-side call sites (listen-in default, QR URL) read the individual constants.
 */
object SnapcastPorts {
    const val STREAM = 1804
    const val TCP = 1805
    const val HTTP = 1880

    val asSnapserverPorts = SnapserverPorts(streamPort = STREAM, tcpPort = TCP, httpPort = HTTP)
}
