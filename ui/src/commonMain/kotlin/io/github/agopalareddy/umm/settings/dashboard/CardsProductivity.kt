package io.github.agopalareddy.umm.settings.dashboard

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.ui.charts.BarChart
import io.github.agopalareddy.umm.ui.charts.LineChart
import io.github.agopalareddy.umm.ui.charts.compact
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Fewest days a line or trend needs before it is worth drawing. */
private const val MIN_LINE_DAYS = 3

/** Shown in place of a chart when the range holds nothing to draw; the card keeps its title. */
@Composable
fun NothingHere(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Words per day (per Monday-start week for 90 days and All), with the previous period faint behind. */
@Composable
fun WordsPerDayCard(ctx: CardContext) {
    val period = ctx.stats.period
    if (period.current.words == 0) return NothingHere("No words in this range.")
    val weekly = ctx.range == DashboardRange.D90 || ctx.range == DashboardRange.ALL
    val bars = remember(period, weekly) { Series.wordBars(period.days, period.previousDays, weekly) }
    val labels = remember(bars, ctx.range) {
        val format = DateTimeFormatter.ofPattern(if (ctx.range == DashboardRange.D7) "EEE" else "MMM d", Locale.getDefault())
        bars.starts.map(format::format)
    }
    val words = period.current.words
    Fact("Total", "${DashboardText.count(words)} ${if (words == 1) "word" else "words"}")
    Spacer(Modifier.height(8.dp))
    BarChart(
        values = bars.words.map { it.toFloat() },
        labels = labels,
        description = Series.wordsDescription(bars, ctx.range),
        previous = bars.previous?.map { it.toFloat() },
        partial = bars.partial,
        peakLabel = { value -> compact(value).let { if (it == "1") "1 word" else "$it words" } },
    )
    val notes = listOfNotNull(
        if (weekly) "Weekly totals; weeks start on Monday." else null,
        Series.partialNote(bars),
        DashboardText.previous(ctx.range)?.let { "Faint bars show $it." },
    )
    if (notes.isNotEmpty()) Footnote(notes.joinToString(" "), Modifier.padding(top = 8.dp))
}

/** Time saved, adding up day by day across the range. */
@Composable
fun TimeSavedCard(ctx: CardContext) {
    val period = ctx.stats.period
    if (period.days.size < MIN_LINE_DAYS) return NeedsMoreDays()
    if (period.current.timeSavedMs == 0L) return NothingHere("No time saved in this range.")
    val running = remember(period) { Series.cumulative(period.days.map { it.timeSavedMs }) }
    val labels = rememberDayLabels(ctx)
    Fact("Total saved", DashboardText.duration(period.current.timeSavedMs))
    Spacer(Modifier.height(8.dp))
    LineChart(
        values = running.map { it / 60_000f },
        labels = labels,
        description = Series.timeSavedDescription(period.days, ctx.range),
        valueLabel = "Total ${DashboardText.duration(period.current.timeSavedMs)}",
    )
    Footnote("Running total, in minutes. Typing at 40 wpm, minus the time spent speaking and waiting.", Modifier.padding(top = 8.dp))
}

/** Words per minute over time, with the average. Only dictations with 2 s or more of audio count. */
@Composable
fun SpeakingPaceCard(ctx: CardContext) {
    val period = ctx.stats.period
    val average = period.averageWpm
    if (average == null || Series.paceDays(period.days) < MIN_LINE_DAYS) return NeedsMoreDays()
    val labels = rememberDayLabels(ctx)
    Fact("Average", "${average.roundToInt()} wpm")
    Spacer(Modifier.height(8.dp))
    LineChart(
        values = period.days.map { it.wpm?.toFloat() },
        labels = labels,
        description = Series.paceDescription(period.days, average, ctx.range),
        valueLabel = "Peak ${period.days.maxOf { it.wpm ?: 0.0 }.roundToInt()} wpm",
    )
    Footnote("Words per minute of speech; dictations under 2 s of audio are left out.", Modifier.padding(top = 8.dp))
}

@Composable
private fun rememberDayLabels(ctx: CardContext): List<String> = remember(ctx.stats.period, ctx.range) {
    val format = DateTimeFormatter.ofPattern(if (ctx.range == DashboardRange.D7) "EEE" else "MMM d", Locale.getDefault())
    ctx.stats.period.days.map { format.format(it.date) }
}
