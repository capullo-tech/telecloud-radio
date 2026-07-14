package tech.capullo.telecloudradio.ui.groupselector

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.capullo.audio.snapcast.DiscoveredSnapserver
import tech.capullo.audio.ui.LocalRadiosSection
import tech.capullo.source.telegram.data.telegram.TelegramChat
import tech.capullo.telecloudradio.ui.snapcast.SnapcastViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSelectorScreen(
    onGroupSelected: (chatId: Long, chatTitle: String) -> Unit,
    onJoinServer: (host: String, port: Int, name: String) -> Unit,
    onOpenSettings: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
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
    // Adapters for the shared LocalRadiosSection: connect as a snapclient, then navigate to listen-in.
    val onJoinDiscovered: (DiscoveredSnapserver) -> Unit = { server ->
        snapViewModel.connect(server.hostAddress, server.port, server.httpPort)
        onJoinServer(server.hostAddress, server.port, server.serviceName.ifBlank { server.hostAddress })
    }
    val onJoinManual: (host: String, typedPort: Int?) -> Unit = { host, typedPort ->
        // The stream port resolves asynchronously (listen.json fetch); navigation only needs the name.
        snapViewModel.connectManually(host, typedPort)
        onJoinServer(host, 0, host)
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
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                    bottomContentPadding = bottomContentPadding,
                    onSelect = viewModel::selectGroup,
                    onJoinDiscovered = onJoinDiscovered,
                    onJoinManual = onJoinManual,
                    photoLoader = viewModel::chatPhotoPath,
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
    bottomContentPadding: Dp,
    onSelect: (TelegramChat) -> Unit,
    onJoinDiscovered: (DiscoveredSnapserver) -> Unit,
    onJoinManual: (host: String, typedPort: Int?) -> Unit,
    photoLoader: suspend (TelegramChat) -> String?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomContentPadding),
    ) {
        item {
            // Shared radar/scanning section (tech.capullo.audio.ui). TC broadcasts on dynamic ports;
            // manual entry passes the typed port through and the VM resolves the stream port (listen.json).
            LocalRadiosSection(
                servers = servers,
                onJoinServer = onJoinDiscovered,
                onJoinManual = onJoinManual,
            )
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
                    leadingContent = { ChatAvatar(chat, photoLoader) },
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

// Telegram group/channel avatar. The inline minithumbnail (a tiny blurred JPEG that ships with the
// chat, so no download is needed) shows instantly as a placeholder; a LaunchedEffect then downloads
// the crisp full-resolution "small" avatar and crossfades it in. Falls back to a generic group icon
// when the chat has no photo or nothing decodes. [photoLoader] caches per chatId in the VM, so the
// crisp file is fetched once and survives LazyColumn scroll/recompose.
@Composable
private fun ChatAvatar(chat: TelegramChat, photoLoader: suspend (TelegramChat) -> String?) {
    val placeholder = remember(chat.photo) {
        chat.photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    var crisp by remember(chat.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(chat.id, chat.photoFileId) {
        if (chat.photoFileId == null) return@LaunchedEffect
        val path = photoLoader(chat) ?: return@LaunchedEffect
        crisp = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }
    }

    val shown = crisp ?: placeholder
    val avatarModifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
    if (shown != null) {
        Crossfade(targetState = shown, label = "avatar") { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = avatarModifier,
            )
        }
    } else {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = avatarModifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        )
    }
}
