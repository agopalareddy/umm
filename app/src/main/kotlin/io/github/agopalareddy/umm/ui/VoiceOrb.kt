package io.github.agopalareddy.umm.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.pipeline.DictationState

/**
 * The dictation button. It shows the state at a glance and reacts to the voice: halos swell with loudness and
 * fall back slowly, while silence leaves only a faint breathing. Its layout size never changes, so it is safe in
 * a fixed-height panel; all motion is drawing.
 *
 * [continuousRing] overrides the ring and the "Finish" label; pass it only while listening (null follows the state).
 * [clickable] false leaves touch to the caller: no click handling and no ripple.
 */
@Composable
internal fun VoiceOrb(
    state: DictationState,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier,
    continuousRing: Boolean? = null,
    clickable: Boolean = true,
) {
    val listening = state as? DictationState.Listening
    val busy = state == DictationState.Transcribing || state == DictationState.Cleaning
    val failed = state is DictationState.Failed
    val continuous = continuousRing ?: (listening?.continuous == true)

    // Rise fast, fall slowly: a jittery orb reads as noise, a smooth one as a voice.
    val level = remember { Animatable(0f) }
    val target = listening?.let { VoiceLevel.of(it.amplitude) } ?: 0f
    LaunchedEffect(target) { level.animateTo(target, tween(if (target > level.value) 70 else 350)) }

    val transition = rememberInfiniteTransition(label = "orb")
    val breath = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val spin = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")

    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        when {
            failed -> scheme.errorContainer
            listening != null -> scheme.primary
            busy -> scheme.primaryContainer
            else -> scheme.secondaryContainer
        },
        tween(200), label = "fill",
    )
    val ink = when {
        failed -> scheme.onErrorContainer
        listening != null -> scheme.onPrimary
        busy -> scheme.onPrimaryContainer
        else -> scheme.onSecondaryContainer
    }
    // While busy the spinner ring around the disc says it; the disc stays empty.
    val icon = when {
        failed -> Icons.Rounded.Refresh
        listening != null -> Icons.Rounded.Stop
        busy -> null
        else -> Icons.Rounded.Mic
    }
    val description = when {
        continuous -> "Finish"
        listening != null -> "Stop"
        busy -> "Working"
        failed -> "Retry"
        else -> "Speak"
    }
    val accent = scheme.primary

    Box(
        modifier.size(SLOT).drawBehind {
            val radius = DISC.toPx() / 2
            val l = level.value
            if (listening != null) {
                // Outer halo first so the inner ones layer on top. Silence keeps only the innermost, breathing barely.
                for (i in 2 downTo 0) {
                    val reach = (5 + 5 * i).dp.toPx() * l + if (i == 0) 1.5.dp.toPx() * breath.value else 0f
                    val alpha = (0.30f - 0.09f * i) * l + if (i == 0) 0.07f + 0.03f * breath.value else 0f
                    drawCircle(accent.copy(alpha = alpha), radius + reach)
                }
            }
            if (continuous) drawCircle(accent.copy(alpha = 0.8f), radius + 4.dp.toPx(), style = Stroke(2.dp.toPx()))
            if (busy) {
                val ring = radius + 3.dp.toPx()
                drawCircle(accent.copy(alpha = 0.15f), ring, style = Stroke(3.dp.toPx()))
                drawArc(
                    accent, spin.value, 100f, useCenter = false,
                    topLeft = Offset(center.x - ring, center.y - ring), size = Size(ring * 2, ring * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(DISC)
                .graphicsLayer {
                    val s = 1f + 0.06f * level.value + if (listening != null) 0.015f * breath.value else 0f
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(fill)
                .semantics {
                    contentDescription = description
                    // TalkBack's double-tap is an ordinary click, so the double-tap gesture needs an action of its own.
                    if (!busy) customActions = listOf(CustomAccessibilityAction("Record until I finish") { onDoubleClick(); true })
                }
                .then(
                    if (clickable) {
                        Modifier.combinedClickable(enabled = !busy, role = Role.Button, onClick = onClick, onDoubleClick = onDoubleClick)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(40.dp))
        }
    }
}

private val SLOT = 92.dp
private val DISC = 88.dp
