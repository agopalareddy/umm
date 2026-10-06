package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeySource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.ui.LocalUmm
import io.github.agopalareddy.umm.core.data.ThemeMode
import io.github.agopalareddy.umm.core.data.UmmSettings

@Composable
fun SettingsHome(
    settings: UmmSettings,
    keyConnected: Boolean,
    extraSettings: List<SettingsEntry>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val platform = LocalUmm.current.platform
    Page("Settings", onBack) {
        NavRow(Icons.Default.AccountCircle, "Account", if (keyConnected) "Connected to OpenRouter" else "Not connected") { onOpen(Routes.ACCOUNT) }
        val silence = settings.silenceTimeoutSec?.let { "stop after $it s of silence" } ?: "tap to stop"
        NavRow(Icons.Default.Mic, "Dictation", "${settings.defaultLevel.title()} by default · $silence") { onOpen(Routes.DICTATION) }
        extraSettings.forEach { entry -> NavRow(entry.icon, entry.title, entry.subtitle) { onOpen(entry.route) } }
        val languages = settings.keyboardLanguages.joinToString { LANGUAGES[it] ?: it }
        NavRow(Icons.Default.Translate, "Languages", languages) { onOpen(Routes.LANGUAGES) }
        NavRow(Icons.Default.Category, "App categories", "Cleanup level and script per app") { onOpen(Routes.CATEGORIES) }
        NavRow(Icons.Default.AutoAwesome, "Models", settings.modelMode.label() + if (settings.zdrOnly) " · zero data retention" else "") { onOpen(Routes.MODELS) }
        NavRow(Icons.Default.Palette, "Appearance", settings.themeMode.label() + if (settings.dynamicColor) " · dynamic theme" else "") { onOpen(Routes.APPEARANCE) }
        NavRow(Icons.Default.BarChart, "Stats", settings.statsSummary()) { onOpen(Routes.STATS) }
        NavRow(Icons.Default.Info, "About", "Version ${platform.appVersion()} · source on GitHub") {
            platform.openUrl("https://github.com/agopalareddy/umm")
        }
        NavRow(Icons.Default.Lock, "Privacy policy", null) {
            platform.openUrl(PRIVACY_POLICY_URL)
        }
    }
}

@Composable
fun AccountPage(onBack: () -> Unit, onConnect: () -> Unit, onPasteKey: (String) -> Unit, onDisconnect: () -> Unit) {
    val umm = LocalUmm.current
    val key by umm.apiKeyStore.key.collectAsStateWithLifecycle()
    val check = rememberKeyCheck(key)
    var confirmDisconnect by remember { mutableStateOf(false) }
    Page("Account", onBack) {
        ConnectionCard(key, check)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Every OpenRouter sign-in creates a new key, so only offer it when the current one can't be used.
            when {
                key == null -> Button(onClick = onConnect) { Text("Connect with OpenRouter") }
                check == KeyCheck.Rejected -> Button(onClick = onConnect) { Text("Sign in again") }
            }
            if (key != null) OutlinedButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
        }
        Spacer(Modifier.height(8.dp))
        PasteKeyField(onPasteKey)
        Spacer(Modifier.height(8.dp))
        Text(
            "Umm uses your own OpenRouter account. Audio and text go only to OpenRouter.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect OpenRouter?") },
            text = {
                Column {
                    Text("Umm will forget this key. The key itself stays in your OpenRouter account until you delete it there.")
                    TextButton(onClick = { umm.platform.openUrl(OPENROUTER_KEYS_URL) }) {
                        Text("Manage keys on OpenRouter")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

const val OPENROUTER_KEYS_URL = "https://openrouter.ai/settings/keys"
const val PRIVACY_POLICY_URL = "https://agopalareddy.github.io/umm/privacy/"

/** Whether a key is set, how it was added, and what OpenRouter says about it. */
@Composable
fun ConnectionCard(key: String?, check: KeyCheck) {
    val source by LocalUmm.current.apiKeyStore.source.collectAsStateWithLifecycle()
    val bad = key == null || check == KeyCheck.Rejected
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (bad) Icons.Default.Warning else Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        key == null -> "Not connected"
                        check == KeyCheck.Rejected -> "OpenRouter rejected this key"
                        else -> "Connected to OpenRouter"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (key == null) {
                Text("Connect your OpenRouter account so Umm can transcribe.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            InfoRow("Added by", source?.label() ?: "—")
            InfoRow("Key", ApiKeyStore.mask(key))
            when (check) {
                KeyCheck.Checking -> InfoRow("Status", "Checking…")
                KeyCheck.Rejected -> InfoRow("Status", "Sign in again or paste a new key")
                KeyCheck.Unreachable -> InfoRow("Status", "Couldn't reach OpenRouter")
                is KeyCheck.Ok -> {
                    InfoRow("Status", "Working")
                    val zdr by LocalUmm.current.dataPolicy.observe().collectAsStateWithLifecycle(null)
                    if (zdr?.enforced == true) InfoRow("Data policy", "Zero data retention required")
                    if (check.info.label.isNotBlank() && !check.info.label.startsWith("sk-")) InfoRow("Name", check.info.label)
                    check.info.usageMonthlyUsd?.let { InfoRow("Spent this month", usd(it)) }
                    InfoRow("Spent in total", usd(check.info.usageUsd))
                    InfoRow(
                        "Credit limit",
                        check.info.limitUsd?.let { limit ->
                            val left = check.info.limitRemainingUsd?.let { " · ${usd(it)} left" }.orEmpty()
                            val reset = check.info.limitReset?.let { ", resets $it" }.orEmpty()
                            usd(limit) + left + reset
                        } ?: "None",
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(128.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun KeySource.label() = when (this) {
    KeySource.SIGNED_IN -> "Signed in with OpenRouter"
    KeySource.PASTED -> "API key you pasted"
    KeySource.DEVELOPER -> "Developer key (.env)"
}

@Composable
fun DictationPage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange) {
    Page("Dictation", onBack) {
        Section("Default cleanup level") {
            Text("Used for apps in Other, and for categories set to use the default.", style = MaterialTheme.typography.bodySmall)
            CleanupLevel.entries.forEach { level ->
                RadioRow(selected = settings.defaultLevel == level, onClick = { onChange { it.copy(defaultLevel = level) } }) {
                    Column {
                        Text(level.title())
                        Text(level.description(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Section("Stop after silence") {
            val timeout = settings.silenceTimeoutSec
            SwitchRow(checked = timeout != null, onCheckedChange = { on -> onChange { it.copy(silenceTimeoutSec = if (on) 3 else null) } }) {
                Text(if (timeout == null) "Off: tap to stop" else "$timeout seconds", Modifier.weight(1f))
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
            SwitchRow(checked = settings.switchBackAfterInsert, onCheckedChange = { on -> onChange { it.copy(switchBackAfterInsert = on) } }) {
                Text("Switch back to my previous keyboard", Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun LanguagesPage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange) {
    Page("Languages", onBack) {
        Section("Default") {
            LANGUAGES.forEach { (code, name) ->
                RadioRow(selected = settings.defaultLanguage == code, onClick = { onChange { it.copy(defaultLanguage = code) } }) {
                    Text(name)
                }
            }
        }
        Section("In the keyboard's language switch") {
            LANGUAGES.forEach { (code, name) ->
                CheckRow(
                    checked = code in settings.keyboardLanguages,
                    onCheckedChange = { checked ->
                        onChange {
                            val next = if (checked) it.keyboardLanguages + code else it.keyboardLanguages - code
                            it.copy(keyboardLanguages = LANGUAGES.keys.filter { k -> k in next }.ifEmpty { listOf("auto") })
                        }
                    },
                ) {
                    Text(name)
                }
            }
        }
    }
}

@Composable
fun AppearancePage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange) {
    Page("Appearance", onBack) {
        Section("Theme") {
            ThemeMode.entries.forEach { mode ->
                RadioRow(selected = settings.themeMode == mode, onClick = { onChange { it.copy(themeMode = mode) } }) {
                    Text(mode.label())
                }
            }
        }
        Section("Colors") {
            val supported = LocalUmm.current.platform.dynamicColors(dark = false) != null
            SwitchRow(
                checked = settings.dynamicColor && supported,
                enabled = supported,
                onCheckedChange = { on -> onChange { it.copy(dynamicColor = on) } },
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use Dynamic Theme")
                    if (!supported) Text("Needs Android 12 or newer", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Applies to the app and the Umm keyboard.", style = MaterialTheme.typography.bodySmall)
    }
}

fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

fun io.github.agopalareddy.umm.core.data.ModelMode.label() = when (this) {
    io.github.agopalareddy.umm.core.data.ModelMode.RECOMMENDED -> "Recommended"
    io.github.agopalareddy.umm.core.data.ModelMode.NEWEST_STT -> "Newest speech-to-text"
    io.github.agopalareddy.umm.core.data.ModelMode.MANUAL -> "Chosen by you"
}
