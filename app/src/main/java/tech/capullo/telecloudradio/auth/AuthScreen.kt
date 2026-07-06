package tech.capullo.telecloudradio.auth

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.capullo.telecloudradio.data.telegram.AuthState

@Composable
fun AuthScreen(onAuthenticated: () -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
        if (authState is AuthState.Ready) onAuthenticated()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (authState) {
            is AuthState.WaitParameters, is AuthState.Unknown ->
                CredentialsForm(onSubmit = viewModel::submitCredentials)
            is AuthState.WaitPhone ->
                PhoneInputForm(onSubmit = viewModel::submitPhone)
            is AuthState.WaitCode ->
                CodeInputForm(onSubmit = viewModel::submitCode)
            is AuthState.WaitPassword ->
                PasswordInputForm(onSubmit = viewModel::submitPassword)
            is AuthState.Ready ->
                CircularProgressIndicator()
            is AuthState.Error ->
                Text(
                    text = (authState as AuthState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun CredentialsForm(onSubmit: (apiId: String, apiHash: String) -> Unit) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Telecloud Radio", style = MaterialTheme.typography.headlineMedium)
        val linkColor = MaterialTheme.colorScheme.primary
        Text(
            text = buildAnnotatedString {
                append("Connect your Telegram account.\nGet your API credentials at ")
                withLink(LinkAnnotation.Url("https://my.telegram.org/auth")) {
                    withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ) {
                        append("my.telegram.org/auth")
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it },
            label = { Text("API ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it },
            label = { Text("API Hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(apiId, apiHash) },
            enabled = apiId.isNotBlank() && apiHash.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun PhoneInputForm(onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sign in to Telegram", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number (e.g. +1234567890)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(phone.trim()) },
            enabled = phone.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun PasswordInputForm(onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Two-step verification", style = MaterialTheme.typography.headlineSmall)
        Text("Your account is protected by a password")
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(password) },
            enabled = password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun CodeInputForm(onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Verification code", style = MaterialTheme.typography.headlineSmall)
        Text("Check your Telegram app for the code sent to your phone")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(code.trim()) },
            enabled = code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Verify")
        }
    }
}
