package tech.capullo.telecloudradio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudioAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: AudioAnalysisEntity)

    @Query("SELECT * FROM audio_analysis WHERE messageId = :messageId")
    suspend fun get(messageId: Long): AudioAnalysisEntity?

    @Query("DELETE FROM audio_analysis WHERE messageId = :messageId")
    suspend fun delete(messageId: Long)

    @Query("DELETE FROM audio_analysis")
    suspend fun deleteAll()

    @Query("DELETE FROM audio_analysis WHERE messageId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
