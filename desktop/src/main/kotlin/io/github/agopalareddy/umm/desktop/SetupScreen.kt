package io.github.agopalareddy.umm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.auth.KeySource
import io.github.agopalareddy.umm.linux.BindResult
import io.github.agopalareddy.umm.linux.InsertException
import io.github.agopalareddy.umm.settings.Page
import io.github.agopalareddy.umm.settings.PasteKeyField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class Step { TODO, WORKING, DONE, FAILED }

/** First-run setup: connect OpenRouter, approve the shortcut and typing, then try it. */
@Composable
fun SetupScreen(engine: DesktopEngine, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val key by engine.graph.apiKeyStore.key.collectAsState()
    val bind by engine.bindResult.collectAsState()
    val keyringFailed by engine.graph.keyringFailed.collectAsState()

    var typing by remember { mutableStateOf(Step.TODO) }
    var typingError by remember { mutableStateOf<String?>(null) }
    var test by remember { mutableStateOf(Step.TODO) }
    var practice by remember { mutableStateOf("") }

    Page("Set up Umm", onBack = null) {
        StepCard("1", "Connect OpenRouter", done = key != null) {
            if (!engine.graph.keyringAvailable) KeyringWarning(failed = false)
            else if (keyringFailed) KeyringWarning(failed = true)
            if (key == null) {
                Button(onClick = engine.signIn::start) { Text("Connect with OpenRouter") }
                // The keyring may show an unlock prompt; keep that off the UI thread.
                PasteKeyField { pasted -> scope.launch(Dispatchers.IO) { engine.graph.apiKeyStore.set(pasted, KeySource.PASTED) } }
            } else {
                Text("Connected")
            }
        }

        StepCard("2", "Approve the shortcut", done = bind is BindResult.Bound) {
            ShortcutCard(bind, onRetry = engine::bindHotkey, onChange = {})
            if (bind == null || bind is BindResult.Unsupported) {
                Text("Without a shortcut you can still start from the tray menu.", style = MaterialTheme.typography.bodySmall)
            }
        }

        StepCard("3", "Approve typing", done = typing == Step.DONE) {
            Text("Umm types the text for you. On GNOME, switch on “Allow Remote Interaction”.", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = typing != Step.WORKING,
                onClick = {
                    scope.launch {
                        typing = Step.WORKING
                        typingError = null
                        try {
                            engine.inserter.grantPermission()
                            typing = Step.DONE
                        } catch (e: InsertException) {
                            typingError = e.message
                            typing = Step.FAILED
                        }
                    }
                },
            ) { Text(if (typing == Step.FAILED) "Try again" else "Approve typing") }
            typingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        StepCard("4", "Try the shortcut", done = test == Step.DONE) {
            OutlinedButton(
                enabled = test != Step.WORKING && bind is BindResult.Bound,
                onClick = {
                    scope.launch {
                        test = Step.WORKING
                        test = if (engine.controller.testHotkey()) Step.DONE else Step.FAILED
                    }
                },
            ) { Text(if (test == Step.WORKING) "Press the shortcut now…" else "Test the shortcut") }
            if (test == Step.FAILED) {
                Text(
                    "Nothing happened. Another shortcut may use the same keys: change it above or in your desktop's shortcut settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        StepCard("5", "Dictate", done = practice.isNotBlank()) {
            OutlinedTextField(
                value = practice,
                onValueChange = { practice = it },
                label = { Text("Click here, then use the shortcut") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = key != null,
                onClick = {
                    scope.launch {
                        engine.graph.desktopSettings.update { it.copy(setupDone = true) }
                        engine.setStartAtLogin(true)
                        onDone()
                    }
                },
            ) { Text("Finish") }
        }
    }
}

@Composable
private fun StepCard(number: String, title: String, done: Boolean, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (done) "✓ " else "$number. ") + title,
                style = MaterialTheme.typography.titleMedium,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}
