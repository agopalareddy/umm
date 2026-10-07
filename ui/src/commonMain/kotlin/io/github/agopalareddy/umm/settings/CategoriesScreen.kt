package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.data.CategoryConfig
import io.github.agopalareddy.umm.ui.LocalUmm
import kotlinx.coroutines.launch

@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val repo = LocalUmm.current.categories
    val configs by repo.observeAll().collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableStateOf<Category?>(null) }

    Page("App categories", onBack) {
        Text("Each category has its own cleanup level. Apps not listed anywhere use Other.", style = MaterialTheme.typography.bodySmall)
        LocalUmm.current.platform.perAppLevelsNote()?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        configs.forEach { config ->
            Card(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { selected = if (selected == config.category) null else config.category }) {
                Column(Modifier.padding(16.dp)) {
                    Text(config.category.title(), style = MaterialTheme.typography.titleMedium)
                    Text("${config.level?.title() ?: "Default level"} · ${config.script.title()} script", style = MaterialTheme.typography.bodySmall)
                    if (selected == config.category) CategoryDetail(config)
                }
            }
        }
    }
}

@Composable
private fun CategoryDetail(config: CategoryConfig) {
    val umm = LocalUmm.current
    val repo = umm.categories
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<String>()) }
    var refresh by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(config.category, refresh) { apps = repo.appsIn(config.category) }

    Column(Modifier.padding(top = 8.dp)) {
        Text("Level", style = MaterialTheme.typography.labelLarge)
        if (config.category == Category.OTHER) {
            Text("Uses the default level from Settings.", style = MaterialTheme.typography.bodySmall)
        } else {
            CleanupLevel.entries.forEach { level ->
                RadioRow(selected = config.level == level, onClick = { scope.launch { repo.update(config.copy(level = level)) } }) {
                    Text(level.title())
                }
            }
        }
        Text("Script for mixed languages", style = MaterialTheme.typography.labelLarge)
        ScriptPreference.entries.forEach { script ->
            RadioRow(selected = config.script == script, onClick = { scope.launch { repo.update(config.copy(script = script)) } }) {
                Text(if (script == ScriptPreference.LATIN) "Latin letters (kal meeting hai)" else "Each language's own script (कल meeting है)")
            }
        }
        if (config.category != Category.OTHER) {
            Text("Apps", style = MaterialTheme.typography.labelLarge)
            apps.forEach { pkg ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(umm.platform.appLabel(pkg), Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { repo.assign(pkg, Category.OTHER); refresh++ } }) { Text("Remove") }
                }
            }
            TextButton(onClick = { picking = true }) { Text("Add app") }
        }
    }
    if (picking) {
        AppPicker(onDismiss = { picking = false }) { pkg ->
            picking = false
            scope.launch { repo.assign(pkg, config.category); refresh++ }
        }
    }
}

@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val platform = LocalUmm.current.platform
    var query by remember { mutableStateOf("") }
    val apps = remember { platform.installedApps() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add app") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps.filter { it.label.contains(query, ignoreCase = true) }, key = { it.id }) { (pkg, label) ->
                        Text(label, Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
    )
}

fun ScriptPreference.title() = name.lowercase().replaceFirstChar { it.uppercase() }
