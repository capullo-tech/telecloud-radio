package tech.capullo.telecloudradio.data.lyrics

import java.util.Locale

// Title clean-up for the lyrics lookup, from CapulloTube's cleaner. Telegram music chats are full
// of tracks ripped from video sites, so tags and file names often still carry "(Official Video)",
// "[HD]" or a "| channel" tail. Version markers ("(Live at ...)", "(2019 Mix)") stay: they name a
// different recording.
internal object TitleCleaner {

    // Drops bracketed groups made only of packaging words and everything after a '|'. Never blank:
    // a title that is nothing but packaging comes back as it was.
    fun clean(title: String): String = stripGroups(title) { words -> words.all { it in NOISE_WORDS } }
        .substringBefore('|')
        .let(::tidy)
        .ifBlank { title }

    // Drops bracketed groups that hold any packaging word, or any word [isPackaging] accepts.
    fun stripTaggedGroups(text: String, isPackaging: (String) -> Boolean): String {
        val stripped = stripGroups(text) { words ->
            words.any { it in NOISE_WORDS || isPackaging(it) }
        }
        return tidy(stripped)
    }

    // "Artist - Song" at the first spaced dash.
    fun splitAtDash(title: String): Pair<String, String>? {
        val match = ARTIST_PREFIX.find(title) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    // Same name ignoring case, spaces and punctuation: "ImagineDragons" is "Imagine Dragons".
    fun sameArtist(name: String, artist: String?): Boolean {
        val key = nameKey(name)
        return artist != null && key.isNotEmpty() && key == nameKey(artist)
    }

    // Inside out, so "(Official Video (HD))" goes in two passes.
    private fun stripGroups(title: String, drop: (List<String>) -> Boolean): String {
        var text = title
        while (true) {
            val next = GROUP.replace(text) {
                val words = WORD.findAll(it.groupValues[1].lowercase(Locale.ROOT))
                    .map { word -> word.value }
                    .toList()
                if (words.isNotEmpty() && drop(words)) " " else it.value
            }
            if (next == text) return text
            text = next
        }
    }

    private fun nameKey(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun tidy(text: String): String = text.replace(SPACES, " ").trim().replace(DANGLING, "")

    private val NOISE_WORDS = setOf(
        "official",
        "oficial",
        "officiel",
        "music",
        "video",
        "videoclip",
        "clip",
        "audio",
        "mv",
        "m/v",
        "lyric",
        "lyrics",
        "letra",
        "visualizer",
        "visualiser",
        "hd",
        "hq",
        "4k",
    )

    private val GROUP = Regex("""[(\[【]([^()\[\]【】]*)[)\]】]""")

    private val WORD = Regex("""[\p{L}\p{N}/]+""")

    private val ARTIST_PREFIX = Regex("""^(.+?)\s+[-–—]\s+(.+)$""")

    private val SPACES = Regex("""\s+""")

    private val DANGLING = Regex("""[\s\-–—|:,]+$""")
}
