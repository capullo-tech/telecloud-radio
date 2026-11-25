package tech.capullo.telecloudradio.data.lyrics

import tech.capullo.audio.snapcast.Group

object LyricsTiming {

    // The player's position is what ExoPlayer hands the snapserver FIFO; snapserver holds that
    // much audio before any client plays it (snapcast's default buffer, which capullo-audio keeps).
    const val SNAPSERVER_BUFFER_MS = 1_000L

    const val SONG_NUDGE_STEP_MS = 500L

    // How far behind the player's position this device's speaker is. The local snapclient plays a
    // configured latency earlier than the buffer, so that latency comes off. Not playing through a
    // local client (none connected yet, or the speaker released to another app) leaves the buffer.
    // The local client is found the way SnapcastManager finds it: a second snapclient instance on
    // the same host carries the host id with a suffix.
    fun autoDelayMs(groups: List<Group>, localClientId: String): Long {
        if (localClientId.isEmpty()) return SNAPSERVER_BUFFER_MS
        val local = groups.asSequence()
            .flatMap { it.clients }
            .firstOrNull { it.connected && (it.id == localClientId || it.id.contains(localClientId)) }
        return SNAPSERVER_BUFFER_MS - (local?.config?.latency ?: 0)
    }

    fun lineIndex(lyrics: Lyrics, positionMs: Long, offsetMs: Long): Int = if (lyrics.synced) {
        Lrc.currentLineIndex(lyrics.lines, positionMs - offsetMs)
    } else {
        -1
    }

    // The inverse of lineIndex: seeking here highlights the tapped line at once.
    fun seekTarget(lineTimeMs: Long, offsetMs: Long): Long = (lineTimeMs + offsetMs).coerceAtLeast(0)
}
