package io.github.agopalareddy.umm.core.stats

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AppCount(val packageName: String, val dictations: Int)

data class LatencyPoint(val date: LocalDate, val avgMs: Long)

/**
 * When, where and how well the user dictates within a dashboard range. [hourGrid] is indexed
 * `[weekday][hour]` with Monday = 0 and the device's local hour; [successRate] counts every attempt.
 */
data class Habits(
    val hourGrid: List<List<Int>>,
    val topApps: List<AppCount>,
    val levelCounts: Map<CleanupLevel, Int>,
    val latency: List<LatencyPoint>,
    val successRate: Double?,
    val attempts: Int,
) {
    companion object {
        private const val TOP_APPS = 6

        fun from(rows: List<StatsEntry>, range: DashboardRange, nowMs: Long, zone: ZoneId): Habits {
            fun timeOf(row: StatsEntry) = Instant.ofEpochMilli(row.createdAt).atZone(zone)
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

            val window = range.window(today, rows.minOfOrNull { timeOf(it).toLocalDate() })
            val inRange = rows.filter { timeOf(it).toLocalDate() in window }
            val ok = inRange.filter { it.succeeded }

            val grid = List(7) { IntArray(24) }
            ok.forEach { row ->
                val time = timeOf(row)
                grid[time.dayOfWeek.ordinal][time.hour]++
            }

            val topApps = ok.groupingBy { it.packageName }.eachCount()
                .map { (pkg, count) -> AppCount(pkg, count) }
                .sortedWith(compareByDescending<AppCount> { it.dictations }.thenBy { it.packageName })
                .take(TOP_APPS)

            val levels = ok.groupingBy { it.level }.eachCount()
            val latency = ok.filter { it.latencyMs != null }
                .groupBy { timeOf(it).toLocalDate() }
                .toSortedMap()
                .map { (date, day) -> LatencyPoint(date, Math.round(day.sumOf { it.latencyMs!! }.toDouble() / day.size)) }

            return Habits(
                hourGrid = grid.map { it.toList() },
                topApps = topApps,
                levelCounts = CleanupLevel.entries.associateWith { levels[it] ?: 0 },
                latency = latency,
                successRate = if (inRange.isEmpty()) null else ok.size.toDouble() / inRange.size,
                attempts = inRange.size,
            )
        }
    }
}
