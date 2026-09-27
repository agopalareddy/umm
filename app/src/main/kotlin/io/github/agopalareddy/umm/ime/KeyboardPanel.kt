package io.github.agopalareddy.umm.ime

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.pipeline.FailureReason

@Composable
internal fun KeyboardPanel(service: UmmInputMethodService) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.fillMaxWidth().height(260.dp).navigationBarsPadding().padding(12.dp)) {
                val ui = service.ui
                when {
                    !ui.setupDone -> Message("Umm needs microphone access and an OpenRouter key.", "Finish setup") { service.openApp() }
                    ui.password -> Message("Voice input is off in password fields.", null) {}
                    else -> Dictation(service, ui)
                }
                TextButton(onClick = service::onSwitchKeyboard, modifier = Modifier.align(Alignment.BottomStart)) {
                    Text("⌨ Keyboard")
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private enum class Picker { LEVEL, CATEGORY }

@Composable
private fun Dictation(service: UmmInputMethodService, ui: PanelContext) {
    val state by service.state.collectAsState()
    // Options render inside the keyboard window: a popup window would take focus from the app's text field and
    // Android would then hide the keyboard.
    var picker by remember { mutableStateOf<Picker?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { picker = if (picker == Picker.LEVEL) null else Picker.LEVEL }, label = { Text(ui.level?.label() ?: "Level") })
            LanguageChip(ui) { service.onLanguageChosen(it) }
            AssistChip(onClick = { picker = if (picker == Picker.CATEGORY) null else Picker.CATEGORY }, label = { Text(categoryLabel(ui)) })
        }
        Spacer(Modifier.height(16.dp))
        when (picker) {
            Picker.LEVEL -> Options(CleanupLevel.entries.map { it.label() to { service.onLevelChosen(it); picker = null } })
            Picker.CATEGORY -> Options(Category.entries.map { it.label() to { service.onCategoryChosen(it); picker = null } })
            null -> {
                MicButton(state, onClick = service::onMicTapped, onDoubleClick = service::onMicDoubleTapped)
                Spacer(Modifier.height(10.dp))
                Text(statusText(state), style = MaterialTheme.typography.bodyMedium)
                val failed = state as? DictationState.Failed
                when (failed?.reason) {
                    FailureReason.UNAUTHORIZED, FailureReason.MISSING_KEY ->
                        TextButton(onClick = { service.openApp() }) { Text("Reconnect OpenRouter") }
                    FailureReason.NO_CREDITS ->
                        TextButton(onClick = { service.openApp(Uri.parse("https://openrouter.ai/settings/credits")) }) { Text("Add credits on OpenRouter") }
                    else -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(options: List<Pair<String, () -> Unit>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, onClick) -> Button(onClick = onClick) { Text(label) } }
    }
}

private fun categoryLabel(ui: PanelContext): String {
    val category = ui.category?.category ?: Category.OTHER
    val suffix = if (category == Category.OTHER) "Other · Assign" else category.label()
    return "${ui.appLabel.take(14)} · $suffix".removePrefix(" · ")
}

@Composable
private fun MicButton(state: DictationState, onClick: () -> Unit, onDoubleClick: () -> Unit) {
    val listening = state as? DictationState.Listening
    val busy = state == DictationState.Transcribing || state == DictationState.Cleaning
    // Grow with the voice so the user can see the mic hears them.
    val ring = listening?.let { (it.amplitude / 32767f).coerceIn(0f, 1f) } ?: 0f
    val label = when {
        listening?.continuous == true -> "Finish"
        listening != null -> "Stop"
        busy -> "…"
        state is DictationState.Failed -> "Retry"
        else -> "Speak"
    }
    Box(
        Modifier.size((88 + 24 * ring).dp).clip(CircleShape)
            .background(if (listening != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
            .combinedClickable(enabled = !busy, onClick = onClick, onDoubleClick = onDoubleClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (listening != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun statusText(state: DictationState): String = when (state) {
    is DictationState.Listening -> when {
        state.continuous -> "Recording until you tap Finish"
        state.speechDetected -> "Listening…"
        else -> "Speak now · double-tap to whisper"
    }
    DictationState.Transcribing -> "Transcribing…"
    DictationState.Cleaning -> "Cleaning up…"
    is DictationState.Failed -> when (state.reason) {
        FailureReason.UNAUTHORIZED, FailureReason.MISSING_KEY -> "OpenRouter rejected the key"
        FailureReason.NO_CREDITS -> "Your OpenRouter account is out of credits"
        else -> "Couldn't reach OpenRouter"
    }
    DictationState.EmptyTranscript -> "Didn't catch that"
    is DictationState.Done, DictationState.NoSpeech, DictationState.Idle -> "Tap to speak"
}

@Composable
private fun LanguageChip(ui: PanelContext, onChosen: (String) -> Unit) {
    val current = ui.language.encode()
    AssistChip(
        onClick = {
            val options = ui.languages.ifEmpty { listOf(LanguageChoice.AUTO) }
            onChosen(options[(options.indexOf(current) + 1).mod(options.size)])
        },
        label = { Text(if (current == LanguageChoice.AUTO) "Auto" else current.uppercase()) },
    )
}

internal fun CleanupLevel.label() = name.lowercase().replaceFirstChar { it.uppercase() }
internal fun Category.label() = name.lowercase().replaceFirstChar { it.uppercase() }
