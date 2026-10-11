package io.github.agopalareddy.umm.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The changes in each release, newest first, from the CHANGELOG.md built into the app. */
@Composable
fun WhatsNewPage(onBack: (() -> Unit)?) {
    val releases = remember { parseChangelog(changelogLines) }
    Page("What's new", onBack) {
        for (release in releases) {
            Section(listOfNotNull(release.version, release.date).joinToString(" · ")) {
                for (change in release.changes) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("•", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            buildAnnotatedString {
                                for ((text, bold) in boldParts(change)) {
                                    if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text) } else append(text)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
