package io.github.agopalareddy.umm.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun get(id: Long): HistoryEntity?

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Update
    suspend fun update(entity: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY id DESC LIMIT -1 OFFSET :keep")
    suspend fun beyond(keep: Int): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE audioPath IS NOT NULL AND createdAt < :cutoff")
    suspend fun withAudioOlderThan(cutoff: Long): List<HistoryEntity>

    @Query("SELECT * FROM history")
    suspend fun all(): List<HistoryEntity>

    @Query("DELETE FROM history WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}
