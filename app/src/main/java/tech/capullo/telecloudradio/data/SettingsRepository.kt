package tech.capullo.telecloudradio.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    var bufferSizeGb: Float
        get() = prefs.getFloat("buffer_size_gb", 1.0f)
        set(value) { prefs.edit().putFloat("buffer_size_gb", value).apply() }

    var rememberLastGroup: Boolean
        get() = prefs.getBoolean("remember_last_group", false)
        set(value) { prefs.edit().putBoolean("remember_last_group", value).apply() }

    // Sleep timer duration in minutes; the timer itself lives in PlayerViewModel
    var sleepTimerMinutes: Int
        get() = prefs.getInt("sleep_timer_minutes", 30)
        set(value) { prefs.edit().putInt("sleep_timer_minutes", value).apply() }

    // Web player config (served as webcfg.json next to the web player) - flows
    // so PlaybackService can rewrite the file live when they change.
    private val _webDebugPanel = MutableStateFlow(prefs.getBoolean("web_debug_panel", false))
    val webDebugPanel: StateFlow<Boolean> = _webDebugPanel.asStateFlow()

    fun setWebDebugPanel(value: Boolean) {
        prefs.edit().putBoolean("web_debug_panel", value).apply()
        _webDebugPanel.value = value
    }

    private val _webAutoplay = MutableStateFlow(prefs.getBoolean("web_autoplay", false))
    val webAutoplay: StateFlow<Boolean> = _webAutoplay.asStateFlow()

    fun setWebAutoplay(value: Boolean) {
        prefs.edit().putBoolean("web_autoplay", value).apply()
        _webAutoplay.value = value
    }

    var lastGroupId: Long
        get() = prefs.getLong("last_group_id", -1L)
        set(value) { prefs.edit().putLong("last_group_id", value).apply() }

    var lastGroupTitle: String
        get() = prefs.getString("last_group_title", "") ?: ""
        set(value) { prefs.edit().putString("last_group_title", value).apply() }

    // Theme as a flow so MainActivity recomposes immediately when it changes
    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", "SYSTEM")!!) }
            .getOrDefault(ThemeMode.SYSTEM),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    // Stereo balance: -1 = full left, 0 = center, +1 = full right.
    // Flow so PlaybackService can re-mix live while the slider moves.
    private val _balance = MutableStateFlow(prefs.getFloat("balance", 0f))
    val balance: StateFlow<Float> = _balance.asStateFlow()

    fun setBalance(value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        prefs.edit().putFloat("balance", clamped).apply()
        _balance.value = clamped
    }
}
