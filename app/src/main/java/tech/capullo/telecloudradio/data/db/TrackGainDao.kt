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

    @Query("SELECT * FROM track_gain WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun get(chatId: Long, messageId: Long): TrackGainEntity?

    // Emits again when the row is written, so a track already playing picks up its gain as soon
    // as the background analysis lands.
    @Query("SELECT * FROM track_gain WHERE chatId = :chatId AND messageId = :messageId")
    fun observe(chatId: Long, messageId: Long): Flow<TrackGainEntity?>

    @Query("DELETE FROM track_gain WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun delete(chatId: Long, messageId: Long)

    @Query("DELETE FROM track_gain")
    suspend fun deleteAll()

    @Query("DELETE FROM track_gain WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: Long)
}
