package tech.capullo.telecloudradio

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.util.Log
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
import tech.capullo.telecloudradio.data.playlist.ActiveTrackRepository
import tech.capullo.telecloudradio.snapcast.SnapcastManager
import tech.capullo.telecloudradio.ui.theme.TelecloudRadioTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var snapcastManager: SnapcastManager

    // Read only to feed the album-art tint into the theme; playback itself is driven by
    // PlayerViewModel, which owns this repository's writes.
    @Inject lateinit var activeTrackRepository: ActiveTrackRepository

    // ADB test hooks, mirroring QuantumCast's. MainActivity is the exported launcher, so these are
    // gated to debuggable builds - a release build must expose no control surface to other apps.
    // That guard used to make hooks pointless here, because the rig could only run release-signed
    // TC (a debug build changes the signature, which forces an uninstall and wipes the Telegram
    // session). The `rig` build type removes that: debuggable AND release-signed. Build it with
    // :app:assembleRig, or this is dead code.
    //
    // The other side of that guard: on a rig build there is no caller check at all. MainActivity is
    // exported and singleTop, so EVERY app on the device can fire these hooks - `dbg tunnel` from
    // any installed app opens a public trycloudflare.com URL onto the snapserver port. That is
    // acceptable only because rig builds stay on the bench. Never hand one to a user.
    //
    // The activity must ALREADY be running - a cold start does not reach onNewIntent.
    //   adb shell am start -n tech.capullo.telecloudradio/.MainActivity --es dbg tunnel --ez on true
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Activity hygiene, not a hook: this is the one line here that runs on release builds too,
        // so that a later getIntent() sees the intent actually being handled. Nothing in TC reads
        // getIntent() today.
        setIntent(intent)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val hook = intent.getStringExtra("dbg") ?: return
        // A typo in a hook name, or in a flag name, is otherwise silent: you send the command and
        // watch the phone do nothing. The extra keys are logged too, because `--ez On true` is the
        // same class of mistake and just as invisible. `adb logcat -s MainActivity` answers both.
        // joinToString, not the raw keySet: a Bundle's key set has no useful toString() and prints
        // as MapCollections$KeySet@183a9, which defeats the point.
        Log.i(TAG, "dbg hook: $hook, extras=${intent.extras?.keySet()?.joinToString()}")
        when (hook) {
            // Toggle the public-link tunnel without three taps through Settings. PlaybackService
            // collects settings.tunnelEnabled, so writing it here drives the real wiring - the same
            // path the switch uses, not a shortcut around it.
            // `on` defaults to true, matching QuantumCast's identical hook and QC's convention for
            // dbg boolean extras (pcmdump's `probe` too): the default is the thing you usually
            // want, since turning the tunnel ON is what the hook exists for. Defaulting to false
            // was considered and rejected - it only guards against a mistyped command on a build
            // that is debuggable by definition, and it would make a bare `--es dbg tunnel` mean
            // opposite things on two apps that share a rig phone.
            "tunnel" -> settings.setTunnelEnabled(intent.getBooleanExtra("on", true))
            else -> Log.w(TAG, "unknown dbg hook: $hook")
        }
    }

    override fun onStart() {
        super.onStart()
        // Bringing TC to the foreground reclaims the speaker for our local snapclient:
        // reclaimAudioFocus() does request()+refocus(), evicting whatever else holds audio focus
        // (another app, or a co-broadcasting QuantumCast on this device) and restarting our
        // snapclient if a prior focus loss had stopped it. No-op when not broadcasting (audioFocus
        // is null) and idempotent when we already hold focus, so cold launch / rotation are
        // harmless. Without this, the broadcast ExoPlayer stays isPlaying=true forever, so
        // onIsPlayingChanged fires only on the first play - a plain app-switch back to TC never
        // reclaimed. Deliberately more eager than a normal media app (which grabs the speaker only
        // on explicit play, never on mere foreground): the point is "I switched to TC, take over."
        // FUTURE (on/off setting): the only case where always-stealing could annoy is QC + TC
        // BOTH broadcasting on one device and you foreground TC just to glance - it silences QC
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
            val artTheme by settings.artTheme.collectAsStateWithLifecycle()
            val activePlayback by activeTrackRepository.activePlayback.collectAsStateWithLifecycle()
            TelecloudRadioTheme(
                darkTheme = darkTheme,
                albumArt = activePlayback?.albumArt.takeIf { artTheme },
            ) {
                AppNavHost()
            }
        }
    }

    private companion object {
        private const val TAG = "MainActivity"

        // Scrims matching the framework defaults enableEdgeToEdge() uses for the 3-button nav bar.
        private val lightScrim = Color.argb(0xe6, 0xff, 0xff, 0xff)
        private val darkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
