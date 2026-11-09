package tech.capullo.telecloudradio.data

// Identifies a track across stations. TDLib message ids are only unique within one chat (each
// supergroup/channel counts its own), so anything shared between chats keys by both.
data class TrackKey(val chatId: Long, val messageId: Long)
