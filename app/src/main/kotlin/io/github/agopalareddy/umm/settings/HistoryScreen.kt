package io.github.agopalareddy.umm.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.HistoryItem
import io.github.agopalareddy.umm.core.data.HistoryStatus
import io.github.agopalareddy.umm.graph
import kotlinx.coroutines.launch

@Composable
internal fun HistoryScreen() {
    val graph = LocalContext.current.graph
    val items by graph.history.observeRecent().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
        }
        if (items.isEmpty()) Text("Your recent dictations will appear here.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { HistoryCard(it) }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This deletes every saved dictation, including audio kept for retries.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { graph.history.clearAll() } }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryCard(item: HistoryItem) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var levelMenu by remember { mutableStateOf(false) }
    val text = item.cleanText ?: item.rawText
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val time = DateUtils.getRelativeTimeSpanString(item.createdAt).toString()
            Text("$time · ${appLabel(context, item.packageName)} · ${item.level.title()} · ${item.status.label()}", style = MaterialTheme.typography.bodySmall)
            Text(text ?: "(not transcribed)", style = MaterialTheme.typography.bodyLarge)
            Row {
                if (text != null) TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Umm", text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                if (item.rawText != null) Box {
                    TextButton(onClick = { levelMenu = true }) { Text("Re-clean") }
                    DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                        CleanupLevel.entries.forEach { level ->
                            DropdownMenuItem(text = { Text(level.title()) }, onClick = {
                                levelMenu = false
                                scope.launch {
                                    runCatching { graph.pipeline.reclean(item.id, level, item.script) }
                                        .onFailure { Toast.makeText(context, "Couldn't reach OpenRouter", Toast.LENGTH_SHORT).show() }
                                }
                            })
                        }
                    }
                }
                if (item.status == HistoryStatus.FAILED && item.audioPath != null) {
                    TextButton(onClick = { graph.pipeline.reset(); graph.pipeline.retry(item.id) }) { Text("Retry") }
                }
                TextButton(onClick = { scope.launch { graph.history.delete(item.id) } }) { Text("Delete") }
            }
        }
    }
}

private fun HistoryStatus.label() = when (this) {
    HistoryStatus.PENDING -> "Pending"
    HistoryStatus.DONE -> "Done"
    HistoryStatus.CLEANUP_FAILED -> "Cleanup failed"
    HistoryStatus.FAILED -> "Failed"
}
