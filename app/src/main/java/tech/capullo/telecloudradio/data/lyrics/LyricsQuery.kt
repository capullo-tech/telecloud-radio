package tech.capullo.telecloudradio.data.lyrics

import tech.capullo.telecloudradio.util.hasAudioExtension
import tech.capullo.telecloudradio.util.trackNameFromFileName
import java.util.Locale

data class LyricsQuery(val artist: String?, val title: String) {

    val searchText: String get() = listOfNotNull(artist, title).joinToString(" ")

    companion object {

        /**
         * The artist and song to ask LRCLIB for, from what a Telegram track carries: a title and a
         * performer (the file's tags when it has them, else Telegram's audio attributes) and the
         * file name. Telegram audio often has no performer, "Artist - Title" in the title, or no
         * title at all, so:
         * - The name is the title, else the file name without its extension, uploader and video-id
         *   tokens and track number. A title that is itself a file name is read as one.
         * - Placeholders that taggers and recorders fill in ("Unknown Artist", "Track 01",
         *   "AUD-20230101-WA0001") count as missing.
         * - Packaging ("(Official Video)", "| channel"), remaster tags ("(2011 Remaster)",
         *   "- Remastered 2009") and featured artists after "feat." in the artist go: LRCLIB names
         *   the song and its main artist.
         * - With a performer, the performer is the artist. The name is split ("Artist - Song",
         *   "Artist: Song", CJK brackets and the other shapes in [split]) only to drop a side that
         *   repeats the performer, which also turns a "Song - Artist" round. Otherwise the whole
         *   name is the song: after a known artist a dash usually starts a version ("Song - Live").
         * - Without a performer, the split names the artist. A name that does not split is a song
         *   alone, which LRCLIB's search can still find by its duration.
         * Null when no song with a letter or digit is left: there is nothing to look up.
         */
        fun of(title: String?, performer: String?, fileName: String?): LyricsQuery? {
            val performerArtist = performer?.let(::tidyArtist)?.takeUnless(::isPlaceholder)
            val name = title?.trim()?.takeUnless(::isPlaceholder)
                ?.let { if (hasAudioExtension(it)) trackNameFromFileName(it) else it }
                ?.takeUnless(::isPlaceholder)
                ?: trackNameFromFileName(fileName)?.takeUnless(::isPlaceholder)
                ?: return null
            val text = REMASTER_SUFFIX.replace(TitleCleaner.clean(name), "")
            val split = split(text)
            val (artist, songText) = when {
                performerArtist == null ->
                    split?.first?.let(::tidyArtist)?.takeUnless(::isPlaceholder) to (split?.second ?: text)

                split == null -> performerArtist to text

                TitleCleaner.sameArtist(tidyArtist(split.first), performerArtist) ->
                    tidyArtist(split.first) to split.second

                TitleCleaner.sameArtist(tidyArtist(split.second), performerArtist) ->
                    tidyArtist(split.second) to split.first

                else -> performerArtist to text
            }
            val song = tidySong(songText).takeUnless(::isPlaceholder) ?: return null
            return LyricsQuery(artist, song)
        }

        // Video-site title shapes, which ripped tracks keep in their tags and file names.
        private fun split(text: String): Pair<String, String>? {
            bracketed(CJK_BRACKET, text)?.let { return it }
            bracketed(SPACED_BRACKET, text)?.let { return it }
            TitleCleaner.splitAtDash(text)?.let { return it }
            MIDDLE_DOT.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
            TIGHT_DASH.find(text)?.takeIf { isCjk(it.groupValues[2].first()) }
                ?.let { return it.groupValues[1] to it.groupValues[2] }
            QUOTED.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
            COLON.find(text)
                ?.takeIf { it.groupValues[1].trim().split(SPACES).size <= COLON_ARTIST_MAX_WORDS }
                ?.let { return it.groupValues[1] to it.groupValues[2] }
            return cjkNameAndSong(text)
        }

        private fun bracketed(shape: Regex, text: String): Pair<String, String>? {
            val match = shape.find(text) ?: return null
            val artist = match.groupValues[1].trimEnd(*TRAILING_SEPARATORS)
            return if (artist.any(Char::isLetterOrDigit)) artist to match.groupValues[2] else null
        }

        private fun cjkNameAndSong(text: String): Pair<String, String>? {
            val words = text.trim().split(SPACES)
            if (words.size != 2 || !words.all { word -> word.all(::isCjk) }) return null
            return if (words[0].length in CJK_NAME_LENGTH) words[0] to words[1] else null
        }

        // The main artist: "Daft Punk feat. Pharrell" and "Clean Bandit; Sean Paul" are Daft Punk
        // and Clean Bandit. Only ';' and a spaced '/' list artists: "AC/DC" and "Simon & Garfunkel"
        // are one name.
        private fun tidyArtist(raw: String): String {
            val main = FEATURING.replace(raw, "").split(ARTIST_LIST).first()
            val trimmed = main.trim().trimEnd(*TRAILING_SEPARATORS)
            return dropLatinAlias(trimmed.dropWhile { !it.isLetterOrDigit() })
        }

        private fun tidySong(raw: String): String = dropLatinAlias(
            TitleCleaner.stripTaggedGroups(raw, ::isLookupPackaging),
        )
            .trim()
            .trimEnd(*TRAILING_SEPARATORS)

        private fun isLookupPackaging(word: String): Boolean = word in LATIN_PACKAGING ||
            CJK_PACKAGING.any { it in word }

        private fun isPlaceholder(text: String): Boolean {
            if (text.none(Char::isLetterOrDigit)) return true
            return text.lowercase(Locale.ROOT).filter(Char::isLetter) in PLACEHOLDERS
        }

        // "周杰倫 Jay Chou" is 周杰倫: LRCLIB lists a CJK artist or song under its own script.
        private fun dropLatinAlias(text: String): String {
            val lastCjk = text.indexOfLast(::isCjk)
            if (lastCjk < 0) return text
            val tail = text.substring(lastCjk + 1)
            return if (tail.firstOrNull()?.isWhitespace() == true && tail.any(Char::isLetter)) {
                text.substring(0, lastCjk + 1)
            } else {
                text
            }
        }

        private fun isCjk(c: Char): Boolean = when (c) {
            in '\u1100'..'\u11FF',
            in '\u2E80'..'\u2EFF',
            in '\u2F00'..'\u2FD5',
            '\u3005',
            '\u3007',
            in '\u3021'..'\u3029',
            in '\u302E'..'\u302F',
            in '\u3038'..'\u303B',
            in '\u3041'..'\u3096',
            in '\u309D'..'\u309F',
            in '\u30A1'..'\u30FA',
            in '\u30FD'..'\u30FF',
            in '\u3131'..'\u318E',
            in '\u31F0'..'\u31FF',
            in '\u3200'..'\u321E',
            in '\u3260'..'\u327E',
            in '\u32D0'..'\u32FE',
            in '\u3300'..'\u3357',
            in '\u3400'..'\u4DBF',
            in '\u4E00'..'\u9FFF',
            in '\uA960'..'\uA97F',
            in '\uAC00'..'\uD7A3',
            in '\uD7B0'..'\uD7FF',
            in '\uF900'..'\uFAFF',
            in '\uFF66'..'\uFF6F',
            in '\uFF71'..'\uFF9D',
            in '\uFFA0'..'\uFFDC',
            -> true

            else -> false
        }

        private val CJK_NAME_LENGTH = 2..4

        private const val COLON_ARTIST_MAX_WORDS = 4

        // Matched against the letters of a name, so "Track 01" and "AUD-20230101-WA0001" match.
        private val PLACEHOLDERS = setOf(
            "unknown",
            "unknownartist",
            "unknowntitle",
            "untitled",
            "notitle",
            "noartist",
            "artist",
            "title",
            "track",
            "audio",
            "aud",
            "audwa",
            "voice",
            "recording",
            "newrecording",
            "various",
            "variousartists",
            "va",
        )

        private val LATIN_PACKAGING = setOf(
            "from",
            "explicit",
            "remaster",
            "remastered",
            "remasterised",
            "remasterizado",
            "remasterizada",
        )

        private val CJK_PACKAGING = listOf(
            "官方",
            "完整版",
            "歌詞",
            "歌词",
            "中字",
            "字幕",
            "主題曲",
            "主题曲",
            "插曲",
            "電影",
            "电影",
        )

        private val REMASTER_SUFFIX = Regex(
            """\s+[-–—]\s+(?:(?:19|20)\d\d\s+)?(?:digital(?:ly)?\s+)?remaster(?:ed|ised|izad[oa])?""" +
                """(?:\s+(?:version|edition))?(?:\s+(?:19|20)\d\d)?\s*$""",
            RegexOption.IGNORE_CASE,
        )

        private val FEATURING = Regex("""(?:^|\s)[(\[]?(?:feat|ft|featuring)\b\.?\s.*$""", RegexOption.IGNORE_CASE)

        private val ARTIST_LIST = Regex("""\s*;\s*|\s+/\s+""")

        private val CJK_BRACKET = Regex("""^(.*?)\s*[《【]\s*([^》】]+?)\s*[》】]""")

        private val SPACED_BRACKET = Regex("""^(.+?)\s+\[\s+([^\]]+?)\s+]""")

        private val MIDDLE_DOT = Regex("""^(.+?)\s+[‧·・]\s+(.+)$""")

        private val TIGHT_DASH = Regex("""^(.+?)\s+[-–—](\S.*)$""")

        private val QUOTED = Regex("""^(.+?),?\s+["“](.+?)["”]""")

        private val COLON = Regex("""^([^:()\[\]【】]+):\s+(.+)$""")

        private val SPACES = Regex("""\s+""")

        private val TRAILING_SEPARATORS = charArrayOf(' ', '-', '–', '—', ':', '|', '~', ',')
    }
}
