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

    // True while an auth request is in flight. The screen disables the submit button on it so a
    // double-tap can't fire two authorization requests to TDLib ("another authentication is
    // happening"). Reset in finally + when the auth state advances to the next step.
    private val _submitting = MutableStateFlow(false)
    val submitting = _submitting.asStateFlow()

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
        // setupParameters() is idempotent TDLib init (safe on a repeated tap); the guarded
        // submit() below protects the phone/code/password steps where a double-fire is the problem.
        credentials.save(id, apiHash.trim())
        repository.setupParameters()
    }

    fun submitPhone(phone: String) = submit { repository.setPhoneNumber(phone) }

    fun submitCode(code: String) = submit { repository.checkCode(code) }

    fun submitPassword(password: String) = submit { repository.checkPassword(password) }

    // Runs one auth step guarded by [_submitting] so a repeated tap is a no-op until it finishes.
    private fun submit(step: suspend () -> Unit) {
        if (_submitting.value) return
        _submitting.value = true
        viewModelScope.launch {
            try {
                step()
            } catch (e: Throwable) {
                _error.value = e.message
            } finally {
                _submitting.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
