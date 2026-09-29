package io.github.agopalareddy.umm.core.stats

import java.time.ZoneId

/** Everything the stats dashboard draws for one [range]. Engagement is lifetime; the rest follow the range. */
data class DashboardStats(
    val range: DashboardRange,
    /** False until at least one dictation has succeeded; the dashboard shows an empty state instead. */
    val hasData: Boolean,
    val period: PeriodStats,
    val engagement: Engagement,
    val habits: Habits,
    val costs: Costs,
) {
    companion object {
        fun from(rows: List<StatsEntry>, range: DashboardRange, nowMs: Long, zone: ZoneId) = DashboardStats(
            range = range,
            hasData = rows.any { it.succeeded },
            period = PeriodStats.from(rows, range, nowMs, zone),
            engagement = Engagement.from(rows, nowMs, zone),
            habits = Habits.from(rows, range, nowMs, zone),
            costs = Costs.from(rows, range, nowMs, zone),
        )
    }
}
