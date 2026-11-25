package tech.capullo.telecloudradio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun get(chatId: Long, messageId: Long): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lyrics: LyricsEntity)

    @Query("UPDATE lyrics SET offsetMs = :offsetMs WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun setOffset(chatId: Long, messageId: Long, offsetMs: Long)

    @Query("DELETE FROM lyrics WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun delete(chatId: Long, messageId: Long)

    @Query("DELETE FROM lyrics")
    suspend fun deleteAll()

    @Query("DELETE FROM lyrics WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: Long)
}
