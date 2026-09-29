package io.github.agopalareddy.umm.core.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Totals(val dictations: Int, val words: Int, val timeSavedMs: Long, val costUsd: Double)

data class DayStat(
    val date: LocalDate,
    val dictations: Int,
    val words: Int,
    val timeSavedMs: Long,
    val costUsd: Double,
    val wpm: Double?,
)

/** Totals for a dashboard range, the equal window before it, and one zero-filled bucket per day. */
data class PeriodStats(val current: Totals, val previous: Totals?, val days: List<DayStat>, val averageWpm: Double?) {
    companion object {
        private const val TYPING_MS_PER_WORD = 60_000L / 40
        private const val MIN_PACE_AUDIO_MS = 2_000L

        fun from(rows: List<StatsEntry>, range: DashboardRange, nowMs: Long, zone: ZoneId): PeriodStats {
            fun dayOf(row: StatsEntry) = Instant.ofEpochMilli(row.createdAt).atZone(zone).toLocalDate()
            val byDay = rows.groupBy(::dayOf)
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

            val window = range.window(today, byDay.keys.minOrNull())
            val days = window.dates().map { dayStat(it, byDay[it].orEmpty()) }
            val previous = range.previousWindow(today)?.let { prev ->
                totals(prev.dates().map { dayStat(it, byDay[it].orEmpty()) })
            }
            val paced = window.dates().flatMap { byDay[it].orEmpty() }.filter(::isPaced)
            return PeriodStats(totals(days), previous, days, wpm(paced))
        }

        private fun ClosedRange<LocalDate>.dates(): List<LocalDate> =
            generateSequence(start) { it.plusDays(1) }.takeWhile { it <= endInclusive }.toList()

        private fun isPaced(row: StatsEntry) = row.succeeded && row.audioMs >= MIN_PACE_AUDIO_MS

        private fun wpm(paced: List<StatsEntry>): Double? {
            val audioMs = paced.sumOf { it.audioMs }
            return if (audioMs <= 0) null else paced.sumOf { it.rawWords } / (audioMs / 60_000.0)
        }

        private fun dayStat(date: LocalDate, rows: List<StatsEntry>): DayStat {
            val ok = rows.filter { it.succeeded }
            val words = ok.sumOf { it.cleanWords }
            val savedMs = words * TYPING_MS_PER_WORD - ok.sumOf { it.audioMs } - ok.sumOf { it.latencyMs ?: 0 }
            return DayStat(
                date = date,
                dictations = ok.size,
                words = words,
                timeSavedMs = savedMs.coerceAtLeast(0),
                costUsd = rows.sumOf { it.costUsd ?: 0.0 },
                wpm = wpm(ok.filter(::isPaced)),
            )
        }

        private fun totals(days: List<DayStat>) = Totals(
            dictations = days.sumOf { it.dictations },
            words = days.sumOf { it.words },
            timeSavedMs = days.sumOf { it.timeSavedMs },
            costUsd = days.sumOf { it.costUsd },
        )
    }
}
