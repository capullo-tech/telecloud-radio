package tech.capullo.telecloudradio.data.store

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.playlist.PlayerStateStore
import tech.capullo.telecloudradio.data.playlist.playerStateStore
import tech.capullo.telecloudradio.data.settingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads both store files on [scope]'s background threads from the moment it is created, which is
 * when MainApplication starts (it injects this). In CapulloTube the first read of its settings
 * file took 364 ms on a OnePlus 3 (Android 11, debuggable build) and ran on the main thread before
 * the first frame (capullo-tube#39). Here the reads run while the app starts; [settings] and
 * [playerState] block only a caller that needs a store before its read has finished, and log it.
 */
@Singleton
class StoreLoader @Inject constructor(
    @ApplicationContext context: Context,
    scope: CoroutineScope,
) {
    private val settings: Deferred<SettingsRepository> = scope.async {
        timed("settings") { SettingsRepository.load(context.settingsStore, scope) }
    }

    private val playerState: Deferred<PlayerStateStore> = scope.async {
        timed("player state") { PlayerStateStore.load(context.playerStateStore, scope) }
    }

    fun settings(): SettingsRepository = get(settings, "settings")

    fun playerState(): PlayerStateStore = get(playerState, "player state")

    private inline fun <T> timed(name: String, read: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return read().also { Log.d(TAG, "$name read in ${SystemClock.elapsedRealtime() - start} ms") }
    }

    /** Blocks the calling thread until [store] is read, and logs any wait. */
    private fun <T> get(store: Deferred<T>, name: String): T {
        if (store.isCompleted) return runBlocking { store.await() }
        val start = SystemClock.elapsedRealtime()
        return runBlocking { store.await() }.also {
            Log.d(TAG, "${Thread.currentThread().name} waited ${SystemClock.elapsedRealtime() - start} ms for the $name")
        }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
