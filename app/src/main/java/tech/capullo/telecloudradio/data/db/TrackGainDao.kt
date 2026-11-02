package tech.capullo.telecloudradio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackGainDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(gain: TrackGainEntity)

    @Query("SELECT * FROM track_gain WHERE messageId = :messageId")
    suspend fun get(messageId: Long): TrackGainEntity?

    @Query("DELETE FROM track_gain WHERE messageId = :messageId")
    suspend fun delete(messageId: Long)

    @Query("DELETE FROM track_gain")
    suspend fun deleteAll()

    @Query("DELETE FROM track_gain WHERE messageId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
