package tech.capullo.telecloudradio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.capullo.telecloudradio.data.store.SavedPreferences
import tech.capullo.telecloudradio.data.store.replaceCorruptStore

enum class ThemeMode { SYSTEM, DARK, LIGHT }

private const val STORE_NAME = "settings_prefs"

/**
 * The settings file. Builds before the move to DataStore kept the same keys in a SharedPreferences
 * file of the same name; the first read copies them here, with their types, and deletes that file.
 */
val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = STORE_NAME,
    corruptionHandler = replaceCorruptStore(STORE_NAME),
    produceMigrations = { listOf(SharedPreferencesMigration(it, STORE_NAME)) },
)

/**
 * The app's settings, kept in a Preferences DataStore. [load] reads the file before the settings
 * exist, so every value starts at what the user saved, never at a default. A setter changes its
 * value at once and the file soon after (see [SavedPreferences]).
 */
class SettingsRepository private constructor(private val file: SavedPreferences) {

    /** The settings file was corrupt and has been replaced by the defaults; true until the user has been told. */
    val resetNotice: StateFlow<Boolean> = file.resetNotice

    /** The user has been told that the settings were reset. */
    fun acknowledgeReset() = file.acknowledgeReset()

    var bufferSizeGb: Float
        get() = file[BUFFER_SIZE_GB] ?: 1.0f
        set(value) {
            file.edit { it[BUFFER_SIZE_GB] = value }
        }

    var rememberLastGroup: Boolean
        get() = file[REMEMBER_LAST_GROUP] ?: false
        set(value) {
            file.edit { it[REMEMBER_LAST_GROUP] = value }
        }

    // Favorited station ids, for pinning them to the top of the station-select list when no
    // search query is active (GroupSelectorScreen). A flow so the heart toggle and the resulting
    // re-sort are live. Stored as a Set<String>, the type the SharedPreferences file had.
    private val _favoriteStationIds = MutableStateFlow(
        file[FAVORITE_STATION_IDS].orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
    )
    val favoriteStationIds: StateFlow<Set<Long>> = _favoriteStationIds.asStateFlow()

    fun toggleFavoriteStation(chatId: Long) {
        val next = _favoriteStationIds.value.toMutableSet().apply {
            if (!add(chatId)) remove(chatId)
        }
        val stored = next.map { it.toString() }.toSet()
        file.edit { it[FAVORITE_STATION_IDS] = stored }
        _favoriteStationIds.value = next
    }

    // Chats that have had one full-history sync since tracks were keyed by (chat, message).
    // Before DB v7 a track whose message id was already used in another chat was silently skipped
    // at import, and the incremental sync never looks below a chat's newest stored id, so each chat
    // needs one full pass to pick those tracks up. Syncs for different chats can run concurrently
    // (station list + player); the edit adds to the set it is given, so neither mark is lost.
    fun needsFullResync(chatId: Long): Boolean = chatId.toString() !in file[FULL_RESYNC_DONE].orEmpty()

    fun markFullResyncDone(chatId: Long) {
        file.edit { it[FULL_RESYNC_DONE] = it[FULL_RESYNC_DONE].orEmpty() + chatId.toString() }
    }

    // Sleep timer duration in minutes; the timer itself lives in PlayerViewModel
    var sleepTimerMinutes: Int
        get() = file[SLEEP_TIMER_MINUTES] ?: 30
        set(value) {
            file.edit { it[SLEEP_TIMER_MINUTES] = value }
        }

    // Web player config (served as webcfg.json next to the web player) - flows
    // so PlaybackService can rewrite the file live when they change.
    private val _webDebugPanel = MutableStateFlow(file[WEB_DEBUG_PANEL] ?: false)
    val webDebugPanel: StateFlow<Boolean> = _webDebugPanel.asStateFlow()

    fun setWebDebugPanel(value: Boolean) {
        file.edit { it[WEB_DEBUG_PANEL] = value }
        _webDebugPanel.value = value
    }

    private val _webAutoplay = MutableStateFlow(file[WEB_AUTOPLAY] ?: false)
    val webAutoplay: StateFlow<Boolean> = _webAutoplay.asStateFlow()

    fun setWebAutoplay(value: Boolean) {
        file.edit { it[WEB_AUTOPLAY] = value }
        _webAutoplay.value = value
    }

    var lastGroupId: Long
        get() = file[LAST_GROUP_ID] ?: -1L
        set(value) {
            file.edit { it[LAST_GROUP_ID] = value }
        }

    // Where to post the public-link announcement when a broadcast's tunnel comes up
    // (PlaybackService reads these at send time, so plain properties suffice - no flow). 0 = off;
    // TDLib chat ids are never 0 (channels/supergroups are negative). The title is only the
    // dropdown's display label - the id is the source of truth.
    var broadcastNotifyChatId: Long
        get() = file[BROADCAST_NOTIFY_CHAT_ID] ?: 0L
        set(value) {
            file.edit { it[BROADCAST_NOTIFY_CHAT_ID] = value }
        }

    var broadcastNotifyChatTitle: String
        get() = file[BROADCAST_NOTIFY_CHAT_TITLE].orEmpty()
        set(value) {
            file.edit { it[BROADCAST_NOTIFY_CHAT_TITLE] = value }
        }

    // Custom announcement message ({{url}}/{{station}} tokens); blank = the default template.
    var broadcastNotifyTemplate: String
        get() = file[BROADCAST_NOTIFY_TEMPLATE].orEmpty()
        set(value) {
            file.edit { it[BROADCAST_NOTIFY_TEMPLATE] = value }
        }

    var lastGroupTitle: String
        get() = file[LAST_GROUP_TITLE].orEmpty()
        set(value) {
            file.edit { it[LAST_GROUP_TITLE] = value }
        }

    // Theme as a flow so MainActivity recomposes immediately when it changes
    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == file[THEME_MODE] } ?: ThemeMode.SYSTEM,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        file.edit { it[THEME_MODE] = mode.name }
        _themeMode.value = mode
    }

    // Tint the app's accent + surface roles with the current cover's dominant hue. Off = the
    // scheme MainActivity would build anyway (wallpaper dynamic colour on S+, else the static
    // fallback). A flow for the same reason as themeMode: the toggle re-themes on the spot.
    private val _artTheme = MutableStateFlow(file[ART_THEME] ?: true)
    val artTheme: StateFlow<Boolean> = _artTheme.asStateFlow()

    fun setArtTheme(enabled: Boolean) {
        file.edit { it[ART_THEME] = enabled }
        _artTheme.value = enabled
    }

    // Human-facing name this device advertises over NSD (what other devices see when
    // scanning) and uses for its own snapclient entry. Blank = fall back to the device
    // model. A flow so SnapcastManager can re-advertise live when it changes mid-broadcast.
    private val _customServerName = MutableStateFlow(file[CUSTOM_SERVER_NAME].orEmpty())
    val customServerName: StateFlow<String> = _customServerName.asStateFlow()

    fun setCustomServerName(value: String) {
        val trimmed = value.trim()
        file.edit { it[CUSTOM_SERVER_NAME] = trimmed }
        _customServerName.value = trimmed
    }

    // Stereo balance: -1 = full left, 0 = center, +1 = full right.
    // Flow so PlaybackService can re-mix live while the slider moves.
    private val _balance = MutableStateFlow(file[BALANCE] ?: 0f)
    val balance: StateFlow<Float> = _balance.asStateFlow()

    fun setBalance(value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        file.edit { it[BALANCE] = clamped }
        _balance.value = clamped
    }

    // Crossfade between consecutive tracks instead of the plain gapless join. Off by default;
    // flows so PlaybackService picks up a toggle or a new length without a restart.
    private val _crossfadeEnabled = MutableStateFlow(file[CROSSFADE_ENABLED] ?: false)
    val crossfadeEnabled: StateFlow<Boolean> = _crossfadeEnabled.asStateFlow()

    fun setCrossfadeEnabled(value: Boolean) {
        file.edit { it[CROSSFADE_ENABLED] = value }
        _crossfadeEnabled.value = value
    }

    private val _crossfadeSeconds = MutableStateFlow(
        (file[CROSSFADE_SECONDS] ?: DEFAULT_CROSSFADE_SECONDS).coerceIn(CROSSFADE_SECONDS_RANGE),
    )
    val crossfadeSeconds: StateFlow<Int> = _crossfadeSeconds.asStateFlow()

    fun setCrossfadeSeconds(value: Int) {
        val clamped = value.coerceIn(CROSSFADE_SECONDS_RANGE)
        file.edit { it[CROSSFADE_SECONDS] = clamped }
        _crossfadeSeconds.value = clamped
    }

    // Look lyrics up on LRCLIB, which then learns the artist, title and length of each track that
    // plays. Off: only lyrics saved by earlier lookups show. Read by LyricsRepository at lookup time.
    private val _lyricsOnline = MutableStateFlow(file[LYRICS_ONLINE] ?: true)
    val lyricsOnline: StateFlow<Boolean> = _lyricsOnline.asStateFlow()

    fun setLyricsOnline(value: Boolean) {
        file.edit { it[LYRICS_ONLINE] = value }
        _lyricsOnline.value = value
    }

    // Added to the speakers' automatic delay (LyricsTiming.autoDelayMs) for every song; positive
    // shows lyric lines later. A flow so an open lyrics view moves as the stepper does.
    private val _lyricsDelayMs = MutableStateFlow(
        (file[LYRICS_DELAY_MS] ?: 0).coerceIn(LYRICS_DELAY_MS_RANGE),
    )
    val lyricsDelayMs: StateFlow<Int> = _lyricsDelayMs.asStateFlow()

    fun setLyricsDelayMs(value: Int) {
        val clamped = value.coerceIn(LYRICS_DELAY_MS_RANGE)
        file.edit { it[LYRICS_DELAY_MS] = clamped }
        _lyricsDelayMs.value = clamped
    }

    // This device's own snapclient spatial role - persisted so a set-once L/R + latency + volume
    // arrangement survives a restart. Defaults = today's behaviour (stereo / 100% / 0ms).
    var snapclientChannel: String
        get() = file[SNAPCLIENT_CHANNEL] ?: "stereo"
        set(value) {
            file.edit { it[SNAPCLIENT_CHANNEL] = value }
        }

    var snapclientVolume: Int
        get() = file[SNAPCLIENT_VOLUME] ?: 100
        set(value) {
            val clamped = value.coerceIn(0, 100)
            file.edit { it[SNAPCLIENT_VOLUME] = clamped }
        }

    var snapclientLatency: Int
        get() = file[SNAPCLIENT_LATENCY] ?: 0
        set(value) {
            file.edit { it[SNAPCLIENT_LATENCY] = value }
        }

    companion object {
        /** Reads [store] and returns the settings it holds; changes are written on [scope]. */
        suspend fun load(store: DataStore<Preferences>, scope: CoroutineScope): SettingsRepository = SettingsRepository(SavedPreferences.read(STORE_NAME, store, scope))

        const val DEFAULT_CROSSFADE_SECONDS = 6
        val CROSSFADE_SECONDS_RANGE = 2..12
        const val MAX_LYRICS_DELAY_MS = 2_000
        const val LYRICS_DELAY_STEP_MS = 100
        val LYRICS_DELAY_MS_RANGE = -MAX_LYRICS_DELAY_MS..MAX_LYRICS_DELAY_MS

        // Names and types are those of the SharedPreferences file that the migration copies.
        private val BUFFER_SIZE_GB = floatPreferencesKey("buffer_size_gb")
        private val REMEMBER_LAST_GROUP = booleanPreferencesKey("remember_last_group")
        private val FAVORITE_STATION_IDS = stringSetPreferencesKey("favorite_station_ids")
        private val FULL_RESYNC_DONE = stringSetPreferencesKey("full_resync_done_v7")
        private val SLEEP_TIMER_MINUTES = intPreferencesKey("sleep_timer_minutes")
        private val WEB_DEBUG_PANEL = booleanPreferencesKey("web_debug_panel")
        private val WEB_AUTOPLAY = booleanPreferencesKey("web_autoplay")
        private val LAST_GROUP_ID = longPreferencesKey("last_group_id")
        private val BROADCAST_NOTIFY_CHAT_ID = longPreferencesKey("broadcast_notify_chat_id")
        private val BROADCAST_NOTIFY_CHAT_TITLE = stringPreferencesKey("broadcast_notify_chat_title")
        private val BROADCAST_NOTIFY_TEMPLATE = stringPreferencesKey("broadcast_notify_template")
        private val LAST_GROUP_TITLE = stringPreferencesKey("last_group_title")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val ART_THEME = booleanPreferencesKey("art_theme")
        private val CUSTOM_SERVER_NAME = stringPreferencesKey("custom_server_name")
        private val BALANCE = floatPreferencesKey("balance")
        private val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        private val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        private val LYRICS_ONLINE = booleanPreferencesKey("lyrics_online")
        private val LYRICS_DELAY_MS = intPreferencesKey("lyrics_delay_ms")
        private val SNAPCLIENT_CHANNEL = stringPreferencesKey("snapclient_channel")
        private val SNAPCLIENT_VOLUME = intPreferencesKey("snapclient_volume")
        private val SNAPCLIENT_LATENCY = intPreferencesKey("snapclient_latency")
    }
}
