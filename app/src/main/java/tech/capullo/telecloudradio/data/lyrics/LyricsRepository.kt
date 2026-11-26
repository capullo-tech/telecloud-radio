package tech.capullo.telecloudradio.data.lyrics

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.TrackKey
import tech.capullo.telecloudradio.data.db.LyricsDao
import tech.capullo.telecloudradio.data.db.LyricsEntity
import tech.capullo.telecloudradio.data.db.MediaMessageEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

// What a lookup needs from a track: its identity and the names and length LRCLIB matches on.
data class LyricsTrack(
    val key: TrackKey,
    val title: String?,
    val performer: String?,
    val fileName: String?,
    val durationS: Long,
) {
    companion object {
        // Telegram's own length for the audio, in whole seconds; 0 when Telegram had none. An
        // unknown length would match any recording, so the lookup then waits for the length the
        // player measures (LyricsRepository.onDurationLearned).
        fun of(track: MediaMessageEntity): LyricsTrack = LyricsTrack(
            key = TrackKey(track.chatId, track.messageId),
            title = track.title,
            performer = track.performer,
            fileName = track.fileName,
            durationS = (track.duration ?: 0).toLong(),
        )
    }
}

sealed interface LyricsState {
    val key: TrackKey

    data class Loading(override val key: TrackKey) : LyricsState

    data class Done(
        override val key: TrackKey,
        val lyrics: Lyrics?,
        val offsetMs: Long = 0,
        // LRCLIB could not be asked: nothing was cached, and retryFailed asks again.
        val failed: Boolean = false,
    ) : LyricsState
}

// Lyrics of the track playing now. One track at a time: a new track cancels the previous lookup,
// and [current] names the track it is about, so a late answer for an old track is never shown.
@Singleton
class LyricsRepository internal constructor(
    private val dao: LyricsDao,
    private val lrclib: LrclibClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    // Its own scope: a lookup outlives the screen that started it, so the lyrics are ready when
    // the player is opened again.
    @Inject constructor(dao: LyricsDao, lrclib: LrclibClient, settings: SettingsRepository) : this(
        dao,
        lrclib,
        settings,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val _current = MutableStateFlow<LyricsState?>(null)

    val current: StateFlow<LyricsState?> = _current.asStateFlow()

    private var lookup: Job? = null

    // The track the current lookup is for, with the length the player supplied when Telegram had
    // none, so retryFailed asks with it too.
    private var lastTrack: LyricsTrack? = null

    private val offsetWrites = Mutex()

    // Counts lookups so only the newest publishes. A lookup run again for the same track (its
    // length learned) would otherwise race the first, which also waits on Loading(key): a cancel
    // does not stop a lookup that already has its answer.
    private val publishLock = Any()
    private var lookups = 0

    // Called when the player's shown track changes to one that is actually playing. The same
    // track again (a restart, a replay) keeps lyrics it already has instead of blinking through
    // a lookup; one without lyrics is looked up again, as the online setting may have changed.
    fun onTrackStarted(track: LyricsTrack) {
        val now = _current.value
        if (now?.key == track.key && (now is LyricsState.Loading || (now as? LyricsState.Done)?.lyrics != null)) return
        startLookup(track)
    }

    // Looks the current track up again with the length the player measured, when Telegram had
    // none for it (audio sent without metadata or as a file). Telegram's length stays
    // authoritative, and the first usable measurement is kept: the player reports the length
    // again after every seek and rebuffer, which must not start another lookup.
    fun onDurationLearned(key: TrackKey, durationMs: Long) {
        val track = lastTrack?.takeIf { it.key == key && it.durationS <= 0 } ?: return
        // Rounded, not truncated: LRCLIB's exact lookup takes a record within 2 s of this whole
        // second, and bestMatch one within LrclibClient.LYRICS_DURATION_TOLERANCE_S. Rounding
        // spends at most half a second of that margin, truncating up to a whole one.
        val durationS = (durationMs / 1_000.0).roundToLong()
        if (durationS !in 1..MAX_TRACK_SECONDS) return
        // Lyrics the cache already had are kept; only a lookup that ran without a length repeats.
        val now = _current.value
        if (now is LyricsState.Done && now.key == key && now.lyrics != null) return
        Log.i(TAG, "duration key=${key.log()} dur=${durationS}s")
        startLookup(track.copy(durationS = durationS))
    }

    private fun startLookup(track: LyricsTrack) {
        lookup?.cancel()
        lastTrack = track
        val generation = synchronized(publishLock) {
            _current.value = LyricsState.Loading(track.key)
            ++lookups
        }
        lookup = scope.launch {
            val done = load(track)
            synchronized(publishLock) {
                if (generation == lookups) {
                    _current.compareAndSet(LyricsState.Loading(track.key), done)
                }
            }
        }
    }

    // Asks again when the current track's lookup failed (no network, LRCLIB down).
    fun retryFailed() {
        val done = _current.value as? LyricsState.Done ?: return
        val track = lastTrack ?: return
        if (done.failed && done.key == track.key) onTrackStarted(track)
    }

    // The per-song nudge, for synced lyrics only: plain lyrics have no timing to move.
    fun nudge(key: TrackKey, deltaMs: Long) {
        scope.launch {
            offsetWrites.withLock {
                val done = (_current.value as? LyricsState.Done)
                    ?.takeIf { it.key == key && it.lyrics?.synced == true }
                    ?: return@withLock
                val offset = (done.offsetMs + deltaMs).coerceIn(-MAX_SONG_OFFSET_MS, MAX_SONG_OFFSET_MS)
                if (!_current.compareAndSet(done, done.copy(offsetMs = offset))) return@withLock
                try {
                    dao.setOffset(key.chatId, key.messageId, offset)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "lyrics key=${key.log()} offset write failed", e)
                }
            }
        }
    }

    private suspend fun load(track: LyricsTrack): LyricsState.Done {
        val started = SystemClock.elapsedRealtime()
        val key = track.key
        val cached = try {
            dao.get(key.chatId, key.messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lyrics key=${key.log()} cache read failed", e)
            null
        }
        if (cached != null && isFresh(cached, System.currentTimeMillis())) {
            val lyrics = cached.toLyrics()
            log(key, lyrics, started, "cache=hit")
            return LyricsState.Done(key, lyrics, cached.offsetMs)
        }
        val query = LyricsQuery.of(track.title, track.performer, track.fileName)
        val queryText = "q=\"${query?.artist.orEmpty()} | ${query?.title.orEmpty()}\""
        val found = fetch(track, query)
        val entity = found.entity ?: entity(key, KIND_NONE, null, null)
        if (found.cacheable) {
            try {
                dao.upsert(entity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lyrics key=${key.log()} cache write failed", e)
            }
        }
        val lyrics = entity.toLyrics()
        log(key, lyrics, started, "cache=miss $queryText ${found.note}")
        return LyricsState.Done(key, lyrics, entity.offsetMs, failed = found.failed)
    }

    // Only answers LRCLIB gave are cached: a definitive "none" (retried after LYRICS_RETRY_DAYS)
    // or lyrics. A skipped or failed lookup leaves no row, so the next play asks again.
    private suspend fun fetch(track: LyricsTrack, query: LyricsQuery?): Found {
        if (track.durationS !in 1..MAX_TRACK_SECONDS) {
            return Found(null, cacheable = false, failed = false, note = "skipped=duration")
        }
        if (query == null) return Found(null, cacheable = false, failed = false, note = "skipped=no-query")
        // Retry when the player reopens after online lookup is enabled in Settings.
        if (!settings.lyricsOnline.value) return Found(null, cacheable = false, failed = true, note = "lrclib=off")
        val match = try {
            lrclib.find(query, track.durationS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = "lrclib-error=\"${e.message ?: e::class.simpleName}\""
            return Found(null, cacheable = false, failed = true, note = error)
        }
        if (match?.record?.instrumental == true) {
            return Found(null, cacheable = true, failed = false, note = "instrumental=${match.source.logName}")
        }
        return Found(match?.let { lrclibEntity(track.key, it) }, cacheable = true, failed = false, note = "")
    }

    private fun lrclibEntity(key: TrackKey, match: LrclibMatch): LyricsEntity? {
        val record = match.record
        val lrc = record.syncedLyrics?.takeIf { Lyrics.synced(match.source, it) != null }
        if (lrc != null) return entity(key, KIND_SYNCED, match.source, lrc)
        // A partly stamped LRC with no plain text still has every line: show them unsynced.
        val plain = record.plainLyrics?.takeIf { it.isNotBlank() }
            ?: record.syncedLyrics?.let(Lrc::plainText)?.takeIf { it.isNotBlank() }
            ?: return null
        return entity(key, KIND_PLAIN, match.source, plain)
    }

    private fun entity(key: TrackKey, kind: String, source: LyricsSource?, text: String?): LyricsEntity = LyricsEntity(key.chatId, key.messageId, kind, source?.logName, text, System.currentTimeMillis())

    private fun log(key: TrackKey, lyrics: Lyrics?, startedMs: Long, rest: String) {
        Log.i(
            TAG,
            "lyrics key=${key.log()} source=${lyrics?.source?.logName ?: "none"} " +
                "synced=${lyrics?.synced ?: false} " +
                "ms=${SystemClock.elapsedRealtime() - startedMs} ${rest.trim()}",
        )
    }

    private fun TrackKey.log(): String = "$chatId/$messageId"

    private class Found(val entity: LyricsEntity?, val cacheable: Boolean, val failed: Boolean, val note: String)

    internal companion object {

        const val LYRICS_RETRY_DAYS = 7L

        const val MAX_SONG_OFFSET_MS = 60_000L

        // Longer than this is a mix, a live set or an audiobook, not a song LRCLIB could know.
        const val MAX_TRACK_SECONDS = 15 * 60L

        const val KIND_SYNCED = "synced"
        const val KIND_PLAIN = "plain"
        const val KIND_NONE = "none"

        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private const val TAG = "TelecloudLyrics"

        fun isFresh(entity: LyricsEntity, nowMs: Long): Boolean = entity.kind != KIND_NONE ||
            nowMs - entity.fetchedAt < LYRICS_RETRY_DAYS * DAY_MS

        fun LyricsEntity.toLyrics(): Lyrics? {
            val source = source?.let(LyricsSource::fromLogName) ?: return null
            val text = text ?: return null
            return when (kind) {
                KIND_SYNCED -> Lyrics.synced(source, text)
                KIND_PLAIN -> Lyrics.plain(source, text)
                else -> null
            }
        }
    }
}
