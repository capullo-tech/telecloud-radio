package tech.capullo.telecloudradio.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tech.capullo.telecloudradio.MiniPlayerHeight
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

    var stationLimit: Int
        get() = settings.stationLimit
        set(value) {
            settings.stationLimit = value
        }

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    val balance: StateFlow<Float> = settings.balance
    fun setBalance(value: Float) = settings.setBalance(value)

    val webDebugPanel: StateFlow<Boolean> = settings.webDebugPanel
    fun setWebDebugPanel(value: Boolean) = settings.setWebDebugPanel(value)

    val webAutoplay: StateFlow<Boolean> = settings.webAutoplay
    fun setWebAutoplay(value: Boolean) = settings.setWebAutoplay(value)

    val customServerName: StateFlow<String> = settings.customServerName
    fun setCustomServerName(value: String) = settings.setCustomServerName(value)
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
                .padding(bottom = MiniPlayerHeight),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Compact rows (label + info-behind-(i) + tight control), ported from QC's
            // dense Settings. Buffer keeps a narrow decimal field (fractional GB); sleep
            // and stations use +/- steppers with tap-to-type. NOTE: the VM exposes these as
            // plain SharedPreferences-backed vars (not snapshot state), so each row holds a
            // local mutableStateOf and writes through - reading the var alone won't recompose.
            BufferRow(
                title = "Download buffer",
                info = "Maximum disk space for downloaded tracks (GB). " +
                    "The oldest track is deleted when the buffer is full.",
                value = text,
                isError = isError,
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
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            var sleepMinutes by remember { mutableStateOf(viewModel.sleepTimerMinutes) }
            IntStepperRow(
                title = "Sleep timer",
                info = "Countdown started from the moon button on the player. When it runs " +
                    "out, the current track finishes and playback pauses.",
                unit = "min",
                value = sleepMinutes,
                range = 5..120,
                step = 5,
                onValueChange = {
                    sleepMinutes = it
                    viewModel.sleepTimerMinutes = it
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            var stationLimit by remember { mutableStateOf(viewModel.stationLimit) }
            IntStepperRow(
                title = "Stations",
                info = "How many Telegram groups and channels to list on the station-select " +
                    "screen. The list may show a few fewer if some chats aren't groups or channels.",
                unit = null,
                value = stationLimit,
                range = 1..200,
                step = 10,
                onValueChange = {
                    stationLimit = it
                    viewModel.stationLimit = it
                },
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

            val serverName by viewModel.customServerName.collectAsStateWithLifecycle()
            ServerNameRow(
                title = "Server name",
                info = "The name this device shows to others when it broadcasts - in the " +
                    "\"Scanning for local radios\" list and the multiroom client list. " +
                    "Leave blank to use the device name.",
                value = serverName,
                placeholder = android.os.Build.MODEL,
                onCommit = viewModel::setCustomServerName,
            )

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
                    "re-syncs the full chat history on the next station open - including " +
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

// A section label with a small (i) that reveals the full explanation in a dialog, so the
// verbose "why" no longer takes 2–3 permanent lines under every setting (user request).
@Composable
private fun LabelWithInfo(title: String, info: String, modifier: Modifier = Modifier) {
    var showInfo by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { showInfo = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "About $title",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(title) },
            text = { Text(info, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } },
        )
    }
}

// Buffer is fractional GB over a wide range, so it keeps a narrow decimal field rather
// than a stepper (the field is ~96dp instead of the old 160dp island).
@Composable
private fun BufferRow(
    title: String,
    info: String,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelWithInfo(title, info, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("GB") },
            isError = isError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(96.dp),
        )
    }
}

// Free-text name field under a LabelWithInfo. A name needs width, so the field is
// full-width below the label (not a narrow island); it commits via the trailing check
// only when changed, so we don't re-advertise NSD on every keystroke. Placeholder shows
// the device-name fallback used when left blank.
@Composable
private fun ServerNameRow(
    title: String,
    info: String,
    value: String,
    placeholder: String,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabelWithInfo(title, info)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (text.trim() != value) {
                    IconButton(onClick = { onCommit(text) }) {
                        Icon(Icons.Default.Check, "Save", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
        )
    }
}

// Compact [ − ] value [ + ] stepper (ported from QC's SettingsScreen); the value is
// tappable for precise entry via StepperInputDialog. Guards keep it inside [range].
@Composable
private fun IntStepperRow(
    title: String,
    info: String,
    unit: String?,
    value: Int,
    range: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit,
) {
    var showInput by remember { mutableStateOf(false) }
    if (showInput) {
        StepperInputDialog(
            label = title,
            current = value,
            range = range,
            onDismiss = { showInput = false },
            onConfirm = {
                onValueChange(it)
                showInput = false
            },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelWithInfo(title, info, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedIconButton(
                onClick = { if (value - step >= range.first) onValueChange(value - step) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Remove, "Decrease", modifier = Modifier.size(16.dp))
            }
            Text(
                text = if (unit != null) "$value $unit" else "$value",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .clickable { showInput = true },
            )
            OutlinedIconButton(
                onClick = { if (value + step <= range.last) onValueChange(value + step) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Add, "Increase", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun StepperInputDialog(
    label: String,
    current: Int,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("${range.first}–${range.last}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                text.toIntOrNull()?.coerceIn(range)?.let { onConfirm(it) } ?: onDismiss()
            }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
