package tech.capullo.telecloudradio.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsQueryTest {

    private fun assertQuery(artist: String?, song: String, title: String?, performer: String? = null, fileName: String? = null) = assertEquals(
        "of(\"$title\", $performer, $fileName)",
        LyricsQuery(artist, song),
        LyricsQuery.of(title, performer, fileName),
    )

    @Test
    fun `CJK brackets name the song and the artist before them`() {
        assertQuery("周杰倫", "晴天", "周杰倫 Jay Chou【晴天 Sunny Day】-Official Music Video")
        assertQuery("光良", "童話", "光良 Michael Wong【童話】Official Music Video")
        assertQuery("伍佰", "挪威的森林", "伍佰 Wu Bai&China Blue【挪威的森林 Norwegian forest】Official Music Video")
        assertQuery("范逸臣", "放生", "范逸臣 Van Fan《放生》官方MV (Official Music Video)")
        assertQuery(
            "范逸臣",
            "I BELIEVE",
            "范逸臣 Van Fan《I BELIEVE》（電影【我的野蠻女友】中文主題曲）官方MV (Official Music Video)",
        )
        assertQuery("F4", "在這裡等你", "F4《在這裡等你》Zai Zhe Li Deng Ni (Official Video Karaoke)")
    }

    @Test
    fun `square brackets with spaces inside name the song, a packaging tag does not`() {
        assertQuery("張信哲", "過火", "張信哲 Jeff Chang [ 過火 ] 官方完整版 Official MV")
        assertQuery("Eminem", "Lose Yourself", "Eminem - Lose Yourself [HD]")
    }

    @Test
    fun `a short label before a colon is the artist`() {
        assertQuery("twenty one pilots", "Stressed Out", "twenty one pilots: Stressed Out [OFFICIAL VIDEO]")
        assertQuery(
            "twenty one pilots",
            "Heathens",
            "twenty one pilots: Heathens (from Suicide Squad: The Album) [OFFICIAL VIDEO]",
        )
        assertQuery(
            "Gym Class Heroes",
            "Stereo Hearts ft. Adam Levine",
            "Gym Class Heroes: Stereo Hearts ft. Adam Levine [OFFICIAL VIDEO]",
        )
    }

    @Test
    fun `a CJK name keeps no Latin alias, a CJK song keeps no translation`() {
        assertQuery("庾澄慶", "春泥", "庾澄慶 Harlem Yu - 春泥")
        assertQuery("張宇", "曲終人散", "張宇 Phil Chang -  曲終人散 The Curtain Falls (官方完整版MV)")
        assertQuery("張雨生", "大海", "張雨生 Tom Chang - 大海 (official 官方完整版MV)")
        assertQuery("張學友", "吻別", "吻別", performer = "張學友 Jacky Cheung")
    }

    @Test
    fun `a Chinese name and a song with only a space between`() {
        assertQuery("張學友", "一千個傷心的理由", "張學友 一千個傷心的理由")
    }

    @Test
    fun `other separators`() {
        assertQuery("張學友", "聽海", "張學友 ‧ 聽海")
        assertQuery("江蕙", "夢中的情話", "江蕙 -夢中的情話 MENG CHUNG DE CHING HUA(Official Music Video)")
        assertQuery("Eminem", "Mockingbird", "Eminem, \"Mockingbird\" (video lyric)")
        assertQuery("Limp bizkit", "Take a look around", "♪ Limp bizkit - Take a look around")
    }

    @Test
    fun `ripped video titles read the same as in CapulloTube`() {
        assertQuery("Limp Bizkit", "Break Stuff", "Limp Bizkit - Break Stuff (Official Music Video)")
        assertQuery("Amr Diab", "Nour El Ein", "Amr Diab - Nour El Ein | Official Music Video - HD Version | عمرو دياب - نور العين")
        assertQuery(
            "Clean Bandit",
            "Rockabye (feat. Sean Paul & Anne-Marie)",
            "Clean Bandit - Rockabye (feat. Sean Paul & Anne-Marie) [Official Video]",
        )
        assertQuery("The Chainsmokers", "Closer ft. Halsey", "The Chainsmokers - Closer (Lyric) ft. Halsey")
        assertQuery("Sia", "Unstoppable", "Sia - Unstoppable (Official Video - Live from the Nostalgic For The Present Tour)")
        assertQuery("Sia", "Cheap Thrills (Performance Edit)", "Sia - Cheap Thrills (Performance Edit)")
        assertQuery("王識賢", "愛到無命不知驚", "王識賢《愛到無命不知驚》官方MV│金曲獎歌曲")
    }

    @Test
    fun `the performer is the artist, and a title that repeats it loses that side`() {
        assertQuery("Queen", "Bohemian Rhapsody", "Bohemian Rhapsody", performer = "Queen")
        assertQuery("Queen", "Bohemian Rhapsody", "Queen - Bohemian Rhapsody", performer = "Queen")
        assertQuery("Linkin Park", "Faint", "Faint (Official Music Video) [4K UPGRADE] – Linkin Park", performer = "Linkin Park")
        assertQuery("Linkin Park", "In The End", "In The End [Official HD Music Video] - Linkin Park", performer = "Linkin Park")
        assertQuery("Imagine Dragons", "Natural", "Imagine Dragons - Natural", performer = "ImagineDragons")
        assertQuery("The Smashing Pumpkins", "1979", "1979", performer = "The Smashing Pumpkins")
    }

    @Test
    fun `after a known performer a dash starts a version, which stays unless it is a remaster`() {
        assertQuery("The Beatles", "Here Comes The Sun - 2019 Mix", "Here Comes The Sun - 2019 Mix", performer = "The Beatles")
        assertQuery("Queen", "Bohemian Rhapsody", "Bohemian Rhapsody - Remastered 2011", performer = "Queen")
        assertQuery("David Bowie", "Heroes", "Heroes - 2017 Remaster", performer = "David Bowie")
        assertQuery("Fleetwood Mac", "Dreams", "Dreams (2004 Remaster)", performer = "Fleetwood Mac")
        assertQuery("Nirvana", "Smells Like Teen Spirit (Live at Reading 1992)", "Smells Like Teen Spirit (Live at Reading 1992)", performer = "Nirvana")
    }

    @Test
    fun `featured artists go from the artist, not from the song`() {
        assertQuery("Daft Punk", "Get Lucky", "Get Lucky", performer = "Daft Punk feat. Pharrell Williams")
        assertQuery("Clean Bandit", "Rockabye", "Rockabye", performer = "Clean Bandit; Sean Paul; Anne-Marie")
        assertQuery("Calvin Harris", "One Kiss", "One Kiss", performer = "Calvin Harris / Dua Lipa")
        assertQuery("Clean Bandit", "Rockabye", "Clean Bandit ft. Sean Paul - Rockabye")
        assertQuery("AC/DC", "Thunderstruck", "Thunderstruck", performer = "AC/DC")
        assertQuery("Simon & Garfunkel", "The Boxer", "The Boxer", performer = "Simon & Garfunkel")
    }

    @Test
    fun `with no title the file name is read, and a title that is a file name is read as one`() {
        assertQuery("The Frightnrs", "Gotta Find a Way", null, fileName = "12 - The_Frightnrs - Gotta Find a Way.mimarido11.m4a")
        assertQuery("Queen", "Bohemian Rhapsody", null, performer = "Queen", fileName = "Bohemian Rhapsody.mp3")
        assertQuery("Massive Attack", "Teardrop", "01 - Massive Attack - Teardrop.mp3")
    }

    @Test
    fun `placeholders count as missing`() {
        assertQuery("Portishead", "Roads", "Track 01", fileName = "Portishead - Roads.flac")
        assertQuery(null, "Roads", "Roads", performer = "Unknown Artist")
        assertQuery(null, "Roads", "Roads", performer = "Artist")
        assertQuery("Portishead", "Roads", "Title", performer = "Portishead", fileName = "Roads.mp3")
        assertQuery("Portishead", "Roads", "Portishead - Roads", performer = "<unknown>")
        assertNull(LyricsQuery.of("Track 01", "Unknown Artist", "AUD-20230101-WA0001.mp3"))
        assertNull(LyricsQuery.of("Untitled", "VA", null))
    }

    @Test
    fun `no song left gives no query`() {
        assertNull(LyricsQuery.of("(Official Video)", null, null))
        assertNull(LyricsQuery.of("🤘🎤", "Someone", null))
        assertNull(LyricsQuery.of(null, "Queen", null))
        assertNull(LyricsQuery.of("  ", null, null))
    }

    @Test
    fun `the search text is artist then song, or the song alone`() {
        assertEquals("張學友 聽海", LyricsQuery("張學友", "聽海").searchText)
        assertEquals("聽海", LyricsQuery(null, "聽海").searchText)
    }
}
