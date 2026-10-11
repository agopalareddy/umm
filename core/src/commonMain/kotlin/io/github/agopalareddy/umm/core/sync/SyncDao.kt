package io.github.agopalareddy.umm.core.sync

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface SyncDao {
    @Query("SELECT value FROM sync_meta WHERE key = :key")
    suspend fun getMeta(key: String): String?

    @Upsert
    suspend fun putMeta(meta: SyncMetaEntity)
}
