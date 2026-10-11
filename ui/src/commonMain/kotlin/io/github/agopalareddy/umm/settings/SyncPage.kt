package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.sync.DeviceInfo
import io.github.agopalareddy.umm.sync.SyncEngine
import io.github.agopalareddy.umm.sync.SyncStatus
import io.github.agopalareddy.umm.sync.pair.PairedDevice
import io.github.agopalareddy.umm.sync.pair.PairingError
import io.github.agopalareddy.umm.sync.pair.PairingException
import io.github.agopalareddy.umm.sync.pair.PairingOffer
import io.github.agopalareddy.umm.ui.LocalUmm
import io.github.agopalareddy.umm.ui.QrCode
import io.github.agopalareddy.umm.ui.relativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SyncPage(onBack: (() -> Unit)?) {
    val umm = LocalUmm.current
    val sync = umm.sync

    Page("Sync", onBack) {
        if (sync == null) {
            Text("Sync is not available on this platform.", style = MaterialTheme.typography.bodyMedium)
            return@Page
        }

        val status by sync.status.collectAsStateWithLifecycle()
        val devices by sync.devices.collectAsStateWithLifecycle()
        val groupState by sync.group.state.collectAsStateWithLifecycle()
        val relayErrors by sync.relay.relayErrors.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val platform = umm.platform

        var showPairDialog by remember { mutableStateOf(false) }
        var showJoinDialog by remember { mutableStateOf(false) }
        var showLeaveDialog by remember { mutableStateOf(false) }
        var showRenameDialog by remember { mutableStateOf(false) }
        var memberToRemove by remember { mutableStateOf<Member?>(null) }

        // 1. Status Card
        SyncStatusCard(status, umm.platform.is24HourClock())
        Spacer(Modifier.height(16.dp))

        if (groupState == null) {
            // Not Paired State
            Text(
                "Sync your dictation history, stats, and settings across your devices using peer-to-peer Wi-Fi and encrypted Nostr relays.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showPairDialog = true }) {
                    Text("Pair a device")
                }
                OutlinedButton(onClick = { showJoinDialog = true }) {
                    Text("Paste code")
                }
                val scanAction = platform.scanPairingCode()
                if (scanAction != null) {
                    OutlinedButton(onClick = {
                        scope.launch {
                          val scanned = runCatching { scanAction() }.getOrNull()
                          if (scanned != null) {
                              try {
                                  val paired = sync.pairing.join(scanned.trim(), sendKey = true)
                                  platform.showMessage("Paired with ${paired.name}!")
                              } catch (e: PairingException) {
                                  platform.showMessage(e.error.userMessage())
                              } catch (e: Throwable) {
                                  platform.showMessage(e.message ?: "Failed to pair")
                              }
                          }
                        }
                    }) {
                        Text("Scan code")
                    }
                }
            }
        } else {
            // Paired State
            // Devices Section
            Section("Devices") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { dev ->
                        DeviceCard(
                            device = dev,
                            onRename = { showRenameDialog = true },
                            onRemove = { memberToRemove = dev.member },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Transport switches
            Section("Transports") {
                val current = groupState!!
                SwitchRow(
                    checked = current.lanEnabled,
                    onCheckedChange = { scope.launch { sync.setTransports(it, current.relayEnabled) } },
                ) {
                    Text("Sync over Wi-Fi", Modifier.weight(1f))
                }
                SwitchRow(
                    checked = current.relayEnabled,
                    onCheckedChange = { scope.launch { sync.setTransports(current.lanEnabled, it) } },
                ) {
                    Text("Sync through relays", Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(12.dp))

            // Advanced / Relays
            RelaysSection(
                relays = groupState!!.relays,
                relayErrors = relayErrors,
                isRelayConnected = { sync.relay.isConnected(it) },
                onAddRelay = { newRelay ->
                    val updated = groupState!!.relays + newRelay
                    scope.launch { sync.setRelays(updated) }
                },
                onRemoveRelay = { relayToRemove ->
                    val updated = groupState!!.relays.filter { it != relayToRemove }
                    scope.launch { sync.setRelays(updated) }
                },
            )

            Spacer(Modifier.height(16.dp))

            // Main Actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showPairDialog = true }) {
                    Text("Pair a device")
                }
                OutlinedButton(
                    onClick = { scope.launch { sync.syncNow() } },
                    enabled = status !is SyncStatus.Syncing,
                ) {
                    Text("Sync now")
                }
                OutlinedButton(onClick = { showLeaveDialog = true }) {
                    Text("Leave group")
                }
            }
        }

        // Dialogs
        if (showPairDialog) {
            PairDeviceDialog(
                sync = sync,
                onDismiss = { showPairDialog = false },
            )
        }

        if (showJoinDialog) {
            JoinDeviceDialog(
                sync = sync,
                onDismiss = { showJoinDialog = false },
            )
        }

        if (showLeaveDialog) {
            AlertDialog(
                onDismissRequest = { showLeaveDialog = false },
                title = { Text("Leave group?") },
                text = { Text("This device will stop syncing with your other devices. Its local history and stats will stay.") },
                confirmButton = {
                    TextButton(onClick = {
                        showLeaveDialog = false
                        scope.launch { sync.membership.leave() }
                    }) {
                        Text("Leave")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLeaveDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (showRenameDialog) {
            var newName by remember { mutableStateOf(sync.group.deviceName) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename this device") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Device name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRenameDialog = false
                        if (newName.isNotBlank()) {
                            scope.launch { sync.membership.rename(newName.trim()) }
                        }
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        if (memberToRemove != null) {
            val member = memberToRemove!!
            AlertDialog(
                onDismissRequest = { memberToRemove = null },
                title = { Text("Remove ${member.name}?") },
                text = { Text("This device will no longer sync with the group.") },
                confirmButton = {
                    TextButton(onClick = {
                        memberToRemove = null
                        scope.launch { sync.membership.remove(member.deviceId) }
                    }) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { memberToRemove = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun SyncStatusCard(status: SyncStatus, is24Hour: Boolean) {
    val containerColor = when (status) {
        SyncStatus.NotPaired -> MaterialTheme.colorScheme.surfaceVariant
        SyncStatus.Syncing -> MaterialTheme.colorScheme.secondaryContainer
        is SyncStatus.Idle -> MaterialTheme.colorScheme.primaryContainer
        is SyncStatus.Error -> MaterialTheme.colorScheme.errorContainer
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val statusText = when (status) {
                SyncStatus.NotPaired -> "Not paired"
                SyncStatus.Syncing -> "Syncing…"
                is SyncStatus.Idle -> {
                    val lastSync = status.lastSync
                    if (lastSync == null) {
                        "Not synced yet"
                    } else {
                        val rel = relativeTime(lastSync, System.currentTimeMillis()).lowercase()
                        val via = when (status.via) {
                            "lan" -> "Wi-Fi"
                            "relay" -> "relays"
                            "lan+relay" -> "Wi-Fi and relays"
                            else -> status.via ?: "Wi-Fi"
                        }
                        "Synced $rel via $via"
                    }
                }
                is SyncStatus.Error -> {
                    val lastSync = status.lastSync
                    if (lastSync != null) {
                        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
                        val timeStr = Instant.ofEpochMilli(lastSync).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
                        "Not synced since $timeStr — ${status.message}"
                    } else {
                        "Sync error — ${status.message}"
                    }
                }
            }

            Text(statusText, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceInfo,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.member.name, style = MaterialTheme.typography.titleMedium)
                    if (device.isSelf) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "(this device)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val lastSeen = device.lastSeen
                val seenText = when {
                    device.isSelf -> "Active now"
                    lastSeen != null -> "Seen ${relativeTime(lastSeen, System.currentTimeMillis()).lowercase()}"
                    else -> "Not seen yet"
                }
                Text(
                    "${device.member.platform.replaceFirstChar { it.uppercase() }} · $seenText",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (device.isSelf) {
                TextButton(onClick = onRename) {
                    Text("Rename")
                }
            } else {
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun RelaysSection(
    relays: List<String>,
    relayErrors: Map<String, String>,
    isRelayConnected: (String) -> Boolean = { true },
    onAddRelay: (String) -> Unit,
    onRemoveRelay: (String) -> Unit,
) {
    Section("Relays") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            relays.forEach { url ->
                val error = relayErrors[url] ?: if (!isRelayConnected(url)) "Not connected" else null
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(url, style = MaterialTheme.typography.bodyMedium)
                        if (error != null) {
                            Text(
                                "Failing: $error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (relays.size > 1) {
                        IconButton(onClick = { onRemoveRelay(url) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove relay", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            var newRelay by remember { mutableStateOf("") }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newRelay,
                    onValueChange = { newRelay = it.trim() },
                    placeholder = { Text("wss://relay.example.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (newRelay.startsWith("wss://") || newRelay.startsWith("ws://")) {
                            onAddRelay(newRelay)
                            newRelay = ""
                        }
                    },
                    enabled = newRelay.startsWith("wss://") || newRelay.startsWith("ws://"),
                ) {
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun PairDeviceDialog(
    sync: SyncEngine,
    onDismiss: () -> Unit,
) {
    val platform = LocalUmm.current.platform
    var sendKey by remember { mutableStateOf(true) }
    var offer by remember(sendKey) { mutableStateOf(sync.pairing.offer(sendKey)) }
    var pairedResult by remember { mutableStateOf<PairedDevice?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(offer) {
        try {
            val res = offer.result.await()
            pairedResult = res
        } catch (_: CancellationException) {
        } catch (e: Throwable) {
            errorText = e.message ?: "Pairing failed"
        }
    }

    val codeText = offer.code.encode()

    AlertDialog(
        onDismissRequest = {
            offer.result.cancel()
            onDismiss()
        },
        title = { Text("Pair a device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pairedResult != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Paired with ${pairedResult!!.name}!", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    QrCode(
                        text = codeText,
                        modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = codeText,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text("Code") },
                        )
                        OutlinedButton(onClick = {
                            platform.copyText(codeText)
                            platform.showMessage("Code copied")
                        }) {
                            Text("Copy")
                        }
                    }

                    CheckRow(
                        checked = sendKey,
                        onCheckedChange = { sendKey = it },
                    ) {
                        Text("Send OpenRouter key")
                    }

                    Text(
                        "Encrypted copies of your synced data pass through public Nostr relays. They can't read it.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (errorText != null) {
                        Text(
                            errorText!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                offer.result.cancel()
                onDismiss()
            }) {
                Text(if (pairedResult != null) "Done" else "Cancel")
            }
        },
    )
}

@Composable
private fun JoinDeviceDialog(
    sync: SyncEngine,
    onDismiss: () -> Unit,
) {
    val platform = LocalUmm.current.platform
    val scope = rememberCoroutineScope()
    var codeInput by remember { mutableStateOf("") }
    var sendKey by remember { mutableStateOf(true) }
    var joining by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    label = { Text("Pairing code") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    enabled = !joining,
                )
                CheckRow(
                    checked = sendKey,
                    onCheckedChange = { sendKey = it },
                ) {
                    Text("Send OpenRouter key")
                }
                if (joinError != null) {
                    Text(
                        joinError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        joining = true
                        joinError = null
                        try {
                            val paired = sync.pairing.join(codeInput.trim(), sendKey)
                            platform.showMessage("Paired with ${paired.name}!")
                            onDismiss()
                        } catch (e: PairingException) {
                            joinError = e.error.userMessage()
                        } catch (e: Throwable) {
                            joinError = e.message ?: "Failed to pair"
                        } finally {
                            joining = false
                        }
                    }
                },
                enabled = codeInput.isNotBlank() && !joining,
            ) {
                Text(if (joining) "Pairing…" else "Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !joining) {
                Text("Cancel")
            }
        },
    )
}
