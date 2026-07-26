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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.BalanceAudioProcessor
import tech.capullo.audio.player.FifoAudioBufferSink
import tech.capullo.audio.snapcast.firstArtist
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

    private var mediaSession: MediaSession? = null
    private val balanceProcessor = BalanceAudioProcessor()
    private var fifoSink: FifoAudioBufferSink? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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

    private companion object {
        const val TAG = "PlaybackService"

        // Silence watchdog: how often it polls, and how long a playing track may go without any
        // audible PCM before it's judged undecodable and skipped. 8s comfortably clears normal
        // buffering / short silent intros while still cutting a dead track quickly.
        const val WATCHDOG_TICK_MS = 1000L
        const val SILENT_SKIP_MS = 8000L
    }
}
