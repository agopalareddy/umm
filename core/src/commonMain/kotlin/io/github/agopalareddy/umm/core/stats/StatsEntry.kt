package io.github.agopalareddy.umm.core.stats

import androidx.room.Entity
import androidx.room.Index
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel

/** One dictation's numbers. Kept for the lifetime of the install, unlike the 50-item history. */
@Entity(
    tableName = "dictation_stats",
    primaryKeys = ["originDevice", "historyId"],
    indices = [
        Index("originDevice"),
        Index("createdAt"),
    ],
)
data class StatsEntry(
    val originDevice: String,
    val historyId: Long,
    val appLabel: String? = null,
    val createdAt: Long,
    val packageName: String,
    val level: CleanupLevel,
    val rawWords: Int,
    val cleanWords: Int,
    val fillerWords: Int,
    val audioMs: Long,
    /** From the end of recording to text ready; null when the dictation failed. */
    val latencyMs: Long?,
    val costUsd: Double?,
    val sttModel: String?,
    val cleanupModel: String?,
    val succeeded: Boolean,
)
