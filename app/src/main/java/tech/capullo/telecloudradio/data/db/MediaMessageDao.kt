package tech.capullo.telecloudradio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    // Row id per message, -1 where it already existed (IGNORE).
    suspend fun insertAll(messages: List<MediaMessageEntity>): List<Long>

    @Query("SELECT * FROM media_messages WHERE chatId = :chatId AND station IS NOT NULL")
    suspend fun getPlaylist(chatId: Long): List<MediaMessageEntity>

    @Query(
        "SELECT * FROM media_messages WHERE chatId = :chatId AND station IS NOT NULL ORDER BY messageId DESC",
    )
    suspend fun getPlaylistNewestFirst(chatId: Long): List<MediaMessageEntity>

    @Query(
        "SELECT * FROM media_messages WHERE chatId = :chatId AND station IS NOT NULL AND localPath IS NOT NULL ORDER BY messageId DESC",
    )
    suspend fun getLocalTracks(chatId: Long): List<MediaMessageEntity>

    @Query("SELECT MAX(messageId) FROM media_messages WHERE chatId = :chatId")
    suspend fun getLatestMessageId(chatId: Long): Long?

    @Query("SELECT COUNT(*) FROM media_messages WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun exists(chatId: Long, messageId: Long): Int

    @Query("SELECT * FROM media_messages WHERE chatId = :chatId AND station IS NOT NULL LIMIT 1")
    suspend fun getAnyForChat(chatId: Long): MediaMessageEntity?

    @Query("SELECT COUNT(*) FROM media_messages WHERE chatId = :chatId AND station IS NOT NULL")
    suspend fun getTrackCount(chatId: Long): Int

    @Query(
        "SELECT SUM(fileSize) FROM media_messages WHERE chatId = :chatId AND station IS NOT NULL",
    )
    suspend fun getTotalSize(chatId: Long): Long?

    @Query("SELECT localPath FROM media_messages WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun getLocalPath(chatId: Long, messageId: Long): String?

    @Query(
        "UPDATE media_messages SET localPath = :path WHERE chatId = :chatId AND messageId = :messageId",
    )
    suspend fun updateLocalPath(chatId: Long, messageId: Long, path: String?)

    @Query(
        "UPDATE media_messages SET reactions = :reactions WHERE chatId = :chatId AND messageId = :messageId",
    )
    suspend fun updateReactions(chatId: Long, messageId: Long, reactions: String?)

    @Query("SELECT localPath FROM media_messages WHERE localPath IS NOT NULL")
    suspend fun getAllLocalPaths(): List<String>

    @Query("SELECT * FROM media_messages WHERE chatId = :chatId AND messageId IN (:ids)")
    suspend fun getByIds(chatId: Long, ids: List<Long>): List<MediaMessageEntity>

    @Query("DELETE FROM media_messages WHERE chatId = :chatId AND messageId IN (:ids)")
    suspend fun deleteByIds(chatId: Long, ids: List<Long>)

    @Query("DELETE FROM media_messages")
    suspend fun deleteAll()

    @Query(
        "SELECT DISTINCT chatId, station FROM media_messages WHERE station IS NOT NULL ORDER BY station",
    )
    suspend fun getStations(): List<StationInfo>

    @Query("SELECT localPath FROM media_messages WHERE chatId = :chatId AND localPath IS NOT NULL")
    suspend fun getLocalPathsForChat(chatId: Long): List<String>

    @Query("SELECT messageId FROM media_messages WHERE chatId = :chatId")
    suspend fun getMessageIdsForChat(chatId: Long): List<Long>

    @Query("DELETE FROM media_messages WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: Long)
}

data class StationInfo(val chatId: Long, val station: String)
