package tech.capullo.telecloudradio.auth

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.capullo.source.telegram.data.telegram.AuthState
import tech.capullo.telecloudradio.data.credentials.CredentialsRepository
import tech.capullo.telecloudradio.data.credentials.MyTelegramOrgClient
import tech.capullo.telecloudradio.data.credentials.MyTelegramOrgException
import tech.capullo.telecloudradio.data.telegram.TelegramRepository
import javax.inject.Inject
import javax.inject.Provider

// Steps of fetching the API ID/Hash before TDLib can start. Phone + Code sign in to my.telegram.org and
// read the credentials automatically; Manual is the fallback form for when that site fails.
enum class SetupStep { Phone, Code, Manual }

// my.telegram.org's own page waits this long before offering "Resend code".
internal const val SETUP_RESEND_SECONDS = 42

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TelegramRepository,
    private val credentials: CredentialsRepository,
    private val myTelegramOrgProvider: Provider<MyTelegramOrgClient>,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    val authState = repository.authState

    // Phone + random_hash live in SavedStateHandle: the user leaves the app to read the code in
    // Telegram, and the process may be killed meanwhile. The session cookie is in-memory only, so
    // after such a kill the code may be rejected and the user resends from the code step.
    val setupStep = savedState.getStateFlow(KEY_STEP, SetupStep.Phone)
    val setupPhone = savedState.getStateFlow(KEY_PHONE, "")
    val setupCodeSentAt = savedState.getStateFlow(KEY_CODE_SENT_AT, 0L)

    // Phone handed from the my.telegram.org step to TDLib; while set, the screen shows progress
    // instead of a phone form (TDLib passes through WaitPhone before the auto-submit lands).
    val autoPhonePending = savedState.getStateFlow<String?>(KEY_TDLIB_PHONE, null)

    // "Change number" on the Telegram code screen: TDLib accepts a new phone while in WaitCode.
    private val _changingNumber = MutableStateFlow(false)
    val changingNumber = _changingNumber.asStateFlow()

    private var myTelegramOrg: MyTelegramOrgClient? = null

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

    init {
        viewModelScope.launch {
            authState.collect { Log.d(TAG, "auth state: ${it::class.simpleName}") }
        }
        // Once the fetched credentials start TDLib it asks for the phone again: answer with the one
        // already given to my.telegram.org. Waits for !submitting so a still-finishing setup step
        // can't swallow it through submit()'s double-tap guard; consumed once, so a TDLib rejection
        // leaves the user on the (prefilled) phone form.
        viewModelScope.launch {
            combine(authState, _submitting) { state, busy -> state is AuthState.WaitPhone && !busy }
                .collect { ready ->
                    if (!ready) return@collect
                    val phone = savedState.get<String>(KEY_TDLIB_PHONE) ?: return@collect
                    Log.d(TAG, "auto-submitting phone to TDLib")
                    submit(onFinish = { savedState.remove<String>(KEY_TDLIB_PHONE) }) {
                        repository.setPhoneNumber(phone)
                    }
                }
        }
        // Escape from the spinner if TDLib never reaches WaitPhone: drop the pending phone so the
        // (prefilled) phone form shows instead. collectLatest cancels the timer once it is consumed.
        viewModelScope.launch {
            autoPhonePending.collectLatest { phone ->
                if (phone == null) return@collectLatest
                delay(AUTO_PHONE_TIMEOUT_MS)
                Log.w(TAG, "TDLib did not ask for the phone in time; showing the phone form")
                savedState.remove<String>(KEY_TDLIB_PHONE)
            }
        }
    }

    fun submitSetupPhone(rawPhone: String) = submit { sendSetupCode(normalizePhone(rawPhone)) }

    fun resendSetupCode() = submit { sendSetupCode(setupPhone.value) }

    // A fresh my.telegram.org session per sent code, so a resend never reuses a logged-in cookie jar.
    private suspend fun sendSetupCode(phone: String) {
        closeClient()
        val randomHash = client().sendCode(phone)
        Log.d(TAG, "my.telegram.org code sent")
        savedState[KEY_PHONE] = phone
        savedState[KEY_RANDOM_HASH] = randomHash
        savedState[KEY_CODE_SENT_AT] = SystemClock.elapsedRealtime()
        savedState[KEY_STEP] = SetupStep.Code
    }

    fun submitSetupCode(code: String) = submit {
        val phone = setupPhone.value
        val randomHash = savedState.get<String>(KEY_RANDOM_HASH).orEmpty()
        // A wrong code throws here and the error dialog keeps the user on the code step. Once
        // accepted the code is spent, so a retry after a failed fetch must not log in again.
        val client = client()
        if (!client.loggedIn) client.login(phone, randomHash, code.trim())
        val fetched = try {
            client.fetchCredentials()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Site change, refused app creation, timeout, no network: the code can't be reused,
            // so go to the manual form rather than leave the user retrying a spent code.
            savedState[KEY_STEP] = SetupStep.Manual
            Log.w(TAG, "my.telegram.org credentials fetch failed", e)
            _error.value = "${e.message}\n\nEnter your API ID and hash manually."
            return@submit
        }
        Log.d(TAG, "API credentials fetched")
        closeClient()
        credentials.save(fetched.apiId, fetched.apiHash)
        savedState[KEY_TDLIB_PHONE] = phone
        repository.setupParameters()
    }

    // Back to the phone step with a fresh my.telegram.org session (wrong number, or resend the code).
    fun restartSetup() {
        closeClient()
        savedState.remove<String>(KEY_RANDOM_HASH)
        savedState[KEY_STEP] = SetupStep.Phone
    }

    fun enterCredentialsManually() {
        savedState[KEY_STEP] = SetupStep.Manual
    }

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
        submit {
            credentials.save(id, apiHash.trim())
            repository.setupParameters()
        }
    }

    fun submitPhone(rawPhone: String) = submit(onFinish = { _changingNumber.value = false }) {
        repository.setPhoneNumber(normalizePhone(rawPhone))
    }

    fun submitCode(code: String) = submit { repository.checkCode(code) }

    fun resendCode() = submit { repository.resendCode() }

    fun changeNumber(changing: Boolean) {
        _changingNumber.value = changing
    }

    fun submitPassword(password: String) = submit { repository.checkPassword(password) }

    // Runs one auth step guarded by [_submitting] so a repeated tap is a no-op until it finishes.
    // onFinish runs after the step either way (before submitting drops), for one-shot state.
    private fun submit(onFinish: () -> Unit = {}, step: suspend () -> Unit) {
        if (_submitting.value) return
        _submitting.value = true
        viewModelScope.launch {
            try {
                step()
            } catch (e: Throwable) {
                if (isCredentialError(e)) {
                    _credentialsInvalid.value = true
                } else {
                    Log.w(TAG, "auth step failed", e)
                    _error.value = userMessage(e)
                }
            } finally {
                onFinish()
                _submitting.value = false
            }
        }
    }

    // Rate limits are the one error users hit in normal use (repeated codes / logins), so give them a
    // wait time when Telegram states one: TDLib reports "Too Many Requests: retry after <seconds>";
    // my.telegram.org only says "Sorry, too many tries" with no duration.
    private fun userMessage(e: Throwable): String {
        val msg = e.message.orEmpty()
        RETRY_AFTER.find(msg)?.groupValues?.get(1)?.toLongOrNull()?.let { seconds ->
            return "Too many attempts. Try again in ${formatWait(seconds)}."
        }
        if ("too many tries" in msg.lowercase()) return "Too many attempts. Try again later."
        return msg.ifBlank { "Something went wrong. Try again." }
    }

    private fun formatWait(seconds: Long): String = when {
        seconds < 90 -> "$seconds seconds"
        seconds < 90 * 60 -> "${(seconds + 59) / 60} minutes"
        else -> "${(seconds + 3599) / 3600} hours"
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
        // clear() uses SharedPreferences.commit() (synchronous) so the wipe is durably on disk before
        // the exit(0) below - an async apply() would race the process kill and usually lose. commit()
        // is disk I/O, so run it off the main thread; the relaunch + exit stay sequenced after it, so
        // durability still holds while the UI thread is never blocked.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { credentials.clear() }
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                ?.let { context.startActivity(it) }
            Runtime.getRuntime().exit(0)
        }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() = closeClient()

    private fun client() = myTelegramOrg ?: myTelegramOrgProvider.get().also { myTelegramOrg = it }

    private fun closeClient() {
        myTelegramOrg?.close()
        myTelegramOrg = null
    }

    // my.telegram.org wants international format with the leading +; users often type spaces/dashes.
    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return "+$digits"
    }

    private companion object {
        const val TAG = "TeleCloudAuth"
        const val AUTO_PHONE_TIMEOUT_MS = 30_000L
        val RETRY_AFTER = Regex("""(?i)retry after (\d+)""")
        const val KEY_CODE_SENT_AT = "setup_code_sent_at"
        const val KEY_STEP = "setup_step"
        const val KEY_PHONE = "setup_phone"
        const val KEY_RANDOM_HASH = "setup_random_hash"
        const val KEY_TDLIB_PHONE = "tdlib_pending_phone"
    }
}
