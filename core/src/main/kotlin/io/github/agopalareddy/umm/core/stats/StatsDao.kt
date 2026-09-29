package io.github.agopalareddy.umm.core.stats

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface StatsDao {
    @Upsert
    suspend fun upsert(entry: StatsEntry)

    @Query("SELECT * FROM dictation_stats WHERE historyId = :historyId")
    suspend fun get(historyId: Long): StatsEntry?

    @Query("SELECT * FROM dictation_stats ORDER BY createdAt")
    suspend fun all(): List<StatsEntry>

    @Query("SELECT * FROM dictation_stats ORDER BY createdAt")
    fun observeAll(): Flow<List<StatsEntry>>

    @Query("DELETE FROM dictation_stats")
    suspend fun deleteAll()
}
