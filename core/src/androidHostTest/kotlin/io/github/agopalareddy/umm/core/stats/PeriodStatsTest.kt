package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodStatsTest {
    private val zone = ZoneOffset.UTC
    private val nowMs = at("2026-09-27", 18)

    private fun at(day: String, hour: Int = 12, minute: Int = 0, z: ZoneId = zone) =
        LocalDate.parse(day).atTime(hour, minute).atZone(z).toInstant().toEpochMilli()

    private fun entry(
        id: Long,
        day: String,
        words: Int = 10,
        fillers: Int = 1,
        audioMs: Long = 5_000,
        latencyMs: Long? = 1_000,
        cost: Double? = 0.001,
        ok: Boolean = true,
        createdAt: Long = at(day),
    ) = StatsEntry(id, createdAt, "com.whatsapp", CleanupLevel.LIGHT, rawWords = words + fillers, cleanWords = words,
        fillerWords = fillers, audioMs = audioMs, latencyMs = latencyMs, costUsd = cost, sttModel = "stt",
        cleanupModel = "chat", succeeded = ok)

    @Test fun zeroFillsEveryDayOfRange() {
        val s = PeriodStats.from(emptyList(), DashboardRange.D7, nowMs, zone)
        assertEquals(
            (21..27).map { LocalDate.parse("2026-09-$it") },
            s.days.map { it.date },
        )
        assertTrue(s.days.all { it.dictations == 0 && it.words == 0 && it.timeSavedMs == 0L && it.costUsd == 0.0 && it.wpm == null })
        assertEquals(Totals(0, 0, 0L, 0.0), s.current)
        assertEquals(Totals(0, 0, 0L, 0.0), s.previous)
        assertNull(s.averageWpm)
    }

    @Test fun previousIsTheEqualWindowBefore() {
        val rows = listOf(entry(1, "2026-09-20"), entry(2, "2026-09-20"), entry(3, "2026-09-21"))
        val s = PeriodStats.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(1, s.current.dictations)
        assertEquals(2, s.previous!!.dictations)
    }

    @Test fun previousDaysAreZeroFilledAndAlignedWithDays() {
        val rows = listOf(entry(1, "2026-09-14", words = 30), entry(2, "2026-09-20", words = 8), entry(3, "2026-09-21"))
        val s = PeriodStats.from(rows, DashboardRange.D7, nowMs, zone)
        val before = s.previousDays!!
        assertEquals((14..20).map { LocalDate.parse("2026-09-$it") }, before.map { it.date })
        assertEquals(s.days.size, before.size)
        assertEquals(30, before.first().words)
        assertEquals(8, before.last().words)
        assertEquals(s.previous!!.words, before.sumOf { it.words })
    }

    @Test fun allHasNoPreviousAndStartsAtFirstRow() {
        val rows = listOf(entry(1, "2026-09-25"), entry(2, "2026-09-27"))
        val s = PeriodStats.from(rows, DashboardRange.ALL, nowMs, zone)
        assertNull(s.previous)
        assertNull(s.previousDays)
        assertEquals(
            (25..27).map { LocalDate.parse("2026-09-$it") },
            s.days.map { it.date },
        )
        assertEquals(2, s.current.dictations)
    }

    @Test fun failedRowsCostButAddNothingElse() {
        val rows = listOf(entry(1, "2026-09-27", words = 0, fillers = 0, cost = 0.01, latencyMs = null, ok = false))
        val s = PeriodStats.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(0.01, s.current.costUsd, 1e-9)
        assertEquals(0, s.current.dictations)
        assertEquals(0, s.current.words)
        assertEquals(0L, s.current.timeSavedMs)
        assertNull(s.days.last().wpm)
    }

    @Test fun timeSavedIsPerDayAndNeverNegative() {
        val rows = listOf(
            entry(1, "2026-09-26", words = 1, fillers = 0, audioMs = 30_000, latencyMs = 1_000),
            entry(2, "2026-09-27", words = 100, fillers = 0, audioMs = 30_000, latencyMs = 2_000),
        )
        val s = PeriodStats.from(rows, DashboardRange.D7, nowMs, zone)
        assertEquals(0L, s.days[5].timeSavedMs)
        assertEquals(118_000L, s.days[6].timeSavedMs) // 100 * 1500 - 30000 - 2000
        assertEquals(s.days.sumOf { it.timeSavedMs }, s.current.timeSavedMs)
        assertEquals(118_000L, s.current.timeSavedMs)
    }

    @Test fun paceIgnoresClipsUnderTwoSeconds() {
        val short = listOf(entry(1, "2026-09-26", words = 10, fillers = 0, audioMs = 1_999))
        val s1 = PeriodStats.from(short, DashboardRange.D7, nowMs, zone)
        assertNull(s1.days[5].wpm)
        assertNull(s1.averageWpm)

        val rows = short + entry(2, "2026-09-27", words = 60, fillers = 0, audioMs = 30_000)
        val s2 = PeriodStats.from(rows, DashboardRange.D7, nowMs, zone)
        assertNull(s2.days[5].wpm)
        assertEquals(120.0, s2.days[6].wpm!!, 1e-9)
        assertEquals(120.0, s2.averageWpm!!, 1e-9)

        val zero = listOf(entry(3, "2026-09-27", words = 5, fillers = 0, audioMs = 0))
        val s3 = PeriodStats.from(zero, DashboardRange.D7, nowMs, zone)
        assertNull(s3.averageWpm)
        assertEquals(5, s3.current.words)
    }

    @Test fun usesLocalDayNotUtcDay() {
        val la = ZoneId.of("America/Los_Angeles")
        val row = entry(1, "2026-09-28", createdAt = at("2026-09-28", 2, z = zone)) // 19:00 on 09-27 in LA
        val s = PeriodStats.from(listOf(row), DashboardRange.D7, at("2026-09-27", 20, z = la), la)
        assertEquals(LocalDate.parse("2026-09-27"), s.days.last().date)
        assertEquals(1, s.days.last().dictations)
        assertEquals(1, s.current.dictations)
    }

    @Test fun dstDayKeepsBothEndsInOneBucket() {
        val berlin = ZoneId.of("Europe/Berlin")
        val rows = listOf(
            entry(1, "2026-10-25", createdAt = at("2026-10-25", 0, 30, berlin)),
            entry(2, "2026-10-25", createdAt = at("2026-10-25", 23, 30, berlin)),
        )
        val s = PeriodStats.from(rows, DashboardRange.D7, at("2026-10-25", 23, 45, berlin), berlin)
        assertEquals(7, s.days.size)
        val day = s.days.single { it.date == LocalDate.parse("2026-10-25") }
        assertEquals(2, day.dictations)
        assertEquals(2, s.current.dictations)
    }

    @Test fun singleDictationHasNoNanValues() {
        val s = PeriodStats.from(listOf(entry(1, "2026-09-27")), DashboardRange.D7, nowMs, zone)
        assertNotNull(s.averageWpm)
        assertTrue(s.averageWpm!!.isFinite())
        assertTrue(s.current.costUsd.isFinite())
        assertTrue(s.days.all { it.costUsd.isFinite() && (it.wpm?.isFinite() ?: true) })
    }
}
