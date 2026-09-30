package io.github.agopalareddy.umm.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

internal data class DonutSlice(val label: String, val value: Float)

private class Arc(val label: String, val value: Float, val color: Color)

/**
 * A ring split by [slices] with the total in the middle and a legend (label, value, share) below. A slice keeps
 * its color by position, so a zero slice is left out without shifting the others; with nothing above zero only
 * the empty ring is drawn.
 */
@Composable
internal fun Donut(slices: List<DonutSlice>, description: String, modifier: Modifier = Modifier) {
    val drawIn = rememberDrawIn(slices)
    val scheme = MaterialTheme.colorScheme
    val palette = CleanupColors.palette(scheme)
    val arcs = slices.mapIndexedNotNull { i, s ->
        plottable(s.value).takeIf { it > 0f }?.let { Arc(s.label, it, palette[i % palette.size]) }
    }
    val total = arcs.sumOf { it.value.toDouble() }.toFloat()
    val track = scheme.primary.copy(alpha = 0.1f)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(RING)
                .clearAndSetSemantics { contentDescription = description }
                .drawWithCache {
                    val width = 18.dp.toPx()
                    val radius = (size.minDimension - width) / 2
                    val topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius)
                    val box = Size(radius * 2, radius * 2)
                    val stroke = Stroke(width)
                    // A hairline of card between slices; none when one slice is the whole ring.
                    val gap = if (arcs.size > 1) 2.5f else 0f
                    onDrawBehind {
                        drawCircle(track, radius, style = stroke)
                        if (total <= 0f) return@onDrawBehind
                        val budget = 360f * drawIn.value
                        var start = 0f
                        for (arc in arcs) {
                            val visible = min(arc.value / total * 360f, budget - start)
                            if (visible <= 0f) break
                            // A tiny share still gets a sliver rather than vanishing into the gap.
                            val sweep = maxOf(visible - gap, min(visible, 1f))
                            drawArc(arc.color, start - 90f + gap / 2, sweep, false, topLeft, box, style = stroke)
                            start += arc.value / total * 360f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(compact(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("total", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        if (arcs.isNotEmpty()) Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            arcs.forEach { arc -> LegendRow(arc, total) }
        }
    }
}

@Composable
private fun LegendRow(arc: Arc, total: Float) {
    val pct = arc.value / total * 100
    val share = if (pct < 1f) "<1%" else "${pct.roundToInt()}%"
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(arc.color, CircleShape))
        Text(
            arc.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Text(compact(arc.value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            share, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private val RING = 136.dp
