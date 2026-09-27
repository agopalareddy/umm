package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

/** A full-screen page with a title bar and an optional back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Page(
    title: @Composable () -> Unit,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding ->
        val scroll = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(Modifier.fillMaxSize().padding(padding).then(scroll).padding(horizontal = 16.dp, vertical = 8.dp), content = content)
    }
}

@Composable
internal fun Page(title: String, onBack: (() -> Unit)?, scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit) =
    Page(title = { Text(title) }, onBack = onBack, scrollable = scrollable, content = content)

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
internal fun NavRow(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
