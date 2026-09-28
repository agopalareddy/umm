package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Lifetime usage numbers for the home screen. Counts only successful dictations unless noted. */
data class UsageSummary(
    val dictations: Int,
    val words: Int,
    val spokenMs: Long,
    val dictationsThisWeek: Int,
    val fillersRemoved: Int,
    /** Typing the same words at 40 wpm, minus the time spent speaking and waiting. */
    val timeSavedMs: Long,
    val longestWords: Int,
    val topApp: String?,
    val streakDays: Int,
    /** Includes failed attempts, which can still cost money. */
    val totalCostUsd: Double,
    val averageCostUsd: Double?,
    val averageLatencyMs: Long?,
    /** Successful dictations over all attempts that reached OpenRouter. */
    val successRate: Double,
    val levelCounts: Map<CleanupLevel, Int>,
) {
    companion object {
        private const val TYPING_MS_PER_WORD = 60_000L / 40

        fun from(rows: List<StatsEntry>, nowMs: Long, zone: ZoneId): UsageSummary {
            val ok = rows.filter { it.succeeded }
            fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val today = day(nowMs)
            val words = ok.sumOf { it.cleanWords }
            val spokenMs = ok.sumOf { it.audioMs }
            val waitedMs = ok.sumOf { it.latencyMs ?: 0 }
            val totalCost = rows.sumOf { it.costUsd ?: 0.0 }
            val latencies = ok.mapNotNull { it.latencyMs }
            return UsageSummary(
                dictations = ok.size,
                words = words,
                spokenMs = spokenMs,
                dictationsThisWeek = ok.count { !day(it.createdAt).isBefore(today.minusDays(6)) },
                fillersRemoved = ok.sumOf { it.fillerWords },
                timeSavedMs = (words * TYPING_MS_PER_WORD - spokenMs - waitedMs).coerceAtLeast(0),
                longestWords = ok.maxOfOrNull { it.cleanWords } ?: 0,
                topApp = ok.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }?.key,
                streakDays = streak(ok.map { day(it.createdAt) }.toSet(), today),
                totalCostUsd = totalCost,
                averageCostUsd = if (ok.isEmpty()) null else totalCost / ok.size,
                averageLatencyMs = if (latencies.isEmpty()) null else latencies.sum() / latencies.size,
                successRate = if (rows.isEmpty()) 0.0 else ok.size.toDouble() / rows.size,
                levelCounts = ok.groupingBy { it.level }.eachCount(),
            )
        }

        /** Consecutive days with a dictation, ending today, or yesterday if today has none yet. */
        private fun streak(days: Set<LocalDate>, today: LocalDate): Int {
            var day = if (today in days) today else today.minusDays(1)
            var count = 0
            while (day in days) {
                count++
                day = day.minusDays(1)
            }
            return count
        }
    }
}
