@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tech.capullo.telecloudradio.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MultilineChart
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.capullo.audio.snapcast.SnapclientProcess
import tech.capullo.audio.snapcast.firstArtist
import tech.capullo.telecloudradio.LISTEN_IN_CHAT_ID
import tech.capullo.telecloudradio.data.db.AudioAnalysisEntity
import tech.capullo.telecloudradio.data.db.MediaMessageEntity
import tech.capullo.telecloudradio.player.AudioMetadata
import tech.capullo.telecloudradio.snapcast.SnapcastManager
import tech.capullo.audio.ui.SnapcastControlSheet
import tech.capullo.telecloudradio.ui.snapcast.SnapcastViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    chatId: Long,
    chatTitle: String,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    // Activity-scoped (NOT the default nav-entry scope): this VM owns the playback command
    // handler (activeTrackRepository.command → next/prev) + the playlist + the MediaController.
    // Scoping it to the entry meant back-navigation destroyed it, which both wiped the MiniPlayer
    // and left the MiniPlayer/notification skip buttons with no listener. Activity scope keeps the
    // one playback session alive across navigation for the whole app session. (Only PlayerViewModel
    // needs to survive; snapViewModel stays entry-scoped — it's a thin pass-through to the singleton
    // SnapcastManager.)
    viewModel: PlayerViewModel = hiltViewModel(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity,
    ),
    snapViewModel: SnapcastViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snapState by snapViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Listen-in (this device joined another server): render the remote stream's
    // now-playing instead of a Telegram station, and never start local playback.
    val listenIn = chatId == LISTEN_IN_CHAT_ID || snapState.isListening

    var showSnapSheet by remember { mutableStateOf(false) }
    if (showSnapSheet) {
        SnapcastControlSheet(
            groups = snapState.groups,
            snapclientChannel = snapState.snapclientChannel,
            // Sheet passes (clientId, muted, percent); VM takes (clientId, percent, muted)
            onClientVolumeChange = { clientId, muted, percent ->
                snapViewModel.setClientVolume(clientId, percent, muted)
            },
            onClientLatencyChange = snapViewModel::setClientLatency,
            onSetChannel = snapViewModel::setLocalChannel,
            onChangeClientChannel = snapViewModel::changeClientChannel,
            isBroadcaster = !snapState.isListening,
            isStreamLocked = snapState.isStreamLocked,
            onToggleStreamLock = snapViewModel::toggleStreamLock,
            localClientId = snapViewModel.localClientId,
            onResetSelf = snapViewModel::resetSelf,
            onResetAll = snapViewModel::resetAll,
            httpPort = snapState.broadcastHttpPort,
            onDismiss = { showSnapSheet = false },
        )
    }

    if (listenIn) {
        ListenInPlayer(
            state = snapState,
            fallbackTitle = chatTitle,
            localClientId = snapViewModel.localClientId,
            onBack = onBack,
            onControl = snapViewModel::streamControl,
            onOpenClients = { showSnapSheet = true },
            onLeave = {
                snapViewModel.disconnect()
                onBack()
            },
        )
        return
    }

    LaunchedEffect(chatId) { viewModel.loadAndPlay(chatId, chatTitle) }
    LaunchedEffect(Unit) {
        viewModel.downloadToast.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        chatTitle,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::toggleSleepTimer) {
                        if (uiState.sleepTimerActive) {
                            val remaining = uiState.sleepTimerSecondsRemaining
                            Text(
                                // 0 remaining = countdown done, finishing the current track
                                if (remaining > 0) "%d:%02d".format(remaining / 60, remaining % 60) else "zZ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(Icons.Outlined.Bedtime, contentDescription = "Sleep timer")
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.isOffline) OfflineBanner()
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    uiState.isLoading && uiState.track == null -> CircularProgressIndicator()
                    uiState.error != null && uiState.track == null -> Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(32.dp),
                    )
                    uiState.track != null -> {
                        val others = remember(snapState.groups) {
                            snapState.groups.sumOf { g ->
                                g.clients.count { it.connected && it.id != snapViewModel.localClientId }
                            }
                        }
                        val anyConnected = remember(snapState.groups) {
                            snapState.groups.any { g -> g.clients.any { it.connected } }
                        }
                        PortraitPlayer(
                            state = uiState,
                            viewModel = viewModel,
                            connectedOthers = others,
                            anyConnected = anyConnected,
                            onOpenClients = { showSnapSheet = true },
                        )
                    }
                }
            }
        }
    }

    if (uiState.showQueue) {
        // Swiping down in the track lists kept dismissing the sheet — gestures are
        // fully disabled (no drag handle, no springy drag); the X button (and
        // tapping a track) closes it.
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden },
        )
        ModalBottomSheet(
            onDismissRequest = viewModel::toggleQueue,
            sheetState = sheetState,
            sheetGesturesEnabled = false,
            dragHandle = null,
        ) {
            QueueSheet(
                queue = uiState.displayPlaylist,
                library = uiState.unfilteredPlaylist,
                appliedFilters = uiState.queueFilters,
                currentIndex = uiState.currentIndex,
                isOffline = uiState.isOffline,
                playOrder = uiState.playOrder,
                onCycleOrder = viewModel::cyclePlayOrder,
                onApply = viewModel::setQueueFilters,
                onPlayAt = { index ->
                    viewModel.playAt(index)
                    viewModel.toggleQueue()
                },
                onQueueRemove = viewModel::removeFromQueue,
                onQueuePlayNext = viewModel::queuePlayNext,
                onQueueMove = viewModel::moveInQueue,
                onLibPlayNow = { track ->
                    viewModel.playNow(track)
                    viewModel.toggleQueue()
                },
                onLibPlayNext = viewModel::playNext,
                onLibAddToQueue = viewModel::addToQueue,
                onClose = viewModel::toggleQueue,
            )
        }
    }

    if (uiState.showReactions) {
        ModalBottomSheet(
            onDismissRequest = viewModel::toggleReactions,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ReactionsSheet(
                info = uiState.reactionsInfo,
                isLoading = uiState.reactionsLoading,
                onReact = viewModel::setReaction,
            )
        }
    }

    if (uiState.showStats) {
        ModalBottomSheet(
            onDismissRequest = viewModel::toggleStats,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            // LazyColumn integrates with ModalBottomSheet's nested scroll properly;
            // Column+verticalScroll fights the sheet's gesture handling.
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    StatsSheet(
                        track = uiState.track,
                        audioMeta = uiState.audioMeta,
                        analysis = uiState.audioAnalysis,
                        isAnalyzing = uiState.isAnalyzing,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Offline · showing local tracks only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun PortraitPlayer(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    connectedOthers: Int,
    anyConnected: Boolean,
    onOpenClients: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Edge-to-edge square art, no corner radius
        AlbumArtDisplay(
            albumArt = state.albumArt,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .artGestures(viewModel),
        )
        Spacer(Modifier.height(16.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            state.track?.let {
                TrackInfo(
                    track = it,
                    audioMeta = state.audioMeta,
                    onDownload = viewModel::downloadCurrentTrack,
                    onStats = viewModel::toggleStats,
                    onReactions = viewModel::toggleReactions,
                )
            }
            SeekBar(state = state, onSeek = viewModel::seekTo)
            ControlBar(state = state, viewModel = viewModel)
            // Secondary row: Multiroom (under the order button) · Playlist (under repeat)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenClients) {
                    MultiroomIcon(
                        connectedOthers = connectedOthers,
                        anyConnected = anyConnected,
                        isListening = false,
                    )
                }
                IconButton(onClick = viewModel::toggleQueue) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
                }
            }
        }
    }
}

// QuantumCast's clients icon: surround-sound glyph; the count of OTHER connected
// clients (self excluded) sits in its center in place of the dot, alone = plain
// surround-sound. Tint keys on any connection so it stays lit while solo.
@Composable
private fun MultiroomIcon(
    connectedOthers: Int,
    anyConnected: Boolean,
    isListening: Boolean,
) {
    val tint = when {
        !anyConnected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        isListening -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    if (connectedOthers > 0) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(tech.capullo.telecloudradio.R.drawable.ic_surround_sound_nodot),
                contentDescription = "Multiroom · $connectedOthers connected",
                tint = tint,
            )
            Text(
                text = if (connectedOthers > 99) "99" else "$connectedOthers",
                color = tint,
                fontSize = 8.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    } else {
        Icon(
            Icons.Default.SurroundSound,
            contentDescription = "Multiroom",
            tint = tint,
        )
    }
}

// Now-playing when this device has joined another server (snapclient). Metadata,
// art and transport all come from the remote stream via SnapcastManager.state;
// transport buttons drive Stream.Control on the remote.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenInPlayer(
    state: SnapcastManager.SnapcastState,
    fallbackTitle: String,
    localClientId: String,
    onBack: () -> Unit,
    onControl: (String) -> Unit,
    onOpenClients: () -> Unit,
    onLeave: () -> Unit,
) {
    val meta = state.remoteProps?.metadata
    val title = meta?.title?.takeIf { it.isNotBlank() } ?: meta?.station?.takeIf { it.isNotBlank() }
    val artist = meta?.firstArtist().orEmpty()
    val station = meta?.station.orEmpty()
    val props = state.remoteProps
    val isPlaying = props?.playbackStatus == "playing"
    val others = state.groups.sumOf { g -> g.clients.count { it.connected && it.id != localClientId } }
    val anyConnected = state.groups.any { g -> g.clients.any { it.connected } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    // Empty brand-style title (parity with the web player header)
                    Text(
                        state.listenHost,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenClients) {
                        MultiroomIcon(connectedOthers = others, anyConnected = anyConnected, isListening = true)
                    }
                    TextButton(onClick = onLeave) { Text("Leave") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AlbumArtDisplay(
                albumArt = state.remoteArt,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(props?.canGoNext, props?.canGoPrevious) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                            },
                            onDragEnd = {
                                val threshold = 90.dp.toPx()
                                when {
                                    totalDrag <= -threshold && props?.canGoNext == true -> onControl("next")
                                    totalDrag >= threshold && props?.canGoPrevious == true -> onControl("previous")
                                }
                            },
                        )
                    },
            )
            Spacer(Modifier.height(16.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 28.dp),
            ) {
                Text(
                    text = title ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
                )
                if (artist.isNotBlank()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
                    )
                }
                if (station.isNotBlank() && station != title) {
                    Text(
                        text = station,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
                Text(
                    text = when (state.listenState) {
                        SnapclientProcess.ConnectionState.CONNECTED -> "Listening in · ${state.listenHost}"
                        SnapclientProcess.ConnectionState.STARTING -> "Connecting to ${state.listenHost}…"
                        SnapclientProcess.ConnectionState.ERROR -> "Connection error"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // Transport: shown only when the remote stream allows it (hidden when
                // the broadcaster has locked control — mirrors the web now-playing).
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (props?.canGoPrevious == true) {
                        IconButton(onClick = { onControl("previous") }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
                        }
                    }
                    if (props?.canPlay == true || props?.canPause == true) {
                        FilledIconToggleButton(
                            checked = false,
                            onCheckedChange = { onControl(if (isPlaying) "pause" else "play") },
                            modifier = Modifier.size(68.dp),
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    if (props?.canGoNext == true) {
                        IconButton(onClick = { onControl("next") }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    val durationMs = when {
        state.trackDuration > 0L -> state.trackDuration
        (state.track?.duration ?: 0) > 0 -> state.track!!.duration!! * 1000L
        else -> 0L
    }
    // Track the drag locally so the thumb doesn't fight the 500ms position ticker
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shownMs = (dragValue ?: state.currentPosition.coerceIn(0L, durationMs).toFloat()).toLong()
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = dragValue ?: state.currentPosition.coerceIn(0L, durationMs).toFloat(),
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeek(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L && !state.isLoading,
            thumb = {
                // Slim pill thumb instead of the default fat handle
                Box(
                    Modifier
                        .size(width = 4.dp, height = 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(3.dp),
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatDuration((shownMs / 1000).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "-" + formatDuration(((durationMs - shownMs) / 1000).toInt().coerceAtLeast(0)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlBar(state: PlayerUiState, viewModel: PlayerViewModel) {
    // Single row: [order] [prev] [play/pause] [next] [repeat]
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 3-mode order cycle: ↓ newest · ↑ oldest · 🔀 shuffled
        IconButton(onClick = viewModel::cyclePlayOrder) {
            val (icon, desc) = when (state.playOrder) {
                PlayOrder.NEWEST_FIRST -> Pair(Icons.Default.ArrowDownward, "Newest first")
                PlayOrder.OLDEST_FIRST -> Pair(Icons.Default.ArrowUpward, "Oldest first")
                PlayOrder.SHUFFLED -> Pair(Icons.Default.Shuffle, "Shuffled")
            }
            Icon(
                icon,
                contentDescription = desc,
                modifier = Modifier.size(26.dp),
                tint = if (state.playOrder != PlayOrder.NEWEST_FIRST) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(
            onClick = viewModel::prevTrack,
            enabled = !state.isLoading,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
        }
        // Play/pause keeps controlling the audible track while another one loads;
        // the load is indicated by a ring around the button, not by replacing it.
        Box(contentAlignment = Alignment.Center) {
            FilledIconToggleButton(
                checked = false,
                onCheckedChange = { viewModel.togglePlayPause() },
                modifier = Modifier.size(68.dp),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(76.dp), strokeWidth = 2.dp)
            }
        }
        IconButton(
            onClick = viewModel::nextTrack,
            enabled = !state.isLoading && state.nextTrackReady,
            modifier = Modifier.size(56.dp),
        ) {
            val progress = state.nextDownloadProgress
            // Only surface the next-track download once the current track is actually playing —
            // otherwise the Next button flickers between icon and ring during the initial load/
            // prefetch churn (the "loading for next song" jumpiness).
            if (state.isPlaying && !state.nextTrackReady && progress != null) {
                // Next track still downloading — show its progress instead of the icon
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
            }
        }
        IconButton(onClick = viewModel::cycleRepeatMode) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Default.RepeatOne
                } else {
                    Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                modifier = Modifier.size(26.dp),
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

// Tap = play/pause · swipe left = next · swipe right = previous
private fun Modifier.artGestures(viewModel: PlayerViewModel): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onTap = { viewModel.togglePlayPause() })
    }
    .pointerInput(Unit) {
        val threshold = 90.dp.toPx()
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                totalDrag += dragAmount
            },
            onDragEnd = {
                when {
                    totalDrag <= -threshold -> viewModel.nextTrack()
                    totalDrag >= threshold -> viewModel.prevTrack()
                }
            },
        )
    }

@Composable
private fun AlbumArtDisplay(albumArt: ByteArray?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (albumArt != null) {
            val bitmap = remember(albumArt) {
                BitmapFactory.decodeByteArray(albumArt, 0, albumArt.size)?.asImageBitmap()
            }
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("♪", style = MaterialTheme.typography.displayLarge)
            }
        } else {
            Text("♪", style = MaterialTheme.typography.displayLarge)
        }
    }
}

@Composable
private fun TrackInfo(
    track: MediaMessageEntity,
    audioMeta: AudioMetadata?,
    onDownload: () -> Unit,
    onStats: () -> Unit,
    onReactions: () -> Unit,
) {
    val title = track.title ?: track.fileName ?: "Unknown"
    val artist = track.performer
    val uploader = extractUploader(track.caption) ?: extractUploaderFromFilename(track.fileName)
    val dateStr = formatTelegramDate(track.date)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
        )
        if (!artist.isNullOrBlank()) {
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onReactions).padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            track.reactions?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                Icons.Outlined.AddReaction,
                contentDescription = "React",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val uploaderLine = listOfNotNull(uploader, dateStr).joinToString(" · ")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uploaderLine.isNotBlank()) {
                Text(
                    text = uploaderLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
                )
            }
            IconButton(onClick = onDownload, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        audioMeta?.let { meta ->
            val parts = listOfNotNull(
                meta.codec,
                meta.bitrateKbps?.let { "$it kbps" },
                meta.sampleRateHz?.let { "${"%.1f".format(it / 1000f)} kHz" },
                meta.bitDepth?.let { "$it-bit" },
            )
            if (parts.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                    IconButton(onClick = onStats, modifier = Modifier.size(20.dp)) {
                        Icon(
                            Icons.Default.Equalizer,
                            contentDescription = "Stats for nerds",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

// Fallback picker when the chat doesn't report its available reactions
private val defaultReactionEmoji = listOf(
    "👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱", "🎉", "🤩", "🙏", "👌", "😢", "💯",
)

@Composable
private fun ReactionsSheet(
    info: tech.capullo.source.telegram.data.telegram.MessageReactionsInfo?,
    isLoading: Boolean,
    onReact: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Reactions", style = MaterialTheme.typography.titleMedium)
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }

        val emoji = info?.available?.takeIf { it.isNotEmpty() } ?: defaultReactionEmoji
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            emoji.forEach { e ->
                val isOwn = info?.ownEmoji == e
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isOwn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .clickable(enabled = !isLoading) { onReact(e) },
                ) {
                    Text(
                        e,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        info?.ownEmoji?.let {
            Text(
                "Your reaction: $it · tap it again to remove",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        when {
            info == null && isLoading -> {}
            info?.reactors?.isNotEmpty() == true -> {
                Text("Who reacted", style = MaterialTheme.typography.titleSmall)
                info.reactors.forEach { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(r.emoji, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (r.isSelf) "${r.name} (you)" else r.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            info?.summary != null && info.canListReactors.not() ->
                Text(
                    "Reaction authors aren't available here (anonymous in channels)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            info != null ->
                Text(
                    "No reactions yet — be the first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun QueueSheet(
    queue: List<MediaMessageEntity>,
    library: List<MediaMessageEntity>,
    appliedFilters: QueueFilters,
    currentIndex: Int,
    isOffline: Boolean,
    playOrder: PlayOrder,
    onCycleOrder: () -> Unit,
    onApply: (QueueFilters) -> Unit,
    onPlayAt: (Int) -> Unit,
    onQueueRemove: (Int) -> Unit,
    onQueuePlayNext: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    onLibPlayNow: (MediaMessageEntity) -> Unit,
    onLibPlayNext: (MediaMessageEntity) -> Unit,
    onLibAddToQueue: (MediaMessageEntity) -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Queue") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Library") })
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
    }
    if (tab == 0) {
        QueueTab(
            queue = queue,
            currentIndex = currentIndex,
            isOffline = isOffline,
            filtersActive = appliedFilters.isActive,
            playOrder = playOrder,
            onCycleOrder = onCycleOrder,
            onPlayAt = onPlayAt,
            onQueueRemove = onQueueRemove,
            onQueuePlayNext = onQueuePlayNext,
            onQueueMove = onQueueMove,
        )
    } else {
        LibraryTab(
            library = library,
            appliedFilters = appliedFilters,
            onApply = onApply,
            onPlayNow = onLibPlayNow,
            onPlayNext = onLibPlayNext,
            onAddToQueue = onLibAddToQueue,
        )
    }
}

private fun gbString(tracks: List<MediaMessageEntity>): String = "%.1f GB".format(tracks.sumOf { it.fileSize ?: 0L } / (1024.0 * 1024.0 * 1024.0))

@Composable
private fun QueueTab(
    queue: List<MediaMessageEntity>,
    currentIndex: Int,
    isOffline: Boolean,
    filtersActive: Boolean,
    playOrder: PlayOrder,
    onCycleOrder: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onQueueRemove: (Int) -> Unit,
    onQueuePlayNext: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    // Drag-to-reorder state (handle-based; disabled while searching since row
    // positions in the view no longer match queue indices contiguously)
    var draggingQueueIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // Search narrows the *view* only; playback still follows the full queue.
    // Items carry their original queue index so clicks/menus address the right row.
    val visible = remember(queue, search) {
        val q = search.trim().lowercase()
        queue.withIndex().filter { (_, t) ->
            q.isEmpty() ||
                t.title?.lowercase()?.contains(q) == true ||
                t.performer?.lowercase()?.contains(q) == true ||
                t.fileName?.lowercase()?.contains(q) == true
        }
    }
    val listState = rememberLazyListState()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentIndex, queue) {
        val visiblePos = visible.indexOfFirst { it.index == currentIndex }
        if (visiblePos >= 0) listState.scrollToItem(visiblePos)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pos = if (currentIndex >= 0) "${currentIndex + 1}" else "–"
        val suffix = buildString {
            if (filtersActive) append(" · filtered")
            if (isOffline) append(" · offline")
        }
        Text(
            text = "$pos / ${queue.size} · ${gbString(queue)}$suffix",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCycleOrder) {
            val (icon, desc) = when (playOrder) {
                PlayOrder.NEWEST_FIRST -> Pair(Icons.Default.ArrowDownward, "Newest first")
                PlayOrder.OLDEST_FIRST -> Pair(Icons.Default.ArrowUpward, "Oldest first")
                PlayOrder.SHUFFLED -> Pair(Icons.Default.Shuffle, "Shuffled")
            }
            Icon(
                icon,
                contentDescription = desc,
                tint = if (playOrder != PlayOrder.NEWEST_FIRST) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    SheetSearchField(value = search, onValueChange = { search = it })
    val canReorder = search.isBlank()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(visible, key = { _, iv -> iv.index }) { _, indexed ->
                val (qIdx, track) = indexed
                val isDragging = draggingQueueIndex == qIdx
                TrackRow(
                    track = track,
                    isCurrent = qIdx == currentIndex,
                    onClick = { onPlayAt(qIdx) },
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f },
                    trailing = if (canReorder) {
                        {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "Reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.pointerInput(qIdx) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingQueueIndex = qIdx
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffsetY += amount.y
                                        },
                                        onDragEnd = {
                                            val rowHeight = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull()?.size ?: 72
                                            val shift = (dragOffsetY / rowHeight).roundToInt()
                                            if (shift != 0) onQueueMove(qIdx, qIdx + shift)
                                            draggingQueueIndex = null
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingQueueIndex = null
                                            dragOffsetY = 0f
                                        },
                                    )
                                },
                            )
                        }
                    } else {
                        null
                    },
                ) { dismiss ->
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        onClick = {
                            onQueuePlayNext(qIdx)
                            dismiss()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from queue") },
                        onClick = {
                            onQueueRemove(qIdx)
                            dismiss()
                        },
                    )
                }
            }
        }
        ScrollToTopFab(visible = showScrollToTop, modifier = Modifier.align(Alignment.BottomCenter)) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
}

@Composable
private fun LibraryTab(
    library: List<MediaMessageEntity>,
    appliedFilters: QueueFilters,
    onApply: (QueueFilters) -> Unit,
    onPlayNow: (MediaMessageEntity) -> Unit,
    onPlayNext: (MediaMessageEntity) -> Unit,
    onAddToQueue: (MediaMessageEntity) -> Unit,
) {
    // Draft filters: chips + search preview the library live; Apply rebuilds the queue
    var draft by remember(appliedFilters) { mutableStateOf(appliedFilters) }

    val uploaderOptions = remember(library) { library.mapNotNull(::uploaderKey).distinct() }
    val dateOptions = remember(library) {
        datePresets + library.mapNotNull { monthKey(it.date) }.distinct()
    }
    val extOptions = remember(library) { library.mapNotNull(::extensionKey).distinct().sorted() }

    val filtered = remember(library, draft) { library.filter(draft::matches) }
    val listState = rememberLazyListState()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${library.size} tracks · ${gbString(library)}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
    }
    SheetSearchField(value = draft.search, onValueChange = { draft = draft.copy(search = it) })
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        QueueFilterChip(
            label = "Uploader",
            selected = draft.uploaders,
            options = uploaderOptions,
            onToggle = { v ->
                draft = draft.copy(
                    uploaders = if (v in draft.uploaders) draft.uploaders - v else draft.uploaders + v,
                )
            },
            onClear = { draft = draft.copy(uploaders = emptySet()) },
        )
        QueueFilterChip(
            label = "Date",
            selected = draft.months,
            options = dateOptions,
            onToggle = { v ->
                draft = draft.copy(
                    months = if (v in draft.months) draft.months - v else draft.months + v,
                )
            },
            onClear = { draft = draft.copy(months = emptySet()) },
        )
        QueueFilterChip(
            label = "Ext",
            selected = draft.extensions,
            options = extOptions,
            onToggle = { v ->
                draft = draft.copy(
                    extensions = if (v in draft.extensions) draft.extensions - v else draft.extensions + v,
                )
            },
            onClear = { draft = draft.copy(extensions = emptySet()) },
        )
    }
    if (draft != appliedFilters || draft.isActive) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        ) {
            Text(
                "${filtered.size} of ${library.size} tracks",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (draft.isActive) {
                TextButton(onClick = { onApply(QueueFilters()) }) { Text("Reset") }
            }
            Button(
                enabled = draft != appliedFilters,
                onClick = { onApply(draft) },
            ) { Text("Apply to queue") }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(filtered) { _, track ->
                TrackRow(
                    track = track,
                    isCurrent = false,
                    onClick = { onPlayNow(track) },
                ) { dismiss ->
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        onClick = {
                            onPlayNext(track)
                            dismiss()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        onClick = {
                            onAddToQueue(track)
                            dismiss()
                        },
                    )
                }
            }
        }
        ScrollToTopFab(visible = showScrollToTop, modifier = Modifier.align(Alignment.BottomCenter)) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
}

@Composable
private fun SheetSearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("Search") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: MediaMessageEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ListItem(
            headlineContent = {
                Text(
                    track.title ?: track.fileName ?: "Unknown",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            },
            supportingContent = {
                val artist = track.performer
                val uploader = extractUploader(track.caption)
                    ?: extractUploaderFromFilename(track.fileName)
                val duration = track.duration?.takeIf { it > 0 }?.let { formatDuration(it) }
                val date = formatTelegramDate(track.date)
                val parts = listOfNotNull(
                    artist?.takeIf { it.isNotBlank() },
                    uploader?.takeIf { it.isNotBlank() },
                    duration,
                    date,
                )
                if (parts.isNotEmpty()) {
                    Text(
                        parts.joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menuItems { menuOpen = false }
        }
    }
    HorizontalDivider()
}

@Composable
private fun ScrollToTopFab(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(bottom = 48.dp),
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        SmallFloatingActionButton(onClick = onClick) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
        }
    }
}

@Composable
private fun QueueFilterChip(
    label: String,
    selected: Set<String>,
    options: List<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected.isNotEmpty(),
            onClick = { expanded = true },
            label = {
                Text(
                    when {
                        selected.isEmpty() -> label
                        selected.size == 1 -> selected.first()
                        else -> "$label · ${selected.size}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                if (selected.isNotEmpty()) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear $label filter",
                        modifier = Modifier.size(16.dp).clickable(onClick = onClear),
                    )
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val isSelected = option in selected
                DropdownMenuItem(
                    text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    // Keep the menu open — multiple values can be toggled
                    onClick = { onToggle(option) },
                )
            }
        }
    }
}

@Composable
private fun StatsSheet(
    track: MediaMessageEntity?,
    audioMeta: AudioMetadata?,
    analysis: AudioAnalysisEntity?,
    isAnalyzing: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 1.dp),
        ) {
            Icon(Icons.Outlined.Analytics, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text("Stats for nerds", style = MaterialTheme.typography.titleSmall)
        }

        if (audioMeta != null || track != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                audioMeta?.codec?.let { StatChip(Icons.Default.Memory, "Codec", it) }
                audioMeta?.sampleRateHz?.let { StatChip(Icons.Default.GraphicEq, "Sample Rate", "${"%.1f".format(it / 1000f)} kHz") }
                audioMeta?.bitDepth?.let { bd ->
                    StatChip(Icons.Outlined.AudioFile, "Bit Depth", "$bd-bit")
                    StatChip(
                        Icons.Outlined.DataObject,
                        "Decoded",
                        when (bd) {
                            16 -> "s16"
                            24 -> "s24"
                            else -> "f32"
                        },
                    )
                }
                audioMeta?.bitrateKbps?.let { StatChip(Icons.Default.Speed, "Bitrate", "$it kbps") }
                audioMeta?.channels?.let { ch ->
                    StatChip(
                        Icons.Default.SurroundSound,
                        "Channels",
                        when (ch) {
                            1 -> "Mono"
                            2 -> "2 (stereo)"
                            else -> "$ch ch"
                        },
                    )
                }
                track?.duration?.takeIf { it > 0 }?.let { StatChip(Icons.Outlined.Timer, "Duration", formatDuration(it)) }
                audioMeta?.sampleRateHz?.let { StatChip(Icons.Default.MultilineChart, "Nyquist", "${"%.1f".format(it / 2000f)} kHz") }
                track?.fileSize?.takeIf { it > 0L }?.let { bytes ->
                    StatChip(
                        Icons.Default.Storage,
                        "Size",
                        if (bytes >= 1_000_000_000L) {
                            "${"%.1f".format(bytes / 1_000_000_000.0)} GB"
                        } else {
                            "${"%.1f".format(bytes / 1_000_000.0)} MB"
                        },
                    )
                }
            }
        }

        if (isAnalyzing) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Analyzing audio…", style = MaterialTheme.typography.labelSmall)
            }
        } else if (analysis != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                StatChip(Icons.Default.TrendingUp, "Dyn Range", "${"%.2f".format(analysis.dynamicRange)} dB")
                StatChip(Icons.Default.ShowChart, "Peak", "${"%.2f".format(analysis.peakDb)} dB")
                StatChip(Icons.Default.Equalizer, "RMS", "${"%.2f".format(analysis.rmsDb)} dB")
                if (analysis.lufs > -990f) StatChip(Icons.Outlined.VolumeUp, "LUFS", "${"%.1f".format(analysis.lufs)} LUFS")
                if (analysis.truePeakDb > -990f) StatChip(Icons.Outlined.Warning, "True Peak", "${"%.2f".format(analysis.truePeakDb)} dBTP")
                StatChip(
                    if (analysis.clipping) Icons.Outlined.Report else Icons.Default.CheckCircle,
                    "Clipping",
                    if (analysis.clipping) "⚠ Yes" else "None",
                )
                StatChip(Icons.Outlined.FilterAlt, "Cutoff", "${"%.1f".format(analysis.spectralCutoffHz / 1000f)} kHz")
                if (analysis.totalSamples > 0L) {
                    val s = when {
                        analysis.totalSamples >= 1_000_000L -> "${"%.1f".format(analysis.totalSamples / 1_000_000.0)}M"
                        analysis.totalSamples >= 1_000L -> "${"%.1f".format(analysis.totalSamples / 1_000.0)}k"
                        else -> analysis.totalSamples.toString()
                    }
                    StatChip(Icons.Default.Numbers, "Samples", s)
                }
            }

            analysis.channelStatsCsv?.takeIf { it.isNotBlank() }?.let { csv ->
                val chParts = csv.split(";")
                if (chParts.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        chParts.forEachIndexed { idx, part ->
                            val vals = part.split(",")
                            val peak = vals.getOrNull(0)?.toFloatOrNull()
                            val rms = vals.getOrNull(1)?.toFloatOrNull()
                            val dr = vals.getOrNull(2)?.toFloatOrNull()
                            val line = buildString {
                                if (peak != null) append("P ${"%.1f".format(peak)}")
                                if (rms != null) append("  R ${"%.1f".format(rms)}")
                                if (dr != null) append("  DR ${"%.1f".format(dr)}")
                            }
                            if (line.isNotBlank()) StatChip(Icons.Default.GraphicEq, "Ch ${idx + 1}", line)
                        }
                    }
                }
            }

            analysis.spectrogramPath?.let { path ->
                SpectrogramView(spectrogramPath = path)
            }
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SpectrogramView(spectrogramPath: String) {
    val bitmap by produceState<Bitmap?>(null, spectrogramPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(spectrogramPath)
                if (!file.exists()) return@runCatching null
                val bytes = file.readBytes()
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val numT = buf.int
                val numF = buf.int
                if (numT <= 0 || numF <= 0) return@runCatching null
                val pixels = IntArray(numT * numF)
                for (t in 0 until numT) {
                    for (f in 0 until numF) {
                        val encoded = buf.short.toInt() and 0xFFFF
                        val v = encoded / 65535f
                        // flip Y: low freq (f=0) at bottom → row index = numF-1-f
                        pixels[t + (numF - 1 - f) * numT] = spectrogramColorArgb(v)
                    }
                }
                val bmp = Bitmap.createBitmap(numT, numF, Bitmap.Config.ARGB_8888)
                bmp.setPixels(pixels, 0, numT, 0, 0, numT, numF)
                bmp
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Spectrogram",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        // Fallback while loading or if file missing
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            drawRect(color = androidx.compose.ui.graphics.Color(0xFF1A1A1A))
        }
    }
}

// SpotiFLAC _spekColorRGB: dark→blue/purple→magenta→red→orange→yellow→white
private val spectStopPos = floatArrayOf(0f, 0.08f, 0.18f, 0.28f, 0.40f, 0.52f, 0.65f, 0.78f, 0.90f, 1.0f)
private val spectStopR = intArrayOf(0, 0, 50, 200, 255, 255, 255, 255, 255, 255)
private val spectStopG = intArrayOf(0, 0, 30, 0, 0, 100, 180, 235, 255, 255)
private val spectStopB = intArrayOf(0, 80, 255, 200, 0, 0, 0, 30, 130, 255)

private fun spectrogramColorArgb(v: Float): Int {
    val vc = v.coerceIn(0f, 1f)
    var lo = spectStopPos.size - 2
    for (i in 1 until spectStopPos.size) {
        if (spectStopPos[i] >= vc) {
            lo = i - 1
            break
        }
    }
    val hi = lo + 1
    val t = if (spectStopPos[hi] > spectStopPos[lo]) {
        (vc - spectStopPos[lo]) / (spectStopPos[hi] - spectStopPos[lo])
    } else {
        0f
    }
    val r = (spectStopR[lo] + (spectStopR[hi] - spectStopR[lo]) * t).toInt().coerceIn(0, 255)
    val g = (spectStopG[lo] + (spectStopG[hi] - spectStopG[lo]) * t).toInt().coerceIn(0, 255)
    val b = (spectStopB[lo] + (spectStopB[hi] - spectStopB[lo]) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun formatTelegramDate(dateStr: String?): String? {
    if (dateStr.isNullOrBlank()) return null
    return try {
        val epochSeconds = dateStr.toLong()
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000))
    } catch (_: Exception) {
        null
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
