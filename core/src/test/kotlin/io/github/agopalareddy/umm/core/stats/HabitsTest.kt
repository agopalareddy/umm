package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitsTest {
    private val zone = ZoneOffset.UTC
    private val nowMs = at("2026-09-27", 18)

    private fun at(day: String, hour: Int = 12, minute: Int = 0, z: ZoneId = zone) =
        LocalDate.parse(day).atTime(hour, minute).atZone(z).toInstant().toEpochMilli()

    private fun entry(
        id: Long,
        day: String,
        hour: Int = 12,
        pkg: String = "com.whatsapp",
        level: CleanupLevel = CleanupLevel.LIGHT,
        latencyMs: Long? = 1_000,
        ok: Boolean = true,
    ) = StatsEntry(id, at(day, hour), pkg, level, rawWords = 11, cleanWords = 10, fillerWords = 1,
        audioMs = 5_000, latencyMs = if (ok) latencyMs else null, costUsd = 0.001, sttModel = "stt",
        cleanupModel = "chat", succeeded = ok)

    @Test fun hourGridIsMondayFirstLocalHour() {
        val rows = listOf(entry(1, "2026-09-21", hour = 9), entry(2, "2026-09-27", hour = 23))
        val grid = Habits.from(rows, DashboardRange.D7, nowMs, zone).hourGrid
        assertEquals(7, grid.size)
        assertTrue(grid.all { it.size == 24 })
        assertEquals(1, grid[0][9])
        assertEquals(1, grid[6][23])
        assertEquals(2, grid.sumOf { it.sum() })
    }

    @Test fun hourGridUsesTheDeviceZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2026-09-27 20:00 UTC is Monday 2026-09-28 05:00 in Tokyo.
        val row = entry(1, "2026-09-27", hour = 20)
        val now = at("2026-09-28", 12, z = tokyo)
        val grid = Habits.from(listOf(row), DashboardRange.D7, now, tokyo).hourGrid
        assertEquals(1, grid[0][5])
    }

    @Test fun hourGridIgnoresFailedRows() {
        val rows = listOf(entry(1, "2026-09-21", hour = 9, ok = false))
        val grid = Habits.from(rows, DashboardRange.D7, nowMs, zone).hourGrid
        assertEquals(0, grid.sumOf { it.sum() })
    }

    @Test fun topAppsRankedAndCappedAtSix() {
        val rows = buildList {
            var id = 0L
            // app1 gets 7 dictations, app2 gets 6, ... app7 gets 1.
            for (app in 1..7) repeat(8 - app) { add(entry(++id, "2026-09-25", pkg = "com.app$app")) }
        }
        val top = Habits.from(rows, DashboardRange.D7, nowMs, zone).topApps
        assertEquals(6, top.size)
        assertEquals(
            listOf(AppCount("com.app1", 7), AppCount("com.app2", 6), AppCount("com.app3", 5),
                AppCount("com.app4", 4), AppCount("com.app5", 3), AppCount("com.app6", 2)),
            top,
        )
    }

    @Test fun tiesBreakByPackageName() {
        val rows = listOf(
            entry(1, "2026-09-25", pkg = "com.zebra"),
            entry(2, "2026-09-25", pkg = "com.apple"),
            entry(3, "2026-09-25", pkg = "com.mango"),
        )
        val top = Habits.from(rows, DashboardRange.D7, nowMs, zone).topApps
        assertEquals(listOf("com.apple", "com.mango", "com.zebra"), top.map { it.packageName })
    }

    @Test fun levelCountsCoverEveryLevel() {
        val rows = listOf(
            entry(1, "2026-09-25", level = CleanupLevel.LIGHT),
            entry(2, "2026-09-25", level = CleanupLevel.LIGHT),
            entry(3, "2026-09-25", level = CleanupLevel.POLISHED),
            entry(4, "2026-09-25", level = CleanupLevel.RAW, ok = false),
        )
        val counts = Habits.from(rows, DashboardRange.D7, nowMs, zone).levelCounts
        assertEquals(
            mapOf(CleanupLevel.RAW to 0, CleanupLevel.LIGHT to 2, CleanupLevel.FORMATTED to 0, CleanupLevel.POLISHED to 1),
            counts,
        )
    }

    @Test fun successRateCountsAttempts() {
        val rows = listOf(
            entry(1, "2026-09-25"), entry(2, "2026-09-25"), entry(3, "2026-09-26"),
            entry(4, "2026-09-26", ok = false),
        )
        val h = Habits.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(0.75, h.successRate!!, 1e-9)
        assertEquals(4, h.attempts)
    }

    @Test fun successRateNullWithoutRows() {
        val h = Habits.from(emptyList(), DashboardRange.D7, nowMs, zone)
        assertNull(h.successRate)
        assertEquals(0, h.attempts)
        assertTrue(h.topApps.isEmpty())
        assertTrue(h.latency.isEmpty())
    }

    @Test fun latencyAveragesPerDay() {
        val rows = listOf(
            entry(1, "2026-09-25", latencyMs = 1_000),
            entry(2, "2026-09-25", latencyMs = 2_000),
            entry(3, "2026-09-26", latencyMs = 3_000),
            entry(4, "2026-09-27", latencyMs = null),
            entry(5, "2026-09-27", ok = false),
        )
        val latency = Habits.from(rows, DashboardRange.D7, nowMs, zone).latency
        assertEquals(
            listOf(LatencyPoint(LocalDate.parse("2026-09-25"), 1_500), LatencyPoint(LocalDate.parse("2026-09-26"), 3_000)),
            latency,
        )
    }

    @Test fun rowsOutsideRangeAreIgnored() {
        val rows = listOf(
            entry(1, "2026-09-19", hour = 9, pkg = "com.old", ok = false), // 8 days before today
            entry(2, "2026-09-19", hour = 9, pkg = "com.old"),
            entry(3, "2026-09-21", hour = 9, pkg = "com.new"),
        )
        val h = Habits.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(1, h.attempts)
        assertEquals(1.0, h.successRate!!, 1e-9)
        assertEquals(listOf(AppCount("com.new", 1)), h.topApps)
        assertEquals(1, h.hourGrid.sumOf { it.sum() })
        assertEquals(1, h.levelCounts.values.sum())
        assertEquals(listOf(LocalDate.parse("2026-09-21")), h.latency.map { it.date })
    }
}
