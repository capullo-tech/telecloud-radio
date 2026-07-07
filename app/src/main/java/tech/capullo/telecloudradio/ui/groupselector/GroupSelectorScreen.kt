package tech.capullo.telecloudradio.ui.groupselector

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.capullo.audio.snapcast.DiscoveredSnapserver
import tech.capullo.source.telegram.data.telegram.TelegramChat
import tech.capullo.telecloudradio.ui.snapcast.SnapcastViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSelectorScreen(
    onGroupSelected: (chatId: Long, chatTitle: String) -> Unit,
    onJoinServer: (host: String, port: Int, name: String) -> Unit,
    viewModel: GroupSelectorViewModel = hiltViewModel(),
    snapViewModel: SnapcastViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val servers by snapViewModel.discoveredServers.collectAsStateWithLifecycle()

    // Scan for local snapcast servers while this screen is on-screen
    DisposableEffect(Unit) {
        snapViewModel.startDiscovery()
        onDispose { snapViewModel.stopDiscovery() }
    }
    val onJoin: (String, Int, Int, String) -> Unit = { host, port, httpPort, name ->
        snapViewModel.connect(host, port, httpPort)
        onJoinServer(host, port, name)
    }

    // Auto-navigate when sync completes; reset to group list so coming back shows the list
    val syncDoneState = uiState as? GroupSelectorUiState.SyncDone
    LaunchedEffect(syncDoneState?.chat?.id) {
        syncDoneState?.let { state ->
            onGroupSelected(state.chat.id, state.chat.title)
            viewModel.backToList()
        }
    }

    val canGoBack = uiState is GroupSelectorUiState.Syncing ||
        uiState is GroupSelectorUiState.Error
    BackHandler(enabled = canGoBack) { viewModel.backToList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select a station") },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { viewModel.backToList() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val state = uiState) {
                is GroupSelectorUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                is GroupSelectorUiState.Loaded -> ChatList(
                    chats = state.chats,
                    servers = servers,
                    onSelect = viewModel::selectGroup,
                    onJoin = onJoin,
                )
                is GroupSelectorUiState.Syncing -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Syncing ${state.chat.title}…")
                    Text(
                        "Fetching audio message history",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is GroupSelectorUiState.SyncDone -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                is GroupSelectorUiState.Error -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.loadGroups() }) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun ChatList(
    chats: List<TelegramChat>,
    servers: List<DiscoveredSnapserver>,
    onSelect: (TelegramChat) -> Unit,
    onJoin: (host: String, port: Int, httpPort: Int, name: String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            LocalRadiosSection(servers = servers, onJoin = onJoin)
        }
        if (chats.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No groups or channels found")
                }
            }
        } else {
            items(chats) { chat ->
                ListItem(
                    headlineContent = { Text(chat.title) },
                    supportingContent = {
                        Text(
                            chat.type.name
                                .lowercase()
                                .replaceFirstChar { it.uppercase() },
                        )
                    },
                    modifier = Modifier.clickable { onSelect(chat) },
                )
                HorizontalDivider()
            }
        }
    }
}

// Contrasting section above the Telegram stations: an always-scanning row (radar
// sweep) with discovered local snapcast servers listed below, and a tap-to-expand
// manual host:port row. Tapping a server joins it as a listener.
@Composable
private fun LocalRadiosSection(
    servers: List<DiscoveredSnapserver>,
    onJoin: (host: String, port: Int, httpPort: Int, name: String) -> Unit,
) {
    var manualExpanded by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            // Scanning row — tap to reveal/hide the manual connect field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { manualExpanded = !manualExpanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadarSweep(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Scanning for local radios…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Tap to enter an address manually",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (manualExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (manualExpanded) "Hide manual connect" else "Show manual connect",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = manualExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fun submit() {
                        val input = manualInput.trim()
                        if (input.isEmpty()) return
                        val host = input.substringBefore(":")
                        val port = input.substringAfter(":", "")
                            .toIntOrNull() ?: tech.capullo.telecloudradio.snapcast.SnapcastPorts.STREAM
                        // Manual entry can't know the remote's http/control port; assume the default.
                        onJoin(host, port, tech.capullo.telecloudradio.snapcast.SnapcastPorts.HTTP, host)
                    }
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Host or host:port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                    )
                    Button(onClick = { submit() }, enabled = manualInput.isNotBlank()) { Text("Join") }
                }
            }

            // Discovered servers — same row style as the scanning row
            servers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onJoin(
                                server.hostAddress,
                                server.port,
                                server.httpPort,
                                server.serviceName.ifBlank { server.hostAddress },
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.DeviceHub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            server.serviceName.ifBlank { server.hostAddress },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${server.hostAddress}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

// Slow radar sweep — a rotating wedge with a fading trail over faint range rings.
// Deliberately unhurried (~3.5s/rev) so it reads as "scanning the airwaves",
// not a busy loading spinner.
@Composable
private fun RadarSweep(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        // Range rings
        drawCircle(color = accent.copy(alpha = 0.25f), radius = r, style = Stroke(width = r * 0.06f))
        drawCircle(color = accent.copy(alpha = 0.18f), radius = r * 0.62f, style = Stroke(width = r * 0.05f))
        drawCircle(color = accent.copy(alpha = 0.5f), radius = r * 0.08f) // hub
        // Fading trail wedge: sweep gradient from transparent to accent, rotated
        rotate(degrees = angle, pivot = center) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.75f to Color.Transparent,
                    1f to accent.copy(alpha = 0.45f),
                    center = center,
                ),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = true,
            )
            // Bright leading line
            drawLine(
                color = accent,
                start = center,
                end = Offset(center.x + r, center.y),
                strokeWidth = r * 0.06f,
            )
        }
    }
}
