package io.github.agopalareddy.umm.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.ThemeMode
import io.github.agopalareddy.umm.core.data.UmmSettings

internal typealias SettingsChange = ((UmmSettings) -> UmmSettings) -> Unit

@Composable
internal fun SettingsHome(settings: UmmSettings, keyConnected: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Page("Settings", onBack) {
        NavRow(Icons.Default.AccountCircle, "Account", if (keyConnected) "Connected to OpenRouter" else "Not connected") { onOpen(Routes.ACCOUNT) }
        val silence = settings.silenceTimeoutSec?.let { "stop after $it s of silence" } ?: "tap to stop"
        NavRow(Icons.Default.Edit, "Dictation", "${settings.defaultLevel.title()} by default · $silence") { onOpen(Routes.DICTATION) }
        val languages = settings.keyboardLanguages.joinToString { LANGUAGES[it] ?: it }
        NavRow(Icons.Default.Face, "Languages", languages) { onOpen(Routes.LANGUAGES) }
        NavRow(Icons.Default.List, "App categories", "Cleanup level and script per app") { onOpen(Routes.CATEGORIES) }
        NavRow(Icons.Default.Build, "Models", settings.modelMode.label()) { onOpen(Routes.MODELS) }
        NavRow(Icons.Default.Star, "Appearance", settings.themeMode.label() + if (settings.dynamicColor) " · wallpaper colors" else "") { onOpen(Routes.APPEARANCE) }
    }
}

@Composable
internal fun AccountPage(keyConnected: Boolean, onBack: () -> Unit, onConnect: () -> Unit, onPasteKey: (String) -> Unit, onDisconnect: () -> Unit) {
    Page("Account", onBack) {
        Text(if (keyConnected) "Connected to OpenRouter." else "Not connected.", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Umm uses your own OpenRouter account. Audio and text go only to OpenRouter.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect) { Text(if (keyConnected) "Reconnect" else "Connect with OpenRouter") }
            if (keyConnected) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        PasteKeyField(onPasteKey)
    }
}

@Composable
internal fun DictationPage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange) {
    Page("Dictation", onBack) {
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
            Text("Double-tap the mic on the keyboard to keep recording through pauses for one dictation.", style = MaterialTheme.typography.bodySmall)
        }
        Section("After inserting text") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Switch back to my previous keyboard", Modifier.weight(1f))
                Switch(checked = settings.switchBackAfterInsert, onCheckedChange = { on -> onChange { it.copy(switchBackAfterInsert = on) } })
            }
        }
    }
}

@Composable
internal fun LanguagesPage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange) {
    Page("Languages", onBack) {
        Section("Default") {
            LANGUAGES.forEach { (code, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.defaultLanguage == code, onClick = { onChange { it.copy(defaultLanguage = code) } })
                    Text(name)
                }
            }
        }
        Section("In the keyboard's language switch") {
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
    }
}

@Composable
internal fun AppearancePage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange) {
    Page("Appearance", onBack) {
        Section("Theme") {
            ThemeMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.themeMode == mode, onClick = { onChange { it.copy(themeMode = mode) } })
                    Text(mode.label())
                }
            }
        }
        Section("Colors") {
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use wallpaper colors")
                    Text(
                        if (supported) "Match your phone's Material You colors" else "Needs Android 12 or newer",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.dynamicColor && supported,
                    enabled = supported,
                    onCheckedChange = { on -> onChange { it.copy(dynamicColor = on) } },
                )
            }
        }
        Text("Applies to the app and the Umm keyboard.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

internal fun io.github.agopalareddy.umm.core.data.ModelMode.label() = when (this) {
    io.github.agopalareddy.umm.core.data.ModelMode.RECOMMENDED -> "Recommended"
    io.github.agopalareddy.umm.core.data.ModelMode.NEWEST_STT -> "Newest speech-to-text"
    io.github.agopalareddy.umm.core.data.ModelMode.MANUAL -> "Chosen by you"
}
