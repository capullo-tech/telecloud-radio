package tech.capullo.telecloudradio.auth

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.capullo.source.telegram.data.telegram.AuthState

@Composable
fun AuthScreen(onAuthenticated: () -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val credentialsInvalid by viewModel.credentialsInvalid.collectAsStateWithLifecycle()
    val setupStep by viewModel.setupStep.collectAsStateWithLifecycle()
    val setupPhone by viewModel.setupPhone.collectAsStateWithLifecycle()
    val setupCodeSentAt by viewModel.setupCodeSentAt.collectAsStateWithLifecycle()
    val autoPhonePending by viewModel.autoPhonePending.collectAsStateWithLifecycle()
    val changingNumber by viewModel.changingNumber.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
        if (authState is AuthState.Ready) onAuthenticated()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // One sign-in flow whatever runs underneath: phone -> code (my.telegram.org) -> code
        // (Telegram) -> password. Between the two codes TDLib starts and takes the phone
        // automatically, shown as a spinner rather than a second phone prompt.
        when (val state = authState) {
            is AuthState.WaitParameters, is AuthState.Unknown -> when {
                autoPhonePending != null -> Progress()
                setupStep == SetupStep.Phone ->
                    PhoneForm(
                        initialPhone = setupPhone,
                        onSubmit = viewModel::submitSetupPhone,
                        submitting = submitting,
                        secondaryLabel = "Use API ID and hash",
                        onSecondary = viewModel::enterCredentialsManually,
                    )
                setupStep == SetupStep.Code ->
                    CodeForm(
                        phone = setupPhone,
                        resendAfterSeconds = SETUP_RESEND_SECONDS,
                        sentAtElapsedMs = setupCodeSentAt,
                        onSubmit = viewModel::submitSetupCode,
                        onResend = viewModel::resendSetupCode,
                        onChangeNumber = viewModel::restartSetup,
                        submitting = submitting,
                    )
                else ->
                    CredentialsForm(
                        onSubmit = viewModel::submitCredentials,
                        onBack = viewModel::restartSetup,
                    )
            }
            is AuthState.WaitPhone ->
                if (autoPhonePending != null || submitting) {
                    Progress()
                } else {
                    PhoneForm(
                        initialPhone = setupPhone,
                        onSubmit = viewModel::submitPhone,
                        submitting = submitting,
                        // Escape for saved credentials Telegram won't accept.
                        secondaryLabel = "Change API credentials",
                        onSecondary = viewModel::resetCredentials,
                    )
                }
            is AuthState.WaitCode ->
                if (changingNumber) {
                    PhoneForm(
                        initialPhone = state.phoneNumber.withPlus(),
                        onSubmit = viewModel::submitPhone,
                        submitting = submitting,
                        secondaryLabel = "Back",
                        onSecondary = { viewModel.changeNumber(false) },
                    )
                } else {
                    CodeForm(
                        phone = state.phoneNumber.withPlus(),
                        resendAfterSeconds = state.resendAfterSeconds,
                        sentAtElapsedMs = state.sentAtElapsedMs,
                        onSubmit = viewModel::submitCode,
                        onResend = viewModel::resendCode,
                        onChangeNumber = { viewModel.changeNumber(true) },
                        submitting = submitting,
                    )
                }
            is AuthState.WaitPassword ->
                PasswordForm(onSubmit = viewModel::submitPassword, submitting = submitting)
            is AuthState.Ready -> {
                // Authenticated - the LaunchedEffect above navigates away immediately. Render nothing
                // rather than a spinner: the destination shows its own loading, so a spinner here only
                // produces a redundant spinner->spinner flash during the login transition.
            }
            is AuthState.Error ->
                Text(text = state.message, color = MaterialTheme.colorScheme.error)
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
            text = { Text(msg) },
        )
    }

    // Bad API ID/Hash rejected at the phone step: the setup steps are no longer reachable, so offer
    // the only way out - clear the saved creds and restart. Not dismissable, because dismissing would
    // just return to the stranded phone screen.
    if (credentialsInvalid) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = viewModel::resetCredentials) { Text("Start over") }
            },
            text = { Text("Telegram rejected the API credentials.") },
        )
    }
}

// High-contrast field colors so the login inputs don't read as disabled under the (wallpaper-derived)
// dynamic dark scheme, whose default outline/label can be nearly invisible on the dark background.
@Composable
private fun authTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary,
)

// TDLib reports the phone without the leading +.
private fun String.withPlus() = if (startsWith("+")) this else "+$this"

@Composable
private fun AuthColumn(content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun Progress() {
    CircularProgressIndicator()
}

@Composable
private fun PhoneForm(
    initialPhone: String,
    onSubmit: (String) -> Unit,
    submitting: Boolean,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    var phone by remember { mutableStateOf(initialPhone) }
    AuthColumn {
        Text("Sign in with Telegram", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number") },
            placeholder = { Text("+1 234 567 8900") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = authTextFieldColors(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(phone.trim()) },
            enabled = phone.any { it.isDigit() } && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
        TextButton(onClick = onSecondary, enabled = !submitting) { Text(secondaryLabel) }
    }
}

// Both codes (my.telegram.org, then Telegram) use this screen. Resend unlocks resendAfterSeconds after
// sentAtElapsedMs; null means Telegram offers no further delivery, so only "Change number" remains.
@Composable
private fun CodeForm(
    phone: String,
    resendAfterSeconds: Int?,
    sentAtElapsedMs: Long,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
    submitting: Boolean,
) {
    // Keyed on the send time so a resent code clears the field.
    var code by remember(sentAtElapsedMs) { mutableStateOf("") }
    val secondsLeft by produceState(initialValue = 0L, sentAtElapsedMs, resendAfterSeconds) {
        val total = resendAfterSeconds ?: return@produceState
        while (true) {
            val elapsed = (SystemClock.elapsedRealtime() - sentAtElapsedMs) / 1000
            value = (total - elapsed).coerceAtLeast(0)
            if (value == 0L) break
            delay(1000)
        }
    }
    AuthColumn {
        Text("Enter the code", style = MaterialTheme.typography.headlineSmall)
        Text("Sent to your Telegram app · $phone", textAlign = TextAlign.Center)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            colors = authTextFieldColors(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(code.trim()) },
            enabled = code.isNotBlank() && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
        if (resendAfterSeconds != null) {
            TextButton(onClick = onResend, enabled = secondsLeft == 0L && !submitting) {
                Text(
                    if (secondsLeft > 0) {
                        "Resend code in %d:%02d".format(secondsLeft / 60, secondsLeft % 60)
                    } else {
                        "Resend code"
                    },
                )
            }
        }
        TextButton(onClick = onChangeNumber, enabled = !submitting) { Text("Change number") }
    }
}

@Composable
private fun CredentialsForm(onSubmit: (apiId: String, apiHash: String) -> Unit, onBack: () -> Unit) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    AuthColumn {
        Text("API ID and hash", style = MaterialTheme.typography.headlineSmall)
        val linkColor = MaterialTheme.colorScheme.primary
        Text(
            text = buildAnnotatedString {
                append("From ")
                withLink(LinkAnnotation.Url("https://my.telegram.org/apps")) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append("my.telegram.org/apps")
                    }
                }
            },
        )
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it },
            label = { Text("API ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = authTextFieldColors(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it },
            label = { Text("API hash") },
            colors = authTextFieldColors(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(apiId, apiHash) },
            enabled = apiId.isNotBlank() && apiHash.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun PasswordForm(onSubmit: (String) -> Unit, submitting: Boolean) {
    var password by remember { mutableStateOf("") }
    AuthColumn {
        Text("Two-step verification", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = authTextFieldColors(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(password) },
            enabled = password.isNotEmpty() && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}
