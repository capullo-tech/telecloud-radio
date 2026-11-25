package tech.capullo.telecloudradio.data.lyrics

// Where a track's lyrics came from. The log name is also what the cache row stores, so renaming
// one orphans the rows that carry it (they read back as no lyrics and are looked up again).
enum class LyricsSource(val logName: String) {
    LRCLIB_GET("lrclib-get"),
    LRCLIB_SEARCH("lrclib-search"),
    ;

    companion object {
        fun fromLogName(name: String): LyricsSource? = entries.firstOrNull { it.logName == name }
    }
}

// timeMs is null for unsynced lyrics; an empty text on a timed line is an instrumental pause.
data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val source: LyricsSource, val synced: Boolean, val lines: List<LyricLine>) {

    companion object {

        // Synced only when every lyric line carries a stamp: a partly stamped LRC would leave the
        // untimed lines stuck between their neighbours, so it is shown as plain text instead.
        fun synced(source: LyricsSource, lrc: String): Lyrics? = Lrc.parse(lrc)
            .takeIf { it.isNotEmpty() && Lrc.isFullyTimed(lrc) }
            ?.let { Lyrics(source, synced = true, lines = it) }

        // Keeps one empty line between stanzas and none at either end.
        fun plain(source: LyricsSource, text: String): Lyrics? {
            val lines = mutableListOf<LyricLine>()
            text.lineSequence().map { it.trim() }.forEach { line ->
                if (line.isNotEmpty() || lines.lastOrNull()?.text?.isNotEmpty() == true) {
                    lines += LyricLine(null, line)
                }
            }
            while (lines.lastOrNull()?.text?.isEmpty() == true) lines.removeAt(lines.lastIndex)
            return if (lines.isEmpty()) null else Lyrics(source, synced = false, lines = lines)
        }
    }
}

object Lrc {

    // One LyricLine per stamp, so a chorus stamped "[00:12.34][01:05.30]" appears at both times.
    // The [offset:] tag is applied here: a positive offset shows every line earlier.
    fun parse(lrc: String): List<LyricLine> {
        val shift = offsetMs(lrc)
        return lrc.lineSequence()
            .flatMap { raw ->
                var rest = raw.trim()
                val times = mutableListOf<Long>()
                while (true) {
                    val stamp = STAMP.find(rest) ?: break
                    times += stampMs(stamp)
                    rest = rest.substring(stamp.range.last + 1)
                }
                times.map { LyricLine((it - shift).coerceAtLeast(0), rest.trim()) }
            }
            .sortedBy { it.timeMs }
            .toList()
    }

    fun isFullyTimed(lrc: String): Boolean = lrc.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !TAG.matches(it) }
        .all { STAMP.containsMatchIn(it) }

    fun plainText(lrc: String): String = lrc.lineSequence()
        .map { it.trim() }
        .filterNot { TAG.matches(it) }
        .joinToString("\n") { STAMPS.replace(it, "").trim() }

    fun offsetMs(lrc: String): Long = lrc.lineSequence().firstNotNullOfOrNull { line ->
        OFFSET.matchEntire(line.trim())?.groupValues?.get(1)?.toLongOrNull()
    } ?: 0

    // The last line at or before positionMs, -1 before the first one or for unsynced lines.
    // Binary search: this runs on every position tick.
    fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val time = lines[mid].timeMs ?: return -1
            if (time <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    private fun stampMs(stamp: MatchResult): Long {
        val (minutes, seconds, fraction) = stamp.destructured
        // "5" is half a second and "05" five hundredths: the fraction is padded, not parsed.
        val ms = fraction.padEnd(3, '0').toLong()
        return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + ms
    }

    private val STAMP = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    private val STAMPS = Regex("""^(?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")

    private val TAG = Regex("""^\[[A-Za-z#][^\]]*]$""")

    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)
}
