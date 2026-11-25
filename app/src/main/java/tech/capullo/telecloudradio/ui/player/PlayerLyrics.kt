package tech.capullo.telecloudradio.ui.player

import tech.capullo.telecloudradio.data.TrackKey
import tech.capullo.telecloudradio.data.lyrics.Lyrics
import tech.capullo.telecloudradio.data.lyrics.LyricsState

// What the player shows for lyrics. None hides the lyrics button and closes the lyrics view, so a
// track without lyrics leaves the player as it is without the feature.
sealed interface PlayerLyrics {
    data object None : PlayerLyrics

    // The next track's lookup is running after a track that had lyrics: the view stays open on a
    // "looking" state instead of closing and reopening between two songs that both have lyrics.
    data object Pending : PlayerLyrics

    data class Shown(val lyrics: Lyrics, val offsetMs: Long = 0) : PlayerLyrics

    companion object {

        // [key] is the track the player shows; a lookup state about another track never shows.
        // Pending needs a lookup in flight: the player can show a track long before it plays (a
        // station just opened, a paused skip waiting for its download), and no lookup starts for
        // it until then. PlayerViewModel starts the lookup just before it swaps the shown track,
        // so a lookup for the next track can briefly sit beside the previous key: that is Pending
        // too, rather than a close and reopen.
        fun next(previous: PlayerLyrics, key: TrackKey?, state: LyricsState?): PlayerLyrics {
            val done = (state as? LyricsState.Done)?.takeIf { it.key == key }
            return when {
                key == null -> None
                done != null -> done.lyrics?.let { Shown(it, done.offsetMs) } ?: None
                state is LyricsState.Loading && previous != None -> Pending
                else -> None
            }
        }
    }
}
