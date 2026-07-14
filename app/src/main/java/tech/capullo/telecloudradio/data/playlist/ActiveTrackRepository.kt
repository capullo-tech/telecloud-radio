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

enum class PlaybackCommand { NEXT, PREV }

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

    fun clear() {
        _activePlayback.value = null
    }
}
