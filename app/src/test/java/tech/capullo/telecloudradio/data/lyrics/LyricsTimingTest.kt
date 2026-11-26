package tech.capullo.telecloudradio.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.audio.snapcast.Client
import tech.capullo.audio.snapcast.ClientConfig
import tech.capullo.audio.snapcast.Group
import tech.capullo.audio.snapcast.Host
import tech.capullo.audio.snapcast.LastSeen
import tech.capullo.audio.snapcast.SnapClient
import tech.capullo.audio.snapcast.Volume
import tech.capullo.telecloudradio.data.lyrics.LyricsTiming.SNAPSERVER_BUFFER_MS

class LyricsTimingTest {

    private fun client(id: String, latency: Int, connected: Boolean = true) = Client(
        config = ClientConfig(instance = 1, latency = latency, name = id, volume = Volume(muted = false, percent = 100)),
        connected = connected,
        host = Host(arch = "", ip = "", mac = "", name = id, os = ""),
        id = id,
        lastSeen = LastSeen(sec = 0, usec = 0),
        snapclient = SnapClient(name = "Snapclient", protocolVersion = 2, version = "0.34.0"),
    )

    private fun groups(vararg clients: Client) = listOf(Group(clients.toList(), id = "g", muted = false, name = "", streamId = "s"))

    @Test
    fun `the automatic delay is the snapserver buffer less the local client's latency`() {
        assertEquals(SNAPSERVER_BUFFER_MS, LyricsTiming.autoDelayMs(emptyList(), "me"))
        assertEquals(SNAPSERVER_BUFFER_MS - 150, LyricsTiming.autoDelayMs(groups(client("me", 150), client("tv", 400)), "me"))
        assertEquals(SNAPSERVER_BUFFER_MS + 200, LyricsTiming.autoDelayMs(groups(client("me", -200)), "me"))
        assertEquals(SNAPSERVER_BUFFER_MS, LyricsTiming.autoDelayMs(groups(client("tv", 400)), "me"))
        assertEquals(SNAPSERVER_BUFFER_MS, LyricsTiming.autoDelayMs(groups(client("me", 150, connected = false)), "me"))
    }

    @Test
    fun `the local client is found by its host id, as SnapcastManager finds it, and never by an empty id`() {
        assertEquals(SNAPSERVER_BUFFER_MS - 80, LyricsTiming.autoDelayMs(groups(client("me#2", 80)), "me"))
        assertEquals(SNAPSERVER_BUFFER_MS, LyricsTiming.autoDelayMs(groups(client("tv", 400)), ""))
    }

    @Test
    fun `tapping a line seeks to where that same line highlights, at any offset`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(4_000, "b"), LyricLine(9_000, "c"))
        val lyrics = Lyrics(LyricsSource.LRCLIB_GET, synced = true, lines = lines)
        listOf(0L, 1_000L, 2_500L, -800L).forEach { offset ->
            lines.forEachIndexed { index, line ->
                val seekTo = LyricsTiming.seekTarget(line.timeMs!!, offset)
                assertEquals("offset $offset line $index", index, LyricsTiming.lineIndex(lyrics, seekTo, offset))
            }
        }
        assertEquals(0, LyricsTiming.lineIndex(lyrics, 4_000 + 999, 1_000))
        assertEquals(1, LyricsTiming.lineIndex(lyrics, 4_000 + 1_000, 1_000))
        assertEquals(-1, LyricsTiming.lineIndex(lyrics.copy(synced = false), 5_000, 0))
    }
}
