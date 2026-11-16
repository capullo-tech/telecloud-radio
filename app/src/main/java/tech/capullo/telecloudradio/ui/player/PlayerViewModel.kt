package tech.capullo.telecloudradio.ui.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tech.capullo.source.telegram.data.telegram.MessageReactionsInfo
import tech.capullo.telecloudradio.data.ConnectivityMonitor
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.TrackKey
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
import tech.capullo.telecloudradio.player.trackKey
import tech.capullo.telecloudradio.util.PerfTrace
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
    val trackDuration: Long = 0L,
    val isOffline: Boolean = false,
    val audioMeta: AudioMetadata? = null,
    val showStats: Boolean = false,
    val audioAnalysis: AudioAnalysisEntity? = null,
    val isAnalyzing: Boolean = false,
    val nextTrackReady: Boolean = false,
    val showReactions: Boolean = false,
    val reactionsInfo: MessageReactionsInfo? = null,
    val reactionsLoading: Boolean = false,
    val sleepTimerActive: Boolean = false,
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

    // High-frequency fields live OUTSIDE PlayerUiState so their ticks don't recompose the whole
    // PlayerScreen (uiState is collected at the screen root): position (500 ms), TDLib download
    // progress, and the sleep countdown (1 s) are collected only by the leaf composables that
    // render them.
    private val _positionState = MutableStateFlow(0L)
    val positionState: StateFlow<Long> = _positionState.asStateFlow()

    // Download progress (0f..1f) of the current/active track while it's downloading, so the play
    // button can show a determinate ring instead of an indeterminate spinner. Null before the first
    // progress tick (or once the file is on disk) → the button falls back to indeterminate.
    private val _activeDownloadProgress = MutableStateFlow<Float?>(null)
    val activeDownloadProgress: StateFlow<Float?> = _activeDownloadProgress.asStateFlow()

    private val _nextDownloadProgress = MutableStateFlow<Float?>(null)
    val nextDownloadProgress: StateFlow<Float?> = _nextDownloadProgress.asStateFlow()

    // Seconds until the timer fires; 0 while waiting for the current track to finish
    private val _sleepTimerSecondsRemaining = MutableStateFlow(0)
    val sleepTimerSecondsRemaining: StateFlow<Int> = _sleepTimerSecondsRemaining.asStateFlow()

    private var controller: MediaController? = null
    private var orderedPlaylist: List<MediaMessageEntity> = emptyList()

    // basePlaylist = ordered/shuffled, unfiltered; playlist = basePlaylist with queue filters applied
    private var basePlaylist: List<MediaMessageEntity> = emptyList()
    private var playlist: List<MediaMessageEntity> = emptyList()
    private var currentIndex = 0
    private var prefetchJob: Job? = null
    private var readinessJob: Job? = null
    private var positionJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var saveJob: Job? = null

    // totalSizeGb cache: every queue mutator replaces `playlist` with a new list instance
    // (toMutableList / + / filter), so reference identity is a valid invalidation key.
    private var cachedSizeGbTrackList: List<MediaMessageEntity>? = null
    private var cachedTotalSizeGb: Double = 0.0

    // Last prefetch window handed to prefetchJob; used to skip pointless cancel/relaunch cycles.
    private var lastPrefetchWindow: List<Int>? = null

    // Gapless advance: the identity (chat + message) of the track currently staged as the
    // controller's queued-but-not-yet-playing next MediaItem (addMediaItem, never setMediaItem - that
    // would replace the whole queue). Media3 then transitions to it with no flush/preroll, so the FIFO
    // never goes idle crossing a track boundary - unlike the old STATE_ENDED -> setMediaItem path,
    // which tore the sink down and rebuilt it, very likely tripping snapserver's 120ms dryout window
    // on every single track change. Kept to exactly ONE track ahead (not the whole playlist) because
    // the playlist can be reordered/filtered live while listening; queueing further would risk
    // playing a since-filtered-out or since-reordered item. Tracked by identity, not playlist index:
    // a queue edit (playNext, remove, reorder) can put a different track at the same index, and an
    // index match would leave the old one staged. Explicit jumps (user taps a track, NEXT/PREV,
    // playAt) stay destructive via playTrack()/setMediaItem - only the natural end-of-track advance
    // goes through this path: Player.MEDIA_ITEM_TRANSITION_REASON_AUTO for the gapless join, or
    // PlaybackService's crossfade handing off to the staged item (see pendingHandoff; setMediaItem
    // produces neither, and clears this first). The staged item is also what a crossfade fades
    // into, so unstaging it (queue edit, sleep timer) is what cancels one.
    private var stagedNext: TrackKey? = null
    private var enqueueJob: Job? = null

    // A crossfade that handed off to this track (ActiveTrackRepository.crossfadeHandoffs, an
    // explicit signal from PlaybackService: the session's player swap that comes with it carries no
    // transition reason of its own). The signal and the swap reach this VM separately and in no
    // fixed order, so it syncs once the controller shows the track as current (syncCrossfadeHandoff).
    private var pendingHandoff: TrackKey? = null

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
                }, ContextCompat.getMainExecutor(context))
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
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) onGaplessAdvance()
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    // Whichever event the session's player swap shows up as.
                    syncCrossfadeHandoff()
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
            activeTrackRepository.crossfadeHandoffs.collect { target ->
                pendingHandoff = target
                syncCrossfadeHandoff()
            }
        }
        viewModelScope.launch {
            // Notices raised from PlaybackService (e.g. the silence watchdog skipping a corrupt
            // track) surface on the player screen's snackbar.
            activeTrackRepository.messages.collect { _downloadToast.tryEmit(it) }
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
            // refreshNextTrackState() is only needed when the next track's map entry appears or
            // disappears - running it per tick spawned a coroutine for every progress update.
            var hadNextEntry = false
            downloadManager.downloadProgress.collect { map ->
                // Whole body is synchronous (no suspensions), so one balanced section is safe.
                PerfTrace.section("VM.downloadProgressTick") {
                    // Active-track progress → determinate ring on the play button (independent of the
                    // next-track wiring below). Only *raise* it here; the map entry is torn down the
                    // instant the download returns (before playback is prepared), so nulling it here
                    // would snap the ring back to empty mid-load. playTrack owns resetting it to null
                    // at the start of a load and to 1f on completion, so the ring lands full.
                    val activeProgress = downloadManager.activeTrack?.let { map[it] }
                    if (activeProgress != null) {
                        _activeDownloadProgress.emitProgress(activeProgress)
                    }
                    val next = nextIndex()?.let { playlist.getOrNull(it) }
                    val progress = next?.let { map[TrackKey(it.chatId, it.messageId)] }
                    if (progress != null) {
                        hadNextEntry = true
                        _nextDownloadProgress.emitProgress(progress)
                        if (_uiState.value.nextTrackReady) {
                            _uiState.value = _uiState.value.copy(nextTrackReady = false)
                        }
                    } else if (hadNextEntry) {
                        // Next-track download just left the map - verify cache/DB once
                        hadNextEntry = false
                        refreshNextTrackState()
                    }
                }
            }
        }
    }

    // Conflate high-frequency TDLib progress ticks: sub-1% steps are invisible on the rings,
    // so don't emit them; but transitions to/from null (ring appears/disappears) and completion
    // (1f, the ring must visibly land full) always go through.
    private fun MutableStateFlow<Float?>.emitProgress(value: Float) {
        val cur = this.value
        val emit = when {
            cur == null -> true
            value == 1f || cur == 1f -> value != cur
            else -> kotlin.math.abs(value - cur) >= 0.01f
        }
        if (emit) this.value = value
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

    // Cancel-replace: each call supersedes any in-flight readiness check. Without this, the
    // suspending isDownloaded() below means the *last coroutine to finish* wins rather than the
    // last one called - an older, stale check (e.g. from a reorder that was immediately undone,
    // or an orphaned refresher spawned inside prefetchAhead that prefetchJob.cancel() doesn't
    // reach) could land its `false` last and wedge the Next button grey with nothing to re-run it.
    private fun refreshNextTrackState() {
        // Section covers only the synchronous cancel+launch; the launched body suspends.
        PerfTrace.section("VM.refreshNextTrack") {
            readinessJob?.cancel()
            readinessJob = viewModelScope.launch {
                val ni = nextIndex()
                val next = ni?.let { playlist.getOrNull(it) }
                _nextDownloadProgress.value =
                    next?.let { downloadManager.downloadProgress.value[TrackKey(it.chatId, it.messageId)] }
                _uiState.value = _uiState.value.copy(
                    nextTrackReady = next != null && downloadManager.isDownloaded(next.chatId, next.messageId),
                )
            }
        }
    }

    fun loadAndPlay(chatId: Long, chatTitle: String) {
        if (loadedChatId == chatId) {
            // Same station re-opened (group tap just ran a sync, or the library was
            // rebuilt) - pull anything the in-memory queue hasn't seen yet
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
                // the same way the group tap does - look the chat up (for its real type) and fetch
                // its audio history - before giving up, so auto-open doesn't dead-end on the empty
                // "No audio tracks found" screen.
                _uiState.value = PlayerUiState(isLoading = true)
                val chat = runCatching { telegramRepository.getAudioGroups() }
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
        // Debounced + off-main: rapid queue edits (reorder/remove bursts) collapse into one
        // write. The delay comes FIRST, then the snapshot is taken on IO - delay-then-write,
        // never throttle-first, so the final save always lands. playlist/basePlaylist/chatId
        // and the uiState fields below are only mutated on the main thread, so reading the
        // latest values here after the debounce gives a consistent-enough snapshot
        // (last-write-wins).
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500L)
            writeQueueStateNow()
        }
    }

    // The actual snapshot+write. Called from the debounced save on IO, and synchronously from
    // onCleared so a pending save is never dropped when the VM is destroyed (back-navigation
    // within the 500 ms debounce window).
    private fun writeQueueStateNow() {
        PerfTrace.section("VM.saveQueueState") {
            if (chatId == 0L) return@section
            context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit()
                .putString(
                    "queue_ids_$chatId",
                    playlist.joinToString(",") { it.messageId.toString() },
                )
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
        PerfTrace.section("VM.publishQueue") {
            _uiState.value = _uiState.value.copy(
                totalTracks = playlist.size,
                currentIndex = currentIndex,
                displayPlaylist = playlist,
                unfilteredPlaylist = basePlaylist,
                totalSizeGb = PerfTrace.section("VM.totalSizeGb") {
                    if (playlist === cachedSizeGbTrackList) {
                        cachedTotalSizeGb
                    } else {
                        (playlist.sumOf { it.fileSize ?: 0L } / (1024.0 * 1024.0 * 1024.0)).also {
                            cachedSizeGbTrackList = playlist
                            cachedTotalSizeGb = it
                        }
                    }
                },
            )
            refreshNextTrackState()
            // Prefetch even before playback starts (e.g. shuffling right after app open) -
            // otherwise "next" stays greyed with nothing downloading it
            if (playlist.isNotEmpty()) prefetchAhead(currentIndex)
            syncEnqueuedNext()
            saveQueueState()
        }
    }

    // Rebuilds the queue from the library (basePlaylist + filters), discarding manual
    // queue edits. Used on load, order cycle, connectivity reload, and filter Apply.
    private fun rebuildActivePlaylist() {
        PerfTrace.section("VM.rebuildActivePlaylist") {
            val filters = _uiState.value.queueFilters
            playlist = if (filters.isActive) {
                PerfTrace.section("VM.filterMatches") { basePlaylist.filter(filters::matches) }
            } else {
                basePlaylist
            }
            val currentId = _uiState.value.track?.messageId
            // -1 when the playing track is excluded by the filters; "next" then starts at 0
            currentIndex = currentId?.let { id -> playlist.indexOfFirst { it.messageId == id } } ?: -1
            publishQueue()
        }
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
                PerfTrace.section("VM.positionTick") {
                    _positionState.value = controller?.currentPosition ?: 0L
                }
                delay(500L)
            }
        }
    }

    fun toggleSleepTimer() {
        if (_uiState.value.sleepTimerActive) {
            cancelSleepTimer()
            // A pending "finish this track" had unstaged the next one - restore gapless.
            syncEnqueuedNext()
            return
        }
        val totalSec = settings.sleepTimerMinutes * 60
        _sleepTimerSecondsRemaining.value = totalSec
        _uiState.value = _uiState.value.copy(sleepTimerActive = true)
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalSec
            while (remaining > 0) {
                delay(1_000L)
                remaining--
                _sleepTimerSecondsRemaining.value = remaining
            }
            // Countdown done - let the current track play out, then pause. That pause only runs from
            // onTrackEnded (STATE_ENDED), which a gapless advance or a crossfade never reaches, so
            // unstage the next track; syncEnqueuedNext won't restage it while sleepAtTrackEnd is set.
            // PlaybackService cancels a crossfade whose target is unstaged, so none starts either,
            // and one already fading ramps back out. One whose audio already handed off completes:
            // the new track then plays out instead, and stageTargetIndex keeps the next unstaged.
            if (controller?.isPlaying == true) {
                sleepAtTrackEnd = true
                syncEnqueuedNext()
            } else {
                cancelSleepTimer()
            }
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepAtTrackEnd = false
        _sleepTimerSecondsRemaining.value = 0
        _uiState.value = _uiState.value.copy(sleepTimerActive = false)
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
            // currentIndex is -1 when the playing track is filtered out of the queue -
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

    // Computes which playlist index should play after the current one, under the active repeat
    // mode - same semantics as onTrackEnded's switch, factored out so the gapless pre-enqueue
    // (syncEnqueuedNext) and the natural end-of-track advance agree on "what's next". Returns null
    // when nothing should follow (repeat off, sitting on the last track; or repeat-one with the
    // playing track filtered out of the queue, matching onTrackEnded's existing seekTo(0) fallback
    // for that edge case).
    private fun nextIndexForAdvance(): Int? = when (_uiState.value.repeatMode) {
        Player.REPEAT_MODE_ONE -> currentIndex.takeIf { it >= 0 }
        Player.REPEAT_MODE_ALL -> (currentIndex + 1).mod(playlist.size).takeIf { playlist.isNotEmpty() }
        else -> (currentIndex + 1).takeIf { it < playlist.size }
    }

    // The playlist index to stage behind the controller's current item, or null when nothing should
    // be: no next under the repeat mode, the controller isn't on this VM's current track (another
    // chat's item, or a playTrack still downloading), or the sleep timer is waiting for this track
    // to end.
    private fun stageTargetIndex(): Int? {
        val target = nextIndexForAdvance()?.takeIf { it in playlist.indices } ?: return null
        if (!controllerPreparedForCurrentChat || sleepAtTrackEnd) return null
        val playingId = controller?.currentMediaItem?.mediaId?.toLongOrNull() ?: return null
        val currentId = playlist.getOrNull(currentIndex)?.messageId ?: _uiState.value.track?.messageId
        return target.takeIf { playingId == currentId }
    }

    // Keeps exactly one track staged right after the controller's current item (addMediaItem) for
    // gapless auto-advance - see stagedNext's doc. Called after every playlist mutation
    // (publishQueue), after playTrack lands a new current track, after prefetchAhead's download of
    // the next file completes (the file may not have existed yet on the first call), after a
    // gapless transition fires (to stage the one after that), and when the repeat mode or the sleep
    // timer changes what should follow. Cheap no-op when the track that should follow is already
    // the one staged.
    private fun syncEnqueuedNext() {
        val target = stageTargetIndex()
        val targetKey = target?.let { TrackKey(playlist[it].chatId, playlist[it].messageId) }
        if (targetKey != null && targetKey == stagedNext) return
        enqueueJob?.cancel()
        unstageNext()
        if (target == null) return
        val track = playlist[target]
        enqueueJob = viewModelScope.launch {
            // Not downloaded yet: bail quietly. prefetchAhead's own loop calls syncEnqueuedNext
            // again right after this file lands, which retries.
            val path = downloadManager.getCachedPath(track.chatId, track.messageId) ?: return@launch
            val (albumArt, audioMeta) = withContext(Dispatchers.IO) {
                val meta = audioMetadataReader.read(path)
                Pair(squareCropArt(extractAlbumArt(path) ?: albumArtFetcher.cached(track.chatId, track.messageId)), meta)
            }
            // The playlist/current position may have moved on while this suspended (reorder,
            // filter, explicit skip) - re-validate before committing instead of staging a stale item.
            if (stageTargetIndex()?.let { playlist[it].messageId } != track.messageId) return@launch
            val displayTrack = track.copy(
                title = audioMeta.tagTitle ?: track.title,
                performer = audioMeta.tagArtist ?: track.performer,
            )
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(displayTrack.title ?: displayTrack.fileName ?: "Unknown")
                .setArtist(displayTrack.performer)
                .setStation(chatTitle.takeIf { it.isNotBlank() })
                .apply { albumArt?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .setExtras(Bundle().apply { putLong(PlaybackService.EXTRA_CHAT_ID, track.chatId) })
                .build()
            controller?.addMediaItem(
                MediaItem.Builder()
                    .setMediaId(track.messageId.toString())
                    .setUri(Uri.fromFile(File(path)))
                    .setMediaMetadata(mediaMetadata)
                    .build(),
            )
            stagedNext = TrackKey(track.chatId, track.messageId)
        }
    }

    // Drops everything queued AFTER the controller's current item. Never touches the current item
    // (removing it would cut the playing track) or the finished ones before it - onGaplessAdvance
    // clears those.
    private fun unstageNext() {
        controller?.let {
            val firstAfter = it.currentMediaItemIndex + 1
            if (it.mediaItemCount > firstAfter) it.removeMediaItems(firstAfter, it.mediaItemCount)
        }
        stagedNext = null
    }

    // Applies a crossfade handoff (pendingHandoff) once the controller shows its track as current;
    // until then the session's player swap hasn't reached the controller yet. Nothing to do if this
    // VM is already on that track (an advance or a jump got there first).
    private fun syncCrossfadeHandoff() {
        val target = pendingHandoff ?: return
        if (controller?.currentMediaItem?.trackKey() != target) return
        val current = playlist.getOrNull(currentIndex)
        if (current != null && TrackKey(current.chatId, current.messageId) == target) {
            pendingHandoff = null
            return
        }
        onGaplessAdvance()
    }

    // Fires when ExoPlayer itself auto-advances into the staged next item (gapless - no flush, no
    // preroll, the FIFO never idles crossing the boundary), and when a crossfade into it hands off.
    // Mirrors playTrack's "truthful swap" tail: the track is now actually audible, so every
    // user-visible field updates for real. Does not call setMediaItem/prepare/play - ExoPlayer is
    // already playing it. After a gapless join the finished item is still queued before the new
    // current one, so it is removed first: removing items BEFORE currentMediaItemIndex never
    // interrupts playback, and it restores the [current] (+ staged next) shape syncEnqueuedNext
    // works on. A crossfade's new player holds only the new item, so there is nothing to remove.
    private fun onGaplessAdvance() {
        val ctrl = controller ?: return
        val playingIndex = ctrl.currentMediaItemIndex
        if (playingIndex > 0) ctrl.removeMediaItems(0, playingIndex)
        stagedNext = null
        pendingHandoff = null
        if (playlist.isEmpty() || !controllerPreparedForCurrentChat) return
        // Resolve what is actually playing from the controller, not from what was staged. The
        // queue allows duplicates: prefer the slot the advance was expected to land on.
        val messageId = ctrl.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val index = nextIndexForAdvance()?.takeIf { playlist.getOrNull(it)?.messageId == messageId }
            ?: playlist.indexOfFirst { it.messageId == messageId }.takeIf { it >= 0 }
            ?: return
        val track = playlist[index]
        val path = downloadManager.getCachedPath(track.chatId, track.messageId) ?: return
        currentIndex = index
        downloadManager.activeTrack = TrackKey(track.chatId, track.messageId)
        viewModelScope.launch {
            val (albumArt, audioMeta) = withContext(Dispatchers.IO) {
                val meta = audioMetadataReader.read(path)
                Pair(squareCropArt(extractAlbumArt(path) ?: albumArtFetcher.cached(track.chatId, track.messageId)), meta)
            }
            val displayTrack = track.copy(
                title = audioMeta.tagTitle ?: track.title,
                performer = audioMeta.tagArtist ?: track.performer,
            )
            _activeDownloadProgress.value = null
            // ~0 at a gapless join, but the fade length after a crossfade handoff.
            _positionState.value = controller?.currentPosition ?: 0L
            _uiState.value = _uiState.value.copy(
                track = displayTrack,
                albumArt = albumArt,
                isLoading = false,
                audioMeta = audioMeta,
                currentIndex = index,
                trackDuration = controller?.duration?.takeIf { it > 0L } ?: 0L,
                audioAnalysis = null,
                showStats = false,
                showReactions = false,
                reactionsInfo = null,
            )
            activeTrackRepository.set(track, chatId, chatTitle)
            saveLastPlayed(chatId, track.messageId)
            activeTrackRepository.updateTrack(displayTrack)
            activeTrackRepository.updateAlbumArt(albumArt)
            refreshNextTrackState()
            prefetchAhead(index)
            syncEnqueuedNext()
            if (connectivityMonitor.isOnline.value) {
                runCatching {
                    val reactions = telegramRepository.refreshReactions(track.chatId, track.messageId)
                    if (_uiState.value.track?.messageId == track.messageId) {
                        _uiState.value = _uiState.value.copy(
                            track = _uiState.value.track!!.copy(reactions = reactions),
                        )
                    }
                }
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
            _positionState.value = 0L
        } else if (currentIndex > 0) {
            viewModelScope.launch { playTrack(currentIndex - 1) }
        }
    }

    fun togglePlayPause() {
        val ctrl = controller ?: return
        when {
            // Pause always works - even while another track is downloading
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
        syncEnqueuedNext()
    }

    fun seekTo(positionMs: Long) {
        if (!controllerPreparedForCurrentChat) return
        controller?.seekTo(positionMs)
        _positionState.value = positionMs
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
            val cached = audioAnalysisDao.get(track.chatId, track.messageId)
            if (cached != null) {
                _uiState.value = _uiState.value.copy(isAnalyzing = false, audioAnalysis = cached)
                return@launch
            }
            val path = downloadManager.getCachedPath(track.chatId, track.messageId)
                ?: downloadManager.ensureDownloaded(track.chatId, track.messageId)
            if (path != null) {
                val spectFile = downloadManager.spectrogramFile(track.chatId, track.messageId)
                val result = withContext(Dispatchers.Default) {
                    audioAnalyzer.analyze(path, track.mimeType, spectFile)
                }
                if (result != null) {
                    val entity = AudioAnalysisEntity(
                        chatId = track.chatId,
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
            val path = downloadManager.getCachedPath(track.chatId, track.messageId)
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
        // The previous song keeps playing for the whole download, so the screen must keep showing
        // ITS metadata: swapping here captioned the audible track with the pending one's title/art
        // (and did the same to the mini-player and lock screen via activeTrackRepository). Every
        // user-visible field is deferred until the file is ready; only the loading flags move now,
        // leaving the play button's download ring as the feedback that the tap registered.
        // Only defer while something is actually AUDIBLE - paused, resting on a freshly loaded
        // station, or first play, there is nothing to misrepresent, so swapping straight away is
        // both truthful and better feedback than a stale (or blank) player.
        val audibleTrack = _uiState.value.track != null && _uiState.value.isPlaying
        _activeDownloadProgress.value = null
        _uiState.value = if (!audibleTrack) {
            _positionState.value = 0L
            _uiState.value.copy(
                track = track,
                isLoading = true,
                currentIndex = index,
                albumArt = null,
                trackDuration = 0L,
                audioMeta = null,
                audioAnalysis = null,
                showStats = false,
                showReactions = false,
                reactionsInfo = null,
            )
        } else {
            _uiState.value.copy(isLoading = true)
        }

        downloadManager.activeTrack = TrackKey(track.chatId, track.messageId)
        val path = downloadManager.ensureDownloaded(track.chatId, track.messageId)
        if (path == null) {
            // Lazy deletion fallback: if the download failed because the message was
            // deleted from the chat, the row is already purged - skip to the next
            // track instead of stalling
            if (!playlistRepository.exists(track.chatId, track.messageId)) {
                _downloadToast.tryEmit("Removed from chat · skipping")
                removeDeletedFromQueue(setOf(track.messageId))
                if (playlist.isNotEmpty()) {
                    playTrack(index.coerceAtMost(playlist.size - 1))
                } else {
                    _activeDownloadProgress.value = null
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "No tracks left in queue",
                    )
                }
                return
            }
            _activeDownloadProgress.value = null
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Download failed",
            )
            return
        }
        // If this load actually downloaded (ring was showing determinate progress), land it on
        // full so it visibly completes instead of snapping back to empty when the map entry is
        // torn down. Cached skips never set downloadProgress, so they show no ring at all.
        if (_activeDownloadProgress.value != null) {
            _activeDownloadProgress.value = 1f
        }

        val (albumArt, audioMeta) = withContext(Dispatchers.IO) {
            val meta = audioMetadataReader.read(path)
            // Embedded picture first, then previously fetched online art. Square-crop it so the
            // lock-screen/notification artwork and the snapcast/web art aren't stretched: embedded
            // covers can be any aspect ratio (online-fetched art is already square → no-op).
            Pair(squareCropArt(extractAlbumArt(path) ?: albumArtFetcher.cached(track.chatId, track.messageId)), meta)
        }
        val displayTrack = track.copy(
            title = audioMeta.tagTitle ?: track.title,
            performer = audioMeta.tagArtist ?: track.performer,
        )
        // The file is ready and playback is about to start, so the swap is finally truthful. This
        // carries the fields deferred above, not just the metadata.
        _activeDownloadProgress.value = null
        _positionState.value = 0L
        _uiState.value = _uiState.value.copy(
            track = displayTrack,
            albumArt = albumArt,
            isLoading = false,
            audioMeta = audioMeta,
            currentIndex = index,
            trackDuration = 0L,
            audioAnalysis = null,
            showStats = false,
            showReactions = false,
            reactionsInfo = null,
        )
        // Deferred too: the mini-player/lock screen must not name the new track while the old one
        // is still audible, and a download that never completes must not be recorded as last-played.
        activeTrackRepository.set(track, chatId, chatTitle)
        saveLastPlayed(chatId, track.messageId)
        activeTrackRepository.updateTrack(displayTrack)
        activeTrackRepository.updateAlbumArt(albumArt)

        // No embedded or cached art - fetch online in the background (Deezer → iTunes);
        // updates the in-app art when it lands, lock screen picks it up next play
        if (albumArt == null && connectivityMonitor.isOnline.value) {
            viewModelScope.launch {
                val fetched = albumArtFetcher.fetch(
                    chatId = track.chatId,
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
            // The service needs the chat to look up this track's gain: mediaId alone is the
            // messageId, which only identifies a track within one chat.
            .setExtras(Bundle().apply { putLong(PlaybackService.EXTRA_CHAT_ID, track.chatId) })
            .build()
        // setMediaItem replaces the whole controller queue (including any item staged by a
        // previous syncEnqueuedNext) with just this one - drop the stale tracking vars to match. A
        // crossfade handoff still waiting for the controller is superseded by this jump too.
        stagedNext = null
        pendingHandoff = null
        enqueueJob?.cancel()
        controller?.apply {
            setMediaItem(
                MediaItem.Builder()
                    .setMediaId(track.messageId.toString())
                    .setUri(Uri.fromFile(File(path)))
                    .setMediaMetadata(mediaMetadata)
                    .build(),
            )
            prepare()
            play()
        }

        refreshNextTrackState()
        prefetchAhead(index)
        syncEnqueuedNext()
        // Note: no eager eviction of the previous track - recently played files stay on
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

    // The prefetch window: the current track + the next up-to-2 tracks. Wraps to the top under
    // repeat-all so index 0 (the real "next" when sitting on the last row) is included; a plain
    // range would skip it, leaving that track un-prefetched and evictable - Next greys there.
    private fun prefetchWindow(fromIndex: Int): List<Int> {
        if (fromIndex !in playlist.indices) return emptyList()
        val window = mutableListOf(fromIndex)
        var i = fromIndex
        while (window.size < 3) {
            i = when {
                i + 1 < playlist.size -> i + 1
                _uiState.value.repeatMode == Player.REPEAT_MODE_ALL -> 0
                else -> break
            }
            if (i in window) break // wrapped fully around a short playlist - stop
            window.add(i)
        }
        return window
    }

    private fun prefetchAhead(fromIndex: Int) {
        // Synchronous setup only; the launched prefetch body suspends, so no section may span it.
        val window = PerfTrace.section("VM.prefetchAhead") {
            val w = prefetchWindow(fromIndex)
            // Same window with a live prefetch job (e.g. a reorder/remove that left the upcoming
            // tracks untouched): keep the in-flight download instead of cancelling/relaunching it.
            if (w == lastPrefetchWindow && prefetchJob?.isActive == true) return
            prefetchJob?.cancel()
            // Pin the current + upcoming tracks so enforceBuffer (which runs as each prefetch lands)
            // can't evict a track we just prefetched - the window matches the loop below.
            downloadManager.protectedTracks =
                w.mapNotNull { playlist.getOrNull(it)?.let { t -> TrackKey(t.chatId, t.messageId) } }.toSet()
            w
        }
        lastPrefetchWindow = window
        prefetchJob = viewModelScope.launch {
            for (i in window.drop(1)) {
                val t = playlist[i]
                val path = downloadManager.getCachedPath(t.chatId, t.messageId)
                    ?: downloadManager.ensureDownloaded(t.chatId, t.messageId)
                refreshNextTrackState()
                // The file for a gapless-staging target (nextIndexForAdvance) may not have existed
                // on syncEnqueuedNext's earlier attempt; retry now that it (might have) landed.
                if (path != null && i == nextIndexForAdvance()) syncEnqueuedNext()
                // Pre-fetch online art too, so the lock screen has it at play start
                if (path != null && albumArtFetcher.cached(t.chatId, t.messageId) == null &&
                    connectivityMonitor.isOnline.value
                ) {
                    val (embedded, meta) = withContext(Dispatchers.IO) {
                        Pair(extractAlbumArt(path), audioMetadataReader.read(path))
                    }
                    if (embedded == null) {
                        albumArtFetcher.fetch(
                            chatId = t.chatId,
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
        readinessJob?.cancel()
        positionJob?.cancel()
        sleepTimerJob?.cancel()
        // Flush any pending debounced queue save - the VM (and its scope) dies here, so the
        // 500 ms debounce job would otherwise be cancelled before writing.
        saveJob?.cancel()
        writeQueueStateNow()
        controller?.release()
        controller = null
        // Do NOT clear activePlayback here. This VM is scoped to the Player nav entry, so it is
        // destroyed on back-navigation to the station selector - clearing here made the MiniPlayer
        // vanish the moment you left the player. activePlayback is app-scoped state that must
        // outlive this screen; it is cleared when playback truly stops (PlaybackService.onDestroy).
        super.onCleared()
    }
}
