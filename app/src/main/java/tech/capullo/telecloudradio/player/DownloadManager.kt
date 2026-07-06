package tech.capullo.telecloudradio.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.capullo.telecloudradio.data.SettingsRepository
import tech.capullo.telecloudradio.data.db.AudioAnalysisDao
import tech.capullo.telecloudradio.data.db.MediaMessageDao
import tech.capullo.telecloudradio.data.telegram.TelegramException
import tech.capullo.telecloudradio.data.telegram.TelegramRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    private val repository: TelegramRepository,
    private val dao: MediaMessageDao,
    private val settings: SettingsRepository,
    private val analysisDao: AudioAnalysisDao,
    private val albumArtFetcher: AlbumArtFetcher,
    @ApplicationContext private val context: Context,
) {
    private val downloadedFiles = LinkedHashMap<Long, String>()

    // messageId → 0f..1f for downloads currently in flight
    private val _downloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<Long, Float>> = _downloadProgress.asStateFlow()

    // The track currently playing - never evicted by enforceBuffer
    @Volatile var activeMessageId: Long? = null

    suspend fun ensureDownloaded(chatId: Long, messageId: Long): String? {
        downloadedFiles[messageId]?.let { path ->
            if (File(path).exists()) {
                touch(messageId, path)
                return path
            }
            downloadedFiles.remove(messageId)
        }
        dao.getLocalPath(messageId)?.let { path ->
            if (File(path).exists()) {
                downloadedFiles[messageId] = path
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
        return path
    }

    fun getCachedPath(messageId: Long): String? =
        downloadedFiles[messageId]?.takeIf { File(it).exists() }

    suspend fun isDownloaded(messageId: Long): Boolean =
        getCachedPath(messageId) != null ||
            dao.getLocalPath(messageId)?.let { File(it).exists() } == true

    fun spectrogramFile(messageId: Long): File =
        File(context.filesDir, "spectrograms/spect_$messageId.bin")

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
                .firstOrNull { it != currentMessageId && it != activeMessageId } ?: break
            evict(oldest)
        }
    }

    private fun totalDownloadedBytes(): Long =
        downloadedFiles.values.sumOf { path -> File(path).length() }
}
