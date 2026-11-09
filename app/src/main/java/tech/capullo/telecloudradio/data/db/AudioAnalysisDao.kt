package tech.capullo.telecloudradio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudioAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: AudioAnalysisEntity)

    @Query("SELECT * FROM audio_analysis WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun get(chatId: Long, messageId: Long): AudioAnalysisEntity?

    @Query("DELETE FROM audio_analysis WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun delete(chatId: Long, messageId: Long)

    @Query("DELETE FROM audio_analysis")
    suspend fun deleteAll()

    @Query("DELETE FROM audio_analysis WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: Long)
}
