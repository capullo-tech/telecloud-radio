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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
import tech.capullo.audio.player.GainAudioProcessor
import tech.capullo.audio.snapcast.firstArtist
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.telecloudradio.MainActivity
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.TrackKey
import tech.capullo.telecloudradio.data.playlist.ActiveTrackRepository
import tech.capullo.telecloudradio.data.playlist.PlaybackCommand
import tech.capullo.telecloudradio.snapcast.SnapcastManager
import tech.capullo.telecloudradio.util.postFailureMessage
import tech.capullo.telecloudradio.util.renderAnnouncement
import javax.inject.Inject

/**
 * The track a MediaItem plays: PlayerViewModel sets the message id as the mediaId and the chat in
 * [PlaybackService.EXTRA_CHAT_ID]. Null for an item without both (e.g. the listen-in stand-in).
 */
fun MediaItem.trackKey(): TrackKey? {
    val messageId = mediaId.toLongOrNull() ?: return null
    val extras = mediaMetadata.extras ?: return null
    if (!extras.containsKey(PlaybackService.EXTRA_CHAT_ID)) return null
    return TrackKey(extras.getLong(PlaybackService.EXTRA_CHAT_ID), messageId)
}

/**
 * Playback + Snapcast broadcast service.
 *
 * Audio path: ExoPlayer decodes the track; the sink chain forces the PCM to
 * 48000:16:2 ([ChannelMixing → Sonic] ), applies stereo balance and the track's
 * normalization gain, then a tee ([RoutableTee]) copies it into the snapserver FIFO. The
 * AudioTrack output stays (it paces playback and provides the position clock) but player
 * volume is 0 - the audible output on this device is the local snapclient, which is
 * perfectly in sync with every other Snapcast/web listener on the LAN.
 *
 * With crossfade on (Settings), the tee feeds a [CrossfadeMixer] instead, and near the end of
 * each track a second player prerolls the staged next track and fades in over it; at the
 * handoff that player becomes the session's player. See the crossfade section below.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var activeTrackRepository: ActiveTrackRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var snapcastManager: SnapcastManager

    @Inject lateinit var tunnelManager: TunnelManager

    @Inject lateinit var telegramRepository: tech.capullo.telecloudradio.data.telegram.TelegramRepository

    @Inject lateinit var trackGainDao: tech.capullo.telecloudradio.data.db.TrackGainDao

    private var mediaSession: MediaSession? = null
    private var gainJob: Job? = null
    private var fifoSink: FifoAudioBufferSink? = null
    private var releaseSpeakerJob: Job? = null
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

    // The local playback deck (ExoPlayer + its processors; its player wrapped by forwardingPlayer)
    // and the listen-in stand-in player. Held as fields so the isListening collector can swap the
    // MediaSession between them and onDestroy can release both. A crossfade handoff replaces the
    // deck and forwardingPlayer with the ones that prerolled the next track. See the listen-in
    // swap in onCreate for why the stand-in exists.
    private var primary: Deck? = null
    private var forwardingPlayer: Player? = null
    private var remoteListenPlayer: RemoteListenPlayer? = null

    /**
     * One player and the per-player parts of its sink chain. Processors keep per-stream state, so
     * two players can't share them: each crossfade builds a second deck for the incoming track.
     * Both tees are [RoutableTee]s, because Media3 fixes a processor's place in the chain at build
     * time and a crossfade has to repoint them afterwards. [inputLead] is the player's sink, which
     * measures how far its tees run ahead of its position clock.
     */
    private class Deck(
        val player: ExoPlayer,
        val balance: BalanceAudioProcessor,
        val gain: GainAudioProcessor,
        val fifoRoute: RoutableTee,
        val silenceRoute: RoutableTee,
        val inputLead: InputLeadAudioSink,
    ) {
        /** This deck's way into the mixer while crossfade is on; null while its tee feeds the FIFO directly. */
        var lane: CrossfadeMixer.Lane? = null
    }

    /** One crossfade into [target], from arming to handoff. [item] is the staged MediaItem itself. */
    private class Crossfade(val target: TrackKey, val item: MediaItem) {
        var deck: Deck? = null
        var listener: Player.Listener? = null
        var gainJob: Job? = null
        var ready = false
        var fadeMs = 0L
        var fadeStartedElapsedMs = 0L // 0 = still prerolling
    }

    // --- Crossfade ---
    // Nothing here exists until the setting is turned on. Then the primary deck's FIFO tee feeds
    // its mixer lane; near the end of a track crossfadeTick arms a Crossfade on the item
    // PlayerViewModel staged next (by chat + message id), prerolls it on a second deck, starts the
    // fade, and once the mixer has handed the broadcast over, completeCrossfade promotes that deck.
    private var mixer: CrossfadeMixer? = null
    private var crossfade: Crossfade? = null

    // A boundary whose crossfade failed (decode error, too little time left, never handed off): it
    // keeps the gapless join until the primary moves on to another item.
    private var crossfadeSkip: TrackKey? = null

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
        val station = activeTrackRepository.activePlayback.value?.chatTitle.orEmpty()
        val text = renderAnnouncement(settings.broadcastNotifyTemplate, station, url)
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
                activeTrackRepository.emitMessage(postFailureMessage(it, channel, "the public link"))
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

    // Playback callbacks of whichever deck is primary; completeCrossfade moves it to the new one.
    private val primaryListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The track under an armed crossfade changed - an explicit skip (setMediaItem), or the
            // gapless advance beating a fade that didn't finish in time: its target is stale. A
            // gapless advance after the audio handoff (the posted promotion running late) keeps
            // it: taking the broadcast back would restart the incoming track from the top.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || !crossfadeHandedOff()) {
                cancelCrossfade(promoteIfHandedOff = false)
            }
            crossfadeSkip = null
            // Fresh track → restart the silence grace window and clear the per-item skip guard.
            // Fires for the gapless addMediaItem advance as well as explicit setMediaItem jumps.
            trackStartedElapsedMs = SystemClock.elapsedRealtime()
            silenceSkipFiredForItem = false
            // Apply its stored normalization gain (see observeGain). The observer is replaced on
            // every transition, so a late row can't touch the next item.
            gainJob?.cancel()
            val gain = primary?.gain ?: return
            gain.gain = 1f
            val key = mediaItem?.trackKey() ?: return
            gainJob = observeGain(key) { gain.gain = it }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // PlayerViewModel unstaged or replaced the next item (a queue edit, or the sleep timer
            // waiting for this track to end): no crossfade into it may start or finish. Once the
            // audio has handed off, listeners already hear the incoming track and the outgoing one
            // must not come back; the pending completeCrossfade promotes it and PlayerViewModel
            // syncs to it as to a gapless advance.
            val xf = crossfade ?: return
            val player = primary?.player ?: return
            if (stagedNext(player)?.first != xf.target && !crossfadeHandedOff()) {
                cancelCrossfade(promoteIfHandedOff = false)
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                cancelCrossfade(promoteIfHandedOff = false)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerIsPlaying = isPlaying
            if (isPlaying) {
                // Only NOW start feeding the FIFO: writing during preroll
                // deadlocks - the 64KB pipe has no reader yet and a blocked
                // tee stalls READY forever.
                fifoSink?.enableWrites()
                // Release whatever was held since the pause, faded in.
                tailPauseJob?.cancel()
                fifoSink?.resumeOutput()
                snapcastManager.startBroadcast(snapNowPlaying, snapController)
                // Playing again after the speaker was released from ENDED/IDLE: starting a new
                // track there doesn't change playWhenReady, so no explicit-Play callback comes.
                cancelSpeakerRelease()
                snapcastManager.retakeReleasedSpeaker()
            } else {
                // ExoPlayer keeps draining a few hundred ms of PCM into the tee after a pause;
                // reaching snapserver after it has gone idle, those bursts flip the stream back
                // to playing and every speaker plays a fragment. Fade out and hold them instead.
                pauseFifoWhenTailDrained()
                // Paused (or stalled) mid-crossfade: drop the incoming deck, the outgoing track ramps
                // back up. If the audio has already handed off, the pending completeCrossfade promotes
                // the incoming deck and carries this pause over to it.
                if (!crossfadeHandedOff()) cancelCrossfade(promoteIfHandedOff = false)
            }
            publishNowPlaying()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Explicit Play only (a paused->playing change the user asked for, from any
            // controller: app, notification, headset): reclaim audio focus so a co-broadcasting
            // app's local snapclient is evicted and only this device is audible. Not tied to
            // isPlaying, which also flips back to true after every buffering stall and would
            // steal the speaker from whoever the user had since handed it to. Bringing the app
            // to the foreground reclaims separately (MainActivity.onStart).
            if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                snapcastManager.reclaimAudioFocus(fromPlay = true)
            }
            // Paused long enough: give the speaker back (focus + local snapclient) so a paused
            // TC neither blocks nor later auto-resumes over another app. The broadcast stays up.
            if (playWhenReady) cancelSpeakerRelease() else scheduleSpeakerRelease()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // A finished playlist or a player error leaves playWhenReady = true, so the
            // callback above never fires; the speaker would be held for silence forever.
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                scheduleSpeakerRelease()
                // Same guard as the siblings: once the audio has handed off, the outgoing deck
                // ending (or erroring on its tail) before the posted promotion must not take the
                // broadcast back from the incoming one.
                if (!crossfadeHandedOff()) cancelCrossfade(promoteIfHandedOff = false)
            }
        }
    }

    // One decode pipeline: [mix → 2ch] → [resample → 48000] → [balance] → [gain] →
    // [tee → silence route] → [tee → FIFO route] → silent AudioTrack. Balance and gain sit before
    // the tees so every listener (local snapclient, LAN clients, web players) hears the same
    // adjusted audio. Builds the primary deck in onCreate and each crossfade's incoming deck.
    private fun buildDeck(): Deck {
        val balance = BalanceAudioProcessor().apply { this.balance = settings.balance.value }
        val gain = GainAudioProcessor()
        val fifoRoute = RoutableTee()
        val silenceRoute = RoutableTee()
        var inputLead: InputLeadAudioSink? = null
        val renderersFactory = object : DefaultRenderersFactory(this) {
            // Prefer platform decoders, but fall back to the bundled Media3 FFmpeg extension (on the
            // classpath via capullo-audio) for formats the platform can't handle - widens codec
            // coverage and lets genuinely undecodable input surface as a decoder error.
            init {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }

            // The platform's software Codec2 FLAC decoder (c2.android.flac.decoder - there is no
            // hardware FLAC decode on any phone SoC, so this is what "platform decoder" means for
            // FLAC) runs an output buffer pool that barely recycles (measured 1/6 recycle ratio,
            // static for the life of a track). That churns continuous native allocation + Binder
            // bufferpool transfers, which pressures the Java heap and triggers app-process GC every
            // ~1-1.5s during FLAC playback (measured: zero app GC in 40s on a 48kHz MP3 track vs.
            // continuous GC with 20-50MB LOS churn/cycle on a 44.1kHz FLAC track). Each GC pause
            // occasionally exceeds snapserver's hardcoded 120ms AsioStream dryout window, idling the
            // FIFO -> onResync -> local AudioTrack underrun -> audible stutter, repeating ~1/s.
            //
            // Route audio/flac to the bundled FFmpeg extension decoder instead (different code path,
            // no Codec2 bufferpool involved) by excluding it from the platform codec lookup; every
            // other mimeType keeps the normal platform-first behavior untouched.
            private val flacToFfmpegSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                if (mimeType == MimeTypes.AUDIO_FLAC) {
                    emptyList()
                } else {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                }
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    flacToFfmpegSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out,
                )
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
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // keep the chain in 16-bit PCM
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            mixer,
                            resampler,
                            balance,
                            gain,
                            silenceRoute,
                            fifoRoute,
                        ),
                    )
                    .build()
                return InputLeadAudioSink(sink).also { inputLead = it }
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
        // Local audio comes from the snapclient; the tee sits pre-volume so the
        // FIFO always receives full-scale PCM.
        player.volume = 0f
        // ExoPlayer.Builder.build() builds the renderers, and with them the sink, right away.
        return Deck(player, balance, gain, fifoRoute, silenceRoute, checkNotNull(inputLead))
    }

    // The playlist lives in PlayerViewModel. ExoPlayer itself only ever sees at most two items
    // (current + one pre-enqueued "next", for gapless auto-advance - see
    // PlayerViewModel.syncEnqueuedNext) and never a real multi-item queue, so native
    // next/previous are still unusable for user-facing skip. Force the commands available and
    // route them through ActiveTrackRepository instead, the same path the mini player uses -
    // seekToNext()/seekToPrevious() are hard overrides, never falling through to Media3's
    // native skip-into-queue, so this never collides with the pre-enqueued item.
    private fun buildForwardingPlayer(player: Player): ForwardingPlayer = object : ForwardingPlayer(player) {
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

        serviceScope.launch {
            settings.balance.collect {
                primary?.balance?.balance = it
                crossfade?.deck?.balance?.balance = it
            }
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
        // Announce the public link in the configured Telegram chat when THIS collector observes
        // the tunnel connect. The gate is "we have seen a previous emission", not "the previous
        // emission was Starting", and the difference matters because state is a conflating
        // StateFlow: if a reconnect lands while the send from the last one is still suspended, the
        // Starting and the Active collapse into a single Active emission and a Starting-specific
        // gate never fires - silently dropping exactly the reconnect it exists to catch. Requiring
        // only a non-null predecessor still solves what the gate was built for, because
        // TunnelManager is a singleton that outlives this service: on a fresh collector the
        // retained Active(oldUrl) from a dead broadcast IS the first emission, so previous is null
        // and nothing is announced. (drop(1) cannot express that - the first emission can just as
        // well be a legitimate Active after a fast handshake.)
        //
        // Reconnects still re-announce: quick tunnels mint a fresh URL per connection, so the
        // earlier post is dead by then. A reconnect handing back the SAME url is skipped, because
        // the earlier post is live again and a second would be noise. lastAnnouncedUrl lives in
        // the companion so it survives service recreation within the process, like the singleton.
        //
        // The send is launched rather than awaited inline, so the collector returns immediately
        // and the next emission is never missed for being mid-send. That also means the url must
        // be recorded here rather than after a successful post - two connects in flight at once
        // would otherwise both see the old value and double-post; a post that fails is re-announced
        // by the next connect, which carries a fresh url anyway.
        serviceScope.launch {
            var seenFirstEmission = false
            tunnelManager.state.collect { state ->
                val url = (state as? TunnelManager.TunnelState.Active)?.publicUrl
                if (url != null && seenFirstEmission && url != lastAnnouncedUrl) {
                    lastAnnouncedUrl = url
                    serviceScope.launch { announcePublicLink(url) }
                }
                seenFirstEmission = true
            }
        }
        val deck = buildDeck().also { primary = it }
        deck.fifoRoute.attach(sink)
        deck.silenceRoute.attach(silenceSink)
        deck.player.addListener(primaryListener)
        val sessionPlayer = buildForwardingPlayer(deck.player).also { forwardingPlayer = it }

        // Read-only stand-in player for listen-in mode - holds the foreground service; transport is
        // driven by the listen screen (SnapcastManager.sendStreamControl), not through this player.
        remoteListenPlayer = RemoteListenPlayer(Looper.getMainLooper())

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer)
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
                    // The listen-in stack replaced the broadcast: finish or drop any crossfade first.
                    cancelCrossfade(promoteIfHandedOff = true)
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
                val p = primary?.player ?: continue
                if (silenceSkipFiredForItem || !p.isPlaying) continue
                val audibleRef = maxOf(silenceSink?.lastAudibleElapsedMs ?: 0L, trackStartedElapsedMs)
                if (audibleRef > 0L && SystemClock.elapsedRealtime() - audibleRef > SILENT_SKIP_MS) {
                    silenceSkipFiredForItem = true
                    onUndecodableSilence()
                }
            }
        }

        // Crossfade. With the setting off nothing below runs: the tee feeds the FIFO directly and
        // no second player is ever built. On, a tick watches the primary's position for the point
        // to preroll and then fade into the staged next track. Turned off, the primary's tee keeps
        // going through the mixer until that has nothing left a direct route would skip.
        serviceScope.launch {
            combine(settings.crossfadeEnabled, settings.crossfadeSeconds) { enabled, seconds ->
                if (enabled) seconds * 1000L else 0L
            }.distinctUntilChanged().collectLatest { fadeMs ->
                if (fadeMs > 0L) {
                    routeThroughMixer()
                    while (true) {
                        crossfadeTick(fadeMs)
                        delay(CROSSFADE_TICK_MS)
                    }
                } else {
                    cancelCrossfade(promoteIfHandedOff = true)
                    while (!detachFromMixer()) delay(CROSSFADE_TICK_MS)
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

    // After a crossfade the lead lane can still be airing its delayed tail (paced, on the mixer's
    // tail thread) when STATE_ENDED stops playback; pausing the FIFO then would hold and later
    // discard the end of the track. Defer the pause until that lane has drained, unless playback
    // resumes first. Without a lane (crossfade off) this pauses immediately, as before.
    private var tailPauseJob: Job? = null

    private fun pauseFifoWhenTailDrained() {
        tailPauseJob?.cancel()
        val lane = primary?.lane
        val mixer = this.mixer
        if (lane == null || mixer == null || mixer.isDrained(lane)) {
            fifoSink?.pauseOutput()
            return
        }
        tailPauseJob = serviceScope.launch {
            while (!mixer.isDrained(lane)) delay(TAIL_DRAIN_POLL_MS)
            if (!playerIsPlaying) fifoSink?.pauseOutput()
        }
    }

    private fun scheduleSpeakerRelease() {
        releaseSpeakerJob?.cancel()
        releaseSpeakerJob = serviceScope.launch {
            delay(RELEASE_SPEAKER_AFTER_PAUSE_MS)
            snapcastManager.releaseSpeaker()
        }
    }

    private fun cancelSpeakerRelease() {
        releaseSpeakerJob?.cancel()
        releaseSpeakerJob = null
    }

    // Crossfade on: points the primary deck's FIFO tee at its mixer lane. A lane still attached
    // from before (see detachFromMixer) carries on as it is.
    private fun routeThroughMixer() {
        val deck = primary ?: return
        val sink = fifoSink ?: return
        if (deck.lane != null) return
        val mixer = this.mixer ?: CrossfadeMixer(sink).also { this.mixer = it }
        val lane = mixer.newLane()
        mixer.setLead(lane)
        deck.lane = lane
        deck.fifoRoute.attach(lane, replayFormat = false)
    }

    // Crossfade off: points the primary deck's FIFO tee straight at the FIFO sink again (the
    // single-player path, no mixer in between), but only once its lane has nothing left to render
    // that a direct route would skip. That is the ramp back of a fade the switch abandoned
    // (~50 ms), and the delay a handoff left the lane with (up to its AudioTrack prefill, see
    // CrossfadeMixer), which only goes at a flush that drops it (seek, skip, stop) or at the end of
    // the playlist, where the mixer writes it out. Until then the lane passes the PCM through,
    // delayed, with no mixing. True once the tee feeds the FIFO directly.
    private fun detachFromMixer(): Boolean {
        val deck = primary ?: return true
        val lane = deck.lane ?: return true
        val mixer = mixer ?: return true
        val sink = fifoSink ?: return true
        // Nothing can start rendering in the lane again in between: with the setting off no
        // crossfade arms, and a delay only comes from a handoff.
        if (!mixer.isDrained(lane)) return false
        deck.lane = null
        // The lane stays the mixer's lead: a buffer the playback thread already handed it still
        // passes straight through, and nothing reaches it after that one.
        deck.fifoRoute.attach(sink, replayFormat = false)
        return true
    }

    // The item PlayerViewModel staged right after the current one, with its track identity. Null
    // when nothing is staged or it is the same track again (repeat-one keeps the gapless loop).
    private fun stagedNext(player: Player): Pair<TrackKey, MediaItem>? {
        val index = player.currentMediaItemIndex + 1
        if (index >= player.mediaItemCount) return null
        val item = player.getMediaItemAt(index)
        val key = item.trackKey() ?: return null
        if (key == player.currentMediaItem?.trackKey()) return null
        return key to item
    }

    // Runs every CROSSFADE_TICK_MS while crossfade is on. Arms a crossfade PREROLL_LEAD_MS before
    // the fade has to start, and starts it once the outgoing track has only the fade length left
    // (see fadeRoomMs).
    private fun crossfadeTick(durationMs: Long) {
        val deck = primary ?: return
        val player = deck.player
        val xf = crossfade
        if (xf != null && xf.fadeStartedElapsedMs > 0L && !crossfadeHandedOff() &&
            SystemClock.elapsedRealtime() - xf.fadeStartedElapsedMs > xf.fadeMs + CROSSFADE_HANDOFF_TIMEOUT_MS
        ) {
            // The outgoing tee stopped delivering mid-fade, so the mixer never reached the handoff.
            Log.w(TAG, "crossfade into ${xf.target} never handed off; keeping the gapless join")
            crossfadeSkip = xf.target
            cancelCrossfade(promoteIfHandedOff = false)
            return
        }
        if (!player.isPlaying) return
        val room = fadeRoomMs(deck) ?: return
        if (xf == null) {
            val (target, item) = stagedNext(player) ?: return
            if (target == crossfadeSkip) return
            if (room > durationMs + PREROLL_LEAD_MS) return
            if (room < MIN_FADE_MS) return
            armCrossfade(target, item)
        } else if (xf.ready && xf.fadeStartedElapsedMs == 0L && room <= durationMs) {
            // Shortened if the incoming deck only became ready late.
            val fadeMs = minOf(durationMs, room)
            if (fadeMs < MIN_FADE_MS) {
                crossfadeSkip = xf.target
                cancelCrossfade(promoteIfHandedOff = false)
                return
            }
            startFade(xf, fadeMs)
        }
    }

    // How long a fade starting now can last: one that ends with the outgoing track, measured where
    // the mixer sees it. The fade is counted in the frames the outgoing tee delivers, and the tee
    // runs ahead of the position clock by what the AudioTrack holds (measured, see
    // InputLeadAudioSink), so ending with the tee would leave that much of the track unheard. A
    // lane delayed since an earlier handoff mixes samples that much older, which it can still fade
    // past the tee's end: there its later buffers carry the gapless-staged next item, so the mixer
    // stops taking the lead's PCM from them. Never past the clock's own end of the track, though:
    // there the player advances to that staged item, which cancels a crossfade that hasn't handed
    // off yet. FADE_END_MARGIN_MS covers the tee delivering a buffer more than measured.
    private fun fadeRoomMs(deck: Deck): Long? {
        val player = deck.player
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return null
        val teeLeadMs = deck.inputLead.inputLeadMs() ?: TEE_LEAD_FALLBACK_MS
        val delayMs = mixer?.leadDelayMs() ?: 0L
        return duration - player.currentPosition - maxOf(0L, teeLeadMs - delayMs) - FADE_END_MARGIN_MS
    }

    private fun armCrossfade(target: TrackKey, item: MediaItem) {
        val xf = Crossfade(target, item)
        crossfade = xf
        // The incoming deck's gain has to be right before it decodes anything: its PCM goes through
        // its own gain processor straight into the capture ring, so prerolling at unity would fade
        // in at the wrong level. Keeps observing afterwards, like the primary's lookup, and becomes
        // the primary's observer at the handoff.
        xf.gainJob = observeGain(target) { gain ->
            val deck = xf.deck
            if (deck == null) startPreroll(xf, gain) else deck.gain.gain = gain
        }
    }

    // Applies [key]'s stored normalization gain through [onGain]: unity until the row exists (never
    // analyzed, or analysis still running), then the stored value as soon as it is written, so a
    // freshly downloaded track is corrected mid-play rather than only on its next play.
    private fun observeGain(key: TrackKey, onGain: (Float) -> Unit): Job = serviceScope.launch {
        trackGainDao.observe(key.chatId, key.messageId).collect { row ->
            val gain = row?.gainLinear ?: 1f
            Log.d(TAG, "normalization gain ${key.chatId}/${key.messageId} = $gain (${row?.source ?: "none yet"})")
            onGain(gain)
        }
    }

    private fun startPreroll(xf: Crossfade, gain: Float) {
        val mixer = mixer ?: return
        if (crossfade !== xf) return
        val deck = buildDeck()
        deck.gain.gain = gain
        val lane = mixer.newLane()
        deck.lane = lane
        // Capture from the first sample. The silence route stays detached until promotion: the
        // watchdog belongs to the deck listeners currently hear.
        mixer.prepareIncoming(lane)
        deck.fifoRoute.attach(lane)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) xf.ready = true
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "crossfade target ${xf.target} failed: ${error.message}")
                // Posted: cancelling releases this very player, which must not happen inside its
                // own callback.
                mainHandler.post {
                    if (crossfade === xf) {
                        crossfadeSkip = xf.target
                        cancelCrossfade(promoteIfHandedOff = false)
                    }
                }
            }
        }
        xf.deck = deck
        xf.listener = listener
        deck.player.addListener(listener)
        // The staged MediaItem itself: same mediaId, uri and MediaMetadata (title, artist, artwork,
        // station, chat id extra), so after promotion the session, notification and gain lookup
        // all describe the right track.
        deck.player.setMediaItem(xf.item)
        // Decode up to READY but hold the clock until startFade. What it buffers meanwhile is
        // already in the ring and is the first thing the fade mixes in.
        deck.player.playWhenReady = false
        deck.player.prepare()
    }

    private fun startFade(xf: Crossfade, fadeMs: Long) {
        val mixer = mixer ?: return
        val deck = xf.deck ?: return
        val frames = fadeMs * CrossfadeMixer.SAMPLE_RATE_HZ / 1000
        // Runs on the outgoing playback thread right after the last mixed buffer was written.
        val onHandoff = Runnable { mainHandler.post { completeCrossfade(xf) } }
        if (!mixer.beginFade(frames, onHandoff)) {
            cancelCrossfade(promoteIfHandedOff = false)
            return
        }
        xf.fadeMs = fadeMs
        xf.fadeStartedElapsedMs = SystemClock.elapsedRealtime()
        deck.player.play()
        Log.d(TAG, "crossfading into ${xf.target.chatId}/${xf.target.messageId} over $fadeMs ms")
    }

    private fun crossfadeHandedOff(): Boolean = mixer?.isLead(crossfade?.deck?.lane) == true

    /**
     * Ends the crossfade in flight, if any. Before the audio handoff the incoming deck is dropped
     * and the outgoing track ramps back to full level. After it (listeners already hear the
     * incoming track, only the promotion is still pending), [promoteIfHandedOff] finishes the
     * handoff now; otherwise the outgoing deck takes the broadcast back. Never call with
     * [promoteIfHandedOff] from inside the outgoing player's own callbacks: promotion releases it.
     */
    private fun cancelCrossfade(promoteIfHandedOff: Boolean) {
        val xf = crossfade ?: return
        val mixer = mixer
        val keep = primary?.lane
        if (mixer != null && keep != null && !mixer.abortFade(keep)) {
            if (promoteIfHandedOff) {
                completeCrossfade(xf)
                return
            }
            mixer.revertTo(keep)
        }
        crossfade = null
        xf.gainJob?.cancel()
        val deck = xf.deck ?: return
        xf.listener?.let(deck.player::removeListener)
        deck.fifoRoute.attach(null)
        deck.silenceRoute.attach(null)
        deck.player.release()
    }

    /**
     * Makes the crossfade's deck the primary once the mixer has handed the broadcast to it: the
     * playback listener, silence watchdog, gain observer and MediaSession move over and the
     * outgoing deck is released. PlayerViewModel is told explicitly (ActiveTrackRepository): the
     * session's player swap carries no transition reason of its own. It syncs to the new track as
     * for a gapless advance once its controller shows it.
     */
    private fun completeCrossfade(xf: Crossfade) {
        if (crossfade !== xf) return // cancelled or reverted since the handoff was posted
        val incoming = xf.deck ?: return
        val outgoing = primary ?: return
        crossfade = null
        // A pause that reached the outgoing player after the audio handoff carries over.
        val playWhenReady = outgoing.player.playWhenReady
        // The outgoing player goes quietly: releasing it must neither gate the FIFO nor schedule a
        // speaker release, and nothing it still decodes may reach a sink.
        outgoing.player.removeListener(primaryListener)
        outgoing.fifoRoute.attach(null)
        outgoing.silenceRoute.attach(null)
        xf.listener?.let(incoming.player::removeListener)
        incoming.silenceRoute.attach(silenceSink)
        primary = incoming
        gainJob?.cancel()
        gainJob = xf.gainJob
        trackStartedElapsedMs = SystemClock.elapsedRealtime()
        silenceSkipFiredForItem = false
        crossfadeSkip = null
        incoming.player.addListener(primaryListener)
        val sessionPlayer = buildForwardingPlayer(incoming.player)
        forwardingPlayer = sessionPlayer
        // While listening in, the stand-in keeps the session; the collector swaps this one in after.
        mediaSession?.let { if (it.player !== remoteListenPlayer) it.setPlayer(sessionPlayer) }
        activeTrackRepository.emitCrossfadeHandoff(xf.target)
        outgoing.player.release()
        if (!playWhenReady) incoming.player.pause()
        Log.d(TAG, "crossfade handed off to ${xf.target.chatId}/${xf.target.messageId}, broadcast ${mixer?.leadDelayMs()} ms behind its tee")
    }

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
        // Release every player explicitly: the session only holds whichever is currently swapped in,
        // so releasing session.player alone would leak the others.
        cancelCrossfade(promoteIfHandedOff = false)
        primary?.player?.release()
        primary = null
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

        /** [MediaMetadata.extras] key carrying the track's chat: the mediaId is the messageId,
         *  which is only unique within one chat, and the gain lookup needs both. */
        const val EXTRA_CHAT_ID = "chatId"

        private const val TAG = "PlaybackService"

        // Last public URL this process announced in Telegram - or is about to: it is set when the
        // send is launched, not when it succeeds, so two connects arriving close together cannot
        // both read the stale value and post the same url twice. Companion-level on purpose: it
        // must outlive service recreation just like the singleton TunnelManager does, so a
        // reconnect handing back an already-announced url doesn't double-post.
        var lastAnnouncedUrl: String? = null

        // Silence watchdog: how often it polls, and how long a playing track may go without any
        // audible PCM before it's judged undecodable and skipped. 8s comfortably clears normal
        // buffering / short silent intros while still cutting a dead track quickly.
        private const val WATCHDOG_TICK_MS = 1000L
        private const val SILENT_SKIP_MS = 8000L

        // How long playback stays paused before the local speaker is released.
        private const val TAIL_DRAIN_POLL_MS = 20L

        private const val RELEASE_SPEAKER_AFTER_PAUSE_MS = 30_000L

        // Crossfade timing. A fade is scheduled to end this much before the outgoing track does
        // (see fadeRoomMs).
        private const val FADE_END_MARGIN_MS = 100L

        // How far the outgoing tee is taken to run ahead of the position clock until its sink has
        // measured it: the largest AudioTrack buffer the devices have shown (750 ms).
        private const val TEE_LEAD_FALLBACK_MS = 750L

        // How long before the fade the incoming deck starts prerolling.
        private const val PREROLL_LEAD_MS = 2000L

        // A shorter fade isn't worth it (track too short, or the incoming deck ready too late).
        private const val MIN_FADE_MS = 1000L
        private const val CROSSFADE_TICK_MS = 100L

        // Slack past the fade's own length before a fade that never handed off is abandoned.
        private const val CROSSFADE_HANDOFF_TIMEOUT_MS = 3000L
    }
}
