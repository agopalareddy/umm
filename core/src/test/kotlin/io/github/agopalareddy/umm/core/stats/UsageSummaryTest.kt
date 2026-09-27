package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageSummaryTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: String, hour: Int = 12) =
        LocalDate.parse(day).atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun entry(
        id: Long,
        day: String,
        app: String = "com.whatsapp",
        words: Int = 10,
        fillers: Int = 1,
        audioMs: Long = 5_000,
        latencyMs: Long? = 1_000,
        cost: Double? = 0.001,
        level: CleanupLevel = CleanupLevel.LIGHT,
        ok: Boolean = true,
    ) = StatsEntry(id, at(day), app, level, rawWords = words + fillers, cleanWords = words, fillerWords = fillers,
        audioMs = audioMs, latencyMs = latencyMs, costUsd = cost, sttModel = "stt", cleanupModel = "chat", succeeded = ok)

    @Test fun emptyHistory() {
        val s = UsageSummary.from(emptyList(), at("2026-09-27"), zone)
        assertEquals(0, s.dictations)
        assertEquals(0, s.streakDays)
        assertNull(s.topApp)
        assertNull(s.averageLatencyMs)
        assertEquals(0.0, s.successRate, 0.0)
    }

    @Test fun aggregatesSuccessfulDictations() {
        val rows = listOf(
            entry(1, "2026-09-20", words = 40, fillers = 3, audioMs = 12_000, latencyMs = 1_200, cost = 0.002),
            entry(2, "2026-09-26", app = "com.google.android.gm", words = 100, fillers = 5, audioMs = 30_000, latencyMs = 1_800, cost = 0.004, level = CleanupLevel.FORMATTED),
            entry(3, "2026-09-27", words = 20, fillers = 0, audioMs = 6_000, latencyMs = 1_000, cost = 0.001),
            entry(4, "2026-09-27", words = 0, fillers = 0, audioMs = 4_000, latencyMs = null, cost = null, ok = false),
        )
        val s = UsageSummary.from(rows, at("2026-09-27", 18), zone)
        assertEquals(3, s.dictations)
        assertEquals(160, s.words)
        assertEquals(48_000L, s.spokenMs)
        assertEquals(2, s.dictationsThisWeek) // the last 7 days: Sep 21–27
        assertEquals(8, s.fillersRemoved)
        assertEquals(100, s.longestWords)
        assertEquals("com.whatsapp", s.topApp)
        assertEquals(2, s.streakDays) // Sep 26 and 27
        assertEquals(0.007, s.totalCostUsd, 1e-9)
        assertEquals(0.007 / 3, s.averageCostUsd!!, 1e-9)
        assertEquals(1_333L, s.averageLatencyMs)
        assertEquals(0.75, s.successRate, 1e-9)
        assertEquals(mapOf(CleanupLevel.LIGHT to 2, CleanupLevel.FORMATTED to 1), s.levelCounts)
        // Typing 160 words at 40 wpm takes 4 min; speaking and waiting took 48 s + 4 s.
        assertEquals(240_000L - 52_000L, s.timeSavedMs)
    }

    @Test fun streakBreaksOnAMissedDay() {
        val rows = listOf(entry(1, "2026-09-24"), entry(2, "2026-09-25"), entry(3, "2026-09-27"))
        assertEquals(1, UsageSummary.from(rows, at("2026-09-27"), zone).streakDays)
    }

    @Test fun streakSurvivesUntilTodayIsOver() {
        // Nothing yet today: yesterday's streak still counts.
        val rows = listOf(entry(1, "2026-09-25"), entry(2, "2026-09-26"))
        assertEquals(2, UsageSummary.from(rows, at("2026-09-27", 9), zone).streakDays)
    }

    @Test fun timeSavedNeverNegative() {
        val rows = listOf(entry(1, "2026-09-27", words = 1, audioMs = 60_000))
        assertEquals(0L, UsageSummary.from(rows, at("2026-09-27"), zone).timeSavedMs)
    }
}
