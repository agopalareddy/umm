package io.github.agopalareddy.umm.core.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

data class HighWaterMark(
    val originDevice: String,
    val maxSeq: Long,
)

@Dao
internal interface SyncDao {
    @Query("SELECT value FROM sync_meta WHERE key = :key")
    suspend fun getMeta(key: String): String?

    @Upsert
    suspend fun putMeta(meta: SyncMetaEntity)

    @Upsert
    suspend fun putSettingsHlc(entity: SyncSettingsHlcEntity)

    @Query("SELECT * FROM sync_settings_hlc WHERE key = :key")
    suspend fun getSettingsHlc(key: String): SyncSettingsHlcEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOp(op: SyncOpEntity): Long

    @Query("SELECT COUNT(*) FROM sync_ops WHERE originDevice = :origin AND seq = :seq")
    suspend fun countOp(origin: String, seq: Long): Int

    @Query("SELECT MAX(seq) FROM sync_ops WHERE originDevice = :origin")
    suspend fun maxSeq(origin: String): Long?

    @Query("SELECT MIN(seq) FROM sync_ops WHERE originDevice = :origin")
    suspend fun minSeq(origin: String): Long?

    @Query("SELECT * FROM sync_ops ORDER BY originDevice ASC, seq ASC")
    suspend fun allOps(): List<SyncOpEntity>

    @Query("SELECT * FROM sync_ops WHERE type = :type")
    suspend fun opsByType(type: String): List<SyncOpEntity>

    @Query("DELETE FROM sync_ops WHERE originDevice = :origin AND seq = :seq")
    suspend fun deleteOp(origin: String, seq: Long)

    @Query("DELETE FROM sync_ops WHERE hlc < :cutoff")
    suspend fun deleteOpsOlderThan(cutoff: Long)

    @Query("SELECT originDevice, MAX(seq) AS maxSeq FROM sync_ops GROUP BY originDevice")
    suspend fun highWaterMarks(): List<HighWaterMark>
}
