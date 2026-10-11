package io.github.agopalareddy.umm.core.stats

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.agopalareddy.umm.core.data.UmmDatabase
import io.github.agopalareddy.umm.core.sync.ChangeRecorder
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.SyncOp
import io.github.agopalareddy.umm.core.sync.DataKind
import io.github.agopalareddy.umm.core.sync.toSynced
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class StatsRepository(
    private val db: UmmDatabase,
    private val enabled: suspend () -> Boolean = { true },
    private val recorder: ChangeRecorder = ChangeRecorder.None,
) {

    private val dao = db.statsDao()
    private val meta = SyncMeta(db)

    /** Upserts by composite key (originDevice, historyId), so a successful retry replaces the failed attempt. Writes nothing while recording is off. */
    suspend fun record(entry: StatsEntry) {
        if (enabled()) {
            db.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    dao.upsert(entry)
                    recorder.record(SyncOp.StatsUpsert(entry.toSynced()))
                }
            }
        }
    }


    suspend fun get(historyId: Long): StatsEntry? = dao.get(meta.deviceId(), historyId)

    suspend fun all(): List<StatsEntry> = dao.all()

    suspend fun deleteAll() = dao.deleteAll()

    /** Deletes stats before [before], optionally scoped to [origin], and records ClearBefore. */
    suspend fun clear(origin: String? = null, before: Long = System.currentTimeMillis() + 10_000L) {
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                dao.deleteBefore(before, origin)
                recorder.record(SyncOp.ClearBefore(DataKind.STATS, before, origin))
            }
        }
    }

    suspend fun clearThisDevice(before: Long = System.currentTimeMillis() + 10_000L) {
        clear(meta.deviceId(), before)
    }

    /** Deletes the rows whose history ID is [firstId] or higher on this device (the debug sample data). */
    suspend fun deleteFromHistoryId(firstId: Long) = dao.deleteFromHistoryId(meta.deviceId(), firstId)

    fun observeAll(origin: String? = null): Flow<List<StatsEntry>> =
        if (origin == null) dao.observeAll() else dao.observeAll(origin)

    /** Recomputed on every table change, off the main thread. */
    fun observeDashboard(
        range: DashboardRange,
        origin: String? = null,
        clock: () -> Long = System::currentTimeMillis,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<DashboardStats> =
        observeAll(origin).map { DashboardStats.from(it, range, clock(), zone) }.flowOn(Dispatchers.Default)
}
