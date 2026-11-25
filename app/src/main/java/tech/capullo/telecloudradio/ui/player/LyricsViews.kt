package tech.capullo.telecloudradio.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import tech.capullo.telecloudradio.data.lyrics.LyricLine
import tech.capullo.telecloudradio.data.lyrics.Lyrics
import tech.capullo.telecloudradio.data.lyrics.LyricsSource
import tech.capullo.telecloudradio.data.lyrics.LyricsTiming
import kotlin.math.abs

private const val PAUSE_MARK = "♪"

// The current line sits this many lines below the top, so the lines just sung stay in view.
private const val LINES_ABOVE_CURRENT = 2

// Action-row toggle between the album art and the lyrics. Present only while the track has (or,
// between two tracks with lyrics, may have) lyrics.
@Composable
internal fun LyricsButton(open: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Default.Lyrics,
            contentDescription = if (open) "Show album art" else "Show lyrics",
            modifier = Modifier.size(22.dp),
            tint = if (open) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// Takes the album art's square while open. Synced lyrics follow what this device's speaker plays
// ([offsetMs] is the total delay behind the player's position) and scroll themselves until the
// user drags the list; a tap on a timed line seeks there. Colours come from the theme's roles, so
// the album-art tint applies as it does to the rest of the player.
@Composable
internal fun LyricsPanel(
    lyrics: Lyrics?,
    positionState: StateFlow<Long>,
    offsetMs: Long,
    songOffsetMs: Long,
    onSeek: (Long) -> Unit,
    onNudge: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by currentLineIndex(lyrics, positionState, offsetMs)

    val listState = remember(lyrics) { LazyListState() }
    var userMoved by remember(lyrics) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.filterIsInstance<DragInteraction.Start>().first()
        userMoved = true
    }
    LaunchedEffect(listState, current) {
        if (!userMoved && current >= 0) {
            listState.animateScrollToItem((current - LINES_ABOVE_CURRENT).coerceAtLeast(0))
        }
    }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lyrics?.let(::sourceLabel) ?: "Looking for lyrics…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (lyrics?.synced == true) SongNudge(songOffsetMs, onNudge)
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(lyrics?.lines.orEmpty()) { index, line ->
                PanelLine(
                    line,
                    isCurrent = index == current,
                    onSeek = { time -> onSeek(LyricsTiming.seekTarget(time, offsetMs)) },
                )
            }
        }
    }
}

// Moves this song's lines when its LRC is off by a constant; kept with the song's cached lyrics.
@Composable
private fun SongNudge(songOffsetMs: Long, onNudge: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onNudge(-LyricsTiming.SONG_NUDGE_STEP_MS) }) {
            Icon(Icons.Default.Remove, contentDescription = "Lyrics earlier for this song")
        }
        Text(
            nudgeLabel(songOffsetMs),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { onNudge(LyricsTiming.SONG_NUDGE_STEP_MS) }) {
            Icon(Icons.Default.Add, contentDescription = "Lyrics later for this song")
        }
    }
}

@Composable
private fun PanelLine(line: LyricLine, isCurrent: Boolean, onSeek: (Long) -> Unit) {
    val time = line.timeMs
    Text(
        line.shownText(),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (time != null) {
                    Modifier
                        .clickable(onClickLabel = "Play from here") { onSeek(time) }
                        .minimumInteractiveComponentSize()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

// Collected here (leaf) so the 500 ms position ticker recomposes only when the line changes.
@Composable
private fun currentLineIndex(lyrics: Lyrics?, positionState: StateFlow<Long>, offsetMs: Long): State<Int> {
    val position = positionState.collectAsStateWithLifecycle()
    return remember(lyrics, offsetMs) {
        derivedStateOf { lyrics?.let { LyricsTiming.lineIndex(it, position.value, offsetMs) } ?: -1 }
    }
}

private fun sourceLabel(lyrics: Lyrics): String {
    val source = when (lyrics.source) {
        LyricsSource.LRCLIB_GET, LyricsSource.LRCLIB_SEARCH -> "Lyrics by LRCLIB"
    }
    return if (lyrics.synced) source else "$source · not synced"
}

private fun nudgeLabel(offsetMs: Long): String {
    if (offsetMs == 0L) return "0 s"
    val sign = if (offsetMs > 0) "+" else "−"
    val ms = abs(offsetMs)
    return "$sign${ms / 1000}.${ms % 1000 / 100} s"
}

private fun LyricLine.shownText(): String = text.ifEmpty { if (timeMs != null) PAUSE_MARK else "" }
