package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import io.github.agopalareddy.umm.core.policy.ModelPlan
import io.github.agopalareddy.umm.graph
import kotlinx.coroutines.launch

@Composable
internal fun ModelsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(UmmSettings())
    var plan by remember { mutableStateOf<ModelPlan?>(null) }
    LaunchedEffect(settings) { plan = runCatching { graph.modelPlan() }.getOrNull() }

    Page("Models", onBack) {
        val modes = listOf(
            ModelMode.RECOMMENDED to "Recommended (updated by Umm)",
            ModelMode.NEWEST_STT to "Always the newest speech-to-text model",
            ModelMode.MANUAL to "Choose my own",
        )
        modes.forEach { (mode, label) ->
            RadioRow(selected = settings.modelMode == mode, onClick = { scope.launch { graph.settings.update { it.copy(modelMode = mode) } } }) {
                Text(label)
            }
        }
        Section("In use") {
            val p = plan
            if (p == null) Text("Loading…") else InUseTable(p)
        }
        if (settings.modelMode == ModelMode.MANUAL) {
            ModelPicker("Speech-to-text model", settings.manualSttModel, { graph.modelCatalog.sttModels() }) { id ->
                scope.launch { graph.settings.update { it.copy(manualSttModel = id) } }
            }
            ModelPicker("Cleanup model", settings.manualCleanupModel, { graph.modelCatalog.chatModels() }) { id ->
                scope.launch { graph.settings.update { it.copy(manualCleanupModel = id) } }
            }
        }
    }
}

@Composable
private fun ModelPicker(title: String, selected: String?, load: suspend () -> List<ModelInfo>?, onPick: (String) -> Unit) {
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(attempt) {
        failed = false
        models = load()
        failed = models == null
    }
    Section(title) {
        Text("Selected: ${selected ?: "none (recommended is used)"}", style = MaterialTheme.typography.bodySmall)
        when {
            failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load models")
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            }
            models == null -> Text("Loading…")
            else -> {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(models!!.filter { it.id.contains(query, true) || it.name.contains(query, true) }, key = { it.id }) { m ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(vertical = 8.dp)) {
                            Text(m.name, fontWeight = if (m.id == selected) FontWeight.Bold else FontWeight.Normal)
                            Text("${m.id} · input ${m.promptPrice ?: "?"} · output ${m.completionPrice ?: "?"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Which models each step uses, primary first. */
@Composable
private fun InUseTable(plan: ModelPlan) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            listOf("Speech-to-text" to plan.stt, "Cleanup" to plan.cleanup).forEachIndexed { index, (step, models) ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    step,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                models.forEachIndexed { i, id ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (i == 0) "Primary" else "Fallback",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(72.dp),
                        )
                        Column {
                            Text(id.substringAfter('/'), style = MaterialTheme.typography.bodyLarge, fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal)
                            Text(id.substringBefore('/'), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
