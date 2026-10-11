package io.github.agopalareddy.umm.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface HistoryDao {
    @Query("SELECT * FROM history WHERE (:origin IS NULL OR originDevice = :origin) ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int, origin: String?): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun get(id: Long): HistoryEntity?

    @Query("SELECT * FROM history WHERE originDevice = :originDevice AND originId = :originId")
    suspend fun getByOrigin(originDevice: String, originId: Long): HistoryEntity?

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Update
    suspend fun update(entity: HistoryEntity)

    @Query("UPDATE history SET originId = :originId WHERE id = :id")
    suspend fun setOriginId(id: Long, originId: Long)

    @Query("SELECT * FROM history WHERE originDevice = :originDevice ORDER BY createdAt DESC, id DESC LIMIT -1 OFFSET :keep")
    suspend fun beyond(originDevice: String, keep: Int): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE audioPath IS NOT NULL AND createdAt < :cutoff")
    suspend fun withAudioOlderThan(cutoff: Long): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE createdAt < :before AND (:origin IS NULL OR originDevice = :origin)")
    suspend fun getBefore(before: Long, origin: String?): List<HistoryEntity>

    @Query("SELECT * FROM history")
    suspend fun all(): List<HistoryEntity>

    @Query("DELETE FROM history WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}
