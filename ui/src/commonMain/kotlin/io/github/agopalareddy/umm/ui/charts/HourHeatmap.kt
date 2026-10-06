package io.github.agopalareddy.umm.ui.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.format.TextStyle
import io.github.agopalareddy.umm.ui.LocalUmm
import java.util.Locale
import kotlin.math.min

/**
 * A week of hours: 7 rows, Monday first, of 24 cells indexed `[weekday][hour]` like [grid], with weekday names on
 * the left and every sixth hour underneath. A cell fills more, and more strongly, the closer its count is to the
 * busiest hour's. A missing weekday or hour counts as zero, so a short or empty grid still draws an empty week.
 */
@Composable
fun HourHeatmap(grid: List<List<Int>>, description: String, modifier: Modifier = Modifier) {
    val progress = rememberDrawIn(grid)
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val clock24 = LocalUmm.current.platform.is24HourClock()

    Spacer(
        modifier.fillMaxWidth().height(HOUR_HEATMAP_HEIGHT)
            .semantics { contentDescription = description }
            .drawWithCache {
                val counts = List(7) { day -> List(24) { hour -> grid.getOrNull(day)?.getOrNull(hour)?.coerceAtLeast(0) ?: 0 } }
                val max = counts.maxOf { it.max() }
                val locale = Locale.getDefault()
                val rowLabels = DayOfWeek.entries.map { measurer.measure(it.getDisplayName(TextStyle.SHORT, locale), labelStyle, maxLines = 1) }
                val ticks = listOf(0, 6, 12, 18).map { it to measurer.measure(hourLabel(it, clock24), labelStyle, maxLines = 1) }
                val gridLeft = rowLabels.maxOf { it.size.width } + 6.dp.toPx()
                val tickRow = ticks[0].second.size.height + 4.dp.toPx()
                val colPitch = ((size.width - gridLeft) / 24).coerceAtLeast(0f)
                val rowPitch = ((size.height - tickRow) / 7).coerceAtLeast(0f)
                val gap = 2.dp.toPx()
                val cell = Size((colPitch - gap).coerceAtLeast(0f), (rowPitch - gap).coerceAtLeast(0f))
                val corner = CornerRadius(min(cell.minDimension / 4, 3.dp.toPx()))
                fun cellAt(hour: Int, day: Int) = Offset(gridLeft + hour * colPitch, day * rowPitch)
                val track = scheme.primary.copy(alpha = 0.07f)

                onDrawBehind {
                    rowLabels.forEachIndexed { day, layout ->
                        drawText(layout, topLeft = Offset(0f, cellAt(0, day).y + (cell.height - layout.size.height) / 2))
                    }
                    ticks.forEach { (hour, layout) -> drawText(layout, topLeft = Offset(cellAt(hour, 0).x, rowPitch * 7 + 4.dp.toPx())) }
                    val p = progress.value
                    for (hour in 0 until 24) {
                        val t = stagger(p, hour, 24)
                        for (day in 0 until 7) {
                            val level = if (max > 0) counts[day][hour].toFloat() / max else 0f
                            drawHeatCell(cellAt(hour, day), cell, level * t, scheme.primary, track, corner)
                        }
                    }
                }
            },
    )
}

private val HOUR_HEATMAP_HEIGHT = 136.dp

/** `00:00`, `06:00`, ... on a 24-hour clock; `12am`, `6am`, `12pm`, `6pm` otherwise. */
private fun hourLabel(hour: Int, clock24: Boolean): String = when {
    clock24 -> "%02d:00".format(Locale.ROOT, hour)
    hour == 0 -> "12am"
    hour < 12 -> "${hour}am"
    hour == 12 -> "12pm"
    else -> "${hour - 12}pm"
}
