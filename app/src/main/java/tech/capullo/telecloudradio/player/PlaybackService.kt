package tech.capullo.telecloudradio.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.capullo.audio.calibration.CalibrationHost
import tech.capullo.audio.calibration.FileCalibrationHistory
import tech.capullo.audio.calibration.FileCalibrationJournal
import tech.capullo.audio.calibration.FileVolumeUndo
import tech.capullo.audio.calibration.ReferencePcmRing
import tech.capullo.audio.calibration.SyncCalibrator
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.BalanceAudioProcessor
import tech.capullo.audio.player.FifoAudioBufferSink
import tech.capullo.audio.snapcast.firstArtist
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.source.telegram.data.telegram.TelegramException
import tech.capullo.telecloudradio.MainActivity
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.playlist.ActiveTrackRepository
import tech.capullo.telecloudradio.data.playlist.PlaybackCommand
import tech.capullo.telecloudradio.snapcast.SnapcastManager
import javax.inject.Inject

/**
 * Playback + Snapcast broadcast service.
 *
 * Audio path: ExoPlayer decodes the track; the sink chain forces the PCM to
 * 48000:16:2 ([ChannelMixing → Sonic] ), applies stereo balance, then a
 * TeeAudioProcessor copies it into the snapserver FIFO. The AudioTrack output
 * stays (it paces playback and provides the position clock) but player volume
 * is 0 - the audible output on this device is the local snapclient, which is
 * perfectly in sync with every other Snapcast/web listener on the LAN.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var activeTrackRepository: ActiveTrackRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var snapcastManager: SnapcastManager

    @Inject lateinit var tunnelManager: TunnelManager

    @Inject lateinit var telegramRepository: tech.capullo.telecloudradio.data.telegram.TelegramRepository

    private var mediaSession: MediaSession? = null
    private val balanceProcessor = BalanceAudioProcessor()
    private var fifoSink: FifoAudioBufferSink? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // --- Acoustic sync calibration ---
    //
    // Ported from QuantumCast 2026-08-28. Everything measurable lives in capullo-audio's
    // CalibrationHost; what stays here is the app's own knowledge: where the journals live, which
    // clients this service can currently see, and how to arm a reference PCM source.
    //
    // Telecloud has no OS-volume boost (publishOsBoost) - that rides QC's stream metadata, which TC
    // does not publish. Its absence only means a too-quiet client cannot be boosted mid-run.

    /** Crash journal: a killed run's half-written latencies are undone on the next control connect. */
    private val calibrationJournal by lazy { FileCalibrationJournal(this) }

    /** Append-only log of verified corrections. */
    private val calibrationHistory by lazy { FileCalibrationHistory(this) }

    /** Pre-balance volumes, so a balance pass is one action away from reverted. */
    private val calibrationVolumeUndo by lazy { FileVolumeUndo(this) }

    /**
     * Held here rather than by the calibrator so an engine restart mid-run can re-arm the NEW sink
     * and the tap survives.
     */
    @Volatile private var calibrationTap: ReferencePcmRing? = null

    private val calHost by lazy {
        CalibrationHost(
            context = this,
            control = { snapcastManager.calibrationControl },
            connectedClients = {
                snapcastManager.state.value.groups.flatMap { it.clients }.filter { it.connected }.map {
                    SyncCalibrator.CalClient(
                        id = it.id,
                        name = it.config.name,
                        latencyMs = it.config.latency,
                        volumePercent = it.config.volume.percent,
                        muted = it.config.volume.muted,
                    )
                }
            },
            // ALL clients, connected or not - matches QuantumCast; the calibrator's own note explains why.
            clientLatencies = {
                snapcastManager.state.value.groups.flatMap { it.clients }
                    .associate { it.id to it.config.latency }
            },
            localClientId = { snapcastManager.localClientId },
            reference = { ring -> armReference(ring) },
            suppressAudioFocusLosses = { snapcastManager.suppressFocusLosses(it) },
            nowPlaying = {
                val np = snapNowPlaying.value
                "${np.title} | ${np.artist}"
            },
            refreshStatus = { snapcastManager.refreshStatus() },
            journal = calibrationJournal,
            history = calibrationHistory,
            volumeUndo = calibrationVolumeUndo,
        )
    }

    /** The running calibration, for the UI. Refusals are published on the same flow. */
    val calibrationState: StateFlow<SyncCalibrator.State> get() = calHost.state

    /**
     * Arm a reference PCM source, or null when this device has no way to obtain one.
     *
     * Telecloud only broadcasts, so unlike QuantumCast there is no listen-in branch here: the
     * reference is always the buffers already going into the snapserver FIFO.
     */
    private fun armReference(ring: ReferencePcmRing): (() -> Unit)? {
        val sink = fifoSink ?: return null
        calibrationTap = ring
        sink.pcmTap = ring
        return {
            sink.pcmTap = null
            calibrationTap = null
        }
    }

    /**
     * Start a calibration from an Intent, because nothing outside this process holds the service
     * object: the app talks to it through a MediaController, which carries transport commands only.
     * MainActivity's `dbg calibrate` hook sends [ACTION_CALIBRATE] here.
     *
     * Returning WITHOUT delegating to super for our own action is deliberate and was found the hard
     * way: MediaSessionService.onStartCommand re-runs its own start path, which on this service
     * tears the broadcast down and builds a NEW FIFO - destroying the very reference PCM the
     * calibration is about to measure. Only foreign intents (media-button, restart) go to super.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CALIBRATE) {
            startSyncCalibration()
            return START_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Requires RECORD_AUDIO already granted - the caller asks for it first. */
    fun startSyncCalibration() {
        if (calHost.isRunning) {
            Log.w(TAG, "calibrate ignored: a run is already in progress")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return calHost.refuse("microphone permission not granted")
        }
        // Which role this device is in is the app's knowledge, so the check stays here. TC measures
        // only as the broadcaster: without the FIFO sink there is no reference PCM to correlate.
        if (fifoSink == null || !snapcastManager.state.value.isBroadcasting) {
            return calHost.refuse("not broadcasting - nothing to calibrate")
        }
        calHost.start(serviceScope)
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    // The local playback player (ExoPlayer, wrapped by forwardingPlayer) and the listen-in stand-in
    // player. Held as fields so the isListening collector can swap the MediaSession between them and
    // onDestroy can release both. See the listen-in swap in onCreate for why the stand-in exists.
    private var localPlayer: ExoPlayer? = null
    private var forwardingPlayer: Player? = null
    private var remoteListenPlayer: RemoteListenPlayer? = null

    // Mirror for the snapcontrol plugin, which queries from an IO thread while
    // ExoPlayer only allows access from its application thread.
    @Volatile private var playerIsPlaying = false

    // Silence-watchdog state (all touched on the main thread): SilenceWatchdogSink records the last
    // time audible PCM flowed; these track when the current item started and whether we've already
    // skipped it for producing no audible output. See onCreate's watchdog loop.
    private var silenceSink: SilenceWatchdogSink? = null
    private var trackStartedElapsedMs = 0L
    private var silenceSkipFiredForItem = false

    // --- Snapcast control-plugin adapter (capullo-audio SnapcontrolPlugin) ---
    // The engine's SnapcontrolPlugin is contract-driven: a StateFlow<NowPlaying> (read) + a
    // PlaybackController (transport), replacing Telecloud's former fat SnapcontrolCallbacks.
    // buildSnapNowPlaying() maps the active Telegram track onto a NowPlaying; artwork is the
    // track's embedded picture as base64 (the mapper expects NowPlaying.artworkBase64).
    // MutableStateFlow is-a StateFlow, so it satisfies the plugin's read-only param directly.
    private val snapNowPlaying = MutableStateFlow(NowPlaying.EMPTY)

    private val snapController = object : PlaybackController {
        override fun play() = runOnMain { mediaSession?.player?.play() }
        override fun pause() = runOnMain { mediaSession?.player?.pause() }
        override fun next() {
            activeTrackRepository.emitCommand(PlaybackCommand.NEXT)
        }
        override fun previous() {
            activeTrackRepository.emitCommand(PlaybackCommand.PREV)
        }
        override fun seekTo(positionMs: Long) {} // playlist next/prev only - position not driven here
    }

    // Base64 art is expensive (an 800x800 JPEG → a ~300KB string) and changes only per track, but
    // buildSnapNowPlaying() runs on every isPlaying flip. Cache the encoding keyed on the source
    // bytes by identity - the same ByteArray instance is reused for a track's lifetime.
    private var cachedArtBytes: ByteArray? = null
    private var cachedArtBase64: String? = null

    private fun artBase64(bytes: ByteArray?): String? {
        if (bytes == null) return null
        if (bytes !== cachedArtBytes) {
            cachedArtBytes = bytes
            cachedArtBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        return cachedArtBase64
    }

    private fun buildSnapNowPlaying(): NowPlaying {
        val playback = activeTrackRepository.activePlayback.value
        val canSkip = playback != null
        return NowPlaying(
            title = playback?.let { it.track.title ?: it.track.fileName } ?: "",
            artist = playback?.track?.performer ?: "",
            album = playback?.chatTitle ?: "",
            artworkBase64 = artBase64(playback?.albumArt),
            isPlaying = playerIsPlaying,
            canGoNext = canSkip,
            canGoPrevious = canSkip,
        )
    }

    // Push the current metadata to web players / snapclients. Replaces the old
    // snapcontrolCallbacks + snapcastManager.notifyPropertiesChanged() flow. Skips the push when
    // nothing changed: the resync churn toggles isPlaying rapidly, and re-emitting an identical
    // (or no-op) state is pure noise for the control plugin.
    private fun publishNowPlaying() {
        val next = buildSnapNowPlaying()
        if (next == snapNowPlaying.value) return
        snapNowPlaying.value = next
        snapcastManager.notifyPropertiesChanged()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // Posts the tunnel's public URL to the user-configured Telegram chat (Settings → Web player →
    // "Announce public link in Telegram"); 0 = not configured. Every outcome surfaces as a
    // player-screen snackbar (and a log line) - success names the channel, an unset channel and
    // the no-admin-rights failure say so explicitly. Whatever happens, the broadcast itself is
    // never disturbed by the announcement.
    private suspend fun announcePublicLink(url: String) {
        val chatId = settings.broadcastNotifyChatId
        if (chatId == 0L) {
            Log.d(TAG, "Public link up, no announce channel configured")
            activeTrackRepository.emitMessage(
                "Public link is up, but no Telegram channel is set for announcements - " +
                    "pick one in Settings › Web player",
            )
            return
        }
        val channel = settings.broadcastNotifyChatTitle.ifBlank { "the selected channel" }
        val station = activeTrackRepository.activePlayback.value?.chatTitle
            ?.takeIf { it.isNotBlank() }
            ?: "Telecloud Radio"
        val text = "🎙️ $station is live!\n🎧 Listen from anywhere: $url"
        runCatching { telegramRepository.sendMessage(chatId, text) }
            .onSuccess {
                Log.d(TAG, "Announced public link in chat $chatId")
                activeTrackRepository.emitMessage("Public link posted to $channel")
            }
            .onFailure {
                // runCatching traps CancellationException too: tearing the service down while
                // sendMessage is suspended must propagate the cancel, not raise a failure
                // snackbar for a normal shutdown.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Public-link announcement to chat $chatId failed: ${it.message}")
                val noRights = it is TelegramException &&
                    it.message.contains("administrator rights", ignoreCase = true)
                activeTrackRepository.emitMessage(
                    if (noRights) {
                        "No permission to post in $channel - make the app account a channel admin"
                    } else {
                        "Couldn't post the public link to $channel: ${it.message ?: "unknown error"}"
                    },
                )
            }
    }

    // Invoked (on the audio processing thread) by [SilenceWatchdogSink] when the current track has
    // decoded to sustained digital silence - a track that "plays" (timer advancing) but is inaudible,
    // typically a corrupt file the decoder can't parse yet doesn't error on. Warn the user and skip to
    // the next track. tryEmit is thread-safe; NEXT is handled by PlayerViewModel, the playlist owner.
    private fun onUndecodableSilence() {
        val playback = activeTrackRepository.activePlayback.value
        val name = playback?.let { it.track.title ?: it.track.fileName }
        Log.w(TAG, "Track decoded to silence; skipping as undecodable: $name")
        activeTrackRepository.emitMessage(
            "Can't play “${name ?: "this track"}” - file appears corrupt. Skipping.",
        )
        activeTrackRepository.emitCommand(PlaybackCommand.NEXT)
    }

    /**
     * A matrix that collapses [inputChannelCount] channels to stereo. 5.1 (6ch) uses the standard
     * ITU-R BS.775 coefficients (FL FR FC LFE BL BR order); other counts route ch0->L, ch1->R and
     * fold any remaining channels equally into both. Keeps the broadcast in stereo regardless of the
     * source's channel layout.
     */
    private fun stereoDownmixMatrix(inputChannelCount: Int): ChannelMixingMatrix {
        val out = 2
        val c = FloatArray(inputChannelCount * out)
        fun set(inCh: Int, l: Float, r: Float) {
            c[inCh * out] = l
            c[inCh * out + 1] = r
        }
        if (inputChannelCount == 6) {
            set(0, 1f, 0f) // FL -> L
            set(1, 0f, 1f) // FR -> R
            set(2, 0.707f, 0.707f) // FC -> both
            set(3, 0f, 0f) // LFE dropped
            set(4, 0.707f, 0f) // BL -> L
            set(5, 0f, 0.707f) // BR -> R
        } else {
            set(0, 1f, 0f)
            if (inputChannelCount > 1) set(1, 0f, 1f)
            for (i in 2 until inputChannelCount) set(i, 0.707f, 0.707f)
        }
        return ChannelMixingMatrix(inputChannelCount, out, c)
    }

    override fun onCreate() {
        super.onCreate()

        // FIFO + snapserver process wrapper must exist before the sink opens the pipe
        val fifoPath = snapcastManager.prepareBroadcast()
        // enableKeepAlive = false: this sink is fed in bursts (ChannelMixing/Sonic/balance processors
        // + local-file decode paced by AudioTrack backpressure), so the silence keep-alive would
        // trip during normal drain waits and overfeed the FIFO -> stutter. Feed from real PCM only.
        val sink = FifoAudioBufferSink(fifoPath, enableKeepAlive = false).also {
            fifoSink = it
            it.open()
        }

        // Taps the post-decode PCM to record when audible audio last flowed; the watchdog loop below
        // uses it to catch a track that "plays" (clock advancing) but is inaudible - decoding to pure
        // silence, or (corrupt FLAC-in-MP4 the decoder drops entirely) producing no PCM at all. Its
        // own tee sits alongside the FIFO tee.
        val silenceSink = SilenceWatchdogSink().also { this.silenceSink = it }

        // Sink chain: [mix → 2ch] → [resample → 48000] → [balance] → [tee → silence watchdog] →
        // [tee → FIFO]. Balance sits before the tees so every listener (local snapclient, LAN
        // clients, web players) hears the same adjusted stereo image.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            // Prefer platform decoders, but fall back to the bundled Media3 FFmpeg extension (on the
            // classpath via capullo-audio) for formats the platform can't handle - widens codec
            // coverage and lets genuinely undecodable input surface as a decoder error.
            init {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val mixer = ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
                    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
                    // The FFmpeg audio extension (now on the classpath via capullo-audio) decodes
                    // multichannel FLAC/etc. to its native channel count. Register downmix matrices
                    // for 3..8 channels so the sink accepts them instead of failing the renderer
                    // with a MediaCodecAudioRenderer error on e.g. a 5.1 track.
                    for (channels in 3..8) putChannelMixingMatrix(stereoDownmixMatrix(channels))
                }
                // LOCKSTEP with SnapserverProcess.SAMPLE_FORMAT (48000): the FIFO the tee writes is
                // read by snapserver at that rate, so this resampler must output the same. 48000
                // lets 48kHz-native FLAC/Opus pass through with no resample (kills the resync-storm
                // stutter); 44.1k content upsamples once to 48k.
                val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(48000) }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // keep the chain in 16-bit PCM
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            mixer,
                            resampler,
                            balanceProcessor,
                            TeeAudioProcessor(silenceSink),
                            TeeAudioProcessor(sink),
                        ),
                    )
                    .build()
            }
        }
        balanceProcessor.balance = settings.balance.value
        serviceScope.launch {
            settings.balance.collect { balanceProcessor.balance = it }
        }
        serviceScope.launch {
            combine(settings.webDebugPanel, settings.webAutoplay) { debug, autoplay ->
                debug to autoplay
            }.distinctUntilChanged().collect { (debug, autoplay) ->
                snapcastManager.updateWebConfig(debug, autoplay)
            }
        }
        // Public-link tunnel: runs only while the broadcast stack (and thus the web player's
        // HTTP port) is live and the setting is on. The tunnel survives listen-in teardown of
        // the snapclient because isBroadcasting is what matters here.
        serviceScope.launch {
            combine(settings.tunnelEnabled, snapcastManager.state) { enabled, s ->
                enabled to s
            }.collect { (enabled, s) ->
                if (enabled && s.isBroadcasting) {
                    tunnelManager.start(s.broadcastHttpPort)
                } else {
                    tunnelManager.stop()
                }
            }
        }
        // Announce the public link in the configured Telegram chat each time THIS collector
        // observes a tunnel connecting, i.e. a Starting → Active transition. Gating on the
        // transition rather than distinctUntilChanged-on-the-url matters because TunnelManager is
        // a singleton that outlives this service: a fresh collector attaching to a retained
        // Active(oldUrl) from a previous broadcast would announce a link whose snapserver port
        // stopBroadcast() already tore down (drop(1) can't fix that either - the first emission
        // can just as well be a legitimate Active after a fast handshake). Reconnects still
        // re-announce: quick tunnels mint a fresh URL per connection, so the earlier post is dead
        // by then. Only a reconnect handing back the SAME url is skipped - the earlier post is
        // live again, and a second one would be noise (lastAnnouncedUrl lives in the companion
        // precisely so it survives service recreation within the process, like TunnelManager).
        serviceScope.launch {
            var previous: TunnelManager.TunnelState? = null
            tunnelManager.state.collect { state ->
                val url = (state as? TunnelManager.TunnelState.Active)?.publicUrl
                if (url != null && previous == TunnelManager.TunnelState.Starting && url != lastAnnouncedUrl) {
                    lastAnnouncedUrl = url
                    announcePublicLink(url)
                }
                previous = state
            }
        }
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // handleAudioFocus = FALSE: ExoPlayer here is a silent (volume 0)
                // clock that feeds the FIFO - it must never react to focus. The
                // audible endpoint is the local snapclient, which owns focus; when
                // its Oboe stream grabs focus a few seconds in, a focus-handling
                // ExoPlayer would see a "loss" and pause itself, stalling the tee →
                // snapserver goes idle → snapclient runs out of chunks (silence).
                /* handleAudioFocus = */
                false,
            )
            // Becoming-noisy (headphone unplug) must not pause the broadcast either -
            // other rooms/web players keep listening.
            .setHandleAudioBecomingNoisy(false)
            .build()
        localPlayer = player
        // Local audio comes from the snapclient; the tee sits pre-volume so the
        // FIFO always receives full-scale PCM.
        player.volume = 0f

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Fresh track → restart the silence grace window and clear the per-item skip guard.
                trackStartedElapsedMs = SystemClock.elapsedRealtime()
                silenceSkipFiredForItem = false
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerIsPlaying = isPlaying
                if (isPlaying) {
                    // Only NOW start feeding the FIFO: writing during preroll
                    // deadlocks - the 64KB pipe has no reader yet and a blocked
                    // tee stalls READY forever.
                    fifoSink?.enableWrites()
                    snapcastManager.startBroadcast(snapNowPlaying, snapController)
                    // Explicit play (also an app-switch back to us): reclaim audio focus so the
                    // other broadcasting app's local snapclient is evicted and only this device
                    // is audible. startBroadcast() no-ops after the first play, so this is the
                    // only re-request on a subsequent resume.
                    snapcastManager.reclaimAudioFocus()
                }
                publishNowPlaying()
            }
        })

        // The playlist lives in PlayerViewModel (one MediaItem at a time), so ExoPlayer never
        // has a next/previous item and disables those commands - breaking Bluetooth/AVRCP and
        // lock-screen skip buttons. Force the commands available and route them through
        // ActiveTrackRepository, the same path the mini player uses.
        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            override fun isCommandAvailable(command: Int): Boolean = command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                super.isCommandAvailable(command)

            override fun seekToNext() {
                activeTrackRepository.emitCommand(PlaybackCommand.NEXT)
            }

            override fun seekToNextMediaItem() {
                activeTrackRepository.emitCommand(PlaybackCommand.NEXT)
            }

            override fun seekToPrevious() {
                activeTrackRepository.emitCommand(PlaybackCommand.PREV)
            }

            override fun seekToPreviousMediaItem() {
                activeTrackRepository.emitCommand(PlaybackCommand.PREV)
            }
        }

        this.forwardingPlayer = forwardingPlayer

        // Read-only stand-in player for listen-in mode - holds the foreground service; transport is
        // driven by the listen screen (SnapcastManager.sendStreamControl), not through this player.
        remoteListenPlayer = RemoteListenPlayer(Looper.getMainLooper())

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivity)
            .build()

        // Push metadata changes (track/art/play state) to snapserver → web players
        serviceScope.launch {
            activeTrackRepository.activePlayback.collect {
                publishNowPlaying()
            }
        }

        // Listen-in takes over the audio output. The manager already tore the broadcast stack down
        // before connecting; pause our local player and hand the MediaSession the remote stand-in
        // player. That keeps the session *playing* (backed by the snapclient), so PlaybackService
        // stays a foreground mediaPlayback service - without which Android 15 / One UI "audio
        // hardening" mutes the snapclient's audio seconds after the app is backgrounded. Swap the
        // local player back in when the listen-in session ends.
        serviceScope.launch {
            snapcastManager.state.map { it.isListening }.distinctUntilChanged().collect { listening ->
                val session = mediaSession ?: return@collect
                if (listening) {
                    forwardingPlayer?.let { if (it.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) it.pause() }
                    remoteListenPlayer?.let { if (session.player !== it) session.setPlayer(it) }
                } else {
                    forwardingPlayer?.let { if (session.player !== it) session.setPlayer(it) }
                }
            }
        }
        // Feed the remote now-playing into the stand-in player so the notification / lock screen show
        // the track being listened to.
        serviceScope.launch {
            snapcastManager.state.collect { s ->
                if (!s.isListening) return@collect
                remoteListenPlayer?.updateMetadata(buildRemoteMetadata(s))
            }
        }

        // Silence watchdog: a track that reports playing but never delivers audible PCM would
        // otherwise broadcast dead silence with an advancing clock - a corrupt file the decoder drops
        // entirely (no PCM at all) or one that decodes to pure zeros. If, while the local player is
        // playing, no audible audio has reached the sink for SILENT_SKIP_MS since the track started,
        // skip it and warn. Listen-in pauses the local player, so isPlaying is false and this stays
        // quiet then. Runs on Main (serviceScope), the same thread that owns the ExoPlayer.
        serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val p = localPlayer ?: continue
                if (silenceSkipFiredForItem || !p.isPlaying) continue
                val audibleRef = maxOf(silenceSink?.lastAudibleElapsedMs ?: 0L, trackStartedElapsedMs)
                if (audibleRef > 0L && SystemClock.elapsedRealtime() - audibleRef > SILENT_SKIP_MS) {
                    silenceSkipFiredForItem = true
                    onUndecodableSilence()
                }
            }
        }
    }

    // Maps the remote Snapcast stream's metadata + decoded art onto a MediaMetadata for the
    // listen-in notification. Mirrors PlayerScreen's remote now-playing mapping (title falls back to
    // station, then host).
    private fun buildRemoteMetadata(s: SnapcastManager.SnapcastState): MediaMetadata {
        val meta = s.remoteProps?.metadata
        val title = meta?.title?.takeIf { it.isNotBlank() }
            ?: meta?.station?.takeIf { it.isNotBlank() }
            ?: s.listenHost
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(meta?.firstArtist().orEmpty())
            .apply {
                s.remoteArt?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        // Playback is genuinely over (service torn down) → drop the active-playback state so the
        // MiniPlayer disappears. This is the correct clear point; on mere back-navigation the
        // service stays alive (AppViewModel keeps a MediaController bound), so this won't fire.
        activeTrackRepository.clear()
        snapcastManager.stopBroadcast()
        snapcastManager.disconnectListen()
        // The scope above is already cancelled, so the tunnel start/stop collector - the only
        // other stop() caller - can't do this: TunnelManager is a singleton, and without an
        // explicit stop here cloudflared (and its retained Active(publicUrl)) would outlive the
        // service, orphaned against a snapserver port that stopBroadcast() just tore down.
        tunnelManager.stop()
        fifoSink?.close()
        fifoSink = null
        mediaSession?.release()
        mediaSession = null
        // Release both players explicitly: the session only holds whichever is currently swapped in,
        // so releasing session.player alone would leak the other.
        localPlayer?.release()
        localPlayer = null
        remoteListenPlayer?.release()
        remoteListenPlayer = null
        forwardingPlayer = null
        super.onDestroy()
    }

    companion object {
        /** Intent action that starts a sync calibration - see [onStartCommand]. Public because
         *  MainActivity's dbg hook is the only way in: the app holds a MediaController, which
         *  carries transport commands only. */
        const val ACTION_CALIBRATE = "tech.capullo.telecloudradio.CALIBRATE"

        private const val TAG = "PlaybackService"

        // Last public URL this process announced in Telegram. Companion-level on purpose: it
        // must outlive service recreation just like the singleton TunnelManager does, so a
        // reconnect handing back an already-announced url doesn't double-post.
        var lastAnnouncedUrl: String? = null

        // Silence watchdog: how often it polls, and how long a playing track may go without any
        // audible PCM before it's judged undecodable and skipped. 8s comfortably clears normal
        // buffering / short silent intros while still cutting a dead track quickly.
        private const val WATCHDOG_TICK_MS = 1000L
        private const val SILENT_SKIP_MS = 8000L
    }
}
