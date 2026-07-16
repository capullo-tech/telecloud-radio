package tech.capullo.telecloudradio

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.ThemeMode
import tech.capullo.telecloudradio.snapcast.SnapcastManager
import tech.capullo.telecloudradio.ui.theme.TelecloudRadioTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var snapcastManager: SnapcastManager

    override fun onStart() {
        super.onStart()
        // Bringing TC to the foreground reclaims the speaker for our local snapclient:
        // reclaimAudioFocus() does request()+refocus(), evicting whatever else holds audio focus
        // (another app, or a co-broadcasting QuantumCast on this device) and restarting our
        // snapclient if a prior focus loss had stopped it. No-op when not broadcasting (audioFocus
        // is null) and idempotent when we already hold focus, so cold launch / rotation are
        // harmless. Without this, the broadcast ExoPlayer stays isPlaying=true forever, so
        // onIsPlayingChanged fires only on the first play — a plain app-switch back to TC never
        // reclaimed. Deliberately more eager than a normal media app (which grabs the speaker only
        // on explicit play, never on mere foreground): the point is "I switched to TC, take over."
        // FUTURE (on/off setting): the only case where always-stealing could annoy is QC + TC
        // BOTH broadcasting on one device and you foreground TC just to glance — it silences QC
        // here (QC keeps broadcasting to other rooms). If that ever bites, gate this call behind a
        // SettingsRepository "reclaim speaker when opened" toggle (default on), mirroring
        // stationLimit/balance/themeMode: if (settings.reclaimOnForeground.value) reclaim...().
        snapcastManager.reclaimAudioFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // Drive system-bar icon appearance off the in-app theme, not the OS night mode:
            // enableEdgeToEdge()'s default detectDarkMode reads Configuration.uiMode, so forcing the
            // app dark while the phone is in light mode left dark-on-dark (invisible) status icons.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim,
                        darkScrim,
                    ) { darkTheme },
                )
                onDispose {}
            }
            TelecloudRadioTheme(darkTheme = darkTheme) {
                AppNavHost()
            }
        }
    }

    private companion object {
        // Scrims matching the framework defaults enableEdgeToEdge() uses for the 3-button nav bar.
        private val lightScrim = Color.argb(0xe6, 0xff, 0xff, 0xff)
        private val darkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
