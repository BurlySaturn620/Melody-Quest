package code.name.monkey.retromusic.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OnlineMetadataDao {
    @Query("SELECT * FROM online_metadata")
    suspend fun all(): List<OnlineMetadataEntity>

    @Query("SELECT * FROM online_metadata WHERE song_id = :songId LIMIT 1")
    suspend fun forSong(songId: Long): OnlineMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OnlineMetadataEntity)

    @Query("SELECT COUNT(*) FROM online_metadata WHERE status = :status")
    suspend fun countWithStatus(status: Int): Int

    @Query("SELECT COUNT(*) FROM online_metadata")
    suspend fun count(): Int

    @Query("DELETE FROM online_metadata")
    suspend fun clear()
}
