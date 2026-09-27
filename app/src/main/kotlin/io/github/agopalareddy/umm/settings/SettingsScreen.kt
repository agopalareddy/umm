package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.UmmSettings

@Composable
internal fun SettingsScreen(
    settings: UmmSettings,
    keyConnected: Boolean,
    onConnect: () -> Unit,
    onPasteKey: (String) -> Unit,
    onDisconnect: () -> Unit,
    onChange: ((UmmSettings) -> UmmSettings) -> Unit,
    onOpen: (route: String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Umm", style = MaterialTheme.typography.headlineMedium)

        Section("Account") {
            Text(if (keyConnected) "Connected to OpenRouter" else "Not connected")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) { Text(if (keyConnected) "Reconnect" else "Connect with OpenRouter") }
                if (keyConnected) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
            PasteKeyField(onPasteKey)
        }
        HorizontalDivider()

        Section("Default cleanup level") {
            Text("Used for apps in Other, and for categories set to use the default.", style = MaterialTheme.typography.bodySmall)
            CleanupLevel.entries.forEach { level ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.defaultLevel == level, onClick = { onChange { it.copy(defaultLevel = level) } })
                    Column {
                        Text(level.title())
                        Text(level.description(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        HorizontalDivider()

        Section("Stop after silence") {
            val timeout = settings.silenceTimeoutSec
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (timeout == null) "Off: tap to stop" else "$timeout seconds", Modifier.weight(1f))
                Switch(checked = timeout != null, onCheckedChange = { on -> onChange { it.copy(silenceTimeoutSec = if (on) 3 else null) } })
            }
            if (timeout != null) {
                Slider(
                    value = timeout.toFloat(),
                    onValueChange = { v -> onChange { it.copy(silenceTimeoutSec = v.toInt()) } },
                    valueRange = 1f..10f,
                    steps = 8,
                )
            }
        }
        HorizontalDivider()

        Section("After inserting text") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Switch back to my previous keyboard", Modifier.weight(1f))
                Switch(checked = settings.switchBackAfterInsert, onCheckedChange = { on -> onChange { it.copy(switchBackAfterInsert = on) } })
            }
        }
        HorizontalDivider()

        Section("Languages") {
            Text("Default", style = MaterialTheme.typography.labelLarge)
            LANGUAGES.forEach { (code, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.defaultLanguage == code, onClick = { onChange { it.copy(defaultLanguage = code) } })
                    Text(name)
                }
            }
            Text("Shown in the keyboard's language switch", style = MaterialTheme.typography.labelLarge)
            LANGUAGES.forEach { (code, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = code in settings.keyboardLanguages,
                        onCheckedChange = { checked ->
                            onChange {
                                val next = if (checked) it.keyboardLanguages + code else it.keyboardLanguages - code
                                it.copy(keyboardLanguages = LANGUAGES.keys.filter { k -> k in next }.ifEmpty { listOf("auto") })
                            }
                        },
                    )
                    Text(name)
                }
            }
        }
        HorizontalDivider()

        Section("More") {
            TextButton(onClick = { onOpen(Routes.CATEGORIES) }) { Text("App categories") }
            TextButton(onClick = { onOpen(Routes.MODELS) }) { Text("Models") }
            TextButton(onClick = { onOpen(Routes.HISTORY) }) { Text("History") }
        }
    }
}
