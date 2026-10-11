package io.github.agopalareddy.umm.core.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(
    tableName = "sync_ops",
    primaryKeys = ["originDevice", "seq"],
)
data class SyncOpEntity(
    val originDevice: String,
    val seq: Long,
    val hlc: Long,
    val type: String,
    val payload: String,
)

@Entity(tableName = "sync_peers")
data class SyncPeerEntity(
    @PrimaryKey val deviceId: String,
    val marks: String,
    val lastSeen: Long,
    val transport: String?,
)

@Entity(tableName = "sync_settings_hlc")
data class SyncSettingsHlcEntity(
    @PrimaryKey val key: String,
    val hlc: Long,
    val origin: String,
)

@Entity(
    tableName = "sync_clears",
    primaryKeys = ["data", "origin"],
)
data class SyncClearEntity(
    val data: String,
    val origin: String,
    val before: Long,
)

/**
 * A history row that was deleted, kept apart from the op log because the log forgets ops after 90 days and a device
 * that was away longer would bring the row back. [createdAt] is the row's; null when the deleting device did not say.
 */
@Entity(
    tableName = "sync_deletes",
    primaryKeys = ["origin", "originId"],
)
data class SyncDeleteEntity(
    val origin: String,
    val originId: Long,
    val createdAt: Long?,
)
