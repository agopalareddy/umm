package io.github.agopalareddy.umm.settings.dashboard

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.settings.appLabel
import io.github.agopalareddy.umm.settings.title
import io.github.agopalareddy.umm.ui.charts.BarItem
import io.github.agopalareddy.umm.ui.charts.Donut
import io.github.agopalareddy.umm.ui.charts.DonutSlice
import io.github.agopalareddy.umm.ui.charts.HorizontalBars
import io.github.agopalareddy.umm.ui.charts.HourHeatmap
import io.github.agopalareddy.umm.ui.charts.LineChart
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fewest days of wait times a trend needs before it is worth drawing. */
private const val MIN_TREND_DAYS = 3

/** The week as an hour grid: bolder cells are hours with more dictations. */
@Composable
internal fun WhenYouDictateCard(ctx: CardContext) {
    val grid = ctx.stats.habits.hourGrid
    if (grid.none { day -> day.any { it > 0 } }) return NothingHere("No dictations in this range.")
    val clock24 = DateFormat.is24HourFormat(LocalContext.current)
    HourHeatmap(grid, remember(grid, ctx.range, clock24) { Series.hourDescription(grid, ctx.range, clock24) })
    Footnote("Dictations by hour and weekday, in your local time. Bolder cells are busier.", Modifier.padding(top = 8.dp))
}

/** The apps dictated into most, each with the category it is filed under. */
@Composable
internal fun WhereYouDictateCard(ctx: CardContext) {
    val apps = ctx.stats.habits.topApps
    if (apps.isEmpty()) return NothingHere("No dictations in this range.")
    val context = LocalContext.current
    val categories = context.graph.categories
    // The lookup is a database read, so the rows show without a category until it lands.
    val categoryOf by produceState(emptyMap<String, Category>(), apps) {
        value = apps.associate { it.packageName to categories.configFor(it.packageName).category }
    }
    val labels = remember(apps) { apps.map { appLabel(context, it.packageName) } }
    val items = apps.mapIndexed { i, app ->
        BarItem(
            label = labels[i],
            value = app.dictations.toFloat(),
            valueText = DashboardText.dictations(app.dictations),
            sublabel = categoryOf[app.packageName]?.title(),
        )
    }
    val description = Series.appsDescription(
        apps.mapIndexed { i, app -> AppUse(labels[i], categoryOf[app.packageName]?.title(), app.dictations) },
        ctx.range,
    )
    // The rows and bars are announced once, as the sentence, instead of piece by piece.
    Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
        HorizontalBars(items, description)
    }
    Footnote("Most dictations first. Each app shows its category.", Modifier.padding(top = 12.dp))
}

/** How many dictations used each cleanup level. */
@Composable
internal fun CleanupLevelsCard(ctx: CardContext) {
    val counts = ctx.stats.habits.levelCounts
    if (counts.values.all { it <= 0 }) return NothingHere("No dictations in this range.")
    // All four levels go in, so each keeps its color; the donut leaves out the ones with none.
    val levels = remember(counts) { CleanupLevel.entries.map { it.title() to (counts[it] ?: 0) } }
    val description = Series.levelsDescription(levels, ctx.range)
    // The ring and its legend are announced once, as the sentence.
    Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Donut(levels.map { DonutSlice(it.first, it.second.toFloat()) }, description)
    }
    Footnote("Dictations by cleanup level.", Modifier.padding(top = 8.dp))
}

/** The share of attempts that succeeded, then how long each dictation waited, day by day. */
@Composable
internal fun ReliabilityCard(ctx: CardContext) {
    val habits = ctx.stats.habits
    val rate = habits.successRate ?: return NothingHere("No dictations in this range.")
    Fact("Success rate", Series.percent(rate))
    Footnote("Of ${DashboardText.count(habits.attempts)} ${if (habits.attempts == 1) "attempt" else "attempts"}, failed ones included.")
    Spacer(Modifier.height(12.dp))
    val latency = habits.latency
    if (latency.size < MIN_TREND_DAYS) return NeedsMoreDays()
    val labels = remember(latency, ctx.range) {
        val format = DateTimeFormatter.ofPattern(if (ctx.range == DashboardRange.D7) "EEE" else "MMM d", Locale.getDefault())
        latency.map { format.format(it.date) }
    }
    LineChart(
        values = latency.map { it.avgMs / 1000f },
        labels = labels,
        description = Series.latencyDescription(latency, ctx.range),
        valueLabel = "Slowest ${DashboardText.wait(latency.maxOf { it.avgMs })}",
    )
    Footnote("Average wait per dictation each day, in seconds.", Modifier.padding(top = 8.dp))
}
