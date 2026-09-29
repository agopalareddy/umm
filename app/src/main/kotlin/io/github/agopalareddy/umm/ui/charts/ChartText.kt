package io.github.agopalareddy.umm.ui.charts

import kotlin.math.abs
import kotlin.math.roundToInt

/** Text the charts share: axis scale, period-over-period deltas, TalkBack sentences. */
object ChartText {
    /** Largest value, never below 1f so a chart of zeros still has a usable axis. */
    fun scaleMax(values: List<Float>): Float = maxOf(1f, values.maxOrNull() ?: 0f)

    /**
     * Change from [previous] to [current] as `"▲ 12%"` / `"▼ 8%"` (whole percent).
     * Null when there is no previous period or nothing to compare (both zero);
     * `"new"` when it grew from zero.
     */
    fun delta(current: Double, previous: Double?): String? {
        if (previous == null) return null
        if (previous == 0.0) return if (current > 0) "new" else null
        val pct = ((current - previous) / previous * 100).roundToInt()
        return if (pct >= 0) "▲ $pct%" else "▼ ${abs(pct)}%"
    }

    /**
     * One-sentence description naming the biggest point, e.g.
     * `"Words per day, last 30 days. Most on Tue: 420."`. When several points tie
     * for the largest value the first one wins. Falls back to [empty] when no
     * point is above zero.
     */
    fun peak(
        title: String,
        span: String,
        points: List<Pair<String, Double>>,
        format: (Double) -> String,
    ): String {
        var best: Pair<String, Double>? = null
        for (p in points) if (p.second > 0 && (best == null || p.second > best.second)) best = p
        val top = best ?: return empty(title)
        return "$title, $span. Most on ${top.first}: ${format(top.second)}."
    }

    fun empty(title: String): String = "$title: no data yet."
}
