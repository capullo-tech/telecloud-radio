package tech.capullo.telecloudradio.data.lyrics

import android.os.SystemClock
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.TrackKey
import tech.capullo.telecloudradio.data.db.LyricsDao
import tech.capullo.telecloudradio.data.db.LyricsEntity
import tech.capullo.telecloudradio.data.lyrics.LyricsRepository.Companion.KIND_NONE
import tech.capullo.telecloudradio.data.lyrics.LyricsRepository.Companion.KIND_SYNCED
import tech.capullo.telecloudradio.data.lyrics.LyricsRepository.Companion.MAX_TRACK_SECONDS
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRepositoryTest {

    private class FakeDao : LyricsDao {
        val rows = mutableMapOf<TrackKey, LyricsEntity>()

        override suspend fun get(chatId: Long, messageId: Long): LyricsEntity? = rows[TrackKey(chatId, messageId)]

        override suspend fun upsert(lyrics: LyricsEntity) {
            rows[TrackKey(lyrics.chatId, lyrics.messageId)] = lyrics
        }

        override suspend fun setOffset(chatId: Long, messageId: Long, offsetMs: Long) {
            val key = TrackKey(chatId, messageId)
            rows[key]?.let { rows[key] = it.copy(offsetMs = offsetMs) }
        }

        override suspend fun delete(chatId: Long, messageId: Long) {
            rows.remove(TrackKey(chatId, messageId))
        }

        override suspend fun deleteAll() = rows.clear()

        override suspend fun deleteByChat(chatId: Long) {
            rows.keys.removeAll { it.chatId == chatId }
        }
    }

    private val dao = FakeDao()
    private val online = MutableStateFlow(true)
    private val settings = mockk<SettingsRepository> { every { lyricsOnline } returns online }
    private val lrclib = mockk<LrclibClient>()

    private val key = TrackKey(chatId = -100, messageId = 7)

    // Real-looking names: "Artist" and "Title" are tag placeholders the query treats as missing.
    private val track = LyricsTrack(key, title = "Roads", performer = "Portishead", fileName = null, durationS = 200)

    // Audio Telegram has no length for: sent without metadata, or as a file.
    private val unmeasured = track.copy(durationS = 0)

    private val syncedRecord = LrclibRecord(duration = 200.0, syncedLyrics = "[00:01.00]a\n[00:02.00]b", plainLyrics = "a\nb")

    @Before
    fun setUp() {
        mockkStatic(Log::class, SystemClock::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @After
    fun tearDown() = unmockkStatic(Log::class, SystemClock::class)

    private fun TestScope.repository() = LyricsRepository(dao, lrclib, settings, this)

    private fun TestScope.play(repository: LyricsRepository, playing: LyricsTrack = track): LyricsState.Done {
        repository.onTrackStarted(playing)
        advanceUntilIdle()
        return repository.current.value as LyricsState.Done
    }

    @Test
    fun `with the online lookup off nothing is asked or cached, and turning it on asks LRCLIB`() = runTest {
        val repository = repository()
        online.value = false

        assertNull(play(repository).lyrics)
        assertTrue(dao.rows.isEmpty())
        coVerify(exactly = 0) { lrclib.find(any(), any()) }

        online.value = true
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)

        repository.retryFailed()
        advanceUntilIdle()
        val found = repository.current.value as LyricsState.Done

        assertEquals(LyricsSource.LRCLIB_GET, found.lyrics?.source)
        assertTrue(found.lyrics!!.synced)
        assertEquals(KIND_SYNCED, dao.rows.getValue(key).kind)
        coVerify(exactly = 1) { lrclib.find(LyricsQuery("Portishead", "Roads"), 200) }
    }

    @Test
    fun `an instrumental LRCLIB record is cached as no lyrics`() = runTest {
        val instrumental = LrclibRecord(duration = 200.0, instrumental = true)
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_SEARCH, instrumental)

        val done = play(repository())

        assertNull(done.lyrics)
        assertFalse(done.failed)
        assertEquals(KIND_NONE, dao.rows.getValue(key).kind)
    }

    @Test
    fun `no LRCLIB match is cached as no lyrics and not asked again on the next play`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } returns null

        assertNull(play(repository).lyrics)
        assertNull(play(repository).lyrics)

        assertEquals(KIND_NONE, dao.rows.getValue(key).kind)
        coVerify(exactly = 1) { lrclib.find(any(), any()) }
    }

    @Test
    fun `a failed lookup is not cached and is retried`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } throws IOException("offline")

        val failed = play(repository)
        assertNull(failed.lyrics)
        assertTrue(failed.failed)
        assertTrue(dao.rows.isEmpty())

        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)
        repository.retryFailed()
        advanceUntilIdle()

        val retried = repository.current.value as LyricsState.Done
        assertEquals(LyricsSource.LRCLIB_GET, retried.lyrics?.source)
        assertFalse(retried.failed)
    }

    @Test
    fun `a track longer than a song, or of unknown length, is not looked up`() = runTest {
        val repository = repository()

        assertNull(play(repository, track.copy(durationS = 3_600)).lyrics)
        assertNull(play(repository, track.copy(durationS = 0)).lyrics)

        assertTrue(dao.rows.isEmpty())
        coVerify(exactly = 0) { lrclib.find(any(), any()) }
    }

    @Test
    fun `a track without a Telegram length is looked up with the player's, rounded`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } returns
            LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)

        assertNull(play(repository, unmeasured).lyrics)
        assertTrue(dao.rows.isEmpty())

        repository.onDurationLearned(key, 199_600)
        advanceUntilIdle()

        val found = repository.current.value as LyricsState.Done
        assertEquals(LyricsSource.LRCLIB_GET, found.lyrics?.source)
        assertEquals(KIND_SYNCED, dao.rows.getValue(key).kind)
        coVerify(exactly = 1) { lrclib.find(any(), any()) }
        coVerify(exactly = 1) { lrclib.find(LyricsQuery("Portishead", "Roads"), 200) }
    }

    @Test
    fun `the player reporting the length again does not ask LRCLIB again`() = runTest {
        val repository = repository()
        // A failed lookup caches nothing, so a second lookup would reach LRCLIB again.
        coEvery { lrclib.find(any(), any()) } coAnswers {
            delay(1_000)
            throw IOException("offline")
        }
        play(repository, unmeasured)

        repository.onDurationLearned(key, 200_000)
        runCurrent()
        // Once while the first lookup waits on LRCLIB, once after it failed.
        repository.onDurationLearned(key, 200_000)
        advanceUntilIdle()
        repository.onDurationLearned(key, 200_000)
        advanceUntilIdle()

        assertTrue((repository.current.value as LyricsState.Done).failed)
        coVerify(exactly = 1) { lrclib.find(any(), any()) }
    }

    @Test
    fun `a length for another track is ignored`() = runTest {
        val repository = repository()
        val skipped = play(repository, unmeasured)

        repository.onDurationLearned(TrackKey(key.chatId, messageId = 8), 200_000)
        repository.onDurationLearned(TrackKey(chatId = -200, messageId = key.messageId), 200_000)
        advanceUntilIdle()

        assertSame(skipped, repository.current.value)
        coVerify(exactly = 0) { lrclib.find(any(), any()) }
    }

    @Test
    fun `a track with a Telegram length is not looked up again with the player's`() = runTest {
        val repository = repository()
        // Failed, so nothing is cached: a second lookup would reach LRCLIB again.
        coEvery { lrclib.find(any(), any()) } throws IOException("offline")
        val failed = play(repository)

        repository.onDurationLearned(key, 203_000)
        advanceUntilIdle()

        assertSame(failed, repository.current.value)
        coVerify(exactly = 1) { lrclib.find(any(), any()) }
    }

    @Test
    fun `a measured length longer than a song stays skipped`() = runTest {
        val repository = repository()
        val skipped = play(repository, unmeasured)

        repository.onDurationLearned(key, (MAX_TRACK_SECONDS + 1) * 1_000)

        assertSame(skipped, repository.current.value)
        advanceUntilIdle()
        assertTrue(dao.rows.isEmpty())
        coVerify(exactly = 0) { lrclib.find(any(), any()) }
    }

    @Test
    fun `lyrics cached with the player's length are kept on the next play`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } returns
            LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)
        play(repository, unmeasured)
        repository.onDurationLearned(key, 200_000)
        advanceUntilIdle()
        play(repository, track.copy(key = TrackKey(key.chatId, messageId = 8)))

        val cached = play(repository, unmeasured)
        repository.onDurationLearned(key, 200_000)

        assertEquals(LyricsSource.LRCLIB_GET, cached.lyrics?.source)
        assertSame(cached, repository.current.value)
    }

    @Test
    fun `retrying a failed lookup asks with the length the player supplied`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } throws IOException("offline")
        play(repository, unmeasured)
        repository.onDurationLearned(key, 200_000)
        advanceUntilIdle()
        assertTrue((repository.current.value as LyricsState.Done).failed)

        coEvery { lrclib.find(any(), any()) } returns
            LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)
        repository.retryFailed()
        advanceUntilIdle()

        val retried = repository.current.value as LyricsState.Done
        assertEquals(LyricsSource.LRCLIB_GET, retried.lyrics?.source)
        coVerify(exactly = 2) { lrclib.find(LyricsQuery("Portishead", "Roads"), 200) }
    }

    @Test
    fun `a track with nothing to search for is not looked up`() = runTest {
        val nameless = track.copy(title = "Track 01", performer = null, fileName = "AUD-20230101-WA0001.mp3")

        assertNull(play(repository(), nameless).lyrics)

        assertTrue(dao.rows.isEmpty())
        coVerify(exactly = 0) { lrclib.find(any(), any()) }
    }

    @Test
    fun `a partly stamped LRCLIB entry without plain lyrics shows every line, unsynced`() = runTest {
        val partial = LrclibRecord(duration = 200.0, syncedLyrics = "[00:01.00]one\ntwo\n[00:03.00]three")
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, partial)

        val lyrics = play(repository()).lyrics!!

        assertFalse(lyrics.synced)
        assertEquals(listOf("one", "two", "three"), lyrics.lines.map { it.text })
    }

    @Test
    fun `a per-song nudge is stored with the cached row and comes back on the next play`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)
        play(repository)

        repository.nudge(key, LyricsTiming.SONG_NUDGE_STEP_MS)
        repository.nudge(key, LyricsTiming.SONG_NUDGE_STEP_MS)
        repository.nudge(TrackKey(key.chatId, 8), LyricsTiming.SONG_NUDGE_STEP_MS)
        advanceUntilIdle()

        assertEquals(1_000L, (repository.current.value as LyricsState.Done).offsetMs)
        assertEquals(1_000L, dao.rows.getValue(key).offsetMs)

        val other = track.copy(key = TrackKey(key.chatId, 8))
        coEvery { lrclib.find(any(), any()) } returns null
        play(repository, other)

        assertEquals(1_000L, play(repository).offsetMs)
        coVerify(exactly = 2) { lrclib.find(any(), any()) }
    }

    @Test
    fun `replaying the track that has lyrics keeps them without a new lookup state`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)
        val first = play(repository)

        repository.onTrackStarted(track)

        assertEquals(first, repository.current.value)
    }

    @Test
    fun `the same message id in another chat is another track`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)
        play(repository)

        coEvery { lrclib.find(any(), any()) } returns null
        val elsewhere = play(repository, track.copy(key = TrackKey(chatId = -200, messageId = key.messageId)))

        assertNull(elsewhere.lyrics)
        assertEquals(KIND_SYNCED, dao.rows.getValue(key).kind)
    }
}
