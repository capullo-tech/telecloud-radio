package tech.capullo.telecloudradio.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi

/**
 * A minimal, read-only [SimpleBasePlayer] that stands in for the remote Snapcast stream while this
 * device is listening in on another broadcaster.
 *
 * Its sole purpose is to give the MediaSession a *playing* player during listen-in so that
 * [PlaybackService] stays a foreground `mediaPlayback` service. Without that foreground service the
 * platform (Android 15 / One UI "audio hardening") mutes the native snapclient's audio a few seconds
 * after the app is backgrounded.
 *
 * Deliberately **advertises no transport commands** and reports a **constant playing** state:
 *  - No transport commands: the app's other MediaControllers (PlayerViewModel, AppViewModel) issue
 *    `pause()`/`play()` for local-playback bookkeeping. If those were forwarded here they'd be relayed
 *    to the remote broadcaster as Snapcast stream-control commands - e.g. PlayerViewModel's
 *    "pause until a chat is prepared" guard would pause the *broadcaster* in a tight loop. Advertising
 *    no commands makes those calls no-ops. Listen-in transport is driven separately by the listen
 *    screen via SnapcastManager.sendStreamControl.
 *  - Constant playing: the audible output is the snapclient, not this player. Reporting playing for the
 *    whole session keeps the foreground service up even when the remote momentarily pauses, so a remote
 *    resume while backgrounded can't hit the platform's background-FGS-start restriction and get muted.
 */
@OptIn(UnstableApi::class)
class RemoteListenPlayer(looper: Looper) : SimpleBasePlayer(looper) {

    private var metadata: MediaMetadata = MediaMetadata.EMPTY

    /** Push the latest remote now-playing so the notification / lock screen reflect it. */
    fun updateMetadata(metadata: MediaMetadata) {
        this.metadata = metadata
        invalidateState()
    }

    override fun getState(): State {
        // Only metadata-read commands; no transport (see class doc for why).
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .build()
        val item = MediaItemData.Builder(MEDIA_ITEM_UID)
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .setIsDynamic(true) // live stream: no fixed duration
            .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .build()
    }

    private companion object {
        const val MEDIA_ITEM_UID = "listen-in"
    }
}
