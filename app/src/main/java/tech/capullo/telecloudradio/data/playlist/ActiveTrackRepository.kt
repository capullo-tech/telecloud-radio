package tech.capullo.telecloudradio.data.playlist

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.capullo.telecloudradio.data.db.MediaMessageEntity
import javax.inject.Inject
import javax.inject.Singleton

// GAPLESS_ADVANCED: emitted by PlaybackService once a crossfade handoff completes (the new
// player is now the audible one) - PlayerViewModel's handler is the same state-sync code path
// ExoPlayer's own native gapless auto-advance uses (see PlayerViewModel.onGaplessAdvance),
// because from the VM's perspective both cases are identical: "the staged next track is now
// actually playing, sync currentIndex/UI/metadata to it."
enum class PlaybackCommand { NEXT, PREV, GAPLESS_ADVANCED }

data class ActivePlayback(
    val track: MediaMessageEntity,
    val chatId: Long,
    val chatTitle: String,
    val albumArt: ByteArray? = null,
    val isPlaying: Boolean = false,
)

@Singleton
class ActiveTrackRepository @Inject constructor() {
    private val _activePlayback = MutableStateFlow<ActivePlayback?>(null)
    val activePlayback: StateFlow<ActivePlayback?> = _activePlayback.asStateFlow()

    private val _command = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 1)
    val command: SharedFlow<PlaybackCommand> = _command.asSharedFlow()

    // One-off user-facing notices raised from non-UI layers (e.g. PlaybackService's silence
    // watchdog skipping a track that decodes to silence). PlayerViewModel forwards these to the
    // player screen's snackbar.
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun set(track: MediaMessageEntity, chatId: Long, chatTitle: String) {
        _activePlayback.value = ActivePlayback(track, chatId, chatTitle)
    }

    fun updateAlbumArt(albumArt: ByteArray?) {
        _activePlayback.value = _activePlayback.value?.copy(albumArt = albumArt)
    }

    // Replace the track with the tag-resolved one (title/artist read from the file after
    // download) so the snapcast/web now-playing shows the real title/artist, not the filename.
    fun updateTrack(track: MediaMessageEntity) {
        _activePlayback.value = _activePlayback.value?.copy(track = track)
    }

    fun updateIsPlaying(isPlaying: Boolean) {
        _activePlayback.value = _activePlayback.value?.copy(isPlaying = isPlaying)
    }

    fun emitCommand(cmd: PlaybackCommand) {
        _command.tryEmit(cmd)
    }

    fun emitMessage(message: String) {
        _messages.tryEmit(message)
    }

    fun clear() {
        _activePlayback.value = null
    }
}
