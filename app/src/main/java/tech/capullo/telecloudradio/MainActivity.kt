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

    // Returning to the foreground reclaims audio focus so this app owns the speaker while it broadcasts
    // (the local snapclient resumes; the broadcast never stopped).
    override fun onResume() {
        super.onResume()
        snapcastManager.refocusLocalAudio()
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
