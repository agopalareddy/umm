package io.github.agopalareddy.umm.core.sync

import io.github.agopalareddy.umm.core.data.AppAssignmentEntity
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.data.CategoryEntity
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.UmmDatabase
import java.io.File
import kotlinx.serialization.json.Json

class OpApplier(
    private val db: UmmDatabase,
    private val log: OpLog,
    private val settings: SettingsRepository,
    private val clock: HybridClock,
    private val onGroupOp: suspend (OpEnvelope) -> Unit,
) {
    private val opJson = Json { ignoreUnknownKeys = true }

    suspend fun apply(envelopes: List<OpEnvelope>): Int {
        val sorted = envelopes.sortedWith(compareBy({ it.origin }, { it.seq }))
        var newlyApplied = 0
        // Read once per batch and kept current below; a row deleted anywhere must not come back from an upsert.
        val deletedHistory = if (sorted.any { it.op is SyncOp.HistoryUpsert }) deletedHistory().toMutableSet() else mutableSetOf()
        for (envelope in sorted) {
            clock.observe(envelope.hlc)
            val stored = log.store(envelope)
            if (!stored) continue
            newlyApplied++
            when (val op = envelope.op) {
                is SyncOp.HistoryUpsert -> {
                    if (!isCleared(DataKind.HISTORY, op.row.originDevice, op.row.createdAt) &&
                        (op.row.originDevice to op.row.originId) !in deletedHistory
                    ) {
                        applyHistoryUpsert(op.row)
                    }
                }
                is SyncOp.HistoryDelete -> {
                    deletedHistory += op.origin to op.originId
                    applyHistoryDelete(op.origin, op.originId)
                }
                is SyncOp.StatsUpsert -> {
                    if (!isCleared(DataKind.STATS, op.row.originDevice, op.row.createdAt)) {
                        applyStatsUpsert(op.row)
                    }
                }
                is SyncOp.SettingSet -> {
                    applySettingSet(op.key, op.value, envelope.hlc, envelope.origin)
                }
                is SyncOp.CategorySet -> {
                    applyCategorySet(op, envelope.hlc, envelope.origin)
                }
                is SyncOp.AppAssign -> {
                    applyAppAssign(op, envelope.hlc, envelope.origin)
                }
                is SyncOp.ClearBefore -> {
                    applyClearBefore(op)
                }
                is SyncOp.MemberUpsert,
                is SyncOp.MemberRemove,
                is SyncOp.KeyRotation -> {
                    onGroupOp(envelope)
                }
            }
        }
        return newlyApplied
    }

    suspend fun applySnapshot(snapshot: Snapshot) {
        val marksJson = Json.encodeToString(snapshot.marks)
        db.syncDao().putPeer(
            SyncPeerEntity(
                deviceId = snapshot.from,
                marks = marksJson,
                lastSeen = clock.tick(),
                transport = null,
            ),
        )

        snapshot.settings.values.forEach { clock.observe(it.hlc) }

        for (clear in snapshot.clears) {
            applyClearBefore(clear)
        }

        val deletedHistory = deletedHistory()

        for (row in snapshot.history) {
            if (!isCleared(DataKind.HISTORY, row.originDevice, row.createdAt) &&
                (row.originDevice to row.originId) !in deletedHistory
            ) {
                applyHistoryUpsert(row)
            }
        }

        for (row in snapshot.stats) {
            if (!isCleared(DataKind.STATS, row.originDevice, row.createdAt)) {
                applyStatsUpsert(row)
            }
        }

        for ((key, stamped) in snapshot.settings) {
            applySettingSet(key, stamped.value, stamped.hlc, stamped.origin)
        }

        for (cat in snapshot.categories) {
            db.categoryDao().upsert(CategoryEntity(cat.category, cat.level, cat.script))
        }

        for (app in snapshot.apps) {
            if (app.category == Category.OTHER) {
                db.categoryDao().unassign(app.packageName)
            } else {
                db.categoryDao().assign(AppAssignmentEntity(app.packageName, app.category))
            }
        }
    }

    private suspend fun isCleared(data: DataKind, originDevice: String, createdAt: Long): Boolean {
        val clears = db.syncDao().clearsForData(data.name)
        return clears.any { clear ->
            (clear.origin.isEmpty() || clear.origin == originDevice) && createdAt < clear.before
        }
    }

    private suspend fun applyHistoryUpsert(row: SyncedHistory) {
        val historyDao = db.historyDao()
        val existing = historyDao.getByOrigin(row.originDevice, row.originId)
        if (existing != null) {
            historyDao.update(row.toEntity(id = existing.id, audioPath = existing.audioPath))
        } else {
            historyDao.insert(row.toEntity(id = 0, audioPath = null))
        }
        val beyond = historyDao.beyond(row.originDevice, 50)
        if (beyond.isNotEmpty()) {
            beyond.forEach { it.audioPath?.let { path -> File(path).delete() } }
            historyDao.delete(beyond.map { it.id })
        }
    }

    private suspend fun applyHistoryDelete(origin: String, originId: Long) {
        val historyDao = db.historyDao()
        val existing = historyDao.getByOrigin(origin, originId) ?: return
        existing.audioPath?.let { File(it).delete() }
        historyDao.delete(listOf(existing.id))
    }

    private suspend fun applyStatsUpsert(row: SyncedStats) {
        db.statsDao().upsert(row.toEntry())
    }

    private suspend fun applySettingSet(key: String, value: String, hlc: Long, origin: String) {
        val syncDao = db.syncDao()
        val current = syncDao.getSettingsHlc(key)
        if (current == null || isHigher(hlc, origin, current.hlc, current.origin)) {
            settings.applyShared(key, value)
            syncDao.putSettingsHlc(SyncSettingsHlcEntity(key, hlc, origin))
        }
    }

    private suspend fun applyCategorySet(op: SyncOp.CategorySet, hlc: Long, origin: String) {
        val stampKey = "category:${op.category.name}"
        val syncDao = db.syncDao()
        val current = syncDao.getSettingsHlc(stampKey)
        if (current == null || isHigher(hlc, origin, current.hlc, current.origin)) {
            db.categoryDao().upsert(CategoryEntity(op.category, op.level, op.script))
            syncDao.putSettingsHlc(SyncSettingsHlcEntity(stampKey, hlc, origin))
        }
    }

    private suspend fun applyAppAssign(op: SyncOp.AppAssign, hlc: Long, origin: String) {
        val stampKey = "app:${op.packageName}"
        val syncDao = db.syncDao()
        val current = syncDao.getSettingsHlc(stampKey)
        if (current == null || isHigher(hlc, origin, current.hlc, current.origin)) {
            if (op.category == Category.OTHER) {
                db.categoryDao().unassign(op.packageName)
            } else {
                db.categoryDao().assign(AppAssignmentEntity(op.packageName, op.category))
            }
            syncDao.putSettingsHlc(SyncSettingsHlcEntity(stampKey, hlc, origin))
        }
    }

    private suspend fun applyClearBefore(op: SyncOp.ClearBefore) {
        val originKey = op.origin ?: ""
        val syncDao = db.syncDao()
        val existing = syncDao.getClear(op.data.name, originKey)
        val newBefore = maxOf(op.before, existing?.before ?: 0L)
        syncDao.putClear(SyncClearEntity(data = op.data.name, origin = originKey, before = newBefore))

        when (op.data) {
            DataKind.HISTORY -> {
                val toDelete = db.historyDao().getBefore(op.before, op.origin)
                if (toDelete.isNotEmpty()) {
                    toDelete.forEach { it.audioPath?.let { path -> File(path).delete() } }
                    db.historyDao().delete(toDelete.map { it.id })
                }
            }
            DataKind.STATS -> {
                db.statsDao().deleteBefore(op.before, op.origin)
            }
        }
    }

    /** The (origin device, origin id) of every history row a delete in the op log names. */
    private suspend fun deletedHistory(): Set<Pair<String, Long>> =
        db.syncDao().opsByType("HistoryDelete").mapNotNull { entity ->
            try {
                val candidate = opJson.decodeFromString<SyncOp.HistoryDelete>(entity.payload)
                candidate.origin to candidate.originId
            } catch (_: Exception) {
                null
            }
        }.toSet()

    private fun isHigher(hlc1: Long, origin1: String, hlc2: Long, origin2: String): Boolean {
        if (hlc1 != hlc2) return hlc1 > hlc2
        return origin1 > origin2
    }
}
