package io.github.agopalareddy.umm.core.sync

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.data.HistoryEntity
import io.github.agopalareddy.umm.core.data.HistoryStatus
import io.github.agopalareddy.umm.core.stats.StatsEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DataKind { HISTORY, STATS }

@Serializable
data class Member(
    val deviceId: String,
    val name: String,
    val platform: String,
    val publicKey: String,
)

@Serializable
data class SyncedHistory(
    val originDevice: String,
    val originId: Long,
    val appLabel: String? = null,
    val createdAt: Long,
    val packageName: String,
    val level: CleanupLevel,
    val script: ScriptPreference,
    val language: String,
    val rawText: String? = null,
    val cleanText: String? = null,
    val status: HistoryStatus,
    val error: String? = null,
)

internal fun HistoryEntity.toSynced() = SyncedHistory(
    originDevice = originDevice,
    originId = originId,
    appLabel = appLabel,
    createdAt = createdAt,
    packageName = packageName,
    level = level,
    script = script,
    language = language,
    rawText = rawText,
    cleanText = cleanText,
    status = status,
    error = error,
)

internal fun SyncedHistory.toEntity(id: Long = 0, audioPath: String? = null) = HistoryEntity(
    id = id,
    originDevice = originDevice,
    originId = originId,
    appLabel = appLabel,
    createdAt = createdAt,
    packageName = packageName,
    level = level,
    script = script,
    language = language,
    rawText = rawText,
    cleanText = cleanText,
    status = status,
    audioPath = audioPath,
    error = error,
)


@Serializable
data class SyncedStats(
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
    val latencyMs: Long? = null,
    val costUsd: Double? = null,
    val sttModel: String? = null,
    val cleanupModel: String? = null,
    val succeeded: Boolean,
)

fun StatsEntry.toSynced() = SyncedStats(
    originDevice = originDevice,
    historyId = historyId,
    appLabel = appLabel,
    createdAt = createdAt,
    packageName = packageName,
    level = level,
    rawWords = rawWords,
    cleanWords = cleanWords,
    fillerWords = fillerWords,
    audioMs = audioMs,
    latencyMs = latencyMs,
    costUsd = costUsd,
    sttModel = sttModel,
    cleanupModel = cleanupModel,
    succeeded = succeeded,
)

fun SyncedStats.toEntry() = StatsEntry(
    originDevice = originDevice,
    historyId = historyId,
    appLabel = appLabel,
    createdAt = createdAt,
    packageName = packageName,
    level = level,
    rawWords = rawWords,
    cleanWords = cleanWords,
    fillerWords = fillerWords,
    audioMs = audioMs,
    latencyMs = latencyMs,
    costUsd = costUsd,
    sttModel = sttModel,
    cleanupModel = cleanupModel,
    succeeded = succeeded,
)

@Serializable
sealed interface SyncOp {
    @Serializable
    @SerialName("HistoryUpsert")
    data class HistoryUpsert(val row: SyncedHistory) : SyncOp

    @Serializable
    @SerialName("HistoryDelete")
    data class HistoryDelete(val origin: String, val originId: Long) : SyncOp

    @Serializable
    @SerialName("StatsUpsert")
    data class StatsUpsert(val row: SyncedStats) : SyncOp

    @Serializable
    @SerialName("SettingSet")
    data class SettingSet(val key: String, val value: String) : SyncOp

    @Serializable
    @SerialName("CategorySet")
    data class CategorySet(
        val category: Category,
        val level: CleanupLevel?,
        val script: ScriptPreference,
    ) : SyncOp

    @Serializable
    @SerialName("AppAssign")
    data class AppAssign(val packageName: String, val category: Category) : SyncOp

    @Serializable
    @SerialName("ClearBefore")
    data class ClearBefore(val data: DataKind, val before: Long, val origin: String?) : SyncOp

    @Serializable
    @SerialName("MemberUpsert")
    data class MemberUpsert(val member: Member) : SyncOp

    @Serializable
    @SerialName("MemberRemove")
    data class MemberRemove(val deviceId: String) : SyncOp

    @Serializable
    @SerialName("KeyRotation")
    data class KeyRotation(val epoch: Int, val sealed: Map<String, String>) : SyncOp
}

@Serializable
data class OpEnvelope(
    val origin: String,
    val seq: Long,
    val hlc: Long,
    val op: SyncOp,
)

fun interface ChangeRecorder {
    suspend fun record(op: SyncOp)

    companion object {
        val None: ChangeRecorder = ChangeRecorder { }
    }
}
