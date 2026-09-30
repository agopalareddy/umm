package io.github.agopalareddy.umm.settings.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.core.stats.DashboardStats
import java.util.Locale

/** What a card draws from: the stats for [range], and the OpenRouter [key] for cards that look up the account. */
internal class CardContext(val stats: DashboardStats, val range: DashboardRange, val key: String?)

internal class CardSpec(val id: String, val title: String, val content: @Composable (CardContext) -> Unit)

/** Every dashboard card by ID; the IDs match `DashboardLayout.DEFAULT_ORDER`. */
internal object Cards {
    val byId: Map<String, CardSpec> = listOf(
        CardSpec("summary", "Summary") { SummaryCard(it) },
        CardSpec("streak_calendar", "Streak") { StreakCard(it) },
        CardSpec("milestones", "Milestones") { MilestonesCard(it) },
        CardSpec("fun_facts", "Fun facts") { FunFactsCard(it) },
        CardSpec("words_per_day", "Words per day") { WordsPerDayCard(it) },
        CardSpec("time_saved", "Time saved") { TimeSavedCard(it) },
        CardSpec("speaking_pace", "Speaking pace") { SpeakingPaceCard(it) },
        CardSpec("spend_per_day", "Spend per day") { SpendPerDayCard(it) },
        CardSpec("spend_by_model", "Spend by model") { SpendByModelCard(it) },
        CardSpec("budget", "Budget") { BudgetCard(it) },
        CardSpec("when_you_dictate", "When you dictate") { WhenYouDictateCard(it) },
        CardSpec("where_you_dictate", "Where you dictate") { WhereYouDictateCard(it) },
        CardSpec("cleanup_levels", "Cleanup levels") { CleanupLevelsCard(it) },
        CardSpec("reliability", "Reliability") { ReliabilityCard(it) },
    ).associateBy { it.id }
}

/** Shown in place of a chart that has too little data to be worth drawing; the card keeps its title. */
@Composable
internal fun NeedsMoreDays() {
    Text("Needs a few more days", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A small explanatory line under a card's figures. */
@Composable
internal fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Number, time and comparison wording shared by the cards. */
internal object DashboardText {
    fun count(n: Int): String = String.format(Locale.US, "%,d", n)

    fun days(n: Int): String = if (n == 1) "1 day" else "${count(n)} days"

    fun dictations(n: Int): String = if (n == 1) "1 dictation" else "${count(n)} dictations"

    /** A wait as `"900 ms"` under a second, else seconds with one decimal (`"1.8 s"`); negative counts as zero. */
    fun wait(ms: Long): String = when {
        ms < 1000 -> "${ms.coerceAtLeast(0)} ms"
        else -> String.format(Locale.US, "%.1f s", ms / 1000.0)
    }

    /** `"44 s"`, `"2 min 44 s"`, `"1 h 5 min"`; negative counts as zero. */
    fun duration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
            else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }
    }

    /**
     * [share] of one novel: one decimal under 10% (`"1.2% of a novel"`), whole percent up to a novel
     * (`"45% of a novel"`), then novels with one decimal under ten (`"1.3 novels"`, `"2 novels"`, `"12 novels"`).
     */
    fun novelShare(share: Double): String {
        if (!share.isFinite() || share <= 0) return "0% of a novel"
        val tenthsOfPercent = Math.round(share * 1000)
        if (tenthsOfPercent == 0L) return "under 0.1% of a novel"
        if (tenthsOfPercent < 100) return "${tenthsOfPercent / 10}.${tenthsOfPercent % 10}% of a novel"
        val percent = Math.round(share * 100)
        if (percent < 100) return "$percent% of a novel"
        val tenths = Math.round(share * 10)
        val novels = when {
            tenths >= 100 -> String.format(Locale.US, "%,d", Math.round(share))
            tenths % 10 == 0L -> "${tenths / 10}"
            else -> "${tenths / 10}.${tenths % 10}"
        }
        return if (novels == "1") "1 novel" else "$novels novels"
    }

    /** The spoken form of a `ChartText.delta` text, so TalkBack says "up 12 percent" instead of reading the glyph. */
    fun spokenDelta(delta: String): String {
        val direction = when {
            delta.startsWith("▲") -> "up"
            delta.startsWith("▼") -> "down"
            else -> return delta
        }
        val amount = delta.drop(1).trim()
        return when {
            amount == "0%" -> "unchanged"
            amount.endsWith("%+") -> "$direction more than ${amount.dropLast(2)} percent"
            else -> "$direction ${amount.removeSuffix("%")} percent"
        }
    }

    /** The stretch a range covers, e.g. "last 30 days" or "all time". */
    fun span(range: DashboardRange): String = range.days?.let { "last $it days" } ?: "all time"

    /** What a range is compared with, e.g. "the previous 30 days"; null for all time. */
    fun previous(range: DashboardRange): String? = range.days?.let { "the previous $it days" }
}
