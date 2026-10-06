package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.ui.LocalUmm
import kotlinx.coroutines.launch

/** Summary shown on the Settings row. */
fun UmmSettings.statsSummary() =
    (if (statsVisible) "Shown" else "Hidden") + " · " + (if (statsRecording) "Recording" else "Paused")

/** Debug-build tools for filling the dashboard with made-up dictations and removing them again. */
class SampleDataActions(val load: suspend () -> Unit, val remove: suspend () -> Unit)

/** [sampleData] is null in release builds, which hides the row count and the sample-data buttons. */
@Composable
fun StatsPage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange, sampleData: SampleDataActions? = null) {
    val stats = LocalUmm.current.stats
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    Page("Stats", onBack) {
        Section("Home") {
            SwitchRow(checked = settings.statsVisible, onCheckedChange = { on -> onChange { it.copy(statsVisible = on) } }) {
                Text("Show stats on Home", Modifier.weight(1f))
            }
            Text("Hides the dashboard. Recording carries on.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Recording") {
            SwitchRow(checked = settings.statsRecording, onCheckedChange = { on -> onChange { it.copy(statsRecording = on) } }) {
                Text("Record stats", Modifier.weight(1f))
            }
            Text("Off stops new rows being saved. Existing stats stay. History is not affected.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Data") {
            if (sampleData != null) {
                val count by stats.observeAll().collectAsStateWithLifecycle(emptyList())
                Text("${count.size} dictations recorded", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete stats data") }
            if (sampleData != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { sampleData.load() } }) {
                        Text("Load sample data")
                    }
                    OutlinedButton(onClick = { scope.launch { sampleData.remove() } }) { Text("Remove sample data") }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete stats data?") },
            text = { Text("This removes every recorded dictation from Stats. Your History is not affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { stats.deleteAll() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
