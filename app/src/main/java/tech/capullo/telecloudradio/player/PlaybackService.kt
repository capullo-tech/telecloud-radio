package tech.capullo.telecloudradio.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.BalanceAudioProcessor
import tech.capullo.audio.player.FifoAudioBufferSink
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
 * 44100:16:2 ([ChannelMixing → Sonic] ), applies stereo balance, then a
 * TeeAudioProcessor copies it into the snapserver FIFO. The AudioTrack output
 * stays (it paces playback and provides the position clock) but player volume
 * is 0 — the audible output on this device is the local snapclient, which is
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

    // Mirror for the snapcontrol plugin, which queries from an IO thread while
    // ExoPlayer only allows access from its application thread.
    @Volatile private var playerIsPlaying = false

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
        override fun seekTo(positionMs: Long) {} // playlist next/prev only — position not driven here
    }

    private fun buildSnapNowPlaying(): NowPlaying {
        val playback = activeTrackRepository.activePlayback.value
        val canSkip = playback != null
        return NowPlaying(
            title = playback?.let { it.track.title ?: it.track.fileName } ?: "",
            artist = playback?.track?.performer ?: "",
            album = playback?.chatTitle ?: "",
            artworkBase64 = playback?.albumArt?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            isPlaying = playerIsPlaying,
            canGoNext = canSkip,
            canGoPrevious = canSkip,
        )
    }

    // Push the current metadata to web players / snapclients. Replaces the old
    // snapcontrolCallbacks + snapcastManager.notifyPropertiesChanged() flow.
    private fun publishNowPlaying() {
        snapNowPlaying.value = buildSnapNowPlaying()
        snapcastManager.notifyPropertiesChanged()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun onCreate() {
        super.onCreate()

        // FIFO + snapserver process wrapper must exist before the sink opens the pipe
        val fifoPath = snapcastManager.prepareBroadcast()
        val sink = FifoAudioBufferSink(fifoPath).also {
            fifoSink = it
            it.open()
        }

        // Sink chain: [mix → 2ch] → [resample → 44100] → [balance] → [tee → FIFO].
        // Balance sits before the tee so every listener (local snapclient, LAN
        // clients, web players) hears the same adjusted stereo image.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val mixer = ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
                    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
                }
                val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(44100) }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // keep the chain in 16-bit PCM
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            mixer,
                            resampler,
                            balanceProcessor,
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
                // clock that feeds the FIFO — it must never react to focus. The
                // audible endpoint is the local snapclient, which owns focus; when
                // its Oboe stream grabs focus a few seconds in, a focus-handling
                // ExoPlayer would see a "loss" and pause itself, stalling the tee →
                // snapserver goes idle → snapclient runs out of chunks (silence).
                /* handleAudioFocus = */
                false,
            )
            // Becoming-noisy (headphone unplug) must not pause the broadcast either —
            // other rooms/web players keep listening.
            .setHandleAudioBecomingNoisy(false)
            .build()
        // Local audio comes from the snapclient; the tee sits pre-volume so the
        // FIFO always receives full-scale PCM.
        player.volume = 0f

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerIsPlaying = isPlaying
                if (isPlaying) {
                    // Only NOW start feeding the FIFO: writing during preroll
                    // deadlocks — the 64KB pipe has no reader yet and a blocked
                    // tee stalls READY forever.
                    fifoSink?.enableWrites()
                    snapcastManager.startBroadcast(snapNowPlaying, snapController)
                }
                publishNowPlaying()
            }
        })

        // The playlist lives in PlayerViewModel (one MediaItem at a time), so ExoPlayer never
        // has a next/previous item and disables those commands — breaking Bluetooth/AVRCP and
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

        // Listen-in takes over the audio output: pause our own playback (the
        // manager already tore the broadcast stack down before connecting).
        serviceScope.launch {
            snapcastManager.state.map { it.isListening }.distinctUntilChanged().collect { listening ->
                if (listening) mediaSession?.player?.pause()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        snapcastManager.stopBroadcast()
        snapcastManager.disconnectListen()
        fifoSink?.close()
        fifoSink = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
