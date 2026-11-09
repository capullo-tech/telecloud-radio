package tech.capullo.telecloudradio.data.db

import androidx.room.Entity

// Deliberately a separate table from audio_analysis, not touched by DownloadManager.evict()'s
// per-file cleanup: a computed gain is a property of the audio content (keyed by the stable
// Telegram (chatId, messageId) pair), not of the locally cached file, so it survives cache
// eviction and doesn't need recomputing when a track is re-downloaded later. Rows go when the
// TRACK goes: a message deleted from its chat (TelegramRepository.removeTracks) or a library
// wipe (wipeLibrary).
@Entity(tableName = "track_gain", primaryKeys = ["chatId", "messageId"])
data class TrackGainEntity(
    val chatId: Long,
    val messageId: Long,
    // Linear multiplier GainAudioProcessor applies directly (1.0 = no change) - pre-converted
    // from dB so playback never redoes that math per buffer.
    val gainLinear: Float,
    // The loudness this gain was computed from (the tag's implied value, or our own measured
    // integrated LUFS), for the stats UI / diagnostics - not used by playback itself.
    val measuredLufs: Float,
    // "REPLAYGAIN_TAG" | "COMPUTED_LUFS" | "FAILED" (undecodable: unity gain, FLOOR_DB loudness, kept
    // so the file isn't re-analyzed on every play) - which path produced this row, so a future change in
    // path priority (e.g. preferring tags even over a stale computed value) needs no migration.
    val source: String,
    val computedAt: Long,
)
