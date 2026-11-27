package tech.capullo.telecloudradio.data.store

import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * One store file in a temporary folder, opened as the app opens it, one process lifetime at a
 * time. Holds what the code under test logged or threw on a background coroutine, for the failure
 * messages. Use it as a JUnit rule.
 */
class StoreProcess(private val name: String) : ExternalResource() {
    private val folder = TemporaryFolder()

    val file: File get() = File(folder.root, "$name.preferences_pb")

    private val trouble = mutableListOf<String>()

    override fun before() {
        folder.create()
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } answers {
            synchronized(trouble) { trouble += "Log.e ${secondArg<String>()}: ${thirdArg<Throwable>()}" }
            0
        }
    }

    override fun after() {
        unmockkStatic(Log::class)
        folder.delete()
    }

    /**
     * Runs [block] with the store over [file], with the app's corruption handler and, when given,
     * the copy from an old SharedPreferences file. [open] builds what the app builds on the store.
     * [block] gets the store too, to wait for a queued write to reach the file before the process
     * ends.
     */
    fun <S, T> run(
        open: suspend (DataStore<Preferences>, CoroutineScope) -> S,
        oldPrefs: SharedPreferences? = null,
        block: suspend (S, DataStore<Preferences>) -> T,
    ): T = runBlocking {
        val job = Job()
        val uncaught = CoroutineExceptionHandler { _, e -> synchronized(trouble) { trouble += "uncaught: $e" } }
        val scope = CoroutineScope(Dispatchers.IO + job + uncaught)
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = replaceCorruptStore(name),
            migrations = listOfNotNull(oldPrefs?.let { prefs -> SharedPreferencesMigration({ prefs }) }),
            scope = scope,
        ) { file }
        try {
            block(open(store, scope), store)
        } finally {
            // DataStore allows one instance per file; the file is free once its scope has ended.
            job.cancelAndJoin()
        }
    }

    /**
     * Reads the store again until [done] holds. One long collection of `data` is not enough: in
     * DataStore 1.2.1 a collection that starts while a write is in progress can miss that write
     * for good (b/431787506, fixed in 1.3.0-alpha03).
     */
    suspend fun DataStore<Preferences>.awaitSaved(done: (Preferences) -> Boolean) {
        var last: Preferences? = null
        val saved = withTimeoutOrNull(5_000) {
            while (true) {
                val now = data.first()
                last = now
                if (done(now)) break
                delay(10)
            }
        }
        if (saved == null) {
            throw AssertionError("not saved within 5 s; the store holds ${last?.asMap()}; ${synchronized(trouble) { trouble.toList() }}")
        }
    }
}

/** A SharedPreferences file as an older build left it; the migration empties it. */
fun oldSharedPreferences(values: Map<String, Any>): SharedPreferences {
    val left = values.toMutableMap()
    val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    every { editor.clear() } answers {
        left.clear()
        editor
    }
    every { editor.commit() } returns true
    val prefs = mockk<SharedPreferences>()
    every { prefs.all } answers { left.toMap() }
    every { prefs.contains(any()) } answers { firstArg<String>() in left }
    every { prefs.edit() } returns editor
    return prefs
}
