package tech.capullo.telecloudradio.data.store

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * What one Preferences DataStore file holds, kept in memory and read from there.
 *
 * The callers came from SharedPreferences and read a value back right after they set it, from any
 * thread and without suspending. So [edit] changes memory at once and queues the same change for
 * the file; one coroutine on the scope writes the queued changes in the order they were made. The
 * changes that queue up while a write runs go to the file together in the next write, so the
 * balance slider, which saves at every step, does not cost one file write per step. A write that
 * fails with an [IOException] is logged, and the values hold in memory until the app stops.
 *
 * The file is read once, in [read], before any write. That keeps the stores clear of the DataStore
 * race b/431787506 (a `data` collection that starts during a write can miss that write for good),
 * which DataStore 1.2.1 still has.
 */
internal class SavedPreferences private constructor(
    private val name: String,
    private val store: DataStore<Preferences>,
    saved: Preferences,
    scope: CoroutineScope,
) {
    @Volatile
    private var values: Preferences = saved

    private val writes = Channel<(MutablePreferences) -> Unit>(Channel.UNLIMITED)

    private val lock = Any()

    init {
        scope.launch {
            for (first in writes) {
                val batch = mutableListOf(first)
                while (true) batch += writes.tryReceive().getOrNull() ?: break
                try {
                    store.edit { prefs -> batch.forEach { it(prefs) } }
                } catch (e: IOException) {
                    Log.e(TAG, "$name: write failed, the values hold until the app stops", e)
                }
            }
        }
    }

    operator fun <T> get(key: Preferences.Key<T>): T? = values[key]

    /**
     * Applies [change] to memory now and to the file soon. [change] may read the preferences it is
     * given, so a read-modify-write such as adding to a set is atomic in memory and in the file.
     */
    fun edit(change: (MutablePreferences) -> Unit) {
        synchronized(lock) {
            values = values.toMutablePreferences().apply(change).toPreferences()
            writes.trySend(change)
        }
    }

    private val _resetNotice = MutableStateFlow(saved[RESET_NOTICE] == true)

    /** The file was corrupt and has been replaced by the defaults; true until the user has been told. */
    val resetNotice: StateFlow<Boolean> = _resetNotice.asStateFlow()

    /** The user has been told that the file was reset. */
    fun acknowledgeReset() {
        edit { it.remove(RESET_NOTICE) }
        _resetNotice.value = false
    }

    companion object {
        private const val TAG = "Settings"

        /** Reads [store], which [name] names in the log, and writes its changes on [scope]. */
        suspend fun read(name: String, store: DataStore<Preferences>, scope: CoroutineScope): SavedPreferences = SavedPreferences(name, store, store.data.defaultsOnReadError(name).first(), scope)
    }
}
