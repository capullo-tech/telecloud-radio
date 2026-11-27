package tech.capullo.telecloudradio.data

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.capullo.telecloudradio.data.store.RESET_NOTICE
import tech.capullo.telecloudradio.data.store.StoreProcess
import tech.capullo.telecloudradio.data.store.oldSharedPreferences
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SettingsRepositoryTest {
    @get:Rule
    val store = StoreProcess("settings_prefs")

    private fun <T> process(
        oldPrefs: SharedPreferences? = null,
        block: suspend StoreProcess.(SettingsRepository, DataStore<Preferences>) -> T,
    ): T = store.run(SettingsRepository::load, oldPrefs) { settings, data -> store.block(settings, data) }

    @Test
    fun `every value of the old SharedPreferences file is read after the move`() {
        val old = oldSharedPreferences(
            mapOf(
                "buffer_size_gb" to 3.0f,
                "remember_last_group" to true,
                "favorite_station_ids" to setOf("-1001", "-1002"),
                "full_resync_done_v7" to setOf("-1001"),
                "sleep_timer_minutes" to 45,
                "web_debug_panel" to true,
                "web_autoplay" to true,
                "last_group_id" to -1001L,
                "broadcast_notify_chat_id" to -1002L,
                "broadcast_notify_chat_title" to "Announcements",
                "broadcast_notify_template" to "On air: {{station}} {{url}}",
                "last_group_title" to "Radio",
                "theme_mode" to "DARK",
                "art_theme" to false,
                "custom_server_name" to "Kitchen",
                "balance" to -0.25f,
                "crossfade_enabled" to true,
                "crossfade_seconds" to 9,
                "lyrics_online" to false,
                "lyrics_delay_ms" to -300,
                "snapclient_channel" to "left",
                "snapclient_volume" to 70,
                "snapclient_latency" to 40,
            ),
        )

        process(old) { _, _ -> }
        // The second process has no migration: the values must come from the DataStore file.
        process { settings, _ ->
            assertEquals(3.0f, settings.bufferSizeGb)
            assertTrue(settings.rememberLastGroup)
            assertEquals(setOf(-1001L, -1002L), settings.favoriteStationIds.value)
            assertFalse(settings.needsFullResync(-1001L))
            assertTrue(settings.needsFullResync(-1002L))
            assertEquals(45, settings.sleepTimerMinutes)
            assertTrue(settings.webDebugPanel.value)
            assertTrue(settings.webAutoplay.value)
            assertEquals(-1001L, settings.lastGroupId)
            assertEquals(-1002L, settings.broadcastNotifyChatId)
            assertEquals("Announcements", settings.broadcastNotifyChatTitle)
            assertEquals("On air: {{station}} {{url}}", settings.broadcastNotifyTemplate)
            assertEquals("Radio", settings.lastGroupTitle)
            assertEquals(ThemeMode.DARK, settings.themeMode.value)
            assertFalse(settings.artTheme.value)
            assertEquals("Kitchen", settings.customServerName.value)
            assertEquals(-0.25f, settings.balance.value)
            assertTrue(settings.crossfadeEnabled.value)
            assertEquals(9, settings.crossfadeSeconds.value)
            assertFalse(settings.lyricsOnline.value)
            assertEquals(-300, settings.lyricsDelayMs.value)
            assertEquals("left", settings.snapclientChannel)
            assertEquals(70, settings.snapclientVolume)
            assertEquals(40, settings.snapclientLatency)
            assertFalse(settings.resetNotice.value)
        }
        assertTrue(old.all.isEmpty())
    }

    @Test
    fun `a value set is read back at once and survives a restart`() {
        process { settings, data ->
            settings.lastGroupTitle = "Jazz"
            settings.sleepTimerMinutes = 50
            assertEquals("Jazz", settings.lastGroupTitle)
            assertEquals(50, settings.sleepTimerMinutes)
            // Writes reach the file in order, so the last one there means both are.
            data.awaitSaved { it[intPreferencesKey("sleep_timer_minutes")] == 50 }
        }
        process { settings, _ ->
            assertEquals(50, settings.sleepTimerMinutes)
            assertEquals("Jazz", settings.lastGroupTitle)
        }
    }

    @Test
    fun `quick writes reach the file in the order they were made`() {
        process { settings, data ->
            for (step in -100..100) settings.setBalance(step / 100f)
            settings.sleepTimerMinutes = 10
            data.awaitSaved { it[intPreferencesKey("sleep_timer_minutes")] == 10 }
        }
        assertEquals(1f, process { settings, _ -> settings.balance.value })
    }

    @Test
    fun `stations marked from several threads at once are all kept`() {
        val chats = (1L..200L).toList()
        process { settings, data ->
            val pool = Executors.newFixedThreadPool(8)
            chats.forEach { chat -> pool.execute { settings.markFullResyncDone(chat) } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(emptyList<Long>(), chats.filter { settings.needsFullResync(it) })
            data.awaitSaved { it[stringSetPreferencesKey("full_resync_done_v7")]?.size == chats.size }
        }
        process { settings, _ ->
            assertEquals(emptyList<Long>(), chats.filter { settings.needsFullResync(it) })
        }
    }

    @Test
    fun `a corrupt file is replaced and the notice stays until it is acknowledged`() {
        store.file.writeBytes(ByteArray(32) { 0xFF.toByte() })

        process { settings, data ->
            assertTrue(settings.resetNotice.value)
            assertEquals(30, settings.sleepTimerMinutes)
            // Without the replacement this write fails with the CorruptionException of the read.
            settings.sleepTimerMinutes = 20
            data.awaitSaved { it[intPreferencesKey("sleep_timer_minutes")] == 20 }
        }
        // Not acknowledged: a process that ended before a screen showed the notice still owes it.
        process { settings, data ->
            assertTrue(settings.resetNotice.value)
            assertEquals(20, settings.sleepTimerMinutes)
            settings.acknowledgeReset()
            assertFalse(settings.resetNotice.value)
            data.awaitSaved { it[RESET_NOTICE] == null }
        }
        assertFalse(process { settings, _ -> settings.resetNotice.value })
    }

    @Test
    fun `a file that cannot be read gives the defaults and is left as it is`() {
        // A directory in the file's place fails to open with an IOException that is not corruption.
        store.file.mkdir()

        process { settings, _ ->
            assertEquals(30, settings.sleepTimerMinutes)
            assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
            assertFalse(settings.resetNotice.value)
        }
        assertTrue(store.file.isDirectory)
    }

    @Test
    fun `a failure that is not an IOException is not turned into the defaults`() {
        val defect = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("defect") }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = error("not used")
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { SettingsRepository.load(defect, CoroutineScope(Dispatchers.Unconfined)) }
        }
    }
}
