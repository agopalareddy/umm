package io.github.agopalareddy.umm.core.sync

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryStatus
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpLogTest {
    private val db = inMemoryUmmDatabase()
    private val meta = SyncMeta(db)
    private var wall = 1_000_000L
    private val clock = HybridClock { wall }
    private var isActive = true
    private val opLog = OpLog(db, meta, clock, active = { isActive })

    @After
    fun tearDown() = db.close()

    @Test
    fun recordsOnlyWhenActive() = runTest {
        val me = meta.deviceId()
        isActive = false
        opLog.record(SyncOp.SettingSet("default_level", "FORMATTED"))
        assertTrue(opLog.since(emptyMap()).isEmpty())

        isActive = true
        opLog.record(SyncOp.SettingSet("default_level", "FORMATTED"))
        opLog.record(SyncOp.SettingSet("default_level", "POLISHED"))

        val ops = opLog.since(emptyMap())
        assertEquals(2, ops.size)
        assertEquals(me, ops[0].origin)
        assertEquals(1L, ops[0].seq)
        assertEquals(me, ops[1].origin)
        assertEquals(2L, ops[1].seq)
        assertTrue(ops[0].hlc < ops[1].hlc)
    }

    @Test
    fun sinceReturnsMissingInOrder() = runTest {
        val me = meta.deviceId()
        // store own seq 1..3
        opLog.store(OpEnvelope(me, 1, 1001, SyncOp.SettingSet("a", "1")))
        opLog.store(OpEnvelope(me, 2, 1002, SyncOp.SettingSet("a", "2")))
        opLog.store(OpEnvelope(me, 3, 1003, SyncOp.SettingSet("a", "3")))

        // store origin B seq 1..2
        opLog.store(OpEnvelope("B", 1, 1004, SyncOp.SettingSet("b", "1")))
        opLog.store(OpEnvelope("B", 2, 1005, SyncOp.SettingSet("b", "2")))

        val missing = opLog.since(mapOf(me to 1L, "B" to 2L))
        assertEquals(2, missing.size)
        assertEquals(me, missing[0].origin)
        assertEquals(2L, missing[0].seq)
        assertEquals(me, missing[1].origin)
        assertEquals(3L, missing[1].seq)
    }

    @Test
    fun deleteCompactsEarlierUpsert() = runTest {
        val origin = "dev1"
        val row = SyncedHistory(
            originDevice = origin,
            originId = 7,
            createdAt = 1000L,
            packageName = "pkg",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "auto",
            status = HistoryStatus.DONE,
        )
        opLog.record(SyncOp.HistoryUpsert(row))
        opLog.record(SyncOp.HistoryDelete(origin, 7))

        val ops = opLog.since(emptyMap())
        assertEquals(1, ops.size)
        val op = ops.single().op
        assertTrue(op is SyncOp.HistoryDelete)
        assertEquals(origin, (op as SyncOp.HistoryDelete).origin)
        assertEquals(7L, op.originId)
    }

    @Test
    fun clearCompactsCoveredUpserts() = runTest {
        val t = 5000L
        val oldStats = SyncedStats(
            originDevice = "dev1",
            historyId = 1,
            createdAt = 4000L,
            packageName = "pkg",
            level = CleanupLevel.LIGHT,
            rawWords = 5,
            cleanWords = 5,
            fillerWords = 0,
            audioMs = 1000,
            succeeded = true,
        )
        val newStats = SyncedStats(
            originDevice = "dev1",
            historyId = 2,
            createdAt = 6000L,
            packageName = "pkg",
            level = CleanupLevel.LIGHT,
            rawWords = 5,
            cleanWords = 5,
            fillerWords = 0,
            audioMs = 1000,
            succeeded = true,
        )
        opLog.record(SyncOp.StatsUpsert(oldStats))
        opLog.record(SyncOp.StatsUpsert(newStats))
        opLog.record(SyncOp.ClearBefore(DataKind.STATS, t, null))

        val ops = opLog.since(emptyMap())
        assertEquals(2, ops.size)
        assertTrue(ops.any { it.op is SyncOp.StatsUpsert && it.op.row.historyId == 2L })
        assertTrue(ops.any { it.op is SyncOp.ClearBefore })
        assertFalse(ops.any { it.op is SyncOp.StatsUpsert && it.op.row.historyId == 1L })
    }


    @Test
    fun pruneDropsOlderThan90Days() = runTest {
        val me = meta.deviceId()
        val nowMs = 100_000_000_000L
        val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000L

        val oldHlc = nowMs - ninetyDaysMs - 1_000_000L
        opLog.store(OpEnvelope(me, 1, oldHlc, SyncOp.SettingSet("a", "old")))

        val recentHlc = nowMs - ninetyDaysMs + 1_000_000L
        opLog.store(OpEnvelope(me, 2, recentHlc, SyncOp.SettingSet("a", "recent")))

        assertEquals(2, opLog.since(emptyMap()).size)

        opLog.prune(nowMs)

        val remaining = opLog.since(emptyMap())
        assertEquals(1, remaining.size)
        assertEquals(2L, remaining.single().seq)
    }
}
