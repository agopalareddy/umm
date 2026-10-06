package io.github.agopalareddy.umm.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One rounded bar per value with [labels] underneath (thinned out when they would collide, always keeping the
 * newest) and the tallest bar's value above it. [previous] is drawn faint behind, index for index, so the last
 * period shows through wherever it was higher. A bar flagged in [partial] is outlined and lighter, for a period that
 * is not whole. [peakLabel] words the tallest bar's value (default: a bare number).
 */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    previous: List<Float>? = null,
    partial: List<Boolean> = emptyList(),
    peakLabel: (Float) -> String = ::compact,
) {
    val bars = values.map(::plottable)
    val behind = previous.orEmpty().map(::plottable)
    val progress = rememberDrawIn(bars)
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val peakStyle = labelStyle.copy(color = scheme.primary, fontWeight = FontWeight.SemiBold)

    Spacer(
        modifier.fillMaxWidth().height(CHART_HEIGHT)
            .semantics { contentDescription = description }
            .drawWithCache {
                val max = scaleOf(bars + behind)
                val n = bars.size
                val peak = bars.indices.maxByOrNull { bars[it] }?.takeIf { bars[it] > 0f }
                val peakText = peak?.let { measurer.measure(peakLabel(bars[it]), peakStyle, maxLines = 1) }
                val plot = plotArea(
                    top = measurer.measure("0", peakStyle).size.height + 4.dp.toPx(),
                    labelHeight = measurer.measure("0", labelStyle).size.height.toFloat(),
                    side = 0f,
                )
                val slot = if (n > 0) plot.width / n else plot.width
                val barWidth = min(slot * 0.62f, 28.dp.toPx())
                val corner = min(barWidth / 2, 6.dp.toPx())
                fun xOf(i: Int) = plot.left + slot * (i + 0.5f)
                val axis = axisLabels(measurer, labels, n, labelStyle, plot.bottom + 6.dp.toPx(), slot, ::xOf)
                val path = Path()
                val ghost = scheme.primary.copy(alpha = 0.2f)

                val outline = Stroke(1.5.dp.toPx())

                fun DrawScope.bar(i: Int, value: Float, t: Float, color: Color, outlined: Boolean = false) {
                    val h = value / max * plot.height * t
                    if (h <= 0f) return
                    val r = min(corner, h)
                    val left = xOf(i) - barWidth / 2
                    path.reset()
                    path.addRoundRect(
                        RoundRect(
                            left, plot.bottom - h, left + barWidth, plot.bottom,
                            topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                            bottomRightCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = CornerRadius.Zero,
                        ),
                    )
                    drawPath(path, if (outlined) color.copy(alpha = 0.3f) else color)
                    if (outlined) drawPath(path, color, style = outline)
                }

                onDrawBehind {
                    drawGrid(plot, scheme.outlineVariant)
                    axis.forEach { (layout, at) -> drawText(layout, topLeft = at) }
                    val p = progress.value
                    for (i in 0 until n) {
                        val t = stagger(p, i, n)
                        behind.getOrNull(i)?.let { bar(i, it, t, ghost) }
                        bar(i, bars[i], t, scheme.primary, outlined = partial.getOrNull(i) == true)
                    }
                    if (peak != null && peakText != null) {
                        val t = stagger(p, peak, n)
                        if (t > 0f) {
                            val w = peakText.size.width
                            val x = (xOf(peak) - w / 2f).coerceAtMost(size.width - w).coerceAtLeast(0f)
                            val top = plot.bottom - bars[peak] / max * plot.height * t
                            drawText(peakText, topLeft = Offset(x, top - peakText.size.height - 2.dp.toPx()), alpha = t)
                        }
                    }
                }
            },
    )
}

val CHART_HEIGHT = 160.dp

/**
 * Draw-in progress from 0 to 1, restarted whenever [key] (the drawn data) changes. It starts, and stays, at 1
 * when the system's animations are off.
 */
@Composable
fun rememberDrawIn(key: Any?): State<Float> {
    val motion = rememberMotionEnabled()
    val progress = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(key, motion) {
        if (motion) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    return progress.asState()
}

/**
 * Top of the scale for [values] (already [plottable]): the largest one, so small amounts such as a few cents of
 * spend still fill the chart. With nothing above zero it falls back to [ChartText.scaleMax], so it is never zero.
 */
fun scaleOf(values: List<Float>): Float {
    val top = values.maxOrNull() ?: 0f
    return if (top > 0f) top else ChartText.scaleMax(values)
}

/** A value a chart can draw: finite and not below zero. */
fun plottable(value: Float?): Float = if (value != null && value.isFinite() && value > 0f) value else 0f

/** Per-item progress for a left-to-right wave: item [i] of [n] starts a little after the one before it. */
fun stagger(p: Float, i: Int, n: Int): Float =
    if (n <= 1) p else ((p - 0.3f * i / (n - 1)) / 0.7f).coerceIn(0f, 1f)

/** Short number for a label: `7`, `42`, `1.2k`, `15k`, `0.034`. */
fun compact(value: Float): String {
    val v = plottable(value)
    return when {
        v >= 9_950f -> "${(v / 1000).roundToInt()}k"
        v >= 999.5f -> "${oneDecimal(v / 1000)}k"
        v >= 10f -> v.roundToInt().toString()
        v >= 1f -> oneDecimal(v)
        v > 0f -> BigDecimal(v.toDouble()).round(MathContext(2)).stripTrailingZeros().toPlainString()
        else -> "0"
    }
}

private fun oneDecimal(v: Float): String {
    val tenths = (v * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** The drawing area: [top] reserved above, a label row below, [side] on the left and right. */
fun CacheDrawScope.plotArea(top: Float, labelHeight: Float, side: Float): Rect =
    Rect(side, top, size.width - side, (size.height - labelHeight - 6.dp.toPx()).coerceAtLeast(top))

/** Faint dashed lines at the top and middle of the scale, and a solid baseline, across the full width. */
fun DrawScope.drawGrid(plot: Rect, color: Color) {
    val stroke = 1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
    val faint = color.copy(alpha = color.alpha * 0.6f)
    for (y in listOf(plot.top, plot.top + plot.height / 2)) {
        drawLine(faint, Offset(0f, y), Offset(size.width, y), stroke, pathEffect = dash)
    }
    drawLine(color, Offset(0f, plot.bottom), Offset(size.width, plot.bottom), stroke)
}

/**
 * Measured x-axis labels that fit side by side. Picks every k-th label so the widest fits in the [spacing]
 * between points, counting back from the last so the newest always shows, and drops any that would still overlap
 * (in practice only the oldest, when it is nudged in from the left edge).
 */
fun CacheDrawScope.axisLabels(
    measurer: TextMeasurer,
    labels: List<String>,
    count: Int,
    style: TextStyle,
    top: Float,
    spacing: Float,
    xOf: (Int) -> Float,
): List<Pair<TextLayoutResult, Offset>> {
    if (count == 0) return emptyList()
    val layouts = List(count) { measurer.measure(labels.getOrElse(it) { "" }, style, maxLines = 1) }
    val gap = 8.dp.toPx()
    val widest = layouts.maxOf { it.size.width }
    // The newest label is nudged in from the right edge; leave room for that so the one before it still fits.
    val nudge = (xOf(count - 1) + layouts[count - 1].size.width / 2f - size.width).coerceAtLeast(0f)
    val step = if (spacing > 0f) ceil((widest + gap + nudge) / spacing).toInt().coerceAtLeast(1) else 1
    val placed = mutableListOf<Pair<TextLayoutResult, Offset>>()
    var limit = size.width
    for (i in count - 1 downTo 0 step step) {
        val w = layouts[i].size.width
        if (w == 0) continue
        val x = (xOf(i) - w / 2f).coerceAtMost(size.width - w).coerceAtLeast(0f)
        if (x + w > limit) continue
        placed += layouts[i] to Offset(x, top)
        limit = x - gap
    }
    return placed
}
