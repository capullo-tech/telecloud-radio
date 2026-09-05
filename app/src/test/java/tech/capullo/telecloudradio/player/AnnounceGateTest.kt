package tech.capullo.telecloudradio.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The announce gate in [PlaybackService.onCreate], isolated.
 *
 * The gate decides when a tunnel URL is posted to Telegram, and it has three jobs that pull against
 * each other: never announce the retained URL a singleton TunnelManager carries over from a dead
 * broadcast, always announce a genuine reconnect, and never post the same URL twice. Two earlier
 * shapes each got one of those wrong, so the rules are pinned here rather than re-derived.
 *
 * The collector body is duplicated rather than driven through the real service: PlaybackService is
 * a MediaSessionService with Hilt injection and an ExoPlayer, none of which a JVM unit test can
 * stand up. Keep this in sync with the collector if that block changes.
 */
class AnnounceGateTest {

    private sealed interface State {
        data object Off : State
        data object Starting : State
        data class Active(val url: String) : State
    }

    /**
     * Runs the gate over [script] and returns the URLs it announced. [sendMs] is how long the
     * Telegram round trip takes - the whole point is that emissions arriving during it are not lost.
     */
    private fun announcementsFor(
        initial: State = State.Off,
        sendMs: Long = 300,
        script: suspend (MutableStateFlow<State>) -> Unit,
    ): List<String> = runBlocking {
        val state = MutableStateFlow(initial)
        val posted = java.util.Collections.synchronizedList(mutableListOf<String>())
        var lastAnnouncedUrl: String? = null
        val job = launch(Dispatchers.Default) {
            var seenFirstEmission = false
            state.collect { s ->
                val url = (s as? State.Active)?.url
                if (url != null && seenFirstEmission && url != lastAnnouncedUrl) {
                    lastAnnouncedUrl = url
                    launch {
                        delay(sendMs)
                        posted += url
                    }
                }
                seenFirstEmission = true
            }
        }
        delay(50)
        script(state)
        delay(sendMs * 4)
        job.cancel()
        posted.toList()
    }

    /**
     * TunnelManager is a @Singleton, so its state outlives PlaybackService. A service recreated in
     * the same process attaches a fresh collector to a retained Active(oldUrl) whose snapserver port
     * stopBroadcast() already tore down. Announcing it would post a link that cannot work.
     */
    @Test
    fun `retained Active from a dead broadcast is not announced`() {
        val posted = announcementsFor(initial = State.Active("https://dead.trycloudflare.com")) { }
        assertEquals(emptyList<String>(), posted)
    }

    /** The ordinary path: one connect, one post. */
    @Test
    fun `a connect is announced once`() {
        val posted = announcementsFor { state ->
            state.value = State.Starting
            delay(50)
            state.value = State.Active("https://first.trycloudflare.com")
        }
        assertEquals(listOf("https://first.trycloudflare.com"), posted)
    }

    /**
     * The regression this test file exists for. StateFlow conflates: when a reconnect lands while
     * the previous send is still in flight, Starting and Active collapse into one Active emission.
     * A gate keyed on "the previous emission was Starting" never fires and the new URL is silently
     * dropped - precisely the flaky-network case the gate is for. Keying on "we have seen a
     * previous emission" survives the collapse.
     */
    @Test
    fun `a reconnect during an in-flight send is still announced`() {
        val posted = announcementsFor { state ->
            state.value = State.Starting
            delay(50)
            state.value = State.Active("https://first.trycloudflare.com")
            delay(50) // send still running
            state.value = State.Starting
            state.value = State.Active("https://second.trycloudflare.com")
        }
        assertEquals(
            listOf("https://first.trycloudflare.com", "https://second.trycloudflare.com"),
            posted,
        )
    }

    /** A reconnect handing back the same URL: the earlier post is live again, so no second one. */
    @Test
    fun `the same url handed back after a drop is not re-announced`() {
        val posted = announcementsFor { state ->
            state.value = State.Starting
            delay(50)
            state.value = State.Active("https://same.trycloudflare.com")
            delay(600) // first send completes
            state.value = State.Starting
            delay(50)
            state.value = State.Active("https://same.trycloudflare.com")
        }
        assertEquals(listOf("https://same.trycloudflare.com"), posted)
    }
}
