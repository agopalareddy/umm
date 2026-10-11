package io.github.agopalareddy.umm.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference

enum class HistoryStatus { PENDING, DONE, CLEANUP_FAILED, FAILED }

data class HistoryItem(
    val id: Long,
    val originDevice: String,
    val originId: Long,
    val appLabel: String?,
    val createdAt: Long,
    val packageName: String,
    val level: CleanupLevel,
    val script: ScriptPreference,
    /** "auto" or an ISO 639-1 code; see LanguageChoice.encode(). */
    val language: String,
    val rawText: String?,
    val cleanText: String?,
    val status: HistoryStatus,
    val audioPath: String?,
    val error: String?,
)

@Entity(
    tableName = "history",
    indices = [
        androidx.room.Index(value = ["originDevice", "originId"], unique = true),
    ],
)
internal data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originDevice: String,
    val originId: Long = 0,
    val appLabel: String? = null,
    val createdAt: Long,
    val packageName: String,
    val level: CleanupLevel,
    val script: ScriptPreference,
    val language: String,
    val rawText: String? = null,
    val cleanText: String? = null,
    val status: HistoryStatus,
    val audioPath: String? = null,
    val error: String? = null,
) {
    fun toItem() = HistoryItem(
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
}
