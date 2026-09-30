package io.github.agopalareddy.umm.settings.dashboard

import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.stats.CalendarDay
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.core.stats.DayStat
import io.github.agopalareddy.umm.core.stats.LatencyPoint
import io.github.agopalareddy.umm.core.stats.ModelSpend
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSeriesTest {
    private val us = Locale.US

    private fun day(date: String, words: Int = 0, savedMs: Long = 0, cost: Double = 0.0, wpm: Double? = null) =
        DayStat(LocalDate.parse(date), if (words > 0) 1 else 0, words, savedMs, cost, wpm)

    /** 2026-09-21 is a Monday. */
    private fun run(from: String, count: Int, words: (Int) -> Int = { 1 }) =
        (0 until count).map { day(LocalDate.parse(from).plusDays(it.toLong()).toString(), words(it)) }

    @Test fun weeksBreakOnMondaysAndKeepPartialEnds() {
        // Sat 19th to Wed 30th: Sat-Sun, Mon-Sun, Mon-Wed.
        val days = run("2026-09-19", 12)
        assertEquals(listOf(0..1, 2..8, 9..11), Series.weeks(days))
    }

    @Test fun weeksOfAnEmptyRangeAreEmpty() {
        assertEquals(emptyList<IntRange>(), Series.weeks(emptyList()))
    }

    @Test fun weeksOfExactlyOneWeek() {
        assertEquals(listOf(0..6), Series.weeks(run("2026-09-21", 7)))
    }

    @Test fun dailyBarsKeepEveryDay() {
        val days = run("2026-09-21", 3) { (it + 1) * 10 }
        val before = run("2026-09-18", 3) { 5 }
        val bars = Series.wordBars(days, before, weekly = false)
        assertEquals(days.map { it.date }, bars.starts)
        assertEquals(listOf(10, 20, 30), bars.words)
        assertEquals(listOf(5, 5, 5), bars.previous)
    }

    @Test fun weeklyBarsSumEachMondayWeekStartingOnItsMonday() {
        val days = run("2026-09-19", 12) { 10 } // Sat, Sun | Mon..Sun | Mon..Wed
        val bars = Series.wordBars(days, null, weekly = true)
        assertEquals(listOf("2026-09-14", "2026-09-21", "2026-09-28").map(LocalDate::parse), bars.starts)
        assertEquals(listOf(20, 70, 30), bars.words)
        assertNull(bars.previous)
    }

    @Test fun weeklyPreviousSumsTheDaysThatLineUpWithEachWeek() {
        val days = run("2026-09-19", 12) { 10 }
        val before = run("2026-09-07", 12) { it } // 0..11
        val bars = Series.wordBars(days, before, weekly = true)
        assertEquals(listOf(0 + 1, 2 + 3 + 4 + 5 + 6 + 7 + 8, 9 + 10 + 11), bars.previous)
    }

    @Test fun wordDescriptionNamesThePeakDayWithUnits() {
        val days = listOf(day("2026-09-21", words = 10), day("2026-09-22", words = 420), day("2026-09-23", words = 1))
        val bars = Series.wordBars(days, null, weekly = false)
        assertEquals(
            "Words per day, last 30 days, most on Sep 22: 420 words.",
            Series.wordsDescription(bars, DashboardRange.D30, us),
        )
    }

    @Test fun wordDescriptionSaysWeekAndSingularWord() {
        val days = run("2026-09-21", 7) { if (it == 0) 1 else 0 }
        val bars = Series.wordBars(days, null, weekly = true)
        assertEquals(
            "Words per week, last 90 days, most on the week of Sep 21: 1 word.",
            Series.wordsDescription(bars, DashboardRange.D90, us),
        )
    }

    @Test fun wordDescriptionWithNoWordsFallsBack() {
        val bars = Series.wordBars(run("2026-09-21", 3) { 0 }, null, weekly = false)
        assertEquals("Words per day: no data yet.", Series.wordsDescription(bars, DashboardRange.D7, us))
    }

    @Test fun cumulativeAddsUpAndKeepsLength() {
        assertEquals(listOf(1L, 4L, 4L, 10L), Series.cumulative(listOf(1L, 3L, 0L, 6L)))
        assertEquals(emptyList<Long>(), Series.cumulative(emptyList()))
    }

    @Test fun timeSavedDescriptionGivesTheTotal() {
        val days = listOf(day("2026-09-21", savedMs = 60_000), day("2026-09-22", savedMs = 3_540_000))
        assertEquals(
            "Time saved, last 7 days, adding up to 1 h 0 min.",
            Series.timeSavedDescription(days, DashboardRange.D7),
        )
    }

    @Test fun paceDescriptionGivesAverageAndFastestDay() {
        val days = listOf(day("2026-09-21", wpm = 120.0), day("2026-09-22"), day("2026-09-23", wpm = 165.4))
        assertEquals(
            "Speaking pace, last 30 days, averaging 140 wpm, fastest on Sep 23: 165 wpm.",
            Series.paceDescription(days, 140.2, DashboardRange.D30, us),
        )
    }

    @Test fun calendarDescriptionIsOneSentence() {
        val days = listOf(
            CalendarDay(LocalDate.parse("2026-09-21"), 0, 0),
            CalendarDay(LocalDate.parse("2026-09-22"), 5, 3),
            CalendarDay(LocalDate.parse("2026-09-23"), 2, 2),
        )
        assertEquals(
            "Dictations per day, last 12 weeks, most on Sep 22: 5, dictated on 2 of 3 days.",
            Series.calendarDescription(days, us),
        )
        assertEquals("Dictations per day: no data yet.", Series.calendarDescription(days.take(1), us))
    }

    @Test fun paceDaysCountOnlyThoseWithAPace() {
        val days = listOf(day("2026-09-21", wpm = 120.0), day("2026-09-22"), day("2026-09-23", wpm = 165.0))
        assertEquals(2, Series.paceDays(days))
    }

    @Test fun spendDescriptionUsesDollarsAndFourDecimalsForCents() {
        val days = listOf(day("2026-09-21", cost = 0.004), day("2026-09-22", cost = 0.42))
        assertEquals(
            "Spend per day, last 30 days, most on Sep 22: $0.42.",
            Series.spendDescription(days, DashboardRange.D30, us),
        )
        val tiny = listOf(day("2026-09-21", cost = 0.004))
        assertEquals(
            "Spend per day, all time, most on Sep 21: $0.0040.",
            Series.spendDescription(tiny, DashboardRange.ALL, us),
        )
    }

    @Test fun modelDescriptionListsEachModelAndItsSpend() {
        val models = listOf(ModelSpend("whisper", 0.31, 12), ModelSpend("mini", 0.0042, 1))
        assertEquals(
            "Speech models by spend: whisper $0.31 (12 dictations), mini $0.0042 (1 dictation).",
            Series.modelsDescription("Speech models", models),
        )
        assertEquals("Cleanup models by spend: none.", Series.modelsDescription("Cleanup models", emptyList()))
    }

    @Test fun spanNames() {
        assertEquals("last 7 days", DashboardText.span(DashboardRange.D7))
        assertEquals("last 90 days", DashboardText.span(DashboardRange.D90))
        assertEquals("all time", DashboardText.span(DashboardRange.ALL))
    }

    private fun info(limit: Double?, remaining: Double?, monthly: Double? = null, reset: String? = null) =
        KeyInfo("k", 5.0, monthly, limit, remaining, reset)

    @Test fun budgetIsNullWithoutARealLimit() {
        assertNull(BudgetUse.of(info(null, null)))
        assertNull(BudgetUse.of(info(0.0, 0.0)))
        assertNull(BudgetUse.of(info(-1.0, null)))
    }

    @Test fun budgetUsedIsLimitMinusRemaining() {
        val b = BudgetUse.of(info(10.0, 8.8, reset = "monthly"))!!
        assertEquals(1.2, b.used!!, 1e-9)
        assertEquals(0.12f, b.fraction!!, 1e-6f)
        assertEquals("$1.20 of $10.00 used", b.usedLine)
        assertEquals("$8.80 left, resets monthly", b.leftLine)
    }

    @Test fun budgetFallsBackToMonthlyUsageWhenRemainingIsUnknown() {
        val b = BudgetUse.of(info(10.0, null, monthly = 2.5))!!
        assertEquals(2.5, b.used!!, 1e-9)
        assertEquals("$7.50 left", b.leftLine)
    }

    @Test fun budgetWithNothingUsedKnownDrawsNoBar() {
        val b = BudgetUse.of(info(10.0, null))!!
        assertNull(b.used)
        assertNull(b.fraction)
        assertEquals("Limit $10.00", b.usedLine)
        assertNull(b.leftLine)
    }

    @Test fun budgetOverTheLimitClampsTheBarAndSaysSo() {
        val b = BudgetUse.of(info(10.0, -0.5))!!
        assertEquals(1f, b.fraction!!, 0f)
        assertEquals("$10.50 of $10.00 used", b.usedLine)
        assertEquals("$0.50 over the limit", b.leftLine)
    }

    @Test fun budgetRemainingAboveTheLimitCountsAsUnused() {
        val b = BudgetUse.of(info(10.0, 12.0))!!
        assertEquals(0.0, b.used!!, 1e-9)
        assertEquals(0f, b.fraction!!, 0f)
    }

    @Test fun budgetSpokenJoinsTheLines() {
        val b = BudgetUse.of(info(10.0, 8.8, reset = "monthly"))!!
        assertEquals("Budget: $1.20 of $10.00 used, $8.80 left, resets monthly.", b.spoken)
        assertTrue(BudgetUse.of(info(10.0, null))!!.spoken.startsWith("Budget: Limit $10.00"))
    }

    private fun grid(vararg cells: Triple<Int, Int, Int>): List<List<Int>> {
        val counts = List(7) { IntArray(24) }
        cells.forEach { (day, hour, n) -> counts[day][hour] = n }
        return counts.map { it.toList() }
    }

    @Test fun hourDescriptionNamesTheBusiestSlot() {
        val g = grid(Triple(1, 14, 12), Triple(4, 9, 3))
        assertEquals(
            "Dictations by hour, last 30 days, most on Tuesday at 2 pm: 12 dictations.",
            Series.hourDescription(g, DashboardRange.D30, clock24 = false, locale = us),
        )
        assertEquals(
            "Dictations by hour, last 7 days, most on Tuesday at 14:00: 12 dictations.",
            Series.hourDescription(g, DashboardRange.D7, clock24 = true, locale = us),
        )
    }

    @Test fun hourDescriptionSaysOneDictationInTheSingular() {
        assertEquals(
            "Dictations by hour, all time, most on Monday at 12 am: 1 dictation.",
            Series.hourDescription(grid(Triple(0, 0, 1)), DashboardRange.ALL, clock24 = false, locale = us),
        )
        assertTrue(Series.hourDescription(grid(Triple(6, 12, 2)), DashboardRange.D30, false, us).contains("Sunday at 12 pm"))
    }

    @Test fun hourDescriptionOfAnEmptyOrShortGridFallsBack() {
        assertEquals("Dictations by hour: no data yet.", Series.hourDescription(grid(), DashboardRange.D30, false, us))
        assertEquals("Dictations by hour: no data yet.", Series.hourDescription(emptyList(), DashboardRange.D30, false, us))
        assertEquals(
            "Dictations by hour, last 30 days, most on Tuesday at 1 am: 4 dictations.",
            Series.hourDescription(listOf(emptyList(), listOf(0, 4)), DashboardRange.D30, false, us),
        )
    }

    @Test fun appsDescriptionListsEachAppWithItsCategoryAndCount() {
        assertEquals(
            "Top apps, last 30 days: Gmail (Work) 12 dictations, Slack 1 dictation, Notes (Personal) 1 dictation.",
            Series.appsDescription(
                listOf(AppUse("Gmail", "Work", 12), AppUse("Slack", null, 1), AppUse("Notes", "Personal", 1)),
                DashboardRange.D30,
            ),
        )
        assertEquals("Top apps: no data yet.", Series.appsDescription(emptyList(), DashboardRange.D30))
    }

    @Test fun levelsDescriptionSkipsLevelsWithNoDictations() {
        assertEquals(
            "Cleanup levels, last 90 days, by dictations: Light 3 (25%), Polished 9 (75%).",
            Series.levelsDescription(listOf("Raw" to 0, "Light" to 3, "Formatted" to 0, "Polished" to 9), DashboardRange.D90),
        )
    }

    @Test fun levelsDescriptionOfNothingFallsBack() {
        assertEquals("Cleanup levels: no data yet.", Series.levelsDescription(listOf("Raw" to 0, "Light" to 0), DashboardRange.D30))
        assertEquals("Cleanup levels: no data yet.", Series.levelsDescription(emptyList(), DashboardRange.ALL))
    }

    @Test fun levelsDescriptionGivesATinyShareAsUnderOnePercent() {
        val text = Series.levelsDescription(listOf("Raw" to 1, "Light" to 500), DashboardRange.ALL)
        assertTrue(text, text.contains("Raw 1 (<1%)"))
        // 99.8% rounds to 100, but another level has a share.
        assertTrue(text, text.contains("Light 500 (99%)"))
        assertTrue(Series.levelsDescription(listOf("Raw" to 0, "Light" to 7), DashboardRange.ALL).contains("Light 7 (100%)"))
    }

    @Test fun percentRoundsButNeverClaimsPerfectOrNothing() {
        assertEquals("100%", Series.percent(1.0))
        assertEquals("0%", Series.percent(0.0))
        assertEquals("97%", Series.percent(0.97))
        assertEquals("98%", Series.percent(0.975))
        // 99.6% rounds up to 100, but a failure happened.
        assertEquals("99%", Series.percent(0.996))
        assertEquals("<1%", Series.percent(0.004))
    }

    private fun latency(date: String, ms: Long) = LatencyPoint(LocalDate.parse(date), ms)

    @Test fun latencyDescriptionGivesTheSpreadAndTheSlowestDay() {
        val points = listOf(latency("2026-09-10", 1_200), latency("2026-09-11", 3_200), latency("2026-09-12", 1_800))
        assertEquals(
            "Wait time, last 30 days, from 1.2 s to 3.2 s, slowest on Sep 11: 3.2 s.",
            Series.latencyDescription(points, DashboardRange.D30, us),
        )
    }

    @Test fun latencyDescriptionOfOneValueSaysNoSpread() {
        val points = listOf(latency("2026-09-10", 900), latency("2026-09-11", 900))
        assertEquals(
            "Wait time, last 7 days, 900 ms, slowest on Sep 10: 900 ms.",
            Series.latencyDescription(points, DashboardRange.D7, us),
        )
    }

    @Test fun latencyDescriptionOfNothingFallsBack() {
        assertEquals("Wait time: no data yet.", Series.latencyDescription(emptyList(), DashboardRange.D30, us))
    }
}
