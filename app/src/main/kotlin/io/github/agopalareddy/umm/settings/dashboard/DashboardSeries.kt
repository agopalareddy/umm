package io.github.agopalareddy.umm.settings.dashboard

import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.stats.CalendarDay
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.core.stats.DayStat
import io.github.agopalareddy.umm.core.stats.LatencyPoint
import io.github.agopalareddy.umm.core.stats.ModelSpend
import io.github.agopalareddy.umm.settings.usd
import io.github.agopalareddy.umm.ui.charts.ChartText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Words per bar: one bar per day, or one per Monday-start week. [previous] lines up with [words] bar for bar.
 * [starts] holds each bar's day, or the Monday of its week. [partial] marks the weeks the range cuts short (the
 * first, and the latest while it is still under way); it is all false for daily bars.
 */
internal class WordBars(
    val starts: List<LocalDate>,
    val words: List<Int>,
    val previous: List<Int>?,
    val weekly: Boolean,
    val partial: List<Boolean>,
)

/** An app in the top-apps list: its label, the title of its category when known, and its dictation count. */
internal class AppUse(val label: String, val category: String?, val dictations: Int)

/** The numbers and TalkBack sentences behind the productivity and cost cards. */
internal object Series {
    /** Index ranges of [days] grouped into weeks that start on Monday; the first and last week may be partial. */
    fun weeks(days: List<DayStat>): List<IntRange> {
        val weeks = mutableListOf<IntRange>()
        var from = 0
        for (i in 1..days.size) {
            if (i == days.size || days[i].date.dayOfWeek == DayOfWeek.MONDAY) {
                weeks += from until i
                from = i
            }
        }
        return if (days.isEmpty()) emptyList() else weeks
    }

    /**
     * Words per day, or per week when [weekly]. A week's previous total sums the earlier days at the same
     * positions, so a partial first week is compared with as many earlier days.
     */
    fun wordBars(days: List<DayStat>, previous: List<DayStat>?, weekly: Boolean): WordBars {
        val groups = if (weekly) weeks(days) else days.indices.map { it..it }
        val before = previous?.takeIf { it.size == days.size }
        return WordBars(
            starts = groups.map { g ->
                val first = days[g.first].date
                if (weekly) first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else first
            },
            words = groups.map { g -> g.sumOf { days[it].words } },
            previous = before?.let { b -> groups.map { g -> g.sumOf { b[it].words } } },
            weekly = weekly,
            // A Monday-to-Sunday week always spans 7 days, so a shorter group is cut off by an end of the range.
            partial = groups.map { weekly && it.count() < 7 },
        )
    }

    /** What to add to the words footnote about outlined bars; null when every bar is a whole week. */
    fun partialNote(bars: WordBars): String? {
        if (bars.partial.none { it }) return null
        return "Outlined bars are partial weeks." + if (bars.partial.last()) " Latest week is still in progress." else ""
    }

    fun cumulative(values: List<Long>): List<Long> {
        var sum = 0L
        return values.map { sum += it; sum }
    }

    /** Days that have a speaking pace at all. */
    fun paceDays(days: List<DayStat>): Int = days.count { it.wpm != null }

    fun wordsDescription(bars: WordBars, range: DashboardRange, locale: Locale = Locale.getDefault()): String {
        val format = DateTimeFormatter.ofPattern("MMM d", locale)
        val points = bars.starts.zip(bars.words) { start, words ->
            (if (bars.weekly) "the week of " else "") + format.format(start) to words.toDouble()
        }
        val tail = if (bars.partial.lastOrNull() == true) ", latest week still in progress" else ""
        return ChartText.peak(if (bars.weekly) "Words per week" else "Words per day", DashboardText.span(range), points, tail) {
            val n = it.toInt()
            if (n == 1) "1 word" else "${DashboardText.count(n)} words"
        }
    }

    fun timeSavedDescription(days: List<DayStat>, range: DashboardRange): String =
        "Time saved, ${DashboardText.span(range)}, adding up to ${DashboardText.duration(days.sumOf { it.timeSavedMs })}."

    fun paceDescription(days: List<DayStat>, average: Double?, range: DashboardRange, locale: Locale = Locale.getDefault()): String {
        val format = DateTimeFormatter.ofPattern("MMM d", locale)
        val fastest = days.filter { it.wpm != null }.maxByOrNull { it.wpm!! }
        val averaging = average?.let { ", averaging ${it.roundToInt()} wpm" }.orEmpty()
        val peak = fastest?.let { ", fastest on ${format.format(it.date)}: ${it.wpm!!.roundToInt()} wpm" }.orEmpty()
        return "Speaking pace, ${DashboardText.span(range)}$averaging$peak."
    }

    /** The busiest of the last 12 weeks' days, and on how many of them anything was dictated, in one sentence. */
    fun calendarDescription(days: List<CalendarDay>, locale: Locale = Locale.getDefault()): String {
        val format = DateTimeFormatter.ofPattern("MMM d", locale)
        val active = days.count { it.dictations > 0 }
        return ChartText.peak(
            "Dictations per day", "last 12 weeks", days.map { format.format(it.date) to it.dictations.toDouble() },
            tail = ", dictated on $active of ${days.size} days",
        ) { DashboardText.count(it.toInt()) }
    }

    fun spendDescription(days: List<DayStat>, range: DashboardRange, locale: Locale = Locale.getDefault()): String {
        val format = DateTimeFormatter.ofPattern("MMM d", locale)
        return ChartText.peak("Spend per day", DashboardText.span(range), days.map { format.format(it.date) to it.costUsd }) { usd(it) }
    }

    fun modelsDescription(title: String, models: List<ModelSpend>): String =
        "$title by spend: " + if (models.isEmpty()) "none." else models.joinToString(", ", postfix = ".") {
            "${it.model} ${usd(it.usd)} (${DashboardText.dictations(it.dictations)})"
        }

    /** The busiest weekday and hour of [grid] (`[weekday][hour]`, Monday first); a missing cell counts as zero. */
    fun hourDescription(grid: List<List<Int>>, range: DashboardRange, clock24: Boolean, locale: Locale = Locale.getDefault()): String {
        val points = (0 until 7).flatMap { day ->
            val name = DayOfWeek.of(day + 1).getDisplayName(TextStyle.FULL, locale)
            (0 until 24).map { hour -> "$name at ${spokenHour(hour, clock24)}" to (grid.getOrNull(day)?.getOrNull(hour) ?: 0).toDouble() }
        }
        return ChartText.peak("Dictations by hour", DashboardText.span(range), points) { DashboardText.dictations(it.toInt()) }
    }

    /** `14:00` on a 24-hour clock, `2 pm` otherwise. */
    private fun spokenHour(hour: Int, clock24: Boolean): String = when {
        clock24 -> "%02d:00".format(Locale.ROOT, hour)
        hour == 0 -> "12 am"
        hour < 12 -> "$hour am"
        hour == 12 -> "12 pm"
        else -> "${hour - 12} pm"
    }

    /** [apps] are most first; an app with a known category reads as `Gmail (Work) 12 dictations`. */
    fun appsDescription(apps: List<AppUse>, range: DashboardRange): String {
        if (apps.isEmpty()) return ChartText.empty("Top apps")
        return "Top apps, ${DashboardText.span(range)}: " + apps.joinToString(", ", postfix = ".") {
            val category = it.category?.let { c -> " ($c)" }.orEmpty()
            "${it.label}$category ${DashboardText.dictations(it.dictations)}"
        }
    }

    /** [levels] are label and dictation count pairs; levels with no dictations are left out. */
    fun levelsDescription(levels: List<Pair<String, Int>>, range: DashboardRange): String {
        val used = levels.filter { it.second > 0 }
        val total = used.sumOf { it.second }
        if (total == 0) return ChartText.empty("Cleanup levels")
        val parts = used.joinToString(", ", postfix = ".") { (label, n) -> "$label ${DashboardText.count(n)} (${share(n.toDouble() / total)})" }
        return "Cleanup levels, ${DashboardText.span(range)}, by dictations: $parts"
    }

    /** Whole percent, but never `0%` for a share above zero (`<1%`) nor `100%` while anything is left over (`99%`). */
    private fun share(fraction: Double): String {
        val pct = Math.round(fraction * 100)
        return when {
            fraction > 0 && pct == 0L -> "<1%"
            fraction < 1 && pct == 100L -> "99%"
            else -> "$pct%"
        }
    }

    /** A success rate (0..1) as text. */
    fun percent(rate: Double): String = share(rate)

    fun latencyDescription(points: List<LatencyPoint>, range: DashboardRange, locale: Locale = Locale.getDefault()): String {
        if (points.isEmpty()) return ChartText.empty("Wait time")
        val format = DateTimeFormatter.ofPattern("MMM d", locale)
        val slowest = points.maxBy { it.avgMs }
        val fastest = points.minOf { it.avgMs }
        val spread = if (fastest == slowest.avgMs) DashboardText.wait(fastest) else "from ${DashboardText.wait(fastest)} to ${DashboardText.wait(slowest.avgMs)}"
        return "Wait time, ${DashboardText.span(range)}, $spread, slowest on ${format.format(slowest.date)}: ${DashboardText.wait(slowest.avgMs)}."
    }
}

/** A key's spending limit and how much of it is used, from what OpenRouter reports. */
internal class BudgetUse private constructor(val limit: Double, val used: Double?, val reset: String?) {
    /** Share of the limit used, clamped to 0..1; null when the use is unknown. */
    val fraction: Float? get() = used?.let { (it / limit).toFloat().coerceIn(0f, 1f) }

    private val remaining: Double? get() = used?.let { limit - it }

    private val resets: String get() = reset?.let { ", resets $it" }.orEmpty()

    val usedLine: String get() = if (used == null) "Limit ${usd(limit)}$resets" else "${usd(used)} of ${usd(limit)} used"

    val leftLine: String?
        get() = remaining?.let { left ->
            (if (left >= 0) "${usd(left)} left" else "${usd(-left)} over the limit") + resets
        }

    /** One sentence for TalkBack, covering the text and the bar. */
    val spoken: String get() = "Budget: $usedLine" + leftLine?.let { ", $it" }.orEmpty() + "."

    companion object {
        /**
         * Used is the limit less what remains, else this month's usage. Null when the key has no limit (missing
         * or not above zero).
         */
        fun of(info: KeyInfo): BudgetUse? {
            val limit = info.limitUsd?.takeIf { it > 0.0 } ?: return null
            val used = info.limitRemainingUsd?.let { (limit - it).coerceAtLeast(0.0) } ?: info.usageMonthlyUsd
            return BudgetUse(limit, used, info.limitReset)
        }
    }
}
