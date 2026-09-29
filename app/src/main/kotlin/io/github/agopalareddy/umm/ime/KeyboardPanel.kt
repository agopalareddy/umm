package io.github.agopalareddy.umm.ime

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.pipeline.FailureReason
import io.github.agopalareddy.umm.ui.UmmTheme
import io.github.agopalareddy.umm.ui.VoiceOrb

@Composable
internal fun KeyboardPanel(service: UmmInputMethodService) {
    UmmTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            // The keyboard button has its own row, so nothing above it can grow over it.
            Column(Modifier.fillMaxWidth().height(300.dp).navigationBarsPadding().padding(12.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val ui = service.ui
                    when {
                        !ui.setupDone -> Message("Umm needs microphone access and an OpenRouter key.", "Finish setup") { service.openApp() }
                        ui.password -> Message("Voice input is off in password fields.", null) {}
                        else -> Dictation(service, ui)
                    }
                }
                TextButton(onClick = service::onSwitchKeyboard) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Keyboard")
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            AssistChip(onClick = { picker = if (picker == Picker.LEVEL) null else Picker.LEVEL }, label = { Text(ui.level?.label() ?: "Level") })
            LanguageChip(ui) { service.onLanguageChosen(it) }
            AssistChip(
                onClick = { picker = if (picker == Picker.CATEGORY) null else Picker.CATEGORY },
                label = { CategoryLabel(ui) },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(8.dp))
        when (picker) {
            Picker.LEVEL -> Options(CleanupLevel.entries.map { it.label() to { service.onLevelChosen(it); picker = null } })
            Picker.CATEGORY -> Options(Category.entries.map { it.label() to { service.onCategoryChosen(it); picker = null } })
            null -> {
                VoiceOrb(state, onClick = service::onMicTapped, onDoubleClick = service::onMicDoubleTapped)
                Spacer(Modifier.height(6.dp))
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

/** One line whatever the screen width: every option takes an equal share, so none can wrap onto the keyboard button. */
@Composable
private fun Options(options: List<Pair<String, () -> Unit>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (label, onClick) ->
            Button(onClick = onClick, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** "App · Category"; an unassigned app shows a prompt instead. Only the app name shrinks when space runs out. */
@Composable
private fun CategoryLabel(ui: PanelContext) {
    ProvideTextStyle(MaterialTheme.typography.labelMedium) {
        Row {
            if (ui.appLabel.isNotEmpty()) {
                Text(ui.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" · ", maxLines = 1, softWrap = false)
            }
            val category = ui.category?.category ?: Category.OTHER
            if (category == Category.OTHER) {
                Text("Other", maxLines = 1, softWrap = false, textDecoration = TextDecoration.Underline)
                Text(" · ", maxLines = 1, softWrap = false)
                Text("Tap to assign", maxLines = 1, softWrap = false, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(category.label(), maxLines = 1, softWrap = false)
            }
        }
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
