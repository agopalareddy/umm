package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.data.UmmDatabase
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class StatsRepository(db: UmmDatabase, private val enabled: suspend () -> Boolean = { true }) {
    private val dao = db.statsDao()

    /** Upserts by history id, so a successful retry replaces the failed attempt. Writes nothing while recording is off. */
    suspend fun record(entry: StatsEntry) {
        if (enabled()) dao.upsert(entry)
    }

    suspend fun get(historyId: Long): StatsEntry? = dao.get(historyId)

    suspend fun all(): List<StatsEntry> = dao.all()

    suspend fun deleteAll() = dao.deleteAll()

    /** Deletes the rows whose history ID is [firstId] or higher (the debug sample data). */
    suspend fun deleteFromHistoryId(firstId: Long) = dao.deleteFromHistoryId(firstId)

    fun observeAll(): Flow<List<StatsEntry>> = dao.observeAll()

    /** Recomputed on every table change, off the main thread. */
    fun observeDashboard(
        range: DashboardRange,
        clock: () -> Long = System::currentTimeMillis,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<DashboardStats> =
        dao.observeAll().map { DashboardStats.from(it, range, clock(), zone) }.flowOn(Dispatchers.Default)
}
