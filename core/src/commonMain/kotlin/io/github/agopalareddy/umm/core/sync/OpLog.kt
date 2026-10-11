package io.github.agopalareddy.umm.core.sync

import io.github.agopalareddy.umm.core.data.UmmDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

class OpLog(
    private val db: UmmDatabase,
    private val meta: SyncMeta,
    private val clock: HybridClock,
    private val active: suspend () -> Boolean,
) : ChangeRecorder {
    private val mutex = Mutex()

    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            polymorphic(SyncOp::class) {
                subclass(SyncOp.HistoryUpsert::class)
                subclass(SyncOp.HistoryDelete::class)
                subclass(SyncOp.StatsUpsert::class)
                subclass(SyncOp.SettingSet::class)
                subclass(SyncOp.CategorySet::class)
                subclass(SyncOp.AppAssign::class)
                subclass(SyncOp.ClearBefore::class)
                subclass(SyncOp.MemberUpsert::class)
                subclass(SyncOp.MemberRemove::class)
                subclass(SyncOp.KeyRotation::class)
            }
        }
    }

    override suspend fun record(op: SyncOp) {
        if (!active()) return
        val origin = meta.deviceId()
        val dao = db.syncDao()
        mutex.withLock {
            val seq = (dao.maxSeq(origin) ?: 0L) + 1L
            val hlc = clock.tick()
            val envelope = OpEnvelope(origin, seq, hlc, op)
            storeInternal(envelope)
            if (op is SyncOp.SettingSet) {
                dao.putSettingsHlc(SyncSettingsHlcEntity(op.key, hlc, origin))
            }
        }
    }

    suspend fun store(envelope: OpEnvelope): Boolean = mutex.withLock {
        storeInternal(envelope)
    }

    private suspend fun storeInternal(envelope: OpEnvelope): Boolean {
        val dao = db.syncDao()
        if (dao.countOp(envelope.origin, envelope.seq) > 0) {
            return false
        }
        val type = typeOf(envelope.op)
        val payload = json.encodeToString(SyncOp.serializer(), envelope.op)
        dao.insertOp(SyncOpEntity(envelope.origin, envelope.seq, envelope.hlc, type, payload))
        compact(envelope.op)
        return true
    }

    private suspend fun compact(op: SyncOp) {
        val dao = db.syncDao()
        when (op) {
            is SyncOp.HistoryDelete -> {
                val upserts = dao.opsByType("HistoryUpsert")
                for (entity in upserts) {
                    val candidate = json.decodeFromString<SyncOp>(entity.payload)
                    if (candidate is SyncOp.HistoryUpsert &&
                        candidate.row.originDevice == op.origin &&
                        candidate.row.originId == op.originId
                    ) {
                        dao.deleteOp(entity.originDevice, entity.seq)
                    }
                }
            }
            is SyncOp.ClearBefore -> {
                when (op.data) {
                    DataKind.STATS -> {
                        val upserts = dao.opsByType("StatsUpsert")
                        for (entity in upserts) {
                            val candidate = json.decodeFromString<SyncOp>(entity.payload)
                            if (candidate is SyncOp.StatsUpsert &&
                                candidate.row.createdAt < op.before &&
                                (op.origin == null || candidate.row.originDevice == op.origin)
                            ) {
                                dao.deleteOp(entity.originDevice, entity.seq)
                            }
                        }
                    }
                    DataKind.HISTORY -> {
                        val upserts = dao.opsByType("HistoryUpsert")
                        for (entity in upserts) {
                            val candidate = json.decodeFromString<SyncOp>(entity.payload)
                            if (candidate is SyncOp.HistoryUpsert &&
                                candidate.row.createdAt < op.before &&
                                (op.origin == null || candidate.row.originDevice == op.origin)
                            ) {
                                dao.deleteOp(entity.originDevice, entity.seq)
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    suspend fun since(marks: Map<String, Long>): List<OpEnvelope> {
        val dao = db.syncDao()
        val all = dao.allOps()
        return all
            .filter { it.seq > (marks[it.originDevice] ?: 0L) }
            .map { entity ->
                val op = json.decodeFromString<SyncOp>(entity.payload)
                OpEnvelope(entity.originDevice, entity.seq, entity.hlc, op)
            }
            .sortedWith(compareBy({ it.origin }, { it.seq }))
    }

    suspend fun highWaterMarks(): Map<String, Long> {
        val dao = db.syncDao()
        return dao.highWaterMarks().associate { it.originDevice to it.maxSeq }
    }

    suspend fun oldestSeq(origin: String): Long? {
        val dao = db.syncDao()
        return dao.minSeq(origin)
    }

    suspend fun prune(nowMs: Long) {
        val maxAgeMs = 90L * 24 * 60 * 60 * 1000L
        val cutoff = nowMs - maxAgeMs
        db.syncDao().deleteOpsOlderThan(cutoff)
    }

    private fun typeOf(op: SyncOp): String = when (op) {
        is SyncOp.HistoryUpsert -> "HistoryUpsert"
        is SyncOp.HistoryDelete -> "HistoryDelete"
        is SyncOp.StatsUpsert -> "StatsUpsert"
        is SyncOp.SettingSet -> "SettingSet"
        is SyncOp.CategorySet -> "CategorySet"
        is SyncOp.AppAssign -> "AppAssign"
        is SyncOp.ClearBefore -> "ClearBefore"
        is SyncOp.MemberUpsert -> "MemberUpsert"
        is SyncOp.MemberRemove -> "MemberRemove"
        is SyncOp.KeyRotation -> "KeyRotation"
    }
}
