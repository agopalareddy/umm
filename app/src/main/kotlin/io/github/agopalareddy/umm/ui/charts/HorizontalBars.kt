package io.github.agopalareddy.umm.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One labelled row with an optional [sublabel] and its [valueText]; the bar under it is the value's share. */
internal data class BarItem(val label: String, val value: Float, val valueText: String, val sublabel: String? = null)

/** A ranked list: each item's label and value, with a bar scaled to the largest value. */
@Composable
internal fun HorizontalBars(items: List<BarItem>, description: String, modifier: Modifier = Modifier) {
    val drawIn = rememberDrawIn(items)
    val scheme = MaterialTheme.colorScheme
    val max = scaleOf(items.map { plottable(it.value) })
    val track = scheme.primary.copy(alpha = 0.14f)
    val fill = SolidColor(scheme.primary)

    Column(
        modifier.fillMaxWidth().semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Nothing to rank still draws the empty track, so the card keeps its shape.
        if (items.isEmpty()) Spacer(Modifier.fillMaxWidth().height(8.dp).drawBehind { drawTrack(0f, track) { fill } })
        items.forEachIndexed { i, item ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item.sublabel?.let {
                            Text(
                                it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        item.valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 1, modifier = Modifier.padding(start = 12.dp),
                    )
                }
                val share = plottable(item.value) / max
                Spacer(
                    Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp).drawBehind {
                        drawTrack(share * stagger(drawIn.value, i, items.size), track) { fill }
                    },
                )
            }
        }
    }
}
