package io.github.agopalareddy.umm.ui.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * A smoothed line over [values] with a soft fill under it and a dot on the newest point. A null value breaks the
 * line; a point with no neighbours is drawn as a dot. The line draws in from the left.
 */
@Composable
internal fun LineChart(values: List<Float?>, labels: List<String>, description: String, modifier: Modifier = Modifier) {
    val points = values.map { v -> v?.let(::plottable) }
    val progress = rememberDrawIn(points)
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)

    Spacer(
        modifier.fillMaxWidth().height(CHART_HEIGHT)
            .semantics { contentDescription = description }
            .drawWithCache {
                val max = scaleOf(points.filterNotNull())
                val n = points.size
                // Side and top room for the halo around the newest point.
                val inset = 8.dp.toPx()
                val plot = plotArea(top = inset, labelHeight = measurer.measure("0", labelStyle).size.height.toFloat(), side = inset)
                val spacing = if (n > 1) plot.width / (n - 1) else plot.width
                fun xOf(i: Int) = if (n == 1) plot.center.x else plot.left + spacing * i
                val at = points.mapIndexed { i, v -> v?.let { Offset(xOf(i), plot.bottom - it / max * plot.height) } }

                // Runs of consecutive points; a null ends one.
                val runs = mutableListOf<List<Offset>>()
                var run = mutableListOf<Offset>()
                for (o in at) {
                    if (o != null) {
                        run += o
                    } else if (run.isNotEmpty()) {
                        runs += run
                        run = mutableListOf()
                    }
                }
                if (run.isNotEmpty()) runs += run

                val line = Path()
                val fill = Path()
                for (r in runs.filter { it.size > 1 }) {
                    line.moveTo(r[0].x, r[0].y)
                    fill.moveTo(r[0].x, plot.bottom)
                    fill.lineTo(r[0].x, r[0].y)
                    val m = monotoneSlopes(r)
                    for (k in 1 until r.size) {
                        val a = r[k - 1]
                        val b = r[k]
                        val h = (b.x - a.x) / 3
                        line.cubicTo(a.x + h, a.y + m[k - 1] * h, b.x - h, b.y - m[k] * h, b.x, b.y)
                        fill.cubicTo(a.x + h, a.y + m[k - 1] * h, b.x - h, b.y - m[k] * h, b.x, b.y)
                    }
                    fill.lineTo(r.last().x, plot.bottom)
                    fill.close()
                }
                val lone = runs.filter { it.size == 1 }.map { it[0] }
                val last = at.lastOrNull { it != null }
                val shade = Brush.verticalGradient(
                    listOf(scheme.primary.copy(alpha = 0.28f), scheme.primary.copy(alpha = 0f)),
                    startY = plot.top, endY = plot.bottom,
                )
                val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val axis = axisLabels(measurer, labels, n, labelStyle, plot.bottom + 6.dp.toPx(), spacing, ::xOf)

                onDrawBehind {
                    drawGrid(plot, scheme.outlineVariant)
                    axis.forEach { (layout, pos) -> drawText(layout, topLeft = pos) }
                    val p = progress.value
                    clipRect(right = size.width * p) {
                        drawPath(fill, shade)
                        drawPath(line, scheme.primary, style = stroke)
                        lone.forEach { drawCircle(scheme.primary, 3.dp.toPx(), it) }
                    }
                    if (last != null) {
                        val s = ((p - 0.8f) / 0.2f).coerceIn(0f, 1f)
                        if (s > 0f) {
                            drawCircle(scheme.primary.copy(alpha = 0.22f), 8.dp.toPx() * s, last)
                            drawCircle(scheme.primary, 4.dp.toPx() * s, last)
                        }
                    }
                }
            },
    )
}

/**
 * Tangent slopes for a monotone cubic through [p] (Fritsch–Carlson): the curve is smooth but never bulges past
 * its neighbouring points, so it cannot overshoot the data or dip below the baseline.
 */
private fun monotoneSlopes(p: List<Offset>): FloatArray {
    val n = p.size
    val d = FloatArray(n - 1) { (p[it + 1].y - p[it].y) / (p[it + 1].x - p[it].x) }
    val m = FloatArray(n) { k ->
        when {
            k == 0 -> d[0]
            k == n - 1 -> d[n - 2]
            d[k - 1] * d[k] <= 0f -> 0f
            else -> (d[k - 1] + d[k]) / 2
        }
    }
    for (k in 0 until n - 1) {
        if (d[k] == 0f) {
            m[k] = 0f
            m[k + 1] = 0f
            continue
        }
        val a = m[k] / d[k]
        val b = m[k + 1] / d[k]
        val s = a * a + b * b
        if (s > 9f) {
            val t = 3f / sqrt(s)
            m[k] = t * a * d[k]
            m[k + 1] = t * b * d[k]
        }
    }
    return m
}
