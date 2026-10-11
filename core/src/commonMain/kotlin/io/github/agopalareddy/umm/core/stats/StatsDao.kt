package io.github.agopalareddy.umm.core.stats

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface StatsDao {
    @Upsert
    suspend fun upsert(entry: StatsEntry)

    @Query("SELECT * FROM dictation_stats WHERE originDevice = :originDevice AND historyId = :historyId")
    suspend fun get(originDevice: String, historyId: Long): StatsEntry?

    @Query("SELECT * FROM dictation_stats ORDER BY createdAt")
    suspend fun all(): List<StatsEntry>

    @Query("SELECT * FROM dictation_stats WHERE (:origin IS NULL OR originDevice = :origin) ORDER BY createdAt")
    fun observeAll(origin: String?): Flow<List<StatsEntry>>

    @Query("SELECT * FROM dictation_stats ORDER BY createdAt")
    fun observeAll(): Flow<List<StatsEntry>>

    @Query("DELETE FROM dictation_stats")
    suspend fun deleteAll()

    @Query("DELETE FROM dictation_stats WHERE originDevice = :originDevice AND historyId >= :firstId")
    suspend fun deleteFromHistoryId(originDevice: String, firstId: Long)

    @Query("DELETE FROM dictation_stats WHERE createdAt < :before AND (:origin IS NULL OR originDevice = :origin)")
    suspend fun deleteBefore(before: Long, origin: String?)
}
