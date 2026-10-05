package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngagementTest {
    private val zone = ZoneOffset.UTC
    private val today = "2026-09-27"
    private fun at(day: String, hour: Int = 12) =
        LocalDate.parse(day).atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun entry(
        id: Long,
        day: String,
        words: Int = 10,
        fillers: Int = 1,
        audioMs: Long = 5_000,
        ok: Boolean = true,
    ) = StatsEntry(id, at(day), "com.whatsapp", CleanupLevel.LIGHT, rawWords = words + fillers, cleanWords = words,
        fillerWords = fillers, audioMs = audioMs, latencyMs = 1_000, costUsd = 0.001, sttModel = "stt",
        cleanupModel = "chat", succeeded = ok)

    private fun from(rows: List<StatsEntry>) = Engagement.from(rows, at(today), zone)

    /** One row per day, `count` rows on the day `daysAgo` before today. */
    private fun rowsOn(daysAgo: Long, count: Int, startId: Long) =
        (0 until count).map { entry(startId + it, LocalDate.parse(today).minusDays(daysAgo).toString()) }

    @Test fun calendarCoversLast84DaysEndingToday() {
        val e = from(emptyList())
        assertEquals(84, e.calendar.size)
        assertEquals(LocalDate.parse(today), e.calendar.last().date)
        assertEquals(LocalDate.parse(today).minusDays(83), e.calendar.first().date)
    }

    @Test fun stepsFollowQuartilesOfNonZeroDays() {
        val rows = rowsOn(3, 1, 1) + rowsOn(2, 2, 10) + rowsOn(1, 3, 20) + rowsOn(0, 4, 30)
        val e = from(rows)
        assertEquals(listOf(1, 2, 3, 4), e.calendar.takeLast(4).map { it.step })
        assertEquals(listOf(1, 2, 3, 4), e.calendar.takeLast(4).map { it.dictations })
        assertEquals(0, e.calendar[e.calendar.size - 5].step)
    }

    @Test fun stepsIgnoreDaysOutsideTheCalendar() {
        // Heavy days older than the 84-day window must not flatten the visible quartiles.
        val rows = rowsOn(100, 50, 1_000) + rowsOn(101, 60, 2_000) +
            rowsOn(3, 1, 1) + rowsOn(2, 2, 10) + rowsOn(1, 3, 20) + rowsOn(0, 4, 30)
        assertEquals(listOf(1, 2, 3, 4), from(rows).calendar.takeLast(4).map { it.step })
    }

    @Test fun calendarBucketsByLocalDay() {
        // 23:30 UTC on Sep 26 is already Sep 27 in Tokyo.
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        val row = entry(1, "2026-09-26").copy(createdAt = at("2026-09-26", 23) + 30 * 60_000)
        val e = Engagement.from(listOf(row), at("2026-09-27"), tokyo)
        assertEquals(LocalDate.parse("2026-09-27"), e.calendar.last().date)
        assertEquals(1, e.calendar.last().dictations)
    }

    @Test fun equalCountsAllGetTopStep() {
        val rows = rowsOn(2, 5, 1) + rowsOn(1, 5, 10) + rowsOn(0, 5, 20)
        assertEquals(listOf(4, 4, 4), from(rows).calendar.takeLast(3).map { it.step })
    }

    @Test fun currentStreakEndsTodayOrYesterday() {
        assertEquals(2, from(rowsOn(0, 1, 1) + rowsOn(1, 1, 2)).currentStreak)
        assertEquals(2, from(rowsOn(1, 1, 1) + rowsOn(2, 1, 2)).currentStreak)
        assertEquals(0, from(rowsOn(2, 1, 1) + rowsOn(3, 1, 2)).currentStreak)
    }

    @Test fun bestStreakIsLongestRun() {
        // A run of 2 days, a gap, then a run of 4 days.
        val rows = listOf(20L, 19L, 10L, 9L, 8L, 7L).mapIndexed { i, ago -> rowsOn(ago, 1, i.toLong()).single() }
        val e = from(rows)
        assertEquals(4, e.bestStreak)
        assertEquals(0, e.currentStreak)
    }

    @Test fun twoBigDictationsEarnWordBadges() {
        val e = from(listOf(entry(1, today, words = 500), entry(2, today, words = 500)))
        val earned = e.badges.filter { it.earned }.map { it.id }
        assertTrue("words_100" in earned)
        assertTrue("words_1000" in earned)
        assertFalse("words_10000" in earned)
        val next = e.next!!
        assertEquals("streak_3", next.id)
        assertEquals(1f / 3, next.progress, 1e-6f)
    }

    @Test fun badgeIdsAndLabelsInListOrder() {
        val e = from(emptyList())
        assertEquals(
            listOf(
                "words_100", "words_1000", "words_10000",
                "dictations_10", "dictations_100", "dictations_1000",
                "streak_3", "streak_7", "streak_30",
                "spoken_1h",
            ),
            e.badges.map { it.id },
        )
        assertEquals("100 words", e.badges.first().label)
    }

    @Test fun allBadgesEarnedLeavesNoNext() {
        val days = (0L until 30L).flatMap { rowsOn(it, 40, it * 100) } // 1,200 dictations
        val big = entry(9_999, today, words = 10_000, audioMs = 3_600_000)
        val e = from(days + big)
        assertTrue(e.badges.all { it.earned })
        assertEquals(null, e.next)
    }

    @Test fun emptyRowsEarnNothing() {
        val e = from(emptyList())
        assertNotNull(e.next)
        assertEquals(0, e.currentStreak)
        assertEquals(0, e.bestStreak)
        assertTrue(e.badges.none { it.earned })
        assertTrue(e.calendar.all { it.step == 0 })
    }

    @Test fun factsFromTotals() {
        val e = from(listOf(entry(1, today, words = 300, fillers = 4), entry(2, "2026-09-20", words = 200, fillers = 6)))
        assertEquals(10, e.fillersRemoved)
        assertEquals(500 / 80_000.0, e.novelShare, 1e-12)
        assertEquals(500 * 1_500L, e.typingAvoidedMs)
    }

    @Test fun failedRowsAreIgnored() {
        val e = from(listOf(entry(1, today, words = 500, fillers = 3, ok = false)))
        assertEquals(0, e.currentStreak)
        assertEquals(0, e.fillersRemoved)
        assertEquals(0L, e.typingAvoidedMs)
        assertTrue(e.calendar.all { it.dictations == 0 })
    }
}
