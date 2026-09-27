package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel

/** Languages offered for the default and the keyboard's language chip. */
internal val LANGUAGES = linkedMapOf(
    "auto" to "Auto-detect",
    "en" to "English",
    "hi" to "Hindi",
    "kn" to "Kannada",
    "ta" to "Tamil",
    "te" to "Telugu",
    "mr" to "Marathi",
    "bn" to "Bengali",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
)

internal fun CleanupLevel.title() = name.lowercase().replaceFirstChar { it.uppercase() }

internal fun CleanupLevel.description() = when (this) {
    CleanupLevel.RAW -> "Exactly what the transcription model heard"
    CleanupLevel.LIGHT -> "Removes filler and self-corrections, fixes punctuation"
    CleanupLevel.FORMATTED -> "Light, plus lists and paragraphs"
    CleanupLevel.POLISHED -> "Rewrites into clear prose"
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
