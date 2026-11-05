package tech.capullo.telecloudradio.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tech.capullo.source.telegram.data.telegram.TelegramException
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.db.AudioAnalysisDao
import tech.capullo.telecloudradio.data.db.MediaMessageDao
import tech.capullo.telecloudradio.data.db.TrackGainDao
import tech.capullo.telecloudradio.data.db.TrackGainEntity
import tech.capullo.telecloudradio.data.telegram.TelegramRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class DownloadManager @Inject constructor(
    private val repository: TelegramRepository,
    private val dao: MediaMessageDao,
    private val settings: SettingsRepository,
    private val analysisDao: AudioAnalysisDao,
    private val trackGainDao: TrackGainDao,
    private val audioMetadataReader: AudioMetadataReader,
    private val audioAnalyzer: AudioAnalyzer,
    private val albumArtFetcher: AlbumArtFetcher,
    @ApplicationContext private val context: Context,
) {
    private val downloadedFiles = LinkedHashMap<Long, String>()

    // Background gain computation must never block or be cancelled by a caller's own coroutine
    // (ensureDownloaded is awaited by playTrack, which must return as soon as the FILE is ready,
    // not after a full-track loudness pass). A SupervisorJob-rooted scope outlives any one playlist
    // mutation's cancellation, so a downloaded track still gets its gain whatever the user does next.
    private val gainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One analysis at a time: each is a full decode, and several tracks can land together (the
    // prefetch window). Memory is constant per analysis now; this bounds CPU next to a broadcast.
    private val gainSemaphore = Semaphore(1)

    // Tracks whose gain is being computed, so repeated ensureDownloaded calls don't queue the same
    // file twice. Touched only from gainScope coroutines and ensureDownloaded; synchronized on itself.
    private val gainInFlight = HashSet<Long>()

    // messageId → 0f..1f for downloads currently in flight
    private val _downloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<Long, Float>> = _downloadProgress.asStateFlow()

    // The track currently playing - never evicted by enforceBuffer
    @Volatile var activeMessageId: Long? = null

    // The prefetch window (current + upcoming tracks) - pinned so enforceBuffer can't evict the
    // very track it just prefetched. Without this, prefetching N+2 could evict N+1 (the "next"
    // track), leaving the Next button greyed with nothing to re-download it. Kept in sync by the
    // player whenever the queue position moves.
    @Volatile var protectedMessageIds: Set<Long> = emptySet()

    suspend fun ensureDownloaded(chatId: Long, messageId: Long): String? {
        downloadedFiles[messageId]?.let { path ->
            if (File(path).exists()) {
                touch(messageId, path)
                triggerGainComputation(messageId, path)
                return path
            }
            downloadedFiles.remove(messageId)
        }
        dao.getLocalPath(messageId)?.let { path ->
            if (File(path).exists()) {
                downloadedFiles[messageId] = path
                triggerGainComputation(messageId, path)
                return path
            }
            dao.updateLocalPath(messageId, null)
        }
        _downloadProgress.value = _downloadProgress.value + (messageId to 0f)
        val path = try {
            repository.downloadFile(chatId, messageId) { progress ->
                _downloadProgress.value = _downloadProgress.value + (messageId to progress)
            }
        } catch (e: TelegramException) {
            // Lazy deletion fallback: the message was deleted from the chat -
            // purge it so playback can skip it instead of stalling
            if (e.message.contains("not found", ignoreCase = true)) {
                runCatching { repository.removeTracks(chatId, listOf(messageId)) }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            _downloadProgress.value = _downloadProgress.value - messageId
        }
        if (path == null) return null

        downloadedFiles[messageId] = path
        dao.updateLocalPath(messageId, path)
        enforceBuffer(currentMessageId = messageId)
        triggerGainComputation(messageId, path)
        return path
    }

    // Computes and persists this track's normalization gain in the background if not already
    // known - called from every success path in ensureDownloaded (not just fresh downloads) so
    // tracks cached before this feature existed get covered too. A row is written even when the
    // analysis fails (source FAILED, unity gain) so an undecodable file isn't retried on every play.
    private fun triggerGainComputation(messageId: Long, path: String) {
        if (!synchronized(gainInFlight) { gainInFlight.add(messageId) }) return
        gainScope.launch {
            try {
                if (trackGainDao.get(messageId) != null) return@launch
                gainSemaphore.withPermit {
                    if (trackGainDao.get(messageId) != null) return@withPermit
                    trackGainDao.insert(computeGain(messageId, path))
                }
            } finally {
                synchronized(gainInFlight) { gainInFlight.remove(messageId) }
            }
        }
    }

    private suspend fun computeGain(messageId: Long, path: String): TrackGainEntity {
        val tag = withContext(Dispatchers.IO) { audioMetadataReader.readReplayGain(path) }
        val (gainDb, peak, measuredLufs, source) = if (tag != null) {
            // The tag IS the gain (no decode needed). ReplayGain 2.0 taggers target -18 LUFS, so
            // the implied loudness is only a diagnostic; it never drives the gain.
            GainInputs(tag.gainDb, tag.peak, TARGET_LUFS - tag.gainDb, "REPLAYGAIN_TAG")
        } else {
            val l = withContext(Dispatchers.IO) { audioAnalyzer.measureLoudness(path) }
                ?: return TrackGainEntity(messageId, 1f, AudioAnalyzer.FLOOR_DB, "FAILED", System.currentTimeMillis())
            GainInputs(TARGET_LUFS - l.integratedLufs, l.samplePeak, l.integratedLufs, "COMPUTED_LUFS")
        }
        // Loud tracks may come down a long way; quiet ones go up at most +6 dB, and never so far
        // that their sample peak passes ~-1 dBFS (GainAudioProcessor hard-clips).
        var gainLinear = 10f.pow(gainDb.coerceIn(-24f, 6f) / 20f)
        if (peak != null && peak > 0f && gainLinear > 1f) gainLinear = minOf(gainLinear, maxOf(1f, PEAK_CEILING / peak))
        return TrackGainEntity(messageId, gainLinear, measuredLufs, source, System.currentTimeMillis())
    }

    private data class GainInputs(val gainDb: Float, val peak: Float?, val measuredLufs: Float, val source: String)

    fun getCachedPath(messageId: Long): String? = downloadedFiles[messageId]?.takeIf { File(it).exists() }

    suspend fun isDownloaded(messageId: Long): Boolean = getCachedPath(messageId) != null ||
        dao.getLocalPath(messageId)?.let { File(it).exists() } == true

    fun spectrogramFile(messageId: Long): File = File(context.filesDir, "spectrograms/spect_$messageId.bin")

    suspend fun evict(messageId: Long) {
        val path = downloadedFiles.remove(messageId) ?: return
        File(path).delete()
        dao.updateLocalPath(messageId, null)
        val analysis = analysisDao.get(messageId)
        analysis?.spectrogramPath?.let { File(it).delete() }
        analysisDao.delete(messageId)
        albumArtFetcher.evict(messageId)
    }

    // Re-insert so recently accessed tracks move to the back of the eviction queue (LRU)
    private fun touch(messageId: Long, path: String) {
        downloadedFiles.remove(messageId)
        downloadedFiles[messageId] = path
    }

    // Library reset: deletes downloaded files, spectrograms, analysis rows and the
    // track index - for one station (chatId) or everything (null). The next station
    // open re-syncs the full history.
    suspend fun wipeLibrary(chatId: Long? = null) {
        if (chatId == null) {
            dao.getAllLocalPaths().forEach { File(it).delete() }
            downloadedFiles.clear()
            File(context.filesDir, "spectrograms").deleteRecursively()
            albumArtFetcher.clearAll()
            analysisDao.deleteAll()
            dao.deleteAll()
        } else {
            dao.getLocalPathsForChat(chatId).forEach { File(it).delete() }
            val ids = dao.getMessageIdsForChat(chatId)
            ids.forEach { id ->
                downloadedFiles.remove(id)
                spectrogramFile(id).delete()
                albumArtFetcher.evict(id)
            }
            ids.chunked(500).forEach { analysisDao.deleteByIds(it) }
            dao.deleteByChat(chatId)
        }
    }

    private suspend fun enforceBuffer(currentMessageId: Long) {
        val limitBytes = (settings.bufferSizeGb * 1024 * 1024 * 1024).toLong()
        while (totalDownloadedBytes() > limitBytes) {
            val oldest = downloadedFiles.keys
                .firstOrNull {
                    it != currentMessageId && it != activeMessageId &&
                        it !in protectedMessageIds
                } ?: break
            evict(oldest)
        }
    }

    private fun totalDownloadedBytes(): Long = downloadedFiles.values.sumOf { path -> File(path).length() }

    private companion object {
        // ReplayGain 2.0's reference level - matches what modern taggers (and the
        // REPLAYGAIN_TRACK_GAIN tags they write) target, so a tag-derived gain and a
        // measured gain land library tracks at the same loudness.
        const val TARGET_LUFS = -18f

        // Max sample peak after gain (~-1 dBFS).
        const val PEAK_CEILING = 0.89f
    }
}
