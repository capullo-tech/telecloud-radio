package tech.capullo.telecloudradio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.ThemeMode
import tech.capullo.telecloudradio.data.db.StationInfo
import tech.capullo.telecloudradio.data.playlist.PlaylistRepository
import tech.capullo.telecloudradio.player.DownloadManager
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val downloadManager: DownloadManager,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    var rebuildDone by mutableStateOf(false)
        private set

    var stations by mutableStateOf<List<StationInfo>>(emptyList())
        private set

    fun loadStations() {
        viewModelScope.launch { stations = playlistRepository.getStations() }
    }

    // chatId = null rebuilds everything
    fun rebuildLibrary(chatId: Long?) {
        viewModelScope.launch {
            downloadManager.wipeLibrary(chatId)
            rebuildDone = true
        }
    }

    var bufferSizeGb: Float
        get() = settings.bufferSizeGb
        set(value) {
            settings.bufferSizeGb = value
        }

    var rememberLastGroup: Boolean
        get() = settings.rememberLastGroup
        set(value) {
            settings.rememberLastGroup = value
        }

    var sleepTimerMinutes: Int
        get() = settings.sleepTimerMinutes
        set(value) {
            settings.sleepTimerMinutes = value
        }

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    val balance: StateFlow<Float> = settings.balance
    fun setBalance(value: Float) = settings.setBalance(value)

    val webDebugPanel: StateFlow<Boolean> = settings.webDebugPanel
    fun setWebDebugPanel(value: Boolean) = settings.setWebDebugPanel(value)

    val webAutoplay: StateFlow<Boolean> = settings.webAutoplay
    fun setWebAutoplay(value: Boolean) = settings.setWebAutoplay(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var text by remember {
        mutableStateOf(
            viewModel.bufferSizeGb.let {
                if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
            },
        )
    }
    var isError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                // Keep the last section clear of the mini-player overlay
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Download buffer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Maximum disk space for downloaded tracks (GB). The oldest track is deleted when the buffer is full.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    text = input
                    val parsed = input.toFloatOrNull()
                    if (parsed != null && parsed > 0f) {
                        isError = false
                        viewModel.bufferSizeGb = parsed
                    } else {
                        isError = input.isNotBlank()
                    }
                },
                label = { Text("GB") },
                isError = isError,
                supportingText = if (isError) ({ Text("Enter a positive number") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(160.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Countdown started from the moon button on the player. When it runs " +
                    "out, the current track finishes and playback pauses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var sleepText by remember { mutableStateOf(viewModel.sleepTimerMinutes.toString()) }
            var sleepError by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = sleepText,
                onValueChange = { input ->
                    sleepText = input
                    val parsed = input.toIntOrNull()
                    if (parsed != null && parsed > 0) {
                        sleepError = false
                        viewModel.sleepTimerMinutes = parsed
                    } else {
                        sleepError = input.isNotBlank()
                    }
                },
                label = { Text("Minutes") },
                isError = sleepError,
                supportingText = if (sleepError) ({ Text("Enter a positive whole number") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(160.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Navigation", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-open last station", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Skip station list and go directly to the last played station on launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                var rememberChecked by remember { mutableStateOf(viewModel.rememberLastGroup) }
                Switch(
                    checked = rememberChecked,
                    onCheckedChange = {
                        rememberChecked = it
                        viewModel.rememberLastGroup = it
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val themeOptions = listOf(
                ThemeMode.SYSTEM to "System",
                ThemeMode.DARK to "Dark",
                ThemeMode.LIGHT to "Light",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themeOptions.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themeOptions.size),
                    ) { Text(label) }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Balance", style = MaterialTheme.typography.titleMedium)
            Text(
                "Adjust left/right channel volume",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val balance by viewModel.balance.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("L", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = balance,
                    onValueChange = { viewModel.setBalance(it) },
                    valueRange = -1f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text("R", style = MaterialTheme.typography.labelMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        balance < -0.01f -> "Left ${(balance * -100).toInt()}%"
                        balance > 0.01f -> "Right ${(balance * 100).toInt()}%"
                        else -> "Centered"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { viewModel.setBalance(0f) },
                    enabled = balance != 0f,
                ) { Text("Center") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Multiroom web player", style = MaterialTheme.typography.titleMedium)
            val webAutoplay by viewModel.webAutoplay.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Autostart listening", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Web clients start listening on page load instead of waiting for the " +
                            "headphones button. Browsers may still require one tap the first " +
                            "time. Applies on the page's next reload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = webAutoplay, onCheckedChange = viewModel::setWebAutoplay)
            }
            val webDebugPanel by viewModel.webDebugPanel.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Debug panel", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Show the audio debug bar in the web player. Applies on the page's next reload.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = webDebugPanel, onCheckedChange = viewModel::setWebDebugPanel)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Library", style = MaterialTheme.typography.titleMedium)
            Text(
                "Rebuild deletes the local track index and all downloaded files, then " +
                    "re-syncs the full chat history on the next station open — including " +
                    "audio sent as file attachments that older versions missed. " +
                    "Nothing on Telegram is affected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var showRebuildDialog by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    viewModel.loadStations()
                    showRebuildDialog = true
                },
                enabled = !viewModel.rebuildDone,
            ) {
                Text(if (viewModel.rebuildDone) "Library cleared · reopen a station" else "Rebuild library")
            }
            if (showRebuildDialog) {
                AlertDialog(
                    onDismissRequest = { showRebuildDialog = false },
                    title = { Text("Rebuild which station?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Downloaded tracks of the chosen station are deleted and its " +
                                    "list re-fetched from Telegram on the next open.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            viewModel.stations.forEach { station ->
                                TextButton(onClick = {
                                    showRebuildDialog = false
                                    viewModel.rebuildLibrary(station.chatId)
                                }) { Text(station.station) }
                            }
                            HorizontalDivider()
                            TextButton(onClick = {
                                showRebuildDialog = false
                                viewModel.rebuildLibrary(null)
                            }) { Text("All stations") }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showRebuildDialog = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}
