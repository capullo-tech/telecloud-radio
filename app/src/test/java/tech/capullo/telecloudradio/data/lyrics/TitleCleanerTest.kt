package tech.capullo.telecloudradio.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCleanerTest {

    private fun assertCleans(expected: String, title: String) = assertEquals("clean(\"$title\")", expected, TitleCleaner.clean(title))

    @Test
    fun `packaging groups are removed`() {
        assertCleans("Rick Astley - Never Gonna Give You Up", "Rick Astley - Never Gonna Give You Up (Official Music Video)")
        assertCleans("Ed Sheeran - Perfect", "Ed Sheeran - Perfect [Official Lyric Video]")
        assertCleans("Billie Eilish - bad guy", "Billie Eilish - bad guy (Lyrics)")
        assertCleans("a-ha - Take On Me", "a-ha - Take On Me (Official Video) [4K]")
        assertCleans("Bad Bunny - Tití Me Preguntó", "Bad Bunny - Tití Me Preguntó (Video Oficial)")
        assertCleans(
            "Daft Punk - Get Lucky ft. Pharrell Williams, Nile Rodgers",
            "Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers",
        )
    }

    @Test
    fun `nested packaging clears from the inside out`() {
        assertCleans("Avicii - Wake Me Up", "Avicii - Wake Me Up (Official Video (HD))")
    }

    @Test
    fun `version markers stay`() {
        assertCleans("Smells Like Teen Spirit (Live at Reading 1992)", "Smells Like Teen Spirit (Live at Reading 1992)")
        assertCleans("Dreams (2004 Remaster)", "Dreams (2004 Remaster)")
        assertCleans("Fix You (Official Live Video)", "Fix You (Official Live Video)")
        assertCleans("Here Comes The Sun (2019 Mix)", "Here Comes The Sun (2019 Mix)")
        assertCleans("PSY - GANGNAM STYLE(강남스타일) M/V", "PSY - GANGNAM STYLE(강남스타일) M/V")
    }

    @Test
    fun `pipe tails are cut`() {
        assertCleans(
            "Kesariya - Full Song",
            "Kesariya - Full Song | Brahmāstra | Ranbir Kapoor | Alia Bhatt | Pritam | Arijit Singh",
        )
        assertCleans("Ed Sheeran - Shape of You", "Ed Sheeran - Shape of You (Official Video) | Ed Sheeran")
    }

    @Test
    fun `never returns blank`() {
        assertCleans("[Official Music Video]", "[Official Music Video]")
        assertCleans("(Official Video) | Some Channel", "(Official Video) | Some Channel")
    }

    @Test
    fun `the same artist ignores case, spaces and punctuation`() {
        assertTrue(TitleCleaner.sameArtist("Imagine Dragons", "ImagineDragons"))
        assertTrue(TitleCleaner.sameArtist("a-ha", "A-HA"))
        assertFalse(TitleCleaner.sameArtist("Calvin Harris, Dua Lipa", "Calvin Harris"))
        assertFalse(TitleCleaner.sameArtist("Queen", null))
        assertFalse(TitleCleaner.sameArtist("🤘", "🎤"))
    }
}
