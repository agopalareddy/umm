package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatsTest {
    private val zone = ZoneOffset.UTC
    private val nowMs = at("2026-09-27", 18)

    private fun at(day: String, hour: Int = 12) =
        LocalDate.parse(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun entry(id: Long, day: String, ok: Boolean = true) =
        StatsEntry(id, at(day), "com.whatsapp", CleanupLevel.LIGHT, rawWords = 11, cleanWords = 10, fillerWords = 1,
            audioMs = 5_000, latencyMs = if (ok) 1_000 else null, costUsd = 0.001, sttModel = "stt",
            cleanupModel = "chat", succeeded = ok)

    @Test fun hasDataFalseForNoRowsAndForOnlyFailures() {
        assertFalse(DashboardStats.from(emptyList(), DashboardRange.D30, nowMs, zone).hasData)
        val failures = listOf(entry(1, "2026-09-27", ok = false))
        assertFalse(DashboardStats.from(failures, DashboardRange.D30, nowMs, zone).hasData)
        assertTrue(DashboardStats.from(failures + entry(2, "2026-09-27"), DashboardRange.D30, nowMs, zone).hasData)
    }

    @Test fun usesTheGivenRangeForPeriodAndHabits() {
        val rows = listOf(entry(1, "2026-09-19"), entry(2, "2026-09-27")) // 8 days and 0 days before today
        val stats = DashboardStats.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(DashboardRange.D7, stats.range)
        assertEquals(1, stats.period.current.dictations)
        assertEquals(1, stats.habits.attempts)
        // Lifetime facts still see the old row.
        assertEquals(20.0 / 80_000, stats.engagement.novelShare, 1e-9)
    }
}
