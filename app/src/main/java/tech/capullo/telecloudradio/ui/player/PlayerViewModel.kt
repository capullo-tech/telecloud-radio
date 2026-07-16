package tech.capullo.telecloudradio.ui.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tech.capullo.source.telegram.data.telegram.MessageReactionsInfo
import tech.capullo.telecloudradio.data.ConnectivityMonitor
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.db.AudioAnalysisDao
import tech.capullo.telecloudradio.data.db.AudioAnalysisEntity
import tech.capullo.telecloudradio.data.db.MediaMessageEntity
import tech.capullo.telecloudradio.data.playlist.ActiveTrackRepository
import tech.capullo.telecloudradio.data.playlist.PlaybackCommand
import tech.capullo.telecloudradio.data.playlist.PlaylistRepository
import tech.capullo.telecloudradio.data.telegram.TelegramRepository
import tech.capullo.telecloudradio.player.AlbumArtFetcher
import tech.capullo.telecloudradio.player.AudioAnalyzer
import tech.capullo.telecloudradio.player.AudioMetadata
import tech.capullo.telecloudradio.player.AudioMetadataReader
import tech.capullo.telecloudradio.player.DownloadManager
import tech.capullo.telecloudradio.player.PlaybackService
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

enum class PlayOrder { NEWEST_FIRST, OLDEST_FIRST, SHUFFLED }

data class PlayerUiState(
    val track: MediaMessageEntity? = null,
    val albumArt: ByteArray? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentIndex: Int = 0,
    val totalTracks: Int = 0,
    val totalSizeGb: Double = 0.0,
    val isPlaying: Boolean = false,
    val playOrder: PlayOrder = PlayOrder.NEWEST_FIRST,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val showQueue: Boolean = false,
    val orderedPlaylist: List<MediaMessageEntity> = emptyList(),
    val displayPlaylist: List<MediaMessageEntity> = emptyList(),
    val unfilteredPlaylist: List<MediaMessageEntity> = emptyList(),
    val queueFilters: QueueFilters = QueueFilters(),
    val currentPosition: Long = 0L,
    val trackDuration: Long = 0L,
    val isOffline: Boolean = false,
    // Download progress (0f..1f) of the current/active track while it's downloading, so the play
    // button can show a determinate ring instead of an indeterminate spinner. Null before the first
    // progress tick (or once the file is on disk) → the button falls back to indeterminate.
    val downloadProgress: Float? = null,
    val audioMeta: AudioMetadata? = null,
    val showStats: Boolean = false,
    val audioAnalysis: AudioAnalysisEntity? = null,
    val isAnalyzing: Boolean = false,
    val nextTrackReady: Boolean = false,
    val nextDownloadProgress: Float? = null,
    val showReactions: Boolean = false,
    val reactionsInfo: MessageReactionsInfo? = null,
    val reactionsLoading: Boolean = false,
    val sleepTimerActive: Boolean = false,
    // Seconds until the timer fires; 0 while waiting for the current track to finish
    val sleepTimerSecondsRemaining: Int = 0,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val downloadManager: DownloadManager,
    private val activeTrackRepository: ActiveTrackRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val telegramRepository: TelegramRepository,
    private val audioMetadataReader: AudioMetadataReader,
    private val audioAnalyzer: AudioAnalyzer,
    private val audioAnalysisDao: AudioAnalysisDao,
    private val settings: SettingsRepository,
    private val albumArtFetcher: AlbumArtFetcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    private val _downloadToast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val downloadToast = _downloadToast.asSharedFlow()

    private var controller: MediaController? = null
    private var orderedPlaylist: List<MediaMessageEntity> = emptyList()

    // basePlaylist = ordered/shuffled, unfiltered; playlist = basePlaylist with queue filters applied
    private var basePlaylist: List<MediaMessageEntity> = emptyList()
    private var playlist: List<MediaMessageEntity> = emptyList()
    private var currentIndex = 0
    private var prefetchJob: Job? = null
    private var positionJob: Job? = null
    private var sleepTimerJob: Job? = null

    // Set when the countdown hit zero mid-track: playback finishes the current
    // track, then onTrackEnded pauses instead of advancing.
    private var sleepAtTrackEnd = false
    private var loadedChatId = 0L
    private var chatId = 0L
    private var chatTitle = ""
    private var controllerPreparedForCurrentChat = false

    init {
        viewModelScope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            controller = suspendCancellableCoroutine { cont ->
                val future = MediaController.Builder(context, token).buildAsync()
                future.addListener({
                    runCatching { cont.resume(future.get()) }
                }, context.mainExecutor)
                cont.invokeOnCancellation { future.cancel(true) }
            }
            if (!controllerPreparedForCurrentChat) controller?.pause()
            controller?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        val dur = controller?.duration ?: 0L
                        if (dur > 0L) _uiState.value = _uiState.value.copy(trackDuration = dur)
                    }
                    if (state == Player.STATE_ENDED) onTrackEnded()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && !controllerPreparedForCurrentChat) {
                        controller?.pause()
                        return
                    }
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                    activeTrackRepository.updateIsPlaying(isPlaying)
                    if (isPlaying) startPositionTracking() else positionJob?.cancel()
                }
            })
        }
        viewModelScope.launch {
            activeTrackRepository.command.collect { cmd ->
                when (cmd) {
                    PlaybackCommand.NEXT -> nextTrack()
                    PlaybackCommand.PREV -> prevTrack()
                }
            }
        }
        viewModelScope.launch {
            connectivityMonitor.isOnline.drop(1).collect { online ->
                if (chatId != 0L) reloadPlaylist(online)
            }
        }
        viewModelScope.launch {
            telegramRepository.newTrackStored.collect { (incomingChatId, title) ->
                if (incomingChatId == chatId && chatId != 0L) {
                    mergeNewTracks()
                    _downloadToast.tryEmit("New track · $title")
                }
            }
        }
        viewModelScope.launch {
            telegramRepository.tracksDeleted.collect { (delChatId, ids) ->
                if (delChatId == chatId && chatId != 0L) {
                    val removed = removeDeletedFromQueue(ids.toSet())
                    if (removed > 0) {
                        _downloadToast.tryEmit(
                            "$removed track${if (removed > 1) "s" else ""} removed from chat",
                        )
                    }
                }
            }
        }
        // Safety net for uploads that TDLib push misses (or that happened while the
        // app was closed and the station was auto-opened, skipping the group-tap sync):
        // incremental sync every 5 minutes while this player is active.
        viewModelScope.launch {
            while (true) {
                delay(5 * 60_000L)
                syncAndMerge()
            }
        }
        viewModelScope.launch {
            downloadManager.downloadProgress.collect { map ->
                // Active-track progress → determinate ring on the play button (independent of the
                // next-track wiring below). Null once the download finishes / isn't in flight.
                val activeProgress = downloadManager.activeMessageId?.let { map[it] }
                if (_uiState.value.downloadProgress != activeProgress) {
                    _uiState.value = _uiState.value.copy(downloadProgress = activeProgress)
                }
                val nextId = nextIndex()?.let { playlist.getOrNull(it)?.messageId }
                val progress = nextId?.let { map[it] }
                if (progress != null) {
                    _uiState.value = _uiState.value.copy(
                        nextDownloadProgress = progress,
                        nextTrackReady = false,
                    )
                } else {
                    // No download in flight for the next track — verify cache/DB
                    refreshNextTrackState()
                }
            }
        }
    }

    private fun nextIndex(): Int? {
        if (playlist.isEmpty()) return null
        val next = currentIndex + 1
        return when {
            next < playlist.size -> next
            _uiState.value.repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> null
        }
    }

    private fun refreshNextTrackState() {
        viewModelScope.launch {
            val ni = nextIndex()
            val nextId = ni?.let { playlist.getOrNull(it)?.messageId }
            _uiState.value = _uiState.value.copy(
                nextTrackReady = nextId != null && downloadManager.isDownloaded(nextId),
                nextDownloadProgress = nextId?.let { downloadManager.downloadProgress.value[it] },
            )
        }
    }

    fun loadAndPlay(chatId: Long, chatTitle: String) {
        if (loadedChatId == chatId) {
            // Same station re-opened (group tap just ran a sync, or the library was
            // rebuilt) — pull anything the in-memory queue hasn't seen yet
            viewModelScope.launch { mergeNewTracks() }
            return
        }
        loadedChatId = chatId
        this.chatId = chatId
        this.chatTitle = chatTitle
        controllerPreparedForCurrentChat = false
        positionJob?.cancel()
        prefetchJob?.cancel()
        controller?.pause()
        // Always persist so auto-open works next launch
        settings.lastGroupId = chatId
        settings.lastGroupTitle = chatTitle
        viewModelScope.launch {
            val isOnline = connectivityMonitor.isOnline.value
            orderedPlaylist = if (isOnline) {
                playlistRepository.loadPlaylist(chatId)
            } else {
                playlistRepository.loadLocalPlaylist(chatId)
            }
            if (orderedPlaylist.isEmpty() && isOnline) {
                // Auto-open can land on a station whose library hasn't been synced yet (a fresh or
                // cleared DB with a remembered last group): it skips the group-tap sync. Sync it now
                // the same way the group tap does — look the chat up (for its real type) and fetch
                // its audio history — before giving up, so auto-open doesn't dead-end on the empty
                // "No audio tracks found" screen.
                _uiState.value = PlayerUiState(isLoading = true)
                val chat = runCatching { telegramRepository.getAudioGroups(200) }
                    .getOrNull()?.find { it.id == chatId }
                if (chat != null) {
                    runCatching { telegramRepository.syncAudioMessages(chat) }
                    orderedPlaylist = playlistRepository.loadPlaylist(chatId)
                }
            }
            if (orderedPlaylist.isEmpty()) {
                basePlaylist = emptyList()
                playlist = emptyList()
                _uiState.value = PlayerUiState(
                    error = if (isOnline) {
                        "No audio tracks found in this station"
                    } else {
                        "No local tracks available offline"
                    },
                )
                return@launch
            }
            val (playOrder, filters) = restoreQueueState()
            val totalSizeGb = playlist.sumOf { it.fileSize ?: 0L } / (1024.0 * 1024.0 * 1024.0)
            val savedMessageId = getLastPlayed(chatId)
            val startIndex = if (savedMessageId != null) {
                playlist.indexOfFirst { it.messageId == savedMessageId }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            currentIndex = startIndex
            val track = playlist[startIndex]
            _uiState.value = PlayerUiState(
                track = track,
                isLoading = false,
                totalTracks = playlist.size,
                totalSizeGb = totalSizeGb,
                orderedPlaylist = orderedPlaylist,
                displayPlaylist = playlist,
                unfilteredPlaylist = basePlaylist,
                currentIndex = startIndex,
                isOffline = !isOnline,
                playOrder = playOrder,
                queueFilters = filters,
            )
            activeTrackRepository.set(track, chatId, chatTitle)
            refreshNextTrackState()
            prefetchAhead(startIndex)
            saveQueueState()
            // Catch up on uploads since the last sync (auto-open skips the group-tap sync)
            syncAndMerge()
        }
    }

    // Restores the persisted queue (order, filters, manual edits) for this chat,
    // merging in tracks that were synced while the app was closed. Falls back to
    // the default newest-first full queue. Sets basePlaylist/playlist.
    private fun restoreQueueState(): Pair<PlayOrder, QueueFilters> {
        val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val savedOrder = runCatching {
            PlayOrder.valueOf(prefs.getString("play_order_$chatId", null) ?: "")
        }.getOrNull()
        val filters = QueueFilters.fromJson(prefs.getString("filters_$chatId", null))
        val byId = orderedPlaylist.associateBy { it.messageId }
        fun tracksFor(key: String) = prefs.getString(key, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull()?.let(byId::get) }
            ?: emptyList()
        val savedBase = tracksFor("base_ids_$chatId")
        val savedQueue = tracksFor("queue_ids_$chatId")

        if (savedOrder == null || savedBase.isEmpty()) {
            basePlaylist = orderedPlaylist
            playlist = orderedPlaylist
            return PlayOrder.NEWEST_FIRST to QueueFilters()
        }

        val known = savedBase.mapTo(HashSet()) { it.messageId }
        val fresh = orderedPlaylist.filter { it.messageId !in known }
        basePlaylist = when (savedOrder) {
            PlayOrder.NEWEST_FIRST -> orderedPlaylist
            PlayOrder.OLDEST_FIRST -> orderedPlaylist.reversed()
            PlayOrder.SHUFFLED -> savedBase + fresh
        }
        val admitted = if (filters.isActive) fresh.filter(filters::matches) else fresh
        playlist = when (savedOrder) {
            PlayOrder.NEWEST_FIRST -> admitted + savedQueue
            else -> savedQueue + admitted
        }.ifEmpty { basePlaylist } // never restore into an unplayable empty queue
        return savedOrder to filters
    }

    private fun saveQueueState() {
        if (chatId == 0L) return
        context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit()
            .putString("queue_ids_$chatId", playlist.joinToString(",") { it.messageId.toString() })
            .putString(
                "base_ids_$chatId",
                basePlaylist.joinToString(",") {
                    it.messageId.toString()
                },
            )
            .putString("play_order_$chatId", _uiState.value.playOrder.name)
            .putString("filters_$chatId", _uiState.value.queueFilters.toJson())
            .apply()
    }

    // Drops deleted tracks from library + queue; returns how many queue rows went away.
    // The currently playing track keeps playing even if its row was deleted.
    private fun removeDeletedFromQueue(ids: Set<Long>): Int {
        val before = playlist.size
        orderedPlaylist = orderedPlaylist.filterNot { it.messageId in ids }
        basePlaylist = basePlaylist.filterNot { it.messageId in ids }
        val currentTrackId = _uiState.value.track?.messageId
        playlist = playlist.filterNot { it.messageId in ids }
        currentIndex = currentTrackId?.let { id ->
            playlist.indexOfFirst { it.messageId == id }
        } ?: -1
        _uiState.value = _uiState.value.copy(orderedPlaylist = orderedPlaylist)
        publishQueue()
        return before - playlist.size
    }

    private suspend fun syncAndMerge() {
        if (chatId == 0L || !connectivityMonitor.isOnline.value) return
        runCatching {
            val added = telegramRepository.syncChatById(chatId)
            if (added > 0) {
                mergeNewTracks()
                _downloadToast.tryEmit(
                    "$added new track${if (added > 1) "s" else ""} · library updated",
                )
            }
        }
    }

    private suspend fun reloadPlaylist(online: Boolean) {
        if (chatId == 0L) return
        val currentTrackId = _uiState.value.track?.messageId
        val newOrdered = if (online) {
            playlistRepository.loadPlaylist(chatId)
        } else {
            playlistRepository.loadLocalPlaylist(chatId)
        }
        if (newOrdered.isEmpty()) {
            _uiState.value = _uiState.value.copy(isOffline = !online)
            return
        }
        orderedPlaylist = newOrdered
        basePlaylist = when (_uiState.value.playOrder) {
            PlayOrder.NEWEST_FIRST -> orderedPlaylist
            PlayOrder.OLDEST_FIRST -> orderedPlaylist.reversed()
            PlayOrder.SHUFFLED -> {
                val current = orderedPlaylist.firstOrNull { it.messageId == currentTrackId }
                if (current != null) {
                    listOf(current) +
                        orderedPlaylist.filter { it.messageId != currentTrackId }.shuffled()
                } else {
                    orderedPlaylist.shuffled()
                }
            }
        }
        _uiState.value = _uiState.value.copy(
            orderedPlaylist = orderedPlaylist,
            isOffline = !online,
        )
        rebuildActivePlaylist()
    }

    // Publishes the current queue to UI state and re-targets readiness/prefetch.
    // Callers mutate `playlist`/`currentIndex` first, then call this.
    private fun publishQueue() {
        _uiState.value = _uiState.value.copy(
            totalTracks = playlist.size,
            currentIndex = currentIndex,
            displayPlaylist = playlist,
            unfilteredPlaylist = basePlaylist,
            totalSizeGb = playlist.sumOf { it.fileSize ?: 0L } / (1024.0 * 1024.0 * 1024.0),
        )
        refreshNextTrackState()
        // Prefetch even before playback starts (e.g. shuffling right after app open) —
        // otherwise "next" stays greyed with nothing downloading it
        if (playlist.isNotEmpty()) prefetchAhead(currentIndex)
        saveQueueState()
    }

    // Rebuilds the queue from the library (basePlaylist + filters), discarding manual
    // queue edits. Used on load, order cycle, connectivity reload, and filter Apply.
    private fun rebuildActivePlaylist() {
        val filters = _uiState.value.queueFilters
        playlist = if (filters.isActive) basePlaylist.filter(filters::matches) else basePlaylist
        val currentId = _uiState.value.track?.messageId
        // -1 when the playing track is excluded by the filters; "next" then starts at 0
        currentIndex = currentId?.let { id -> playlist.indexOfFirst { it.messageId == id } } ?: -1
        publishQueue()
    }

    fun setQueueFilters(filters: QueueFilters) {
        if (filters == _uiState.value.queueFilters) return
        _uiState.value = _uiState.value.copy(queueFilters = filters)
        rebuildActivePlaylist()
        _downloadToast.tryEmit("Queue rebuilt · ${playlist.size} tracks")
    }

    // ---- Queue editing (duplicates allowed, everything index-based) ----

    fun playAt(index: Int) {
        if (_uiState.value.isLoading) return
        if (index in playlist.indices) viewModelScope.launch { playTrack(index) }
    }

    // Library tap: insert right after the current position and jump to it
    fun playNow(track: MediaMessageEntity) {
        if (_uiState.value.isLoading) return
        val insertAt = (currentIndex + 1).coerceIn(0, playlist.size)
        playlist = playlist.toMutableList().apply { add(insertAt, track) }
        publishQueue()
        viewModelScope.launch { playTrack(insertAt) }
    }

    fun playNext(track: MediaMessageEntity) {
        val insertAt = (currentIndex + 1).coerceIn(0, playlist.size)
        playlist = playlist.toMutableList().apply { add(insertAt, track) }
        publishQueue()
        _downloadToast.tryEmit("Playing next · ${track.title ?: track.fileName ?: "Unknown"}")
    }

    fun addToQueue(track: MediaMessageEntity) {
        playlist = playlist + track
        publishQueue()
        _downloadToast.tryEmit("Added to queue · ${track.title ?: track.fileName ?: "Unknown"}")
    }

    fun removeFromQueue(index: Int) {
        if (index !in playlist.indices) return
        if (index == currentIndex) {
            _downloadToast.tryEmit("Can't remove the playing track")
            return
        }
        playlist = playlist.toMutableList().apply { removeAt(index) }
        if (index < currentIndex) currentIndex--
        publishQueue()
    }

    // Drag-reorder: move a queue row, keeping the playing row tracked
    fun moveInQueue(from: Int, to: Int) {
        if (from !in playlist.indices || from == to) return
        val target = to.coerceIn(0, playlist.size - 1)
        if (from == target) return
        val mutable = playlist.toMutableList()
        val track = mutable.removeAt(from)
        mutable.add(target, track)
        playlist = mutable
        currentIndex = when {
            from == currentIndex -> target
            from < currentIndex && target >= currentIndex -> currentIndex - 1
            from > currentIndex && target <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        publishQueue()
    }

    // Queue long-press "Play next": move an existing queue row to right after current
    fun queuePlayNext(index: Int) {
        if (index !in playlist.indices || index == currentIndex) return
        val mutable = playlist.toMutableList()
        val track = mutable.removeAt(index)
        if (index < currentIndex) currentIndex--
        val insertAt = (currentIndex + 1).coerceIn(0, mutable.size)
        mutable.add(insertAt, track)
        playlist = mutable
        publishQueue()
        _downloadToast.tryEmit("Playing next · ${track.title ?: track.fileName ?: "Unknown"}")
    }

    // Pulls freshly pushed tracks from the DB into the library and inserts them into
    // the live queue (without discarding manual queue edits) when they pass the
    // applied filters. NEWEST → queue top, OLDEST/SHUFFLED → queue end.
    private suspend fun mergeNewTracks() {
        val isOnline = connectivityMonitor.isOnline.value
        val newOrdered = if (isOnline) {
            playlistRepository.loadPlaylist(chatId)
        } else {
            playlistRepository.loadLocalPlaylist(chatId)
        }
        if (newOrdered.isEmpty()) return
        val known = orderedPlaylist.map { it.messageId }.toSet()
        val fresh = newOrdered.filter { it.messageId !in known }
        orderedPlaylist = newOrdered
        basePlaylist = when (_uiState.value.playOrder) {
            PlayOrder.NEWEST_FIRST -> orderedPlaylist
            PlayOrder.OLDEST_FIRST -> orderedPlaylist.reversed()
            PlayOrder.SHUFFLED -> basePlaylist + fresh
        }
        _uiState.value = _uiState.value.copy(orderedPlaylist = orderedPlaylist)
        val filters = _uiState.value.queueFilters
        val admitted = if (filters.isActive) fresh.filter(filters::matches) else fresh
        if (admitted.isNotEmpty()) {
            when (_uiState.value.playOrder) {
                PlayOrder.NEWEST_FIRST -> {
                    playlist = admitted + playlist
                    if (currentIndex >= 0) currentIndex += admitted.size
                }
                else -> playlist = playlist + admitted
            }
        }
        publishQueue()
    }

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                _uiState.value =
                    _uiState.value.copy(currentPosition = controller?.currentPosition ?: 0L)
                delay(500L)
            }
        }
    }

    fun toggleSleepTimer() {
        if (_uiState.value.sleepTimerActive) {
            cancelSleepTimer()
            return
        }
        val totalSec = settings.sleepTimerMinutes * 60
        _uiState.value = _uiState.value.copy(
            sleepTimerActive = true,
            sleepTimerSecondsRemaining = totalSec,
        )
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalSec
            while (remaining > 0) {
                delay(1_000L)
                remaining--
                _uiState.value = _uiState.value.copy(sleepTimerSecondsRemaining = remaining)
            }
            // Countdown done — let the current track play out, then pause
            if (controller?.isPlaying == true) {
                sleepAtTrackEnd = true
            } else {
                cancelSleepTimer()
            }
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAtTrackEnd = false
        _uiState.value =
            _uiState.value.copy(sleepTimerActive = false, sleepTimerSecondsRemaining = 0)
    }

    private fun onTrackEnded() {
        if (sleepAtTrackEnd) {
            // Sleep timer fired mid-track: stop here instead of advancing
            cancelSleepTimer()
            controller?.pause()
            return
        }
        if (playlist.isEmpty() || !controllerPreparedForCurrentChat) return
        when (_uiState.value.repeatMode) {
            // currentIndex is -1 when the playing track is filtered out of the queue —
            // repeat it in place rather than indexing into the playlist
            Player.REPEAT_MODE_ONE ->
                if (currentIndex >= 0) {
                    viewModelScope.launch { playTrack(currentIndex) }
                } else {
                    controller?.run {
                        seekTo(0L)
                        play()
                    }
                }
            Player.REPEAT_MODE_ALL -> viewModelScope.launch {
                playTrack((currentIndex + 1) % playlist.size)
            }
            else ->
                if (currentIndex + 1 <
                    playlist.size
                ) {
                    viewModelScope.launch { playTrack(currentIndex + 1) }
                }
        }
    }

    fun nextTrack() {
        if (_uiState.value.isLoading) return
        val next = currentIndex + 1
        if (next < playlist.size) {
            viewModelScope.launch { playTrack(next) }
        } else if (_uiState.value.repeatMode ==
            Player.REPEAT_MODE_ALL
        ) {
            viewModelScope.launch { playTrack(0) }
        }
    }

    fun prevTrack() {
        if (_uiState.value.isLoading) return
        val position = controller?.currentPosition ?: 0L
        if (position > 3_000L) {
            controller?.seekTo(0L)
            _uiState.value = _uiState.value.copy(currentPosition = 0L)
        } else if (currentIndex > 0) {
            viewModelScope.launch { playTrack(currentIndex - 1) }
        }
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        when {
            // Pause always works — even while another track is downloading
            ctrl.isPlaying -> ctrl.pause()
            // While loading, only resume the already-prepared audio; never start a second load
            _uiState.value.isLoading ->
                if (ctrl.playbackState != Player.STATE_IDLE && ctrl.mediaItemCount > 0) ctrl.play()
            playlist.isEmpty() -> return
            !controllerPreparedForCurrentChat ->
                viewModelScope.launch { playTrack(currentIndex.coerceAtLeast(0)) }
            ctrl.playbackState != Player.STATE_IDLE && ctrl.mediaItemCount > 0 -> ctrl.play()
            else -> viewModelScope.launch { playTrack(currentIndex.coerceAtLeast(0)) }
        }
    }

    fun cyclePlayOrder() {
        val current = _uiState.value.track
        val newOrder = when (_uiState.value.playOrder) {
            PlayOrder.NEWEST_FIRST -> PlayOrder.OLDEST_FIRST
            PlayOrder.OLDEST_FIRST -> PlayOrder.SHUFFLED
            PlayOrder.SHUFFLED -> PlayOrder.NEWEST_FIRST
        }
        basePlaylist = when (newOrder) {
            PlayOrder.NEWEST_FIRST -> orderedPlaylist
            PlayOrder.OLDEST_FIRST -> orderedPlaylist.reversed()
            PlayOrder.SHUFFLED -> {
                val cur = current ?: orderedPlaylist.firstOrNull() ?: return
                listOf(cur) + orderedPlaylist.filter { it.messageId != cur.messageId }.shuffled()
            }
        }
        _uiState.value = _uiState.value.copy(playOrder = newOrder)
        rebuildActivePlaylist()
    }

    fun cycleRepeatMode() {
        val next = when (_uiState.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        _uiState.value = _uiState.value.copy(repeatMode = next)
        refreshNextTrackState()
    }

    fun seekTo(positionMs: Long) {
        if (!controllerPreparedForCurrentChat) return
        controller?.seekTo(positionMs)
        _uiState.value = _uiState.value.copy(currentPosition = positionMs)
    }

    fun toggleQueue() {
        _uiState.value = _uiState.value.copy(showQueue = !_uiState.value.showQueue)
    }

    fun toggleStats() {
        val showing = !_uiState.value.showStats
        _uiState.value = _uiState.value.copy(showStats = showing)
        if (showing && _uiState.value.audioAnalysis == null && !_uiState.value.isAnalyzing) {
            analyzeCurrentTrack()
        }
    }

    private fun analyzeCurrentTrack() {
        val track = _uiState.value.track ?: return
        _uiState.value = _uiState.value.copy(isAnalyzing = true)
        viewModelScope.launch {
            val cached = audioAnalysisDao.get(track.messageId)
            if (cached != null) {
                _uiState.value = _uiState.value.copy(isAnalyzing = false, audioAnalysis = cached)
                return@launch
            }
            val path = downloadManager.getCachedPath(track.messageId)
                ?: downloadManager.ensureDownloaded(track.chatId, track.messageId)
            if (path != null) {
                val spectFile = downloadManager.spectrogramFile(track.messageId)
                val result = withContext(Dispatchers.Default) {
                    audioAnalyzer.analyze(path, track.mimeType, spectFile)
                }
                if (result != null) {
                    val entity = AudioAnalysisEntity(
                        messageId = track.messageId,
                        peakDb = result.peakDb,
                        rmsDb = result.rmsDb,
                        dynamicRange = result.dynamicRange,
                        spectralCutoffHz = result.spectralCutoffHz,
                        nyquistHz = result.nyquistHz,
                        likelyTrueLossless = result.likelyTrueLossless,
                        spectrumCsv = result.spectrumMagnitudesDb.joinToString(",") {
                            "%.1f".format(it)
                        },
                        spectrogramPath = result.spectrogramFile?.absolutePath,
                        analyzedAt = System.currentTimeMillis(),
                        lufs = result.lufs,
                        truePeakDb = result.truePeakDb,
                        clipping = result.clipping,
                        totalSamples = result.totalSamples,
                        channelStatsCsv = result.channelStats
                            .joinToString(";") {
                                "${"%.2f".format(
                                    it.peakDb,
                                )},${"%.2f".format(it.rmsDb)},${"%.2f".format(it.drDb)}"
                            }
                            .takeIf { result.channelStats.isNotEmpty() },
                    )
                    audioAnalysisDao.insert(entity)
                    if (_uiState.value.track?.messageId == track.messageId) {
                        _uiState.value =
                            _uiState.value.copy(isAnalyzing = false, audioAnalysis = entity)
                    }
                    return@launch
                }
            }
            _uiState.value = _uiState.value.copy(isAnalyzing = false)
        }
    }

    fun downloadCurrentTrack() {
        val track = _uiState.value.track ?: return
        viewModelScope.launch {
            val path = downloadManager.getCachedPath(track.messageId)
                ?: downloadManager.ensureDownloaded(track.chatId, track.messageId)
                ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = track.fileName ?: File(path).name
                    val mimeType = track.mimeType ?: "audio/*"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Check if file already exists in Downloads
                        val existing = context.contentResolver.query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Downloads._ID),
                            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                            arrayOf(fileName),
                            null,
                        )
                        val alreadyExists = (existing?.count ?: 0) > 0
                        existing?.close()
                        if (alreadyExists) {
                            _downloadToast.tryEmit("Already in Downloads · $fileName")
                            return@runCatching
                        }
                        val values = android.content.ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, mimeType)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@runCatching
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            File(path).inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS,
                        )
                        dir.mkdirs()
                        val dest = File(dir, fileName)
                        if (dest.exists()) {
                            _downloadToast.tryEmit("Already in Downloads · $fileName")
                            return@runCatching
                        }
                        File(path).copyTo(dest)
                    }
                    _downloadToast.tryEmit("Saved to Downloads · $fileName")
                }
            }
        }
    }

    private suspend fun playTrack(index: Int) {
        currentIndex = index
        val track = playlist[index]
        _uiState.value = _uiState.value.copy(
            track = track,
            isLoading = true,
            downloadProgress = null,
            currentIndex = index,
            albumArt = null,
            currentPosition = 0L,
            trackDuration = 0L,
            audioMeta = null,
            audioAnalysis = null,
            showStats = false,
            showReactions = false,
            reactionsInfo = null,
        )
        activeTrackRepository.set(track, chatId, chatTitle)
        saveLastPlayed(chatId, track.messageId)

        downloadManager.activeMessageId = track.messageId
        val path = downloadManager.ensureDownloaded(track.chatId, track.messageId)
        if (path == null) {
            // Lazy deletion fallback: if the download failed because the message was
            // deleted from the chat, the row is already purged — skip to the next
            // track instead of stalling
            if (!playlistRepository.exists(track.messageId)) {
                _downloadToast.tryEmit("Removed from chat · skipping")
                removeDeletedFromQueue(setOf(track.messageId))
                if (playlist.isNotEmpty()) {
                    playTrack(index.coerceAtMost(playlist.size - 1))
                } else {
                    _uiState.value =
                        _uiState.value.copy(isLoading = false, error = "No tracks left in queue")
                }
                return
            }
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Download failed")
            return
        }

        val (albumArt, audioMeta) = withContext(Dispatchers.IO) {
            val meta = audioMetadataReader.read(path)
            // Embedded picture first, then previously fetched online art. Square-crop it so the
            // lock-screen/notification artwork and the snapcast/web art aren't stretched: embedded
            // covers can be any aspect ratio (online-fetched art is already square → no-op).
            Pair(squareCropArt(extractAlbumArt(path) ?: albumArtFetcher.cached(track.messageId)), meta)
        }
        val displayTrack = track.copy(
            title = audioMeta.tagTitle ?: track.title,
            performer = audioMeta.tagArtist ?: track.performer,
        )
        _uiState.value = _uiState.value.copy(
            track = displayTrack,
            albumArt = albumArt,
            isLoading = false,
            audioMeta = audioMeta,
        )
        activeTrackRepository.updateTrack(displayTrack)
        activeTrackRepository.updateAlbumArt(albumArt)

        // No embedded or cached art — fetch online in the background (Deezer → iTunes);
        // updates the in-app art when it lands, lock screen picks it up next play
        if (albumArt == null && connectivityMonitor.isOnline.value) {
            viewModelScope.launch {
                val fetched = albumArtFetcher.fetch(
                    messageId = track.messageId,
                    artist = audioMeta.tagArtist ?: track.performer,
                    title = audioMeta.tagTitle ?: track.title,
                    fileName = track.fileName,
                )
                if (fetched != null && _uiState.value.track?.messageId == track.messageId) {
                    _uiState.value = _uiState.value.copy(albumArt = fetched)
                    activeTrackRepository.updateAlbumArt(fetched)
                }
            }
        }

        controllerPreparedForCurrentChat = true
        // MediaMetadata drives the lock screen / notification (title, artist, artwork)
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(displayTrack.title ?: displayTrack.fileName ?: "Unknown")
            .setArtist(displayTrack.performer)
            .setStation(chatTitle.takeIf { it.isNotBlank() })
            .apply { albumArt?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        controller?.apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(File(path)))
                    .setMediaMetadata(mediaMetadata)
                    .build(),
            )
            prepare()
            play()
        }

        refreshNextTrackState()
        prefetchAhead(index)
        // Note: no eager eviction of the previous track — recently played files stay on
        // disk (LRU) until the GB buffer limit pushes them out, so "previous" is instant.

        if (connectivityMonitor.isOnline.value) {
            viewModelScope.launch {
                runCatching {
                    val reactions = telegramRepository.refreshReactions(
                        track.chatId,
                        track.messageId,
                    )
                    if (_uiState.value.track?.messageId == track.messageId) {
                        _uiState.value = _uiState.value.copy(
                            track = _uiState.value.track!!.copy(reactions = reactions),
                        )
                    }
                }
            }
        }
    }

    private fun prefetchAhead(fromIndex: Int) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            for (i in (fromIndex + 1)..minOf(fromIndex + 2, playlist.size - 1)) {
                val t = playlist[i]
                val path = downloadManager.getCachedPath(t.messageId)
                    ?: downloadManager.ensureDownloaded(t.chatId, t.messageId)
                refreshNextTrackState()
                // Pre-fetch online art too, so the lock screen has it at play start
                if (path != null && albumArtFetcher.cached(t.messageId) == null &&
                    connectivityMonitor.isOnline.value
                ) {
                    val (embedded, meta) = withContext(Dispatchers.IO) {
                        Pair(extractAlbumArt(path), audioMetadataReader.read(path))
                    }
                    if (embedded == null) {
                        albumArtFetcher.fetch(
                            messageId = t.messageId,
                            artist = meta.tagArtist ?: t.performer,
                            title = meta.tagTitle ?: t.title,
                            fileName = t.fileName,
                        )
                    }
                }
            }
        }
    }

    fun toggleReactions() {
        val showing = !_uiState.value.showReactions
        _uiState.value = _uiState.value.copy(showReactions = showing)
        if (showing) loadReactionsInfo()
    }

    private fun loadReactionsInfo() {
        val track = _uiState.value.track ?: return
        _uiState.value = _uiState.value.copy(reactionsLoading = true, reactionsInfo = null)
        viewModelScope.launch {
            val info = runCatching {
                telegramRepository.getReactionsInfo(track.chatId, track.messageId)
            }.getOrNull()
            if (_uiState.value.track?.messageId == track.messageId) {
                _uiState.value = _uiState.value.copy(reactionsLoading = false, reactionsInfo = info)
            }
        }
    }

    fun setReaction(emoji: String) {
        val track = _uiState.value.track ?: return
        // Tapping your current reaction removes it
        val newEmoji = if (_uiState.value.reactionsInfo?.ownEmoji == emoji) null else emoji
        _uiState.value = _uiState.value.copy(reactionsLoading = true)
        viewModelScope.launch {
            val result = runCatching {
                telegramRepository.setOwnReaction(track.chatId, track.messageId, newEmoji)
            }
            val info = result.getOrNull()
            // Surface the failure instead of silently doing nothing.
            result.exceptionOrNull()?.let { e ->
                _downloadToast.tryEmit("Couldn't react: ${e.message ?: "unknown error"}")
            }
            if (_uiState.value.track?.messageId == track.messageId) {
                _uiState.value = _uiState.value.copy(
                    reactionsLoading = false,
                    // The read-back can lag TDLib's own reaction update, so trust our intended
                    // choice for ownEmoji rather than a possibly-stale server echo.
                    reactionsInfo = info?.copy(ownEmoji = newEmoji) ?: _uiState.value.reactionsInfo,
                    track = if (info != null) {
                        _uiState.value.track?.copy(reactions = info.summary)
                    } else {
                        _uiState.value.track
                    },
                )
            }
        }
    }

    private fun extractAlbumArt(path: String): ByteArray? = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(path)
            mmr.embeddedPicture
        }
    }.getOrNull()

    // Center-crop artwork to a square so it isn't stretched by the OS notification/lock-screen
    // (Media3 hands the raw bytes straight through). Already-square art is returned untouched.
    private fun squareCropArt(bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        return runCatching {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            if (bitmap.width == bitmap.height) return bytes
            val side = minOf(bitmap.width, bitmap.height)
            val cropped = Bitmap.createBitmap(
                bitmap,
                (bitmap.width - side) / 2,
                (bitmap.height - side) / 2,
                side,
                side,
            )
            java.io.ByteArrayOutputStream().use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
        }.getOrDefault(bytes)
    }

    private fun saveLastPlayed(chatId: Long, messageId: Long) {
        context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .edit().putLong("last_played_$chatId", messageId).apply()
    }

    private fun getLastPlayed(chatId: Long): Long? {
        val id = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
            .getLong("last_played_$chatId", -1L)
        return if (id == -1L) null else id
    }

    override fun onCleared() {
        prefetchJob?.cancel()
        positionJob?.cancel()
        sleepTimerJob?.cancel()
        controller?.release()
        controller = null
        // Do NOT clear activePlayback here. This VM is scoped to the Player nav entry, so it is
        // destroyed on back-navigation to the station selector — clearing here made the MiniPlayer
        // vanish the moment you left the player. activePlayback is app-scoped state that must
        // outlive this screen; it is cleared when playback truly stops (PlaybackService.onDestroy).
        super.onCleared()
    }
}
