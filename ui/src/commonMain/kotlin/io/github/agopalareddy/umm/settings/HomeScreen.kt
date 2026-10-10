package io.github.agopalareddy.umm.settings

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.policy.ModelPlan
import io.github.agopalareddy.umm.ui.LocalUmm
import io.github.agopalareddy.umm.settings.dashboard.Dashboard
import io.github.agopalareddy.umm.ui.UmmLogo
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(home: HomeSetup, onOpen: (String) -> Unit) {
    val setupComplete = home.complete
    val onSetup = home.onSetup
    val umm = LocalUmm.current
    val scope = rememberCoroutineScope()
    val settings by remember { umm.settings.settings }.collectAsStateWithLifecycle(null)
    val change: SettingsChange = { transform -> scope.launch { umm.settings.update(transform) } }
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
    val stats by remember(range, today) { range?.let { umm.stats.observeDashboard(it) } ?: emptyFlow() }
        .collectAsStateWithLifecycle(null)
    var plan by remember { mutableStateOf<ModelPlan?>(null) }
    val zdr by remember { umm.dataPolicy.observe() }.collectAsStateWithLifecycle(null)
    LaunchedEffect(zdr) { plan = runCatching { umm.modelPlan() }.getOrNull() }
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
            // The desktop sidebar already links both.
            if (LocalShowBack.current) {
                IconButton(onClick = { onOpen(Routes.HISTORY) }) { Icon(Icons.Default.History, contentDescription = "History") }
                IconButton(onClick = { onOpen(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
        },
    ) {
        val connected = umm.apiKeyStore.key.collectAsStateWithLifecycle().value != null
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
                Text(home.intro, style = MaterialTheme.typography.bodyMedium)
                var tryText by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = tryText,
                    onValueChange = { tryText = it },
                    label = { Text("Try it here") },
                    placeholder = { Text(home.tryPlaceholder) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    home.switchKeyboard?.let { switch ->
                        FilledTonalButton(onClick = switch) { Text("Choose keyboard") }
                    }
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
        // The desktop sidebar already links both.
        if (LocalShowBack.current) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { onOpen(Routes.HISTORY) }, modifier = Modifier.weight(1f)) { Text("History") }
                FilledTonalButton(onClick = { onOpen(Routes.SETTINGS) }, modifier = Modifier.weight(1f)) { Text("Settings") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Shown once when Umm learns the account only allows zero data retention providers. */
@Composable
private fun ZdrNotice(plan: ModelPlan?) {
    val umm = LocalUmm.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val acknowledge: () -> Unit = { scope.launch { umm.dataPolicy.acknowledgeNotice() } }
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
                umm.platform.openUrl(OPENROUTER_PRIVACY_URL)
            }) { Text("Privacy settings") }
        },
    )
}
