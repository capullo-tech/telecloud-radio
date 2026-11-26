package tech.capullo.telecloudradio.data.db

import androidx.room.Entity

// Lyrics found for a track, or that none were (kind "none", looked up again after a few days).
// Keyed like track_gain by the Telegram (chatId, messageId) pair: lyrics belong to the track, not
// to the cached file, so they survive cache eviction and re-downloads, and go with the track (a
// message deleted from its chat, or a library wipe).
@Entity(tableName = "lyrics", primaryKeys = ["chatId", "messageId"])
data class LyricsEntity(
    val chatId: Long,
    val messageId: Long,
    // "synced" (LRC text) | "plain" | "none".
    val kind: String,
    // LyricsSource.logName; null for "none".
    val source: String?,
    val text: String?,
    val fetchedAt: Long,
    // The per-song nudge from the lyrics view, in ms; positive shows lines later.
    val offsetMs: Long = 0,
)
