package tech.capullo.telecloudradio.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.playlist.PlayerStateStore

/**
 * Says once that a store file was corrupt and has been replaced by the defaults. Without it a
 * reset looks like the app forgetting what the user set. The notice stays in the file until the
 * user closes this dialog, so it also covers a reset that happened with no screen open.
 */
@Composable
fun StoreResetDialog(settings: SettingsRepository, playerState: PlayerStateStore) {
    val settingsReset by settings.resetNotice.collectAsStateWithLifecycle()
    val playerStateReset by playerState.resetNotice.collectAsStateWithLifecycle()
    val lost = listOfNotNull(
        (
            "Telecloud could not read its settings file, because the file was damaged. " +
                "All settings are at their defaults again, and the favorite stations are gone. " +
                "Each station reads its whole history from Telegram once more. " +
                "The Telegram login, the downloaded tracks and the lyrics are not affected."
            ).takeIf { settingsReset },
        (
            "Telecloud could not read the saved play queues, because the file was damaged. " +
                "Each station starts again from its newest track, in the default order, " +
                "with no filters."
            ).takeIf { playerStateReset },
    )
    if (lost.isEmpty()) return
    val acknowledge = {
        if (settingsReset) settings.acknowledgeReset()
        if (playerStateReset) playerState.acknowledgeReset()
    }
    AlertDialog(
        onDismissRequest = acknowledge,
        title = { Text(if (settingsReset) "Settings were reset" else "Play queues were reset") },
        text = { Text(lost.joinToString("\n\n")) },
        confirmButton = { TextButton(onClick = acknowledge) { Text("OK") } },
    )
}
