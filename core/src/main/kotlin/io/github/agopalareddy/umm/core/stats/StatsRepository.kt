package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.data.UmmDatabase
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StatsRepository(db: UmmDatabase) {
    private val dao = db.statsDao()

    /** Upserts by history id, so a successful retry replaces the failed attempt. */
    suspend fun record(entry: StatsEntry) = dao.upsert(entry)

    suspend fun get(historyId: Long): StatsEntry? = dao.get(historyId)

    suspend fun all(): List<StatsEntry> = dao.all()

    fun observeSummary(clock: () -> Long = System::currentTimeMillis, zone: ZoneId = ZoneId.systemDefault()): Flow<UsageSummary> =
        dao.observeAll().map { UsageSummary.from(it, clock(), zone) }
}
