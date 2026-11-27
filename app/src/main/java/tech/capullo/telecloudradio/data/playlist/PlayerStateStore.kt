package tech.capullo.telecloudradio.data.playlist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import tech.capullo.telecloudradio.data.store.SavedPreferences
import tech.capullo.telecloudradio.data.store.replaceCorruptStore

private const val STORE_NAME = "player_prefs"

/**
 * Each station's play queue and last played track. Builds before the move to DataStore kept the
 * same keys in a SharedPreferences file of the same name; the first read copies them here, with
 * their types, and deletes that file. A store apart from the settings, because it grows with the
 * number of stations and holds no setting.
 */
val Context.playerStateStore: DataStore<Preferences> by preferencesDataStore(
    name = STORE_NAME,
    corruptionHandler = replaceCorruptStore(STORE_NAME),
    produceMigrations = { listOf(SharedPreferencesMigration(it, STORE_NAME)) },
)

/** One station's queue as PlayerViewModel saved it; a null field was never saved. */
data class SavedQueue(
    val playOrder: String?,
    val filters: String?,
    val baseIds: String?,
    val queueIds: String?,
)

/** Per-station player state, read from memory and written to the file soon after a change (see [SavedPreferences]). */
class PlayerStateStore private constructor(private val file: SavedPreferences) {

    /** The file was corrupt and has been replaced by an empty one; true until the user has been told. */
    val resetNotice: StateFlow<Boolean> = file.resetNotice

    /** The user has been told that the saved queues were reset. */
    fun acknowledgeReset() = file.acknowledgeReset()

    fun queue(chatId: Long) = SavedQueue(
        playOrder = file[playOrderKey(chatId)],
        filters = file[filtersKey(chatId)],
        baseIds = file[baseIdsKey(chatId)],
        queueIds = file[queueIdsKey(chatId)],
    )

    fun saveQueue(chatId: Long, playOrder: String, filters: String, baseIds: String, queueIds: String) {
        file.edit {
            it[playOrderKey(chatId)] = playOrder
            it[filtersKey(chatId)] = filters
            it[baseIdsKey(chatId)] = baseIds
            it[queueIdsKey(chatId)] = queueIds
        }
    }

    fun lastPlayed(chatId: Long): Long? = file[lastPlayedKey(chatId)]

    fun setLastPlayed(chatId: Long, messageId: Long) {
        file.edit { it[lastPlayedKey(chatId)] = messageId }
    }

    companion object {
        /** Reads [store] and returns the state it holds; changes are written on [scope]. */
        suspend fun load(store: DataStore<Preferences>, scope: CoroutineScope): PlayerStateStore = PlayerStateStore(SavedPreferences.read(STORE_NAME, store, scope))

        // Names and types are those of the SharedPreferences file that the migration copies.
        private fun playOrderKey(chatId: Long) = stringPreferencesKey("play_order_$chatId")
        private fun filtersKey(chatId: Long) = stringPreferencesKey("filters_$chatId")
        private fun baseIdsKey(chatId: Long) = stringPreferencesKey("base_ids_$chatId")
        private fun queueIdsKey(chatId: Long) = stringPreferencesKey("queue_ids_$chatId")
        private fun lastPlayedKey(chatId: Long) = longPreferencesKey("last_played_$chatId")
    }
}
