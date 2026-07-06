package tech.capullo.telecloudradio

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.playlist.ActiveTrackRepository
import tech.capullo.telecloudradio.data.playlist.PlaybackCommand
import tech.capullo.telecloudradio.player.PlaybackService
import javax.inject.Inject
import kotlin.coroutines.resume

@HiltViewModel
class AppViewModel @Inject constructor(
    private val activeTrackRepository: ActiveTrackRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val activePlayback = activeTrackRepository.activePlayback

    private var controller: MediaController? = null

    init {
        viewModelScope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            controller = suspendCancellableCoroutine { cont ->
                val future = MediaController.Builder(context, token).buildAsync()
                future.addListener({
                    runCatching { cont.resume(future.get()) }
                }, context.mainExecutor)
                cont.invokeOnCancellation { future.cancel(true) }
            }
        }
    }

    /** Returns (chatId, chatTitle) to auto-open if the user has the setting on. */
    fun getAutoOpenDestination(): Pair<Long, String>? {
        if (!settings.rememberLastGroup) return null
        val id = settings.lastGroupId
        val title = settings.lastGroupTitle
        return if (id != -1L && title.isNotBlank()) Pair(id, title) else null
    }

    fun saveLastGroup(chatId: Long, chatTitle: String) {
        settings.lastGroupId = chatId
        settings.lastGroupTitle = chatTitle
    }

    fun playPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() {
        activeTrackRepository.emitCommand(PlaybackCommand.NEXT)
    }
    fun prev() {
        activeTrackRepository.emitCommand(PlaybackCommand.PREV)
    }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}
