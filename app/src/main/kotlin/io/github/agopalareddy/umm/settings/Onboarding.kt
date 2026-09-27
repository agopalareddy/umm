package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class SetupStatus(val keyboardEnabled: Boolean, val micGranted: Boolean, val keyConnected: Boolean) {
    val complete get() = keyboardEnabled && micGranted && keyConnected
}

@Composable
internal fun OnboardingScreen(
    status: SetupStatus,
    onEnableKeyboard: () -> Unit,
    onRequestMic: () -> Unit,
    onConnect: () -> Unit,
    onPasteKey: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up Umm", style = MaterialTheme.typography.headlineMedium)
        Text("Three steps, then switch to the Umm keyboard in any text field and start talking.")
        Step(1, "Enable the Umm keyboard", status.keyboardEnabled, "Open keyboard settings", onEnableKeyboard) {
            Text(
                "Android warns that keyboards can collect what you type. Umm only records while its keyboard is open " +
                    "and sends audio only to OpenRouter with your key.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Step(2, "Allow the microphone", status.micGranted, "Allow microphone", onRequestMic)
        Step(3, "Connect OpenRouter", status.keyConnected, "Connect with OpenRouter", onConnect) {
            PasteKeyField(onPasteKey)
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (done) "✓" else "$number."}  $title", style = MaterialTheme.typography.titleMedium)
            }
            if (!done) {
                Button(onClick = onAction) { Text(action) }
                extra()
            }
        }
    }
}

@Composable
internal fun PasteKeyField(onSave: (String) -> Unit) {
    val (value, setValue) = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = { setValue(it.trim()) },
        label = { Text("Or paste an OpenRouter key") },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { onSave(value); setValue("") }, enabled = value.isNotEmpty()) { Text("Save key") }
}
