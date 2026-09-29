package io.github.agopalareddy.umm.core.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One heatmap cell: `step` is 0 for no dictations, else 1..4 by rank among the days in the calendar that have any. */
data class CalendarDay(val date: LocalDate, val dictations: Int, val step: Int)

data class Badge(val id: String, val label: String, val earned: Boolean)

/** The closest unearned badge; `progress` is 0..1. */
data class NextBadge(val id: String, val label: String, val progress: Float)

/** Streaks, badges and fun facts. Lifetime numbers; the calendar is the last 12 weeks. Counts only successful dictations. */
data class Engagement(
    val calendar: List<CalendarDay>,
    val currentStreak: Int,
    val bestStreak: Int,
    val badges: List<Badge>,
    val next: NextBadge?,
    val fillersRemoved: Int,
    /** Words dictated as a share of one 80,000-word novel. */
    val novelShare: Double,
    /** Typing the same words at 40 wpm. */
    val typingAvoidedMs: Long,
) {
    private class Goal(val id: String, val label: String, val current: Long, val target: Long)

    companion object {
        private const val CALENDAR_DAYS = 84L
        private const val TYPING_MS_PER_WORD = 60_000L / 40
        private const val NOVEL_WORDS = 80_000.0
        private const val HOUR_MS = 3_600_000L

        fun from(rows: List<StatsEntry>, nowMs: Long, zone: ZoneId): Engagement {
            val ok = rows.filter { it.succeeded }
            fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val today = day(nowMs)
            val counts = ok.groupingBy { day(it.createdAt) }.eachCount()
            val words = ok.sumOf { it.cleanWords }

            val window = (CALENDAR_DAYS - 1 downTo 0).map { today.minusDays(it) to (counts[today.minusDays(it)] ?: 0) }
            // Quartiles cover only the days shown, so old heavy days don't flatten the heatmap.
            val nonZero = window.map { it.second }.filter { it > 0 }
            val calendar = window.map { (date, count) ->
                val step = if (count == 0) 0 else {
                    val rank = nonZero.count { it <= count }
                    (4 * rank + nonZero.size - 1) / nonZero.size
                }
                CalendarDay(date, count, step)
            }

            val bestStreak = bestStreak(counts.keys)
            val goals = listOf(
                Goal("words_100", "100 words", words.toLong(), 100),
                Goal("words_1000", "1,000 words", words.toLong(), 1_000),
                Goal("words_10000", "10,000 words", words.toLong(), 10_000),
                Goal("dictations_10", "10 dictations", ok.size.toLong(), 10),
                Goal("dictations_100", "100 dictations", ok.size.toLong(), 100),
                Goal("dictations_1000", "1,000 dictations", ok.size.toLong(), 1_000),
                Goal("streak_3", "3-day streak", bestStreak.toLong(), 3),
                Goal("streak_7", "7-day streak", bestStreak.toLong(), 7),
                Goal("streak_30", "30-day streak", bestStreak.toLong(), 30),
                Goal("spoken_1h", "1 hour spoken", ok.sumOf { it.audioMs }, HOUR_MS),
            )
            val next = goals.filter { it.current < it.target }
                .maxByOrNull { it.current.toDouble() / it.target } // first wins ties
                ?.let { NextBadge(it.id, it.label, (it.current.toDouble() / it.target).toFloat()) }

            return Engagement(
                calendar = calendar,
                currentStreak = currentStreak(counts.keys, today),
                bestStreak = bestStreak,
                badges = goals.map { Badge(it.id, it.label, it.current >= it.target) },
                next = next,
                fillersRemoved = ok.sumOf { it.fillerWords },
                novelShare = words / NOVEL_WORDS,
                typingAvoidedMs = words * TYPING_MS_PER_WORD,
            )
        }

        /** Consecutive days with a dictation, ending today, or yesterday if today has none yet. */
        private fun currentStreak(days: Set<LocalDate>, today: LocalDate): Int {
            var day = if (today in days) today else today.minusDays(1)
            var count = 0
            while (day in days) {
                count++
                day = day.minusDays(1)
            }
            return count
        }

        private fun bestStreak(days: Set<LocalDate>): Int {
            var best = 0
            var run = 0
            var previous: LocalDate? = null
            for (day in days.sorted()) {
                run = if (previous != null && day == previous.plusDays(1)) run + 1 else 1
                best = maxOf(best, run)
                previous = day
            }
            return best
        }
    }
}
