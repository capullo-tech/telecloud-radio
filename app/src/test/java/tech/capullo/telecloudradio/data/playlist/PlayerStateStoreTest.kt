package tech.capullo.telecloudradio.data.playlist

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.capullo.telecloudradio.data.store.StoreProcess
import tech.capullo.telecloudradio.data.store.oldSharedPreferences

class PlayerStateStoreTest {
    @get:Rule
    val store = StoreProcess("player_prefs")

    private fun <T> process(
        oldPrefs: SharedPreferences? = null,
        block: suspend StoreProcess.(PlayerStateStore, DataStore<Preferences>) -> T,
    ): T = store.run(PlayerStateStore::load, oldPrefs) { state, data -> store.block(state, data) }

    @Test
    fun `each station's queue and last track of the old SharedPreferences file are read after the move`() {
        val old = oldSharedPreferences(
            mapOf(
                "play_order_-1001" to "SHUFFLED",
                "filters_-1001" to """{"minDurationSec":60}""",
                "base_ids_-1001" to "3,1,2",
                "queue_ids_-1001" to "1,2",
                "last_played_-1001" to 3L,
                "last_played_-1002" to 7L,
            ),
        )

        process(old) { _, _ -> }
        process { state, _ ->
            assertEquals(SavedQueue("SHUFFLED", """{"minDurationSec":60}""", "3,1,2", "1,2"), state.queue(-1001L))
            assertEquals(3L, state.lastPlayed(-1001L))
            assertEquals(7L, state.lastPlayed(-1002L))
            assertEquals(SavedQueue(null, null, null, null), state.queue(-1002L))
            assertNull(state.lastPlayed(-1003L))
        }
        assertTrue(old.all.isEmpty())
    }

    @Test
    fun `a saved queue is read back at once and survives a restart`() {
        process { state, data ->
            state.saveQueue(-1001L, playOrder = "OLDEST_FIRST", filters = "{}", baseIds = "1,2,3", queueIds = "2,3")
            state.setLastPlayed(-1001L, 2L)
            assertEquals(SavedQueue("OLDEST_FIRST", "{}", "1,2,3", "2,3"), state.queue(-1001L))
            assertEquals(2L, state.lastPlayed(-1001L))
            data.awaitSaved { it[longPreferencesKey("last_played_-1001")] == 2L }
        }
        process { state, _ ->
            assertEquals(SavedQueue("OLDEST_FIRST", "{}", "1,2,3", "2,3"), state.queue(-1001L))
            assertEquals(2L, state.lastPlayed(-1001L))
        }
    }

    @Test
    fun `a corrupt file is replaced by an empty one with the notice`() {
        store.file.writeBytes(ByteArray(32) { 0xFF.toByte() })

        process { state, _ ->
            assertTrue(state.resetNotice.value)
            assertEquals(SavedQueue(null, null, null, null), state.queue(-1001L))
        }
    }
}
