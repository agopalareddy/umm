package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
internal fun NavRow(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) =
    NavRow(rememberVectorPainter(icon), title, summary, onClick)

@Composable
internal fun NavRow(icon: Painter, title: String, summary: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// Rows where the whole line, label included, toggles the control.

/** [dimmed] grays the row out but keeps it tappable, so the caller can explain why the option won't work. */
@Composable
internal fun RadioRow(selected: Boolean, onClick: () -> Unit, dimmed: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .alpha(if (dimmed) 0.45f else 1f).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        content()
    }
}

@Composable
internal fun CheckRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        content()
    }
}

/** [content] sits before the switch; give it Modifier.weight(1f). */
@Composable
internal fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
