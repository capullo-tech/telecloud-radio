package tech.capullo.telecloudradio.ui.groupselector

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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
                // Placeholder rows rather than a lone spinner: the list's shape is known in
                // advance, so showing it means the content lands in place instead of replacing a
                // centred spinner with a full screen of rows.
                is GroupSelectorUiState.Loading -> ChatListSkeleton()
                is GroupSelectorUiState.Loaded -> ChatList(
                    chats = state.chats,
                    servers = servers,
                    lastGroupId = viewModel.lastGroupId,
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

// Below this many stations the list is scannable at a glance and a search field is just chrome.
private const val SEARCH_THRESHOLD = 8

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(
    chats: List<TelegramChat>,
    servers: List<DiscoveredSnapserver>,
    lastGroupId: Long,
    bottomContentPadding: Dp,
    onSelect: (TelegramChat) -> Unit,
    onJoinDiscovered: (DiscoveredSnapserver) -> Unit,
    onJoinManual: (host: String, typedPort: Int?) -> Unit,
    photoLoader: suspend (TelegramChat) -> String?,
) {
    // rememberSaveable so the filter survives rotation, but not opening a station: ChatList leaves
    // composition at the Syncing state, and backToList() re-queries on the way out - so the screen
    // is always re-entered with a fresh list and an empty field.
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = chats.size >= SEARCH_THRESHOLD
    val filtered = remember(chats, query, searchable) {
        val needle = query.trim()
        if (!searchable || needle.isEmpty()) {
            chats
        } else {
            chats.filter { it.title.contains(needle, ignoreCase = true) }
        }
    }
    // Lowering the Stations limit reloads the list live (GroupSelectorViewModel), which can pull it
    // under the threshold and take the field away mid-query. Filtering is gated on `searchable`
    // above so the rows come back in the same frame the field goes; this just drops the orphaned
    // text, so raising the limit again doesn't restore a filter the user has forgotten about.
    LaunchedEffect(searchable) { if (!searchable) query = "" }

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
        if (searchable) {
            // Sticky so the filter stays reachable once the radar section and the first rows have
            // scrolled past; the Surface gives it an opaque backdrop to sit on.
            stickyHeader {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    StationSearchField(value = query, onValueChange = { query = it })
                }
            }
        }
        when {
            chats.isEmpty() -> item {
                EmptyStations(
                    icon = Icons.Default.Groups,
                    message = "No groups or channels found",
                    hint = "Stations come from your Telegram groups and channels that contain " +
                        "audio. Raise the station limit in Settings if you expect more.",
                )
            }
            filtered.isEmpty() -> item {
                EmptyStations(
                    icon = Icons.Default.SearchOff,
                    message = "No station matches \"${query.trim()}\"",
                    hint = null,
                )
            }
            else -> items(filtered, key = { it.id }) { chat ->
                ChatRow(
                    chat = chat,
                    isLastPlayed = chat.id == lastGroupId,
                    photoLoader = photoLoader,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: TelegramChat,
    isLastPlayed: Boolean,
    photoLoader: suspend (TelegramChat) -> String?,
    onSelect: (TelegramChat) -> Unit,
) {
    val kind = chat.type.name.lowercase().replaceFirstChar { it.uppercase() }
    ListItem(
        leadingContent = { ChatAvatar(chat, photoLoader) },
        headlineContent = {
            Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = if (isLastPlayed) "Last played · $kind" else kind,
                color = if (isLastPlayed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // The station you last listened to is the one you most often want again, so it carries a
        // container tint on top of the label - findable without re-reading every title.
        colors = if (isLastPlayed) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable { onSelect(chat) },
    )
}

@Composable
private fun StationSearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("Search stations") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyStations(icon: ImageVector, message: String, hint: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(message, style = MaterialTheme.typography.titleSmall)
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val SKELETON_ROWS = 7

// Placeholder rows for the Loading state: avatar circle + two text bars, pulsing together so the
// screen reads as "filling in" rather than "stuck".
@Composable
private fun ChatListSkeleton() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonPulse",
    )
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        repeat(SKELETON_ROWS) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SkeletonBlock(Modifier.size(AVATAR_SIZE).clip(CircleShape), pulse)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Staggered widths so the block reads as a list of titles, not a bar chart.
                    val titleWidth = if (index % 2 == 0) 180.dp else 132.dp
                    SkeletonBlock(Modifier.height(14.dp).width(titleWidth).clip(RoundedCornerShape(6.dp)), pulse)
                    SkeletonBlock(Modifier.height(11.dp).width(72.dp).clip(RoundedCornerShape(6.dp)), pulse)
                }
            }
        }
    }
}

// [modifier] carries the size AND the shape clip - the caller owns both, so the same block serves
// the circular avatar and the rounded text bars.
@Composable
private fun SkeletonBlock(modifier: Modifier, pulse: Float) {
    Spacer(modifier = modifier.alpha(pulse).background(MaterialTheme.colorScheme.surfaceVariant))
}

// Rows lost their per-item HorizontalDivider: with an avatar on every row the divider was a second
// separator doing the first one's job. A larger avatar carries the rhythm instead, and it doubles
// as a better station identifier now that it is the only structure in the list.
private val AVATAR_SIZE = 48.dp

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
        .size(AVATAR_SIZE)
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
