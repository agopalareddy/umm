package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import io.github.agopalareddy.umm.core.policy.DataPolicy
import io.github.agopalareddy.umm.core.policy.ModelPlan
import io.github.agopalareddy.umm.core.policy.Recommendation
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.alpha
import io.github.agopalareddy.umm.graph
import kotlinx.coroutines.launch

@Composable
internal fun ModelsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(UmmSettings())
    val zdr by remember { graph.dataPolicy.observe() }.collectAsStateWithLifecycle(null)
    var plan by remember { mutableStateOf<ModelPlan?>(null) }
    var policy by remember { mutableStateOf<DataPolicy?>(null) }
    var rec by remember { mutableStateOf<Recommendation?>(null) }
    var newestStt by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<ZdrWarning?>(null) }
    LaunchedEffect(settings, zdr) {
        plan = runCatching { graph.modelPlan() }.getOrNull()
        policy = runCatching { graph.dataPolicy.current() }.getOrNull()
        rec = runCatching { graph.recommendations.current() }.getOrNull()
        newestStt = graph.modelCatalog.sttModels()?.maxByOrNull { it.createdEpochSec }?.id
    }
    val account = policy
    val p = account?.withAppChoice(settings.zdrOnly)
    var checking by remember { mutableStateOf(false) }
    var zdrDialog by remember { mutableStateOf<String?>(null) }
    /** Turning ZDR off re-checks the account, since OpenRouter may still require it. */
    fun turnZdrOff() {
        checking = true
        scope.launch {
            val stillRequired = rec?.let { graph.dataPolicy.recheck(it) }
            graph.settings.update { it.copy(zdrOnly = false) }
            checking = false
            zdrDialog = when (stillRequired) {
                true -> "Your OpenRouter account still requires zero data retention, so Umm has to keep using ZDR models. " +
                    "Turn it off in OpenRouter's privacy settings first, then try again."
                null -> "Couldn't reach OpenRouter to check your account. Try again in a moment."
                false -> null
            }
        }
    }
    /** The model that stops [mode] from working with the data policy, if any. */
    fun blockedModel(mode: ModelMode): String? = if (p == null || !p.enforced) null else when (mode) {
        ModelMode.RECOMMENDED -> rec?.let { r -> listOf(r.stt.primary, r.cleanup.primary).firstOrNull { !p.allows(it) } }
        ModelMode.NEWEST_STT -> newestStt?.takeIf { !p.allows(it) }
        ModelMode.MANUAL -> null
    }

    Page("Models", onBack) {
        val accountRequires = account?.enforced == true
        SwitchRow(
            checked = settings.zdrOnly || accountRequires,
            enabled = !checking,
            onCheckedChange = { on ->
                if (on) scope.launch { graph.settings.update { it.copy(zdrOnly = true) } } else turnZdrOff()
            },
        ) {
            Column(Modifier.weight(1f)) {
                Text("Zero data retention only")
                val note = when {
                    checking -> "Checking your OpenRouter account…"
                    accountRequires -> "Required by your OpenRouter account"
                    else -> null
                }
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        val modes = listOf(
            ModelMode.RECOMMENDED to "Recommended (updated by Umm)",
            ModelMode.NEWEST_STT to "Always the newest speech-to-text model",
            ModelMode.MANUAL to "Choose my own",
        )
        modes.forEach { (mode, label) ->
            val blocked = blockedModel(mode)
            val choose = { scope.launch { graph.settings.update { it.copy(modelMode = mode) } }; Unit }
            RadioRow(
                selected = settings.modelMode == mode,
                dimmed = blocked != null,
                onClick = { if (blocked != null) warning = ZdrWarning(blocked, choose) else choose() },
            ) {
                Column {
                    Text(label)
                    if (blocked != null) Text("Not available with zero data retention", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Section("In use") {
            val current = plan
            if (current == null) Text("Loading…") else InUseTable(current)
        }
        if (settings.modelMode == ModelMode.MANUAL) {
            val allows: (String) -> Boolean = { id -> p?.allows(id) ?: true }
            ModelPicker("Speech-to-text model", settings.manualSttModel, allows, { graph.modelCatalog.sttModels() }) { id ->
                val pick = { scope.launch { graph.settings.update { it.copy(manualSttModel = id) } }; Unit }
                if (allows(id)) pick() else warning = ZdrWarning(id, pick)
            }
            ModelPicker("Cleanup model", settings.manualCleanupModel, allows, { graph.modelCatalog.chatModels() }) { id ->
                val pick = { scope.launch { graph.settings.update { it.copy(manualCleanupModel = id) } }; Unit }
                if (allows(id)) pick() else warning = ZdrWarning(id, pick)
            }
        }
    }
    zdrDialog?.let { message -> ZdrStillRequired(message) { zdrDialog = null } }
    warning?.let { w ->
        AlertDialog(
            onDismissRequest = { warning = null },
            title = { Text("Not available with zero data retention") },
            text = {
                Text(
                    "${w.model} has no provider with zero data retention. If you pick it anyway, Umm keeps using " +
                        "ZDR models instead.",
                )
            },
            confirmButton = { TextButton(onClick = { warning = null; w.proceed() }) { Text("Pick anyway") } },
            dismissButton = { TextButton(onClick = { warning = null }) { Text("Cancel") } },
        )
    }
}

private class ZdrWarning(val model: String, val proceed: () -> Unit)

@Composable
private fun ZdrStillRequired(message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zero data retention is still on") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(OPENROUTER_PRIVACY_URL)))
            }) { Text("Open OpenRouter settings") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun ModelPicker(
    title: String,
    selected: String?,
    allows: (String) -> Boolean,
    load: suspend () -> List<ModelInfo>?,
    onPick: (String) -> Unit,
) {
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
                        val ok = allows(m.id)
                        Column(Modifier.fillMaxWidth().clickable { onPick(m.id) }.alpha(if (ok) 1f else 0.45f).padding(vertical = 8.dp)) {
                            Text(m.name, fontWeight = if (m.id == selected) FontWeight.Bold else FontWeight.Normal)
                            val note = if (ok) "" else " · no ZDR"
                            Text("${m.id} · input ${m.promptPrice ?: "?"} · output ${m.completionPrice ?: "?"}$note", style = MaterialTheme.typography.bodySmall)
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
