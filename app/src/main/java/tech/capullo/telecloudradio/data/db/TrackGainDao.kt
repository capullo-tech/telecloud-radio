package tech.capullo.telecloudradio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackGainDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(gain: TrackGainEntity)

    @Query("SELECT * FROM track_gain WHERE messageId = :messageId")
    suspend fun get(messageId: Long): TrackGainEntity?

    // Emits again when the row is written, so a track already playing picks up its gain as soon
    // as the background analysis lands.
    @Query("SELECT * FROM track_gain WHERE messageId = :messageId")
    fun observe(messageId: Long): Flow<TrackGainEntity?>

    @Query("DELETE FROM track_gain WHERE messageId = :messageId")
    suspend fun delete(messageId: Long)

    @Query("DELETE FROM track_gain")
    suspend fun deleteAll()

    @Query("DELETE FROM track_gain WHERE messageId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
