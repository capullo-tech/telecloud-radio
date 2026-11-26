package tech.capullo.telecloudradio.util

// A track's name read from its file name, for the online lookups (album art, lyrics) when the
// tags carry no title: "12 - The_Frightnrs - Gotta Find a Way.mimarido11.m4a" reads as
// "The Frightnrs - Gotta Find a Way". Trailing dot tokens go while they are an audio extension, a
// Telegram-style uploader tag or a YouTube video id; then underscores become spaces and a leading
// track number goes. Null when nothing is left.
internal fun trackNameFromFileName(fileName: String?): String? {
    if (fileName.isNullOrBlank()) return null
    val parts = fileName.split(".").toMutableList()
    while (parts.size > 1) {
        val last = parts.last().trim()
        if (last.lowercase() in AUDIO_EXTENSIONS ||
            USERNAME.matches(last) ||
            YOUTUBE_ID.matches(last)
        ) {
            parts.removeAt(parts.size - 1)
        } else {
            break
        }
    }
    var base = parts.joinToString(".").replace('_', ' ').trim()
    base = base.replace(LEADING_TRACK_NUMBER, "").trim()
    return base.takeIf { it.isNotBlank() }
}

// True for a name ending in an audio file extension: some uploads carry the file name as the title.
internal fun hasAudioExtension(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "aiff", "aif", "alac", "ape",
)
private val USERNAME = Regex("^[a-zA-Z][a-zA-Z0-9_]{4,31}$")
private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
private val LEADING_TRACK_NUMBER = Regex("^\\d{1,3}[\\s.\\-]+")
