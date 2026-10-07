package io.github.agopalareddy.umm.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.desktop.engine.DesktopState
import io.github.agopalareddy.umm.ui.UmmTheme
import io.github.agopalareddy.umm.ui.VoiceOrb
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlinx.coroutines.delay

private val ORB_SIZE = DpSize(140.dp, 140.dp)
private val PILL_SIZE = DpSize(380.dp, 72.dp)

/**
 * The floating status orb: a borderless, always-on-top window that never takes focus (so the target app keeps
 * typing focus), shown while dictating and briefly after.
 */
@Composable
fun OrbWindow(
    state: DesktopState,
    position: OrbPosition,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    // The level changes every 100 ms while listening; only a change of kind should restart the timer.
    val phase = if (state is DesktopState.Listening) DesktopState.Listening(0) else state
    LaunchedEffect(phase) {
        visible = state != DesktopState.Idle
        OrbModel.hideAfterMs(state)?.let {
            delay(it)
            visible = false
        }
    }

    val size = if (OrbModel.message(state) == null) ORB_SIZE else PILL_SIZE
    val windowState = rememberWindowState(size = size, position = placement(position, size))
    LaunchedEffect(position, size) {
        windowState.size = size
        windowState.position = placement(position, size)
    }

    Window(
        onCloseRequest = {},
        state = windowState,
        visible = visible,
        title = "Umm",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        UmmTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val message = OrbModel.message(state)
                when {
                    state is DesktopState.Listening -> ListeningOrb(state.level, onStop, onCancel)
                    state == DesktopState.Processing -> VoiceOrb(DictationState.Transcribing, onClick = {}, onDoubleClick = {})
                    message != null -> Pill(message, failed = state is DesktopState.Failed, onClick = onOpen.takeIf { state == DesktopState.NeedsKey })
                }
            }
        }
    }
}

@Composable
private fun ListeningOrb(level: Int, onStop: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.size(ORB_SIZE), contentAlignment = Alignment.Center) {
        VoiceOrb(DictationState.Listening(level, speechDetected = true), onClick = onStop, onDoubleClick = {})
        Surface(
            onClick = onCancel,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Text("✕", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun Pill(text: String, failed: Boolean, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.padding(horizontal = 8.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun placement(position: OrbPosition, size: DpSize): WindowPosition {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val (x, y) = OrbModel.place(config.bounds, insets, position, size.width.value.toInt(), size.height.value.toInt())
    return WindowPosition(x.dp, y.dp)
}
