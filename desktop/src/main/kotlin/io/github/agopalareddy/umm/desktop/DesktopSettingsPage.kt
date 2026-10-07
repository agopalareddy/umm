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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.linux.BindResult
import io.github.agopalareddy.umm.linux.JavaSoundMicrophone
import io.github.agopalareddy.umm.settings.Page
import io.github.agopalareddy.umm.settings.RadioRow
import io.github.agopalareddy.umm.settings.Section
import io.github.agopalareddy.umm.settings.SwitchRow
import io.github.agopalareddy.umm.ui.LocalUmm
import kotlinx.coroutines.launch

object DesktopRoutes {
    const val SETUP = "desktop/setup"
    const val SETTINGS = "desktop/settings"
}

/** Settings that only exist on the desktop: the shortcut, the status orb, start at login and the microphone. */
@Composable
fun DesktopSettingsPage(engine: DesktopEngine, onBack: () -> Unit, onSetup: () -> Unit) {
    val scope = rememberCoroutineScope()
    val platform = LocalUmm.current.platform
    val prefs by engine.graph.desktopSettings.prefs.collectAsState(DesktopPrefs())
    val bind by engine.bindResult.collectAsState()
    val change: ((DesktopPrefs) -> DesktopPrefs) -> Unit = { transform -> scope.launch { engine.graph.desktopSettings.update(transform) } }
    val microphones = remember { JavaSoundMicrophone.inputDevices() }

    Page("Desktop", onBack) {
        if (!engine.graph.keyringAvailable) KeyringWarning()

        Section("Shortcut") {
            ShortcutCard(
                bind = bind,
                onRetry = engine::bindHotkey,
                onChange = {
                    scope.launch {
                        if (!engine.hotkey.configure()) platform.showMessage("Change it in your desktop's shortcut settings")
                    }
                },
            )
        }

        Section("Status orb") {
            OrbPosition.entries.forEach { position ->
                RadioRow(selected = prefs.orbPosition == position, onClick = { change { it.copy(orbPosition = position) } }) {
                    Text(position.label())
                }
            }
            SwitchRow(prefs.sounds, { on -> change { it.copy(sounds = on) } }) {
                Text("Sounds", Modifier.weight(1f))
            }
        }

        Section("Startup") {
            SwitchRow(
                checked = prefs.startAtLogin,
                onCheckedChange = { on -> scope.launch { engine.setStartAtLogin(on) } },
                enabled = engine.launcher != null,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Start at login")
                    if (engine.launcher == null) Text("Only in the installed app", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Section("Microphone") {
            RadioRow(selected = prefs.microphone == null, onClick = { change { it.copy(microphone = null) } }) { Text("System default") }
            microphones.forEach { name ->
                RadioRow(selected = prefs.microphone == name, onClick = { change { it.copy(microphone = name) } }) { Text(name) }
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSetup) { Text("Run setup again") }
    }
}

@Composable
internal fun KeyringWarning() {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            "No keyring found: your key won't be saved",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
internal fun ShortcutCard(bind: BindResult?, onRetry: () -> Unit, onChange: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (bind) {
                null -> Text("Setting up…")
                is BindResult.Bound -> {
                    Text(bind.trigger, style = MaterialTheme.typography.titleLarge)
                    Text("Tap to start and stop. Hold to talk.", style = MaterialTheme.typography.bodySmall)
                }
                BindResult.Unsupported -> {
                    Text("Not available on this desktop", style = MaterialTheme.typography.titleMedium)
                    Text("Use Start dictation in the tray menu.", style = MaterialTheme.typography.bodySmall)
                }
                is BindResult.Failed -> Text(bind.reason, style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bind is BindResult.Failed) Button(onClick = onRetry) { Text("Try again") }
                if (bind is BindResult.Bound) OutlinedButton(onClick = onChange) { Text("Change shortcut") }
            }
        }
    }
}

private fun OrbPosition.label() = when (this) {
    OrbPosition.BOTTOM_CENTER -> "Bottom centre"
    OrbPosition.BOTTOM_LEFT -> "Bottom left"
    OrbPosition.BOTTOM_RIGHT -> "Bottom right"
    OrbPosition.TOP_CENTER -> "Top centre"
}
