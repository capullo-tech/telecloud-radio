package tech.capullo.telecloudradio.auth

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.capullo.telecloudradio.data.credentials.CredentialsRepository
import tech.capullo.telecloudradio.data.telegram.TelegramRepository
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TelegramRepository,
    private val credentials: CredentialsRepository,
) : ViewModel() {

    val authState = repository.authState

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    // Set when Telegram rejects the saved API ID/Hash. This only surfaces at the PHONE step (TDLib
    // validates the api_id there, not at SetTdlibParameters), by which point authState has already
    // advanced to WaitPhone and the credentials form is no longer shown - stranding the user. The
    // screen shows a recovery dialog on this flag whose action re-enters the credentials flow.
    private val _credentialsInvalid = MutableStateFlow(false)
    val credentialsInvalid = _credentialsInvalid.asStateFlow()

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
                if (isCredentialError(e)) {
                    _credentialsInvalid.value = true
                } else {
                    _error.value = e.message
                }
            } finally {
                _submitting.value = false
            }
        }
    }

    // Telegram reports bad API credentials as API_ID_INVALID (and, when the same public api_id is
    // over-used, API_ID_PUBLISHED_FLOOD) - matched on the message since it arrives as a generic
    // TelegramException. These are unrecoverable in place, hence the credentials-reset path.
    private fun isCredentialError(e: Throwable): Boolean {
        val msg = e.message?.uppercase() ?: return false
        return "API_ID_INVALID" in msg || "API_ID_PUBLISHED_FLOOD" in msg
    }

    // Recovery for a stranded WaitPhone-with-bad-creds session: drop the saved credentials and
    // restart the process. A fresh start with no credentials returns TDLib to WaitTdlibParameters →
    // the credentials form. This is the app-only reset - TDLib can't re-run SetTdlibParameters in
    // process once past it, and TelegramClient exposes no logout. No DB wipe: api_id is a per-launch
    // SetTdlibParameters argument (not baked into the local DB) and no session ever completed.
    fun resetCredentials() {
        credentials.clear()
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            ?.let { context.startActivity(it) }
        Runtime.getRuntime().exit(0)
    }

    fun clearError() {
        _error.value = null
    }
}
