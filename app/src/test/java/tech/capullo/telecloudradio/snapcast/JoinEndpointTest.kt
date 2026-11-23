package tech.capullo.telecloudradio.snapcast

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.audio.snapcast.JoinUrl
import tech.capullo.audio.snapcast.SnapserverPorts

class JoinEndpointTest {

    private fun target(url: String) = requireNotNull(JoinUrl.parse(url)) { "not a join URL: $url" }

    @Test
    fun `LAN listen json wins over the block derived from the URL`() {
        // OS-assigned fallback ports: not stream = http-2, so only listen.json knows the stream port.
        val listenInfo = SnapserverPorts(streamPort = 40111, tcpPort = 52007, httpPort = 38433)

        val endpoint = JoinEndpoint.of(target("http://192.168.1.20:38433/join/"), listenInfo)

        assertEquals(JoinEndpoint("192.168.1.20", 40111, 38433, "tcp"), endpoint)
    }

    @Test
    fun `LAN without listen json dials the derived stream port`() {
        val endpoint = JoinEndpoint.of(target("http://192.168.1.20:41002/join/"), listenInfo = null)

        assertEquals(JoinEndpoint("192.168.1.20", 41000, 41002, "tcp"), endpoint)
    }

    @Test
    fun `legacy fixed HTTP port without listen json dials the fixed stream port`() {
        val endpoint = JoinEndpoint.of(target("http://10.0.0.5:1680/join"), listenInfo = null)

        assertEquals(JoinEndpoint("10.0.0.5", 1604, 1680, "tcp"), endpoint)
    }

    @Test
    fun `tunnel streams over wss on 443 and ignores any listen json`() {
        val stray = SnapserverPorts(streamPort = 41000, tcpPort = 41001, httpPort = 41002)

        val endpoint = JoinEndpoint.of(target("https://quiet-river-1234.trycloudflare.com/join/"), stray)

        assertEquals(JoinEndpoint("quiet-river-1234.trycloudflare.com", 443, 443, "wss"), endpoint)
    }
}
