package io.github.agopalareddy.umm.settings.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.core.stats.ModelSpend
import io.github.agopalareddy.umm.settings.KeyCheck
import io.github.agopalareddy.umm.settings.rememberKeyCheck
import io.github.agopalareddy.umm.settings.usd
import io.github.agopalareddy.umm.ui.charts.BarChart
import io.github.agopalareddy.umm.ui.charts.BarItem
import io.github.agopalareddy.umm.ui.charts.HorizontalBars
import io.github.agopalareddy.umm.ui.charts.ProgressBar
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dollars per day, then the range's total, the cost of one dictation, and the projected month. */
@Composable
fun SpendPerDayCard(ctx: CardContext) {
    val costs = ctx.stats.costs
    if (costs.totalUsd <= 0.0) return NothingHere("No spend in this range.")
    val days = ctx.stats.period.days
    val labels = remember(days, ctx.range) {
        val format = DateTimeFormatter.ofPattern(if (ctx.range == DashboardRange.D7) "EEE" else "MMM d", Locale.getDefault())
        days.map { format.format(it.date) }
    }
    Fact("Total", usd(costs.totalUsd))
    costs.perDictationUsd?.let { Fact("Per dictation", usd(it)) }
    // Only with enough recent days; it also ignores the range.
    costs.projectedMonthUsd?.let { Fact("Projected this month", usd(it)) }
    Spacer(Modifier.height(8.dp))
    BarChart(
        values = days.map { it.costUsd.toFloat() },
        labels = labels,
        description = Series.spendDescription(days, ctx.range),
        peakLabel = { usd(it.toDouble()) },
    )
    Footnote("Includes failed attempts, which can still be billed.", Modifier.padding(top = 8.dp))
}

/** Spend under each speech model and each cleanup model, biggest first. */
@Composable
fun SpendByModelCard(ctx: CardContext) {
    val costs = ctx.stats.costs
    if (costs.totalUsd <= 0.0) return NothingHere("No spend in this range.")
    ModelList("Speech models", costs.bySttModel)
    Spacer(Modifier.height(16.dp))
    ModelList("Cleanup models", costs.byCleanupModel)
    Footnote("Each dictation's full cost counts under the model it used.", Modifier.padding(top = 12.dp))
}

@Composable
private fun ModelList(title: String, models: List<ModelSpend>) {
    val description = Series.modelsDescription(title, models)
    val items = remember(models) {
        models.map {
            BarItem(
                label = it.model,
                value = it.usd.toFloat(),
                valueText = usd(it.usd),
                sublabel = if (it.dictations == 1) "1 dictation" else "${DashboardText.count(it.dictations)} dictations",
            )
        }
    }
    // The title, rows and bars are announced once, as the sentence, instead of piece by piece.
    Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        if (items.isEmpty()) Footnote("None in this range.") else HorizontalBars(items, description)
    }
}

/**
 * How much of the key's OpenRouter spending limit is used. The lookup only runs while this card is composed,
 * so a hidden card costs no network call.
 */
@Composable
fun BudgetCard(ctx: CardContext) {
    val check = rememberKeyCheck(ctx.key)
    when {
        ctx.key == null -> NothingHere("Connect your OpenRouter account in Settings to see its spending limit.")
        check == KeyCheck.Checking -> NothingHere("Checking your key…")
        check == KeyCheck.Rejected -> NothingHere("OpenRouter rejected this key, so its limit can't be shown. Sign in again in Settings.")
        check == KeyCheck.Unreachable -> NothingHere("Couldn't reach OpenRouter to read the limit.")
        check is KeyCheck.Ok -> {
            val budget = BudgetUse.of(check.info)
            if (budget == null) {
                NothingHere("This key has no spending limit, so there is nothing to measure. Set one on OpenRouter to track it here.")
                return
            }
            val spoken = budget.spoken
            // One announcement for the lines and the bar, instead of three.
            Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
                Text(budget.usedLine, style = MaterialTheme.typography.titleMedium)
                budget.fraction?.let { ProgressBar(it, spoken, Modifier.padding(vertical = 8.dp)) }
                budget.leftLine?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Footnote("The spending limit on your OpenRouter key.", Modifier.padding(top = 8.dp))
        }
    }
}
