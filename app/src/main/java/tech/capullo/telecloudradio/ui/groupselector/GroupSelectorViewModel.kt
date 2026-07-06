package tech.capullo.telecloudradio.ui.groupselector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.capullo.telecloudradio.data.telegram.TelegramChat
import tech.capullo.telecloudradio.data.telegram.TelegramRepository
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
class GroupSelectorViewModel @Inject constructor(private val repository: TelegramRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<GroupSelectorUiState>(GroupSelectorUiState.Loading)
    val uiState = _uiState.asStateFlow()

    init {
        loadGroups()
    }

    fun loadGroups(limit: Int = 20) = viewModelScope.launch {
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
