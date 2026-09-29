package io.github.agopalareddy.umm.core.stats

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Spend under one model; [dictations] counts every attempt that used it, failed or not. */
data class ModelSpend(val model: String, val usd: Double, val dictations: Int)

/**
 * Spend within a dashboard range. Failed attempts count toward [totalUsd] but not the
 * [perDictationUsd] divisor. Each row's full cost is attributed to the model it used in each
 * list, so each list sums to [totalUsd]. [projectedMonthUsd] ignores the range: it scales the
 * last 7 days (today included) to the current month, once at least 3 of them have rows.
 */
data class Costs(
    val totalUsd: Double,
    val perDictationUsd: Double?,
    val bySttModel: List<ModelSpend>,
    val byCleanupModel: List<ModelSpend>,
    val projectedMonthUsd: Double?,
) {
    companion object {
        private const val UNKNOWN_MODEL = "Unknown"
        private const val PROJECTION_DAYS = 7
        private const val PROJECTION_MIN_ACTIVE_DAYS = 3

        fun from(rows: List<StatsEntry>, range: DashboardRange, nowMs: Long, zone: ZoneId): Costs {
            fun dayOf(row: StatsEntry) = Instant.ofEpochMilli(row.createdAt).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

            val window = range.window(today, rows.minOfOrNull(::dayOf))
            val inRange = rows.filter { dayOf(it) in window }
            val total = inRange.sumOf { it.costUsd ?: 0.0 }
            val succeeded = inRange.count { it.succeeded }

            return Costs(
                totalUsd = total,
                perDictationUsd = if (succeeded == 0) null else total / succeeded,
                bySttModel = spendBy(inRange) { it.sttModel },
                byCleanupModel = spendBy(inRange) { it.cleanupModel },
                projectedMonthUsd = projectedMonth(rows.groupBy(::dayOf), today),
            )
        }

        private fun spendBy(rows: List<StatsEntry>, model: (StatsEntry) -> String?): List<ModelSpend> =
            rows.groupBy { model(it) ?: UNKNOWN_MODEL }
                .map { (name, group) -> ModelSpend(name, group.sumOf { it.costUsd ?: 0.0 }, group.size) }
                .sortedWith(compareByDescending<ModelSpend> { it.usd }.thenBy { it.model })

        private fun projectedMonth(byDay: Map<LocalDate, List<StatsEntry>>, today: LocalDate): Double? {
            val recent = (0 until PROJECTION_DAYS).map { byDay[today.minusDays(it.toLong())].orEmpty() }
            if (recent.count { it.isNotEmpty() } < PROJECTION_MIN_ACTIVE_DAYS) return null
            val spend = recent.sumOf { day -> day.sumOf { it.costUsd ?: 0.0 } }
            return spend / PROJECTION_DAYS * YearMonth.from(today).lengthOfMonth()
        }
    }
}
