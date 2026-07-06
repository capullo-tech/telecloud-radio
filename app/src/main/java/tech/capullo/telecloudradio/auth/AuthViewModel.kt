package tech.capullo.telecloudradio.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.capullo.telecloudradio.data.credentials.CredentialsRepository
import tech.capullo.telecloudradio.data.telegram.TelegramRepository
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: TelegramRepository,
    private val credentials: CredentialsRepository,
) : ViewModel() {

    val authState = repository.authState

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun submitCredentials(apiId: String, apiHash: String) {
        val id = apiId.trim().toIntOrNull()
        if (id == null || id == 0) {
            _error.value = "API ID must be a non-zero number"
            return
        }
        if (apiHash.isBlank()) {
            _error.value = "API Hash cannot be empty"
            return
        }
        credentials.save(id, apiHash.trim())
        repository.setupParameters()
    }

    fun submitPhone(phone: String) = viewModelScope.launch {
        runCatching { repository.setPhoneNumber(phone) }
            .onFailure { _error.value = it.message }
    }

    fun submitCode(code: String) = viewModelScope.launch {
        runCatching { repository.checkCode(code) }
            .onFailure { _error.value = it.message }
    }

    fun submitPassword(password: String) = viewModelScope.launch {
        runCatching { repository.checkPassword(password) }
            .onFailure { _error.value = it.message }
    }

    fun clearError() {
        _error.value = null
    }
}
