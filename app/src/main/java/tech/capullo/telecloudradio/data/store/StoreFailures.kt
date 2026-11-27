package tech.capullo.telecloudradio.data.store

import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

private const val TAG = "Settings"

/**
 * Present in the file that replaced a corrupt one, and removed when the user has been told. It is
 * kept in the store itself so that the notice survives a process that ends before a screen shows
 * it: the playback service reads the settings with no activity on screen.
 */
internal val RESET_NOTICE = booleanPreferencesKey("reset_notice")

/**
 * Replaces a Preferences DataStore file that cannot be parsed with one that holds only
 * [RESET_NOTICE], so that the store reads and writes again and the user learns of the loss.
 * Without a handler DataStore throws the `CorruptionException` again on every read and every
 * write and leaves the file as it is (seen in QuantumCast, quantumcast#39).
 */
internal fun replaceCorruptStore(name: String) = ReplaceFileCorruptionHandler { e ->
    Log.e(TAG, "$name: the file is corrupt, replaced by the defaults", e)
    preferencesOf(RESET_NOTICE to true)
}

/**
 * Gives the defaults in place of a file that cannot be read, and logs why. Corruption does not
 * arrive here ([replaceCorruptStore] has replaced the file by then); what does is an I/O failure
 * such as a file that cannot be opened. Anything that is not an [IOException] is a defect in the
 * app and is thrown again.
 */
internal fun Flow<Preferences>.defaultsOnReadError(name: String): Flow<Preferences> = catch { e ->
    if (e !is IOException) throw e
    Log.e(TAG, "$name: read failed, using the defaults", e)
    emit(emptyPreferences())
}
