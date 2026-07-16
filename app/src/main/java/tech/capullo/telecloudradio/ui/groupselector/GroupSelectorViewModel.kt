package tech.capullo.telecloudradio.ui.groupselector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tech.capullo.source.telegram.data.telegram.TelegramChat
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.telegram.TelegramRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

sealed class GroupSelectorUiState {
    data object Loading : GroupSelectorUiState()
    data class Loaded(val chats: List<TelegramChat>) : GroupSelectorUiState()
    data class Syncing(val chat: TelegramChat) : GroupSelectorUiState()
    data class SyncDone(
        val chat: TelegramChat,
        val newCount: Int,
        val totalTracks: Int,
        val totalGb: Double,
    ) : GroupSelectorUiState()
    data class Error(val message: String) : GroupSelectorUiState()
}

@HiltViewModel
class GroupSelectorViewModel @Inject constructor(
    private val repository: TelegramRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GroupSelectorUiState>(GroupSelectorUiState.Loading)
    val uiState = _uiState.asStateFlow()

    // chatId → local path of the downloaded crisp avatar. Survives LazyColumn scroll/recompose so
    // avatars aren't re-fetched every time a row scrolls back into view.
    private val photoPathCache = ConcurrentHashMap<Long, String>()

    init {
        // Initial load with the current value, then reload live whenever the "Stations" setting
        // changes - so the list repopulates immediately instead of only on (re)entry (E2). drop(1)
        // skips the StateFlow's replayed current value (already loaded here); debounce coalesces the
        // rapid steps of the settings stepper into a single re-query.
        loadGroups()
        viewModelScope.launch {
            settings.stationLimit
                .drop(1)
                .debounce(400)
                .collect { loadGroups(it) }
        }
    }

    // Returns the local path to the crisp avatar for [chat], downloading it once and caching per
    // chatId. Null when the chat has no photo or the download fails (caller keeps the placeholder).
    suspend fun chatPhotoPath(chat: TelegramChat): String? {
        photoPathCache[chat.id]?.let { return it }
        val fileId = chat.photoFileId ?: return null
        val path = repository.downloadChatPhoto(fileId)
            ?.takeIf { it.isNotEmpty() && File(it).exists() }
            ?: return null
        photoPathCache[chat.id] = path
        return path
    }

    fun loadGroups(limit: Int = settings.stationLimit.value) = viewModelScope.launch {
        _uiState.value = GroupSelectorUiState.Loading
        runCatching { repository.getAudioGroups(limit) }
            .onSuccess { _uiState.value = GroupSelectorUiState.Loaded(it) }
            .onFailure {
                _uiState.value =
                    GroupSelectorUiState.Error(it.message ?: "Failed to load groups")
            }
    }

    fun backToList() = loadGroups()

    fun selectGroup(chat: TelegramChat) = viewModelScope.launch {
        _uiState.value = GroupSelectorUiState.Syncing(chat)
        runCatching { repository.syncAudioMessages(chat) }
            .onSuccess { newCount ->
                val totalTracks = repository.getTrackCount(chat.id)
                val totalGb = repository.getTotalSize(chat.id) / (1024.0 * 1024.0 * 1024.0)
                _uiState.value = GroupSelectorUiState.SyncDone(chat, newCount, totalTracks, totalGb)
            }
            .onFailure { _uiState.value = GroupSelectorUiState.Error(it.message ?: "Sync failed") }
    }
}
