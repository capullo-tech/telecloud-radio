package tech.capullo.telecloudradio.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.telecloudradio.data.TrackKey
import tech.capullo.telecloudradio.data.lyrics.LyricLine
import tech.capullo.telecloudradio.data.lyrics.Lyrics
import tech.capullo.telecloudradio.data.lyrics.LyricsSource
import tech.capullo.telecloudradio.data.lyrics.LyricsState

class PlayerLyricsTest {

    private val lyrics = Lyrics(LyricsSource.LRCLIB_GET, synced = true, lines = listOf(LyricLine(0, "a")))

    private val a = TrackKey(1, 10)
    private val b = TrackKey(1, 11)
    private val c = TrackKey(2, 10)

    @Test
    fun `lyrics show only for the shown track`() {
        assertEquals(
            PlayerLyrics.Shown(lyrics),
            PlayerLyrics.next(PlayerLyrics.None, a, LyricsState.Done(a, lyrics)),
        )
        assertEquals(
            PlayerLyrics.Shown(lyrics, offsetMs = 1_500),
            PlayerLyrics.next(PlayerLyrics.Shown(lyrics), a, LyricsState.Done(a, lyrics, offsetMs = 1_500)),
        )

        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.None, b, LyricsState.Done(a, lyrics)))
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.None, c, LyricsState.Done(a, lyrics)))
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.Shown(lyrics), null, LyricsState.Done(a, lyrics)))
    }

    @Test
    fun `shown lyrics stay as pending while the next lookup runs`() {
        val shown = PlayerLyrics.Shown(lyrics)
        assertEquals(PlayerLyrics.Pending, PlayerLyrics.next(shown, b, LyricsState.Loading(b)))
        assertEquals(PlayerLyrics.Pending, PlayerLyrics.next(shown, a, LyricsState.Loading(b)))
        assertEquals(PlayerLyrics.Pending, PlayerLyrics.next(PlayerLyrics.Pending, c, LyricsState.Loading(c)))

        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.None, b, LyricsState.Loading(b)))
    }

    @Test
    fun `a track shown before it plays has no lyrics until its lookup starts`() {
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.Shown(lyrics), b, LyricsState.Done(a, lyrics)))
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.Pending, b, LyricsState.Done(a, lyrics)))
    }

    @Test
    fun `a track without lyrics has none`() {
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.Pending, b, LyricsState.Done(b, null)))
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.Shown(lyrics), b, LyricsState.Done(b, null)))
    }
}
