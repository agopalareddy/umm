package io.github.agopalareddy.umm.ui.charts

import kotlin.math.abs
import kotlin.math.floor

/** Text the charts share: axis scale, period-over-period deltas, TalkBack sentences. */
object ChartText {
    /** Largest value, never below 1f so a chart of zeros still has a usable axis. */
    fun scaleMax(values: List<Float>): Float =
        maxOf(1f, values.filter { it.isFinite() }.maxOrNull() ?: 0f)

    /**
     * Change from [previous] to [current] as `"▲ 12%"` / `"▼ 8%"` (whole percent,
     * half rounds away from zero, magnitudes above 999% print as `"▲ 999%+"`).
     * Null when there is no previous period, either value is not finite, or there
     * is nothing to compare (both zero); `"new"` when it grew from zero.
     */
    fun delta(current: Double, previous: Double?): String? {
        if (previous == null || !current.isFinite() || !previous.isFinite()) return null
        if (previous == 0.0) return if (current > 0) "new" else null
        val change = (current - previous) / previous * 100
        val pct = floor(abs(change) + 0.5)
        val text = if (pct > 999) "999%+" else "${pct.toInt()}%"
        return if (change < 0 && pct > 0) "▼ $text" else "▲ $text"
    }

    /**
     * One-sentence description naming the biggest point, e.g.
     * `"Words per day, last 30 days, most on Tue: 420."`. When several points tie
     * for the largest value the first one wins. Non-finite points are skipped.
     * [tail] is an extra clause (with its own leading comma) before the closing
     * period. Falls back to [empty] when no point is above zero.
     */
    fun peak(
        title: String,
        span: String,
        points: List<Pair<String, Double>>,
        tail: String = "",
        format: (Double) -> String,
    ): String {
        var best: Pair<String, Double>? = null
        for (p in points) if (p.second.isFinite() && p.second > 0 && (best == null || p.second > best.second)) best = p
        val top = best ?: return empty(title)
        return "$title, $span, most on ${top.first}: ${format(top.second)}$tail."
    }

    fun empty(title: String): String = "$title: no data yet."
}
