package io.github.agopalareddy.umm.settings.dashboard

import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.core.stats.DayStat
import io.github.agopalareddy.umm.core.stats.ModelSpend
import io.github.agopalareddy.umm.settings.usd
import io.github.agopalareddy.umm.ui.charts.ChartText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Words per bar: one bar per day, or one per Monday-start week. [previous] lines up with [words] bar for bar.
 * [starts] holds each bar's day, or the Monday of its week.
 */
internal class WordBars(val starts: List<LocalDate>, val words: List<Int>, val previous: List<Int>?, val weekly: Boolean)

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
        )
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
        return ChartText.peak(if (bars.weekly) "Words per week" else "Words per day", DashboardText.span(range), points) {
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
        val peak = fastest?.let { " Fastest on ${format.format(it.date)}: ${it.wpm!!.roundToInt()} wpm." }.orEmpty()
        return "Speaking pace, ${DashboardText.span(range)}$averaging.$peak"
    }

    fun spendDescription(days: List<DayStat>, range: DashboardRange, locale: Locale = Locale.getDefault()): String {
        val format = DateTimeFormatter.ofPattern("MMM d", locale)
        return ChartText.peak("Spend per day", DashboardText.span(range), days.map { format.format(it.date) to it.costUsd }) { usd(it) }
    }

    fun modelsDescription(title: String, models: List<ModelSpend>): String =
        "$title by spend: " + if (models.isEmpty()) "none." else models.joinToString(", ", postfix = ".") { "${it.model} ${usd(it.usd)}" }
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
