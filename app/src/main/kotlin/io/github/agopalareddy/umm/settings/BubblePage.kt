package io.github.agopalareddy.umm.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.bubble.Area
import io.github.agopalareddy.umm.bubble.BubbleControl
import io.github.agopalareddy.umm.bubble.BubblePosition
import io.github.agopalareddy.umm.bubble.BubbleService
import io.github.agopalareddy.umm.bubble.BubbleSetup
import io.github.agopalareddy.umm.core.data.BubbleEdge
import io.github.agopalareddy.umm.core.data.BubbleShowMode
import io.github.agopalareddy.umm.core.data.BubbleSize
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.ui.VoiceOrb
import kotlin.math.roundToInt

internal const val DISCLOSURE_TITLE = "Umm needs the Accessibility permission for the floating button."
internal const val DISCLOSURE_TEXT =
    "It is used to see which text field you have selected, so it can put your dictated text there, " +
        "and which app it is in, to pick the cleanup level. Umm reads only the selected field's current " +
        "text and cursor position, never password fields, and never reads or stores anything else on your " +
        "screen. Your speech goes to OpenRouter as usual; nothing else leaves your phone."

internal fun BubbleShowMode.label() = when (this) {
    BubbleShowMode.WHEN_FOCUSED -> "When a text field is focused"
    BubbleShowMode.ALWAYS -> "Always"
}

internal fun BubbleSize.label() = when (this) {
    BubbleSize.SMALL -> "Small"
    BubbleSize.MEDIUM -> "Medium"
    BubbleSize.LARGE -> "Large"
}

/** The Reset position button: the spec's default dock, leaving every other setting as it is. */
internal fun resetBubblePosition(settings: UmmSettings): UmmSettings =
    settings.copy(bubbleEdge = BubbleEdge.RIGHT, bubbleYPortrait = 0.6f, bubbleYLandscape = 0.5f)

/** What the disclosure's buttons do. Consent and the switch are stored together, only on Accept. */
internal data class DisclosureOutcome(val settings: UmmSettings, val openAccessibility: Boolean)

internal fun disclosureOutcome(settings: UmmSettings, accepted: Boolean, now: Long): DisclosureOutcome =
    if (accepted) {
        DisclosureOutcome(settings.copy(disclosureAcceptedAt = now, bubbleEnabled = true), openAccessibility = true)
    } else {
        DisclosureOutcome(settings, openAccessibility = false)
    }

/** What flipping the switch on does: ask for consent first, or (consent already stored) turn it on. */
internal sealed interface SwitchOn {
    data object ShowDisclosure : SwitchOn
    data class Enable(val openAccessibility: Boolean) : SwitchOn
}

internal fun switchOn(settings: UmmSettings, connected: Boolean): SwitchOn =
    if (settings.disclosureAcceptedAt == 0L) SwitchOn.ShowDisclosure else SwitchOn.Enable(openAccessibility = !connected)

// Hidden SDK constants (Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS and
// Settings.EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME are not in the public stubs); the strings are stable.
internal const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
internal const val EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME = "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME"

internal data class LaunchSpec(val action: String, val extras: Map<String, String>)

/** Umm's own accessibility page first, the generic list if that can't be opened. */
internal fun accessibilityLaunchPlan(serviceComponent: String): List<LaunchSpec> = listOf(
    LaunchSpec(ACTION_ACCESSIBILITY_DETAILS_SETTINGS, mapOf(EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME to serviceComponent)),
    LaunchSpec(Settings.ACTION_ACCESSIBILITY_SETTINGS, emptyMap()),
)

/** Marks the enable as awaited, then opens Umm's page in Accessibility settings (or the list, as a fallback). */
private fun openAccessibilitySettings(context: Context) {
    BubbleSetup.awaitingEnable = true
    val component = ComponentName(context, BubbleService::class.java).flattenToString()
    for (spec in accessibilityLaunchPlan(component)) {
        val intent = Intent(spec.action).apply { spec.extras.forEach { (k, v) -> putExtra(k, v) } }
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    BubbleSetup.awaitingEnable = false
}

@Composable
internal fun BubblePage(settings: UmmSettings, onBack: () -> Unit, onChange: SettingsChange) {
    val context = LocalContext.current
    val connected by BubbleService.connected.collectAsStateWithLifecycle()
    var showDisclosure by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }

    // The page is back in front. If the user backed out of Accessibility settings without turning Umm on, a later
    // enable from system settings must not pull the app forward; when the service connected, it has cleared the flag.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumes++
        if (BubbleSetup.shouldClearAwaiting(BubbleSetup.awaitingEnable, BubbleService.connected.value)) {
            BubbleSetup.awaitingEnable = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (BubbleSetup.shouldClearAwaiting(BubbleSetup.awaitingEnable, BubbleService.connected.value)) {
                BubbleSetup.awaitingEnable = false
            }
        }
    }

    val advancedProtectionBlocked = remember(resumes) {
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val apm = context.getSystemService(android.security.advancedprotection.AdvancedProtectionManager::class.java)
                apm?.isAdvancedProtectionEnabled == true
            }.getOrDefault(false)
        } else {
            false
        }
    }

    Page("Floating button", onBack) {
        SwitchRow(
            checked = settings.bubbleEnabled && !advancedProtectionBlocked,
            enabled = !advancedProtectionBlocked,
            onCheckedChange = { on ->
                if (on) {
                    when (val action = switchOn(settings, connected)) {
                        SwitchOn.ShowDisclosure -> showDisclosure = true
                        is SwitchOn.Enable -> {
                            onChange { it.copy(bubbleEnabled = true) }
                            if (action.openAccessibility) openAccessibilitySettings(context)
                        }
                    }
                } else {
                    onChange { it.copy(bubbleEnabled = false) }
                }
            },
        ) {
            Text("Floating button", Modifier.weight(1f))
        }

        if (advancedProtectionBlocked) {
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Android blocks the floating button because Advanced Protection is turned on.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "The Umm keyboard still works as usual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        } else if (settings.bubbleEnabled) {
            Spacer(Modifier.height(4.dp))
            if (connected) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Active", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openAccessibilitySettings(context) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Turn Umm on in Accessibility settings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (!connected && !advancedProtectionBlocked) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clickable { showHelp = !showHelp }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Can't turn it on?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (showHelp) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (showHelp) {
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "If Android restricts this setting:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("1. Open system Settings → Apps → Umm", style = MaterialTheme.typography.bodySmall)
                        Text("2. Tap ⋮ (the three dots in the top right corner)", style = MaterialTheme.typography.bodySmall)
                        Text("3. Tap Allow restricted settings", style = MaterialTheme.typography.bodySmall)
                        Text("4. Return here and turn Umm on in Accessibility settings", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            },
                        ) {
                            Text("Open Umm's app info")
                        }
                    }
                }
            }
        }

        Section("Visibility") {
            BubbleShowMode.entries.forEach { mode ->
                RadioRow(
                    selected = settings.bubbleVisibility == mode,
                    onClick = { onChange { it.copy(bubbleVisibility = mode) } },
                ) {
                    Text(mode.label())
                }
            }
        }

        Section("Size") {
            BubbleSize.entries.forEach { size ->
                RadioRow(
                    selected = settings.bubbleSize == size,
                    onClick = { onChange { it.copy(bubbleSize = size) } },
                ) {
                    Text(size.label())
                }
            }
        }

        Section("Position") {
            BubblePreview(settings, onChange)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { onChange { resetBubblePosition(it) } }) {
                    Text("Reset position")
                }
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text(DISCLOSURE_TITLE) },
            text = { Text(DISCLOSURE_TEXT, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        val now = System.currentTimeMillis()
                        onChange { disclosureOutcome(it, accepted = true, now = now).settings }
                        if (disclosureOutcome(settings, accepted = true, now = now).openAccessibility) {
                            openAccessibilitySettings(context)
                        }
                    },
                ) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDisclosure = false },
                ) {
                    Text("Decline")
                }
            },
        )
    }
}

@Composable
private fun BubblePreview(
    settings: UmmSettings,
    onChange: SettingsChange,
) {
    val density = LocalDensity.current.density
    val boxPx = BubblePosition.boxPx(settings.bubbleSize, density)
    val orbSizePx = BubblePosition.sizePx(settings.bubbleSize, density)

    val previewWidthDp = 180.dp
    val previewHeightDp = 300.dp
    val previewWidthPx = with(LocalDensity.current) { previewWidthDp.roundToPx() }
    val previewHeightPx = with(LocalDensity.current) { previewHeightDp.roundToPx() }
    val area = remember(previewWidthPx, previewHeightPx) {
        Area(0, 0, previewWidthPx, previewHeightPx)
    }

    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    val (placedX, placedY) = BubblePosition.place(area, boxPx, settings.bubbleEdge, settings.bubbleYPortrait)
    val targetX = if (dragging) dragX else placedX.toFloat()
    val targetY = if (dragging) dragY else placedY.toFloat()
    val animX by animateFloatAsState(
        targetValue = targetX,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
        label = "animX",
    )
    val animY by animateFloatAsState(
        targetValue = targetY,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
        label = "animY",
    )
    val currentX = if (dragging) dragX else animX
    val currentY = if (dragging) dragY else animY

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(previewWidthDp, previewHeightDp)
                .clip(RoundedCornerShape(24.dp))
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .pointerInput(settings.bubbleSize, area, placedX, placedY) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            val orbCenterX = placedX + boxPx / 2f
                            val orbCenterY = placedY + boxPx / 2f
                            val dist = kotlin.math.hypot(offset.x - orbCenterX, offset.y - orbCenterY)
                            val (startX, startY) = if (dist < boxPx) {
                                placedX.toFloat() to placedY.toFloat()
                            } else {
                                BubbleControl.clamp(area, boxPx, offset.x - boxPx / 2f, offset.y - boxPx / 2f)
                            }
                            dragX = startX
                            dragY = startY
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val (cx, cy) = BubbleControl.clamp(area, boxPx, dragX + dragAmount.x, dragY + dragAmount.y)
                            dragX = cx
                            dragY = cy
                        },
                        onDragEnd = {
                            dragging = false
                            val snap = BubblePosition.snap(area, boxPx, dragX + boxPx / 2f, dragY + boxPx / 2f)
                            onChange { it.copy(bubbleEdge = snap.edge, bubbleYPortrait = snap.yFraction) }
                        },
                        onDragCancel = {
                            dragging = false
                        },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
                    .size(with(LocalDensity.current) { boxPx.toDp() }),
                contentAlignment = Alignment.Center,
            ) {
                VoiceOrb(
                    state = DictationState.Idle,
                    onClick = {},
                    onDoubleClick = {},
                    modifier = Modifier
                        .requiredSize(92.dp)
                        .graphicsLayer {
                            val scale = orbSizePx / 88.dp.toPx()
                            scaleX = scale
                            scaleY = scale
                        },
                    clickable = false,
                )
            }
        }
    }
}
