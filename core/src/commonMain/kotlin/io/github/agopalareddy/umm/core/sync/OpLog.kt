package io.github.agopalareddy.umm.core.sync

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.agopalareddy.umm.core.data.UmmDatabase
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

class OpLog(
    private val db: UmmDatabase,
    private val meta: SyncMeta,
    private val clock: HybridClock,
    private val active: suspend () -> Boolean,
    var onRecorded: (() -> Unit)? = null,
) : ChangeRecorder {

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
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                // The stored mark outlives pruning, so a device whose old ops are all gone does not start at 1 again.
                val marks = storedMarks()
                val seq = maxOf(dao.maxSeq(origin) ?: 0L, marks[origin] ?: 0L) + 1L
                val hlc = clock.tick()
                val envelope = OpEnvelope(origin, seq, hlc, op)
                storeInternal(envelope)
                putMarks(marks + (origin to seq))
                when (op) {
                    is SyncOp.SettingSet -> dao.putSettingsHlc(SyncSettingsHlcEntity(op.key, hlc, origin))
                    is SyncOp.CategorySet -> dao.putSettingsHlc(SyncSettingsHlcEntity("category:${op.category.name}", hlc, origin))
                    is SyncOp.AppAssign -> dao.putSettingsHlc(SyncSettingsHlcEntity("app:${op.packageName}", hlc, origin))
                    else -> Unit
                }
            }
        }
        onRecorded?.invoke()
    }

    suspend fun store(envelope: OpEnvelope): Boolean = db.useWriterConnection { transactor ->
        transactor.immediateTransaction {
            // Marks that were never stored start from the ops held, so they are read before this op is added.
            storedMarks()
            storeInternal(envelope)
        }
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

    /**
     * Per origin, the seq up to which this device has every op that origin still keeps: "everything through N", not
     * "something as high as N". An op that arrives ahead of a missed one is stored and applied but does not move
     * the mark, so the missed one is still asked for. A device's own mark is its newest op.
     */
    suspend fun highWaterMarks(): Map<String, Long> = db.useWriterConnection { transactor ->
        transactor.immediateTransaction { storedMarks() }
    }

    /**
     * Records that everything of each origin through the given seq is here, because a sender that holds it all sent
     * what was missing. Marks never move back.
     */
    suspend fun markComplete(through: Map<String, Long>) {
        if (through.isEmpty()) return
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val marks = storedMarks()
                val merged = marks + through.filter { (origin, seq) -> seq > (marks[origin] ?: 0L) }
                if (merged != marks) putMarks(merged)
            }
        }
    }

    /**
     * Moves [origin]'s mark to [through] when the ops that just arrived continue from it: they are all of the
     * origin's ops after [after]. False when ops in between are missing here, and the mark stays where it was.
     */
    suspend fun advanceIfContiguous(origin: String, after: Long, through: Long): Boolean =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val marks = storedMarks()
                val current = marks[origin] ?: 0L
                if (after > current) return@immediateTransaction false
                if (through > current) putMarks(marks + (origin to through))
                true
            }
        }

    /** The marks as stored; before they were stored a mark was the highest seq held, so they start from that. */
    private suspend fun storedMarks(): Map<String, Long> {
        val dao = db.syncDao()
        val stored = dao.getMeta(KEY_MARKS)
        if (stored != null) return markJson.decodeFromString(markSerializer, stored)
        val initial = dao.highWaterMarks().associate { it.originDevice to it.maxSeq }
        putMarks(initial)
        return initial
    }

    private suspend fun putMarks(marks: Map<String, Long>) {
        db.syncDao().putMeta(SyncMetaEntity(KEY_MARKS, markJson.encodeToString(markSerializer, marks)))
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

    private companion object {
        const val KEY_MARKS = "complete_marks"
        val markJson = Json
        val markSerializer = MapSerializer(String.serializer(), Long.serializer())
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
