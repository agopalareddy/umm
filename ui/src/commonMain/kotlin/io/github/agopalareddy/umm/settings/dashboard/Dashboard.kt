package io.github.agopalareddy.umm.settings.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.stats.DashboardLayout
import io.github.agopalareddy.umm.core.stats.DashboardRange
import io.github.agopalareddy.umm.core.stats.DashboardStats
import io.github.agopalareddy.umm.ui.LocalUmm
import io.github.agopalareddy.umm.settings.SettingsChange

/**
 * The stats dashboard on Home: range chips, then each visible card in the saved order, with an Edit button for
 * showing, hiding and moving cards. Nothing while [stats] loads, and a single line until the first dictation
 * succeeds.
 */
@Composable
fun Dashboard(stats: DashboardStats?, settings: UmmSettings, onChange: SettingsChange) {
    // Kept above the early returns, so a stats reload does not close Edit mode.
    var editing by rememberSaveable { mutableStateOf(false) }
    if (stats == null) return
    if (!stats.hasData) {
        val message = if (settings.statsRecording) "Your stats show up here after your first dictation." else "Recording is paused"
        Text(message, style = MaterialTheme.typography.bodyLarge)
        return
    }
    val apiKey = LocalUmm.current.apiKeyStore.key.collectAsStateWithLifecycle().value
    val layout = remember(settings.dashboardOrder, settings.dashboardHidden) {
        DashboardLayout.merge(settings.dashboardOrder, settings.dashboardHidden)
    }
    // The cards follow the range of the stats they were given, which can trail the chips for a moment.
    val context = CardContext(stats, stats.range, apiKey)
    val allHidden = layout.visible.isEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Your stats",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (!editing && !allHidden) TextButton(onClick = { editing = true }) { Text("Edit") }
        }
        when {
            editing -> EditLayout(
                layout,
                // Applied to the saved layout at write time, so two quick taps do not overwrite each other.
                onEdit = { edit -> onChange(LayoutEdit.editOf(edit)) },
                onDone = { editing = false },
            )
            allHidden -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Stats are hidden", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { editing = true }) { Text("Edit") }
            }
            else -> {
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
    }
}

@Composable
private fun RangeChips(selected: DashboardRange, onSelect: (DashboardRange) -> Unit) {
    // Wraps at a large font size or a narrow screen instead of running off the edge.
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
