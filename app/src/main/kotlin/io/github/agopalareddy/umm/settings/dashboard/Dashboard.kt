package io.github.agopalareddy.umm.settings.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.stats.DashboardLayout
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.core.stats.DashboardStats
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.settings.SettingsChange

/**
 * The stats dashboard on Home: range chips, then each visible card in the saved order. Nothing while [stats]
 * loads, and a single line until the first dictation succeeds.
 */
@Composable
internal fun Dashboard(stats: DashboardStats?, settings: UmmSettings, onChange: SettingsChange) {
    if (stats == null) return
    if (!stats.hasData) {
        Text("Your stats show up here after your first dictation.", style = MaterialTheme.typography.bodyLarge)
        return
    }
    val apiKey = LocalContext.current.graph.apiKeyStore.key.collectAsStateWithLifecycle().value
    val layout = remember(settings.dashboardOrder, settings.dashboardHidden) {
        DashboardLayout.merge(settings.dashboardOrder, settings.dashboardHidden)
    }
    // The cards follow the range of the stats they were given, which can trail the chips for a moment.
    val context = CardContext(stats, stats.range, apiKey)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Your stats",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Column {
            RangeChips(settings.dashboardRange) { range -> onChange { it.copy(dashboardRange = range) } }
            if (!settings.statsRecording) {
                Footnote("Recording is paused")
            }
        }
        layout.visible.forEach { id ->
            val spec = Cards.byId[id] ?: return@forEach
            key(id) { DashboardCard(spec, context) }
        }
    }
}

@Composable
private fun RangeChips(selected: DashboardRange, onSelect: (DashboardRange) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DashboardRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { if (range != selected) onSelect(range) },
                label = { Text(range.label) },
            )
        }
    }
}

private val DashboardRange.label: String
    get() = days?.let { "$it days" } ?: "All"

@Composable
private fun DashboardCard(spec: CardSpec, context: CardContext) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                spec.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp).semantics { heading() },
            )
            spec.content(context)
        }
    }
}
