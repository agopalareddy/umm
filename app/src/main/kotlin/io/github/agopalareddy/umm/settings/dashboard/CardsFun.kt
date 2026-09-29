package io.github.agopalareddy.umm.settings.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.stats.CalendarDay
import io.github.agopalareddy.umm.ui.charts.CalendarHeatmap
import io.github.agopalareddy.umm.ui.charts.ChartText
import io.github.agopalareddy.umm.ui.charts.ProgressBar
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Words, time saved and dictations for the range, each against the period before it (none for all time). */
@Composable
internal fun SummaryCard(ctx: CardContext) {
    val now = ctx.stats.period.current
    val before = ctx.stats.period.previous
    val compared = before != null
    BoxWithConstraints {
        // Three figures side by side: on a narrow card a smaller size keeps "30 min 27 s" on one line.
        val style = if (maxWidth < 330.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure(
                "Words", DashboardText.count(now.words), compared,
                ChartText.delta(now.words.toDouble(), before?.words?.toDouble()), Modifier.weight(1f), style,
            )
            Figure(
                "Time saved", DashboardText.duration(now.timeSavedMs), compared,
                ChartText.delta(now.timeSavedMs.toDouble(), before?.timeSavedMs?.toDouble()), Modifier.weight(1.3f), style,
            )
            Figure(
                "Dictations", DashboardText.count(now.dictations), compared,
                ChartText.delta(now.dictations.toDouble(), before?.dictations?.toDouble()), Modifier.weight(1f), style,
            )
        }
    }
    val compare = DashboardText.previous(ctx.range)?.let { "Compared with $it. " }.orEmpty()
    Footnote("${compare}Time saved assumes typing at 40 wpm.", Modifier.padding(top = 12.dp))
}

/** Current and best streak, then the last 12 weeks as a calendar. Ignores the range. */
@Composable
internal fun StreakCard(ctx: CardContext) {
    val e = ctx.stats.engagement
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Figure("Current streak", DashboardText.days(e.currentStreak), modifier = Modifier.weight(1f))
        Figure("Best streak", DashboardText.days(e.bestStreak), modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    if (e.calendar.none { it.dictations > 0 }) NeedsMoreDays() else CalendarHeatmap(e.calendar, calendarDescription(e.calendar))
    Footnote("Last 12 weeks, whatever the range.", Modifier.padding(top = 8.dp))
}

private fun calendarDescription(days: List<CalendarDay>): String {
    val format = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    val peak = ChartText.peak("Dictations per day", "last 12 weeks", days.map { format.format(it.date) to it.dictations.toDouble() }) {
        DashboardText.count(it.toInt())
    }
    return "$peak Dictated on ${days.count { it.dictations > 0 }} of ${days.size} days."
}

/** Earned badges as chips, then progress toward the closest one not yet earned. Lifetime. */
@Composable
internal fun MilestonesCard(ctx: CardContext) {
    val e = ctx.stats.engagement
    val earned = e.badges.filter { it.earned }
    Text("${earned.size} of ${e.badges.size} earned", style = MaterialTheme.typography.bodyMedium)
    if (earned.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics {
                contentDescription = "Earned: " + earned.joinToString { it.label }
            },
        ) { earned.forEach { BadgeChip(it.label) } }
    }
    val next = e.next
    if (next == null) {
        Text("Every badge earned.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        return
    }
    // Never shows 100% for a badge that is not earned yet.
    val percent = (next.progress * 100).toInt().coerceIn(0, 99)
    val spoken = "Next badge: ${next.label}, $percent percent there"
    // One announcement for the label, the percent and the bar, instead of three.
    Column(Modifier.padding(top = 16.dp).clearAndSetSemantics { contentDescription = spoken }) {
        Row(Modifier.fillMaxWidth()) {
            Text("Next: ${next.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$percent%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        ProgressBar(next.progress, spoken)
    }
}

@Composable
private fun BadgeChip(label: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Umms removed, words as a share of a novel, and typing time avoided. Lifetime. */
@Composable
internal fun FunFactsCard(ctx: CardContext) {
    val e = ctx.stats.engagement
    Fact("Umms removed", DashboardText.count(e.fillersRemoved))
    Fact("Words dictated", DashboardText.novelShare(e.novelShare))
    Fact("Typing time avoided", DashboardText.duration(e.typingAvoidedMs))
    Footnote("All time. Typing at 40 wpm; a novel is 80,000 words.", Modifier.padding(top = 8.dp))
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A big [value] over its [label]. When [compared], a third line holds [delta] (blank when there is nothing to
 * compare, so the figures stay aligned). TalkBack reads it as one item with the delta in words.
 */
@Composable
private fun Figure(
    label: String,
    value: String,
    compared: Boolean = false,
    delta: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
) {
    val spoken = listOfNotNull("$label: $value", delta?.let(DashboardText::spokenDelta)).joinToString(", ")
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Text(value, style = style)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (compared) {
            val down = delta?.startsWith("▼") == true
            Text(
                delta.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = if (down) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
