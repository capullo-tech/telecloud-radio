package tech.capullo.telecloudradio.data.playlist

import tech.capullo.telecloudradio.data.db.MediaMessageDao
import tech.capullo.telecloudradio.data.db.MediaMessageEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(private val dao: MediaMessageDao) {
    suspend fun loadShuffledPlaylist(chatId: Long): List<MediaMessageEntity> =
        dao.getPlaylist(chatId).shuffled()

    suspend fun loadPlaylist(chatId: Long): List<MediaMessageEntity> =
        dao.getPlaylistNewestFirst(chatId)

    suspend fun loadLocalPlaylist(chatId: Long): List<MediaMessageEntity> =
        dao.getLocalTracks(chatId)

    suspend fun getTotalSize(chatId: Long): Long = dao.getTotalSize(chatId) ?: 0L

    suspend fun exists(messageId: Long): Boolean = dao.exists(messageId) > 0

    suspend fun getStations() = dao.getStations()
}
