package io.github.agopalareddy.umm.settings

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.policy.ModelPlan
import io.github.agopalareddy.umm.core.stats.UsageSummary
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.settings.dashboard.Dashboard
import io.github.agopalareddy.umm.ui.UmmLogo
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(setupComplete: Boolean, onSetup: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val summary by remember { graph.stats.observeSummary() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val settings by remember { graph.settings.settings }.collectAsStateWithLifecycle(null)
    val change: SettingsChange = { transform -> scope.launch { graph.settings.update(transform) } }
    // The dashboard flow only re-emits when the table changes, so re-key it on the date to roll "today" over.
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            today = LocalDate.now()
        }
    }
    // No range while settings load or the dashboard is hidden, so nothing is computed.
    val range = settings?.takeIf { it.statsVisible }?.dashboardRange
    val stats by remember(range, today) { range?.let { graph.stats.observeDashboard(it) } ?: emptyFlow() }
        .collectAsStateWithLifecycle(null)
    var plan by remember { mutableStateOf<ModelPlan?>(null) }
    val zdr by remember { graph.dataPolicy.observe() }.collectAsStateWithLifecycle(null)
    LaunchedEffect(zdr) { plan = runCatching { graph.modelPlan() }.getOrNull() }
    if (zdr?.noticePending == true) ZdrNotice(plan)

    Page(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UmmLogo(32.dp)
                Spacer(Modifier.width(12.dp))
                Text("Umm")
            }
        },
        onBack = null,
        actions = {
            IconButton(onClick = { onOpen(Routes.HISTORY) }) { Icon(Icons.Default.History, contentDescription = "History") }
            IconButton(onClick = { onOpen(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        },
    ) {
        val connected = graph.apiKeyStore.key.collectAsStateWithLifecycle().value != null
        AssistChip(
            onClick = { onOpen(Routes.ACCOUNT) },
            label = { Text(if (connected) "OpenRouter connected" else "OpenRouter not connected") },
            leadingIcon = {
                Icon(
                    if (connected) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        if (!setupComplete) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Finish setting up Umm", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onSetup, modifier = Modifier.padding(top = 8.dp)) { Text("Continue setup") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("Talk instead of typing", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Switch to Umm in any text field and start talking. It stops when you do. " +
                        "Double-tap the mic to whisper or pause as long as you like.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                var tryText by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = tryText,
                    onValueChange = { tryText = it },
                    label = { Text("Try it here") },
                    placeholder = { Text("Tap here, switch to Umm, and talk") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(
                        onClick = { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                    ) { Text("Choose keyboard") }
                    if (tryText.isNotEmpty()) TextButton(onClick = { tryText = "" }) { Text("Clear") }
                }
            }
        }

        settings?.let { current ->
            if (current.statsVisible) {
                Spacer(Modifier.height(24.dp))
                Dashboard(stats, current, change)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { onOpen(Routes.HISTORY) }, modifier = Modifier.weight(1f)) { Text("History") }
            FilledTonalButton(onClick = { onOpen(Routes.SETTINGS) }, modifier = Modifier.weight(1f)) { Text("Settings") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Stats(s: UsageSummary, plan: ModelPlan?) {
    val context = LocalContext.current
    Section("Your dictation") {
        Tiles(
            "Dictations" to "${s.dictations}",
            "Words" to "%,d".format(s.words),
            "Time spoken" to duration(s.spokenMs),
            "This week" to "${s.dictationsThisWeek}",
        )
    }
    Section("Fun") {
        Tiles(
            "Umms removed" to "${s.fillersRemoved}",
            "Time saved vs typing" to duration(s.timeSavedMs),
            "Longest dictation" to "${s.longestWords} words",
            "Day streak" to if (s.streakDays > 0) "${s.streakDays} 🔥" else "0",
            "Favorite app" to (s.topApp?.let { appLabel(context, it) } ?: "—"),
        )
    }
    Section("For power users") {
        Tiles(
            "Total spend" to money(s.totalCostUsd),
            "Per dictation" to (s.averageCostUsd?.let(::money) ?: "—"),
            "Average wait" to (s.averageLatencyMs?.let { "%.1f s".format(Locale.US, it / 1000.0) } ?: "—"),
            "Success rate" to "${(s.successRate * 100).toInt()}%",
        )
        val levels = s.levelCounts.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key.title()} ${it.value}" }
        if (levels.isNotEmpty()) Text("Levels: $levels", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        plan?.let {
            Text("Speech-to-text: ${it.stt.first()}", style = MaterialTheme.typography.bodySmall)
            Text("Cleanup: ${it.cleanup.first()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Tiles(vararg tiles: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        tiles.toList().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun duration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

private fun money(usd: Double): String =
    if (usd < 0.01) "$%.4f".format(Locale.US, usd) else "$%.2f".format(Locale.US, usd)

/** Shown once when Umm learns the account only allows zero data retention providers. */
@Composable
private fun ZdrNotice(plan: ModelPlan?) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val acknowledge: () -> Unit = { scope.launch { graph.dataPolicy.acknowledgeNotice() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = acknowledge,
        title = { Text("Zero data retention is on") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your OpenRouter account only allows providers that don't keep your data. Umm switched to models " +
                        "that support this. Some models, including the usual recommended speech model, aren't available " +
                        "to you, and results may be a little less accurate.",
                )
                plan?.let {
                    Text("Speech-to-text: ${it.stt.first()}", style = MaterialTheme.typography.bodySmall)
                    Text("Cleanup: ${it.cleanup.first()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = acknowledge) { Text("OK") } },
        dismissButton = {
            TextButton(onClick = {
                acknowledge()
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(OPENROUTER_PRIVACY_URL)))
            }) { Text("Privacy settings") }
        },
    )
}
