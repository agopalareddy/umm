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
internal fun HistoryScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val items by graph.history.observeRecent().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    Page("History", onBack, scrollable = false) {
        if (items.isNotEmpty()) {
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.End)) { Text("Clear all") }
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
    var reporting by remember { mutableStateOf(false) }
    if (reporting) ReportDialog(item) { reporting = false }
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
                if (item.cleanText != null) TextButton(onClick = { reporting = true }) { Text("Report") }
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

private val REPORT_REASONS = listOf("Offensive or harmful", "Made-up or wrong content", "Something else")

/** Lets people flag AI-written output to the developer. There is no Umm server, so it goes by email. */
@Composable
private fun ReportDialog(item: HistoryItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    var reason by remember { mutableStateOf(REPORT_REASONS.first()) }
    var comment by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this output") },
        text = {
            Column {
                REPORT_REASONS.forEach { r -> RadioRow(selected = reason == r, onClick = { reason = r }) { Text(r) } }
                androidx.compose.material3.OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Details (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Sends what you said and what Umm wrote to $SUPPORT_EMAIL from your email app.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val stats = graph.stats.get(item.id)
                    val body = buildString {
                        appendLine("Reason: $reason")
                        if (comment.isNotBlank()) appendLine("Details: $comment")
                        appendLine()
                        appendLine("What I said (transcript): ${item.rawText.orEmpty()}")
                        appendLine("What Umm wrote: ${item.cleanText.orEmpty()}")
                        appendLine()
                        appendLine("Level: ${item.level.title()} · Speech model: ${stats?.sttModel ?: "?"} · Cleanup model: ${stats?.cleanupModel ?: "?"}")
                        appendLine("Umm ${io.github.agopalareddy.umm.BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE}")
                    }
                    val mail = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
                        .putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Umm: reported output ($reason)")
                        .putExtra(android.content.Intent.EXTRA_TEXT, body)
                    runCatching { context.startActivity(mail) }
                        .onFailure { Toast.makeText(context, "No email app found. Write to $SUPPORT_EMAIL.", Toast.LENGTH_LONG).show() }
                    onDismiss()
                }
            }) { Text("Send report") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
