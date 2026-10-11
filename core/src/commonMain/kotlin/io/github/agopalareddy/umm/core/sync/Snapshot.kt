package io.github.agopalareddy.umm.core.sync

import io.github.agopalareddy.umm.core.data.CategoryEntity
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.UmmDatabase
import io.github.agopalareddy.umm.core.sync.SyncOp.AppAssign
import io.github.agopalareddy.umm.core.sync.SyncOp.CategorySet
import io.github.agopalareddy.umm.core.sync.SyncOp.ClearBefore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class Stamped(
    val value: String,
    val hlc: Long,
    val origin: String,
)

@Serializable
data class Snapshot(
    val from: String,
    val marks: Map<String, Long>,
    val history: List<SyncedHistory>,
    val stats: List<SyncedStats>,
    val settings: Map<String, Stamped>,
    val categories: List<CategorySet>,
    val apps: List<AppAssign>,
    val clears: List<ClearBefore>,
    val members: List<Member>,
    /** History rows the sender knows are deleted, so the receiver drops its copies and does not send them back. */
    val deletes: List<SyncOp.HistoryDelete> = emptyList(),
)

suspend fun buildSnapshot(
    db: UmmDatabase,
    log: OpLog,
    settings: SettingsRepository,
    members: List<Member>,
): Snapshot {
    val from = SyncMeta(db).deviceId()
    val marks = log.highWaterMarks()
    val history = db.historyDao().all().map { it.toSynced() }
    val stats = db.statsDao().all().map { it.toSynced() }

    val currentSettings = settings.settings.first()
    val settingsMap = SharedSettings.keys.associateWith { key ->
        val hlcEntity = db.syncDao().getSettingsHlc(key)
        Stamped(
            value = SharedSettings.read(currentSettings, key),
            hlc = hlcEntity?.hlc ?: 0L,
            origin = hlcEntity?.origin ?: "",
        )
    }

    val categories = db.categoryDao().all().map {
        CategorySet(it.category, it.level, it.script)
    }
    val apps = db.categoryDao().allAssignments().map {
        AppAssign(it.packageName, it.category)
    }
    val clears = db.syncDao().allClears().map {
        ClearBefore(
            data = DataKind.valueOf(it.data),
            before = it.before,
            origin = it.origin.ifEmpty { null },
        )
    }

    return Snapshot(
        from = from,
        marks = marks,
        history = history,
        stats = stats,
        settings = settingsMap,
        categories = categories,
        apps = apps,
        clears = clears,
        members = members,
        deletes = log.deletedHistory(),
    )
}
