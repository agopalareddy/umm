package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CostsTest {
    private val zone = ZoneOffset.UTC
    private val nowMs = at("2026-09-27", 18)

    private fun at(day: String, hour: Int = 12, z: ZoneId = zone) =
        LocalDate.parse(day).atTime(hour, 0).atZone(z).toInstant().toEpochMilli()

    private fun entry(
        id: Long,
        day: String,
        cost: Double? = 0.01,
        stt: String? = "stt-a",
        cleanup: String? = "chat-a",
        ok: Boolean = true,
    ) = StatsEntry(id, at(day), "com.whatsapp", CleanupLevel.LIGHT, rawWords = 11, cleanWords = 10,
        fillerWords = 1, audioMs = 5_000, latencyMs = if (ok) 1_000 else null, costUsd = cost,
        sttModel = stt, cleanupModel = cleanup, succeeded = ok)

    @Test fun eachListSumsToTotal() {
        val rows = listOf(
            entry(1, "2026-09-25", cost = 0.010, stt = "stt-a", cleanup = "chat-a"),
            entry(2, "2026-09-26", cost = 0.020, stt = "stt-b", cleanup = "chat-a"),
            entry(3, "2026-09-27", cost = 0.030, stt = "stt-a", cleanup = "chat-b"),
        )
        val costs = Costs.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(0.06, costs.totalUsd, 1e-9)
        assertEquals(0.06, costs.bySttModel.sumOf { it.usd }, 1e-9)
        assertEquals(0.06, costs.byCleanupModel.sumOf { it.usd }, 1e-9)
        assertEquals(listOf("stt-a", "stt-b"), costs.bySttModel.map { it.model })
        assertEquals(0.04, costs.bySttModel[0].usd, 1e-9)
        assertEquals(2, costs.bySttModel[0].dictations)
        assertEquals(listOf("chat-a", "chat-b"), costs.byCleanupModel.map { it.model })
    }

    @Test fun nullModelBecomesUnknown() {
        val rows = listOf(
            entry(1, "2026-09-26", cost = 0.010, stt = null, cleanup = null),
            entry(2, "2026-09-27", cost = 0.005, stt = "stt-a", cleanup = "chat-a"),
        )
        val costs = Costs.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(listOf("Unknown", "stt-a"), costs.bySttModel.map { it.model })
        assertEquals(listOf("Unknown", "chat-a"), costs.byCleanupModel.map { it.model })
        assertEquals(0.010, costs.bySttModel[0].usd, 1e-9)
    }

    @Test fun failedRowsCountInTotalButNotPerDictationDivisor() {
        val rows = listOf(
            entry(1, "2026-09-26", cost = 0.010),
            entry(2, "2026-09-27", cost = 0.020, ok = false),
        )
        val costs = Costs.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(0.03, costs.totalUsd, 1e-9)
        assertEquals(0.03, costs.perDictationUsd!!, 1e-9)
    }

    @Test fun perDictationNullWithoutSuccess() {
        assertNull(Costs.from(emptyList(), DashboardRange.D7, nowMs, zone).perDictationUsd)
        val failed = listOf(entry(1, "2026-09-27", cost = 0.010, ok = false))
        val costs = Costs.from(failed, DashboardRange.D7, nowMs, zone)
        assertEquals(0.010, costs.totalUsd, 1e-9)
        assertNull(costs.perDictationUsd)
    }

    @Test fun nullCostCountsAsZero() {
        val costs = Costs.from(listOf(entry(1, "2026-09-27", cost = null)), DashboardRange.D7, nowMs, zone)
        assertEquals(0.0, costs.totalUsd, 1e-9)
        assertEquals(0.0, costs.perDictationUsd!!, 1e-9)
    }

    @Test fun listsOnlyCoverTheRange() {
        val rows = listOf(
            entry(1, "2026-09-01", cost = 0.500, stt = "old"),
            entry(2, "2026-09-27", cost = 0.010, stt = "stt-a"),
        )
        val costs = Costs.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(0.010, costs.totalUsd, 1e-9)
        assertEquals(listOf("stt-a"), costs.bySttModel.map { it.model })
    }

    @Test fun projectionNeedsThreeActiveDays() {
        val rows = listOf(entry(1, "2026-09-26"), entry(2, "2026-09-27"), entry(3, "2026-09-27"))
        assertNull(Costs.from(rows, DashboardRange.D7, nowMs, zone).projectedMonthUsd)
    }

    @Test fun projectionScalesToMonthLength() {
        val rows = listOf(
            entry(1, "2026-09-22", cost = 0.010),
            entry(2, "2026-09-25", cost = 0.010),
            entry(3, "2026-09-27", cost = 0.010),
        )
        val projected = Costs.from(rows, DashboardRange.D7, nowMs, zone).projectedMonthUsd
        assertNotNull(projected)
        assertEquals(0.03 / 7 * 30, projected!!, 1e-9)
    }

    @Test fun projectionIgnoresRowsOlderThanSevenDays() {
        val rows = listOf(
            entry(1, "2026-09-20", cost = 1.0),
            entry(2, "2026-09-25"), entry(3, "2026-09-26"), entry(4, "2026-09-27"),
        )
        val projected = Costs.from(rows, DashboardRange.ALL, nowMs, zone).projectedMonthUsd
        assertEquals(0.03 / 7 * 30, projected!!, 1e-9)
    }

    @Test fun projectionIgnoresTheRange() {
        // Every range covers at least the last 7 days, so the projection must come out the same.
        val rows = listOf(entry(1, "2026-09-25"), entry(2, "2026-09-26"), entry(3, "2026-09-27"))
        val d7 = Costs.from(rows, DashboardRange.D7, nowMs, zone).projectedMonthUsd
        val all = Costs.from(rows, DashboardRange.ALL, nowMs, zone).projectedMonthUsd
        assertEquals(d7!!, all!!, 1e-9)
    }

    @Test fun projectionCountsFailedRowsAsActiveDays() {
        val rows = listOf(
            entry(1, "2026-09-25", ok = false), entry(2, "2026-09-26", ok = false),
            entry(3, "2026-09-27", ok = false),
        )
        assertNotNull(Costs.from(rows, DashboardRange.D7, nowMs, zone).projectedMonthUsd)
    }
}
