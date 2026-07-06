package tech.capullo.telecloudradio

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import tech.capullo.telecloudradio.auth.AuthScreen
import tech.capullo.telecloudradio.data.playlist.ActivePlayback
import tech.capullo.telecloudradio.ui.groupselector.GroupSelectorScreen
import tech.capullo.telecloudradio.ui.player.PlayerScreen
import tech.capullo.telecloudradio.ui.settings.SettingsScreen

@Serializable data object AuthRoute : NavKey

@Serializable data object GroupSelectorRoute : NavKey

@Serializable data object SettingsRoute : NavKey

@Serializable data class PlayerRoute(val chatId: Long, val chatTitle: String) : NavKey

// Sentinel chatId for a listen-in (snapclient) session - the now-playing screen
// then renders the remote server's stream instead of a Telegram station.
const val LISTEN_IN_CHAT_ID = Long.MIN_VALUE

@Composable
fun AppNavHost(appViewModel: AppViewModel = hiltViewModel()) {
    val backStack = rememberNavBackStack(AuthRoute)
    val activePlayback by appViewModel.activePlayback.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<AuthRoute> {
                    AuthScreen(onAuthenticated = {
                        val dest = appViewModel.getAutoOpenDestination()
                        if (dest != null) {
                            // addAll is atomic: NavDisplay renders one transition, not two
                            backStack.addAll(listOf(GroupSelectorRoute, PlayerRoute(dest.first, dest.second)))
                        } else {
                            backStack.add(GroupSelectorRoute)
                        }
                    })
                }
                entry<GroupSelectorRoute> {
                    GroupSelectorScreen(
                        onGroupSelected = { chatId, chatTitle ->
                            appViewModel.saveLastGroup(chatId, chatTitle)
                            backStack.add(PlayerRoute(chatId, chatTitle))
                        },
                        // Listen-in: don't persist as last-group (sentinel chatId);
                        // the now-playing screen renders the remote stream.
                        onJoinServer = { _, _, name ->
                            backStack.add(PlayerRoute(LISTEN_IN_CHAT_ID, name))
                        },
                    )
                }
                entry<PlayerRoute> { key ->
                    PlayerScreen(
                        chatId = key.chatId,
                        chatTitle = key.chatTitle,
                        onSettings = { backStack.add(SettingsRoute) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<SettingsRoute> {
                    SettingsScreen(onBack = { backStack.removeLastOrNull() })
                }
            },
        )

        activePlayback?.let { playback ->
            val onPlayerRoute = backStack.lastOrNull() is PlayerRoute
            AnimatedVisibility(
                visible = !onPlayerRoute,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer(
                    playback = playback,
                    onClick = { backStack.add(PlayerRoute(playback.chatId, playback.chatTitle)) },
                    onPrev = appViewModel::prev,
                    onPlayPause = appViewModel::playPause,
                    onNext = appViewModel::next,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    playback: ActivePlayback,
    onClick: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    HorizontalDivider()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val albumArt = playback.albumArt
            if (albumArt != null) {
                val bitmap = remember(albumArt) {
                    BitmapFactory.decodeByteArray(albumArt, 0, albumArt.size)?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    MiniPlayerArtPlaceholder()
                }
            } else {
                MiniPlayerArtPlaceholder()
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playback.track.title ?: playback.track.fileName ?: "Unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(
                        animationMode = MarqueeAnimationMode.Immediately,
                    ),
                )
                Text(
                    text = playback.chatTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(
                        animationMode = MarqueeAnimationMode.Immediately,
                    ),
                )
            }
            IconButton(onClick = { onPrev(); }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = { onPlayPause(); }) {
                Icon(
                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playback.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = { onNext(); }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun MiniPlayerArtPlaceholder() {
    Icon(
        Icons.Default.MusicNote,
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            )
            .padding(8.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}
