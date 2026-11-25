package tech.capullo.telecloudradio.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.telecloudradio.data.db.LyricsEntity
import tech.capullo.telecloudradio.data.lyrics.LrclibClient.Companion.LYRICS_DURATION_TOLERANCE_S
import tech.capullo.telecloudradio.data.lyrics.LrclibClient.Companion.SYNCED_PREFERENCE_WINDOW_S
import tech.capullo.telecloudradio.data.lyrics.LyricsRepository.Companion.KIND_NONE
import tech.capullo.telecloudradio.data.lyrics.LyricsRepository.Companion.KIND_PLAIN
import tech.capullo.telecloudradio.data.lyrics.LyricsRepository.Companion.KIND_SYNCED
import tech.capullo.telecloudradio.data.lyrics.LyricsRepository.Companion.LYRICS_RETRY_DAYS
import tech.capullo.telecloudradio.data.lyrics.LyricsRepository.Companion.toLyrics

class LyricsTest {

    @Test
    fun `LRC stamps in every precision, several per line, in time order`() {
        val lines = Lrc.parse(
            """
            [ar:Someone]
            [offset:+0]
            [00:05.5]first
            [00:12.34][01:05.30]chorus
            [00:20]second
            [00:30.123] third
            [01:00:50]colon fraction
            text without a stamp
            [00:40.00]
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                LyricLine(5_500, "first"),
                LyricLine(12_340, "chorus"),
                LyricLine(20_000, "second"),
                LyricLine(30_123, "third"),
                LyricLine(40_000, ""),
                LyricLine(60_500, "colon fraction"),
                LyricLine(65_300, "chorus"),
            ),
            lines,
        )
    }

    @Test
    fun `LRC with no stamp is not synced lyrics`() {
        assertTrue(Lrc.parse("[ar:Someone]\nplain line").isEmpty())
        assertNull(Lyrics.synced(LyricsSource.LRCLIB_GET, "plain line"))
    }

    @Test
    fun `the offset tag moves every stamp, a positive offset earlier`() {
        assertEquals(
            listOf(LyricLine(9_500, "a"), LyricLine(19_500, "b")),
            Lrc.parse("[offset:+500]\n[00:10.00]a\n[00:20.00]b"),
        )
        assertEquals(listOf(LyricLine(10_250, "a")), Lrc.parse("[ti:Song]\n[offset: -250]\n[00:10.00]a"))
        assertEquals(listOf(LyricLine(0, "a")), Lrc.parse("[offset:1500]\n[00:01.00]a"))
        assertEquals(LyricLine(9_500, "a"), Lyrics.synced(LyricsSource.LRCLIB_GET, "[offset:500]\n[00:10.00]a")!!.lines.single())
    }

    @Test
    fun `an LRC with untimed lyric lines is not synced and keeps every line as plain text`() {
        val lrc = "[ar:Someone]\n[00:01.00]timed one\nuntimed verse\n\n[00:03.00][00:05.00]timed two"
        assertNull(Lyrics.synced(LyricsSource.LRCLIB_GET, lrc))
        assertEquals(
            listOf("timed one", "untimed verse", "", "timed two"),
            Lyrics.plain(LyricsSource.LRCLIB_GET, Lrc.plainText(lrc))!!.lines.map { it.text },
        )
        assertTrue(Lyrics.synced(LyricsSource.LRCLIB_GET, "[ar:Someone]\n[00:01.00]one\n\n[00:02.00]")!!.synced)
    }

    @Test
    fun `the current line is the last one at or before the position`() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c"))
        assertEquals(-1, Lrc.currentLineIndex(lines, 0))
        assertEquals(-1, Lrc.currentLineIndex(lines, 999))
        assertEquals(0, Lrc.currentLineIndex(lines, 1_000))
        assertEquals(0, Lrc.currentLineIndex(lines, 4_999))
        assertEquals(1, Lrc.currentLineIndex(lines, 5_000))
        assertEquals(2, Lrc.currentLineIndex(lines, 9_000))
        assertEquals(2, Lrc.currentLineIndex(lines, 600_000))
        assertEquals(-1, Lrc.currentLineIndex(emptyList(), 1_000))

        assertEquals(-1, Lrc.currentLineIndex(listOf(LyricLine(null, "a")), 1_000))
    }

    @Test
    fun `plain lyrics keep one stanza break between stanzas`() {
        val lyrics = Lyrics.plain(LyricsSource.LRCLIB_SEARCH, "\n\n a \nb\n\n\nc\n\n")!!
        assertEquals(listOf("a", "b", "", "c"), lyrics.lines.map { it.text })
        assertFalse(lyrics.synced)
        assertNull(Lyrics.plain(LyricsSource.LRCLIB_SEARCH, " \n\n "))
    }

    private fun record(duration: Double?, synced: String? = "[00:01.00]x", plain: String? = "x", instrumental: Boolean = false) = LrclibRecord(duration = duration, syncedLyrics = synced, plainLyrics = plain, instrumental = instrumental)

    @Test
    fun `a match counts up to the duration tolerance and not past it`() {
        val track = 200L
        val edge = record(track + LYRICS_DURATION_TOLERANCE_S)
        assertSame(edge, LrclibClient.bestMatch(listOf(edge), track))
        assertSame(edge, LrclibClient.bestMatch(listOf(edge), track + 2 * LYRICS_DURATION_TOLERANCE_S.toLong()))
        assertNull(LrclibClient.bestMatch(listOf(record(track + LYRICS_DURATION_TOLERANCE_S + 0.1)), track))
        assertNull(LrclibClient.bestMatch(listOf(record(track - LYRICS_DURATION_TOLERANCE_S - 0.1)), track))
    }

    @Test
    fun `the nearest duration wins, synced on a tie, and empty entries never match`() {
        val near = record(201.0)
        val far = record(197.0)
        assertSame(near, LrclibClient.bestMatch(listOf(far, near), 200))
        val plainOnly = record(201.0, synced = null)
        assertSame(near, LrclibClient.bestMatch(listOf(plainOnly, near), 200))
        assertNull(LrclibClient.bestMatch(listOf(record(200.0, synced = null, plain = null)), 200))
        assertNull(LrclibClient.bestMatch(listOf(record(null)), 200))
    }

    @Test
    fun `synced wins over a slightly closer plain record, not over a much closer one`() {
        val plainExact = record(200.0, synced = null)
        val syncedNear = record(201.5)
        assertSame(syncedNear, LrclibClient.bestMatch(listOf(plainExact, syncedNear), 200))
        val syncedFar = record(200.0 + SYNCED_PREFERENCE_WINDOW_S + 0.5)
        assertSame(plainExact, LrclibClient.bestMatch(listOf(syncedFar, plainExact), 200))
    }

    @Test
    fun `an instrumental record matches, and lyrics in the same window win over it`() {
        val instrumental = record(200.0, synced = null, plain = null, instrumental = true)
        assertSame(instrumental, LrclibClient.bestMatch(listOf(instrumental), 200))
        val lyrics = record(201.0)
        assertSame(lyrics, LrclibClient.bestMatch(listOf(instrumental, lyrics), 200))
        assertNull(LrclibClient.bestMatch(listOf(record(200.0 + LYRICS_DURATION_TOLERANCE_S + 1, instrumental = true)), 200))
    }

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `a none row is retried after the retry days, lyrics never`() {
        val fetched = 1_000_000L
        val none = LyricsEntity(1, 2, KIND_NONE, null, null, fetched)
        val retryAt = fetched + LYRICS_RETRY_DAYS * day
        assertTrue(LyricsRepository.isFresh(none, retryAt - 1))
        assertFalse(LyricsRepository.isFresh(none, retryAt))
        val synced = LyricsEntity(1, 2, KIND_SYNCED, "lrclib-get", "[00:01.00]a", fetched)
        assertTrue(LyricsRepository.isFresh(synced, fetched + 1_000 * day))
    }

    @Test
    fun `a cache row reads back as the lyrics it stored`() {
        val synced = LyricsEntity(1, 2, KIND_SYNCED, "lrclib-search", "[00:01.00]a\n[00:02.00]b", 0).toLyrics()!!
        assertEquals(LyricsSource.LRCLIB_SEARCH, synced.source)
        assertTrue(synced.synced)
        assertEquals(listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b")), synced.lines)
        val plain = LyricsEntity(1, 2, KIND_PLAIN, "lrclib-get", "a\nb", 0).toLyrics()!!
        assertEquals(LyricsSource.LRCLIB_GET, plain.source)
        assertFalse(plain.synced)
        assertNull(LyricsEntity(1, 2, KIND_NONE, null, null, 0).toLyrics())
        assertNull(LyricsEntity(1, 2, KIND_SYNCED, "unknown-source", "[00:01.00]a", 0).toLyrics())
    }
}
