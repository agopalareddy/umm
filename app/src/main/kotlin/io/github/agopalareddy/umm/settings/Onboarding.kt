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
    Page(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.github.agopalareddy.umm.ui.UmmLogo(32.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 12.dp))
                Text("Set up Umm")
            }
        },
        onBack = null,
    ) {
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
        val context = androidx.compose.ui.platform.LocalContext.current
        Text(
            "Your recordings go to OpenRouter with your own key, and nowhere else. Umm has no server and no tracking.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        androidx.compose.material3.TextButton(onClick = {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_POLICY_URL)))
        }) { Text("Privacy policy") }
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
    Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
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
