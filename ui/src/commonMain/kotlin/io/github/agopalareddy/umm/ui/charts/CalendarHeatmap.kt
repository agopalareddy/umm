package io.github.agopalareddy.umm.ui.charts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.agopalareddy.umm.core.stats.CalendarDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One column per Monday-to-Sunday week, oldest on the left, with each of [days] placed by its date, so the first
 * and last weeks may be partial. A day's `step` (0 to 4) sets how much of its cell is filled as well as how strong
 * the color is. Month names sit above the week a month starts in when they fit, and today (the newest day) is
 * ringed. With no days it draws 12 empty weeks.
 */
@Composable
fun CalendarHeatmap(days: List<CalendarDay>, description: String, modifier: Modifier = Modifier) {
    val progress = rememberDrawIn(days)
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val density = LocalDensity.current
    val locale = Locale.getDefault()

    val first = days.minOfOrNull { it.date }
    val today = days.maxOfOrNull { it.date }
    val start = first?.with(DayOfWeek.MONDAY)
    fun weekOf(date: LocalDate) = ChronoUnit.WEEKS.between(start, date.with(DayOfWeek.MONDAY)).toInt()
    val weeks = if (today != null) weekOf(today) + 1 else EMPTY_WEEKS

    // Mon, Wed and Fri on the left, as on most calendars; every row would crowd the small cells.
    val rowLabels = listOf(0, 2, 4).map { row ->
        row to measurer.measure(DayOfWeek.of(row + 1).getDisplayName(TextStyle.SHORT, locale), labelStyle, maxLines = 1)
    }
    val labelWidth = rowLabels.maxOf { it.second.size.width }.toFloat()
    val pad = with(density) { 6.dp.toPx() }
    val top = measurer.measure("0", labelStyle).size.height + with(density) { 4.dp.toPx() }
    val maxPitch = with(density) { MAX_PITCH.toPx() }

    Spacer(
        modifier.fillMaxWidth()
            // Cells are square and sized by the width, so the grid spans the card and the height follows from it.
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val pitch = min((width - labelWidth - pad) / weeks, maxPitch).coerceAtLeast(0f)
                val height = (top + 7 * pitch).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .semantics { contentDescription = description }
            .drawWithCache {
                // -1 is a slot outside the range (before the oldest day or after today), left blank.
                val steps = IntArray(weeks * 7) { if (days.isEmpty()) 0 else -1 }
                for (d in days) steps[weekOf(d.date) * 7 + d.date.dayOfWeek.ordinal] = d.step.coerceIn(0, 4)

                val pitch = min((size.width - labelWidth - pad) / weeks, maxPitch).coerceAtLeast(0f)
                val cell = (pitch - 3.dp.toPx()).coerceAtLeast(0f)
                val gridLeft = labelWidth + pad
                val corner = CornerRadius(min(cell / 4, 4.dp.toPx()))
                fun cellAt(week: Int, row: Int) = Offset(gridLeft + week * pitch, top + row * pitch)

                // The oldest week is named by its own month, unless a month starts in it; later weeks only where a
                // month starts. Placed right to left so the newest wins when two would overlap.
                val months = sortedMapOf<Int, LocalDate>()
                first?.let { months[0] = it }
                days.filter { it.date.dayOfMonth == 1 }.forEach { months[weekOf(it.date)] = it.date }
                val monthLabels = mutableListOf<Pair<TextLayoutResult, Offset>>()
                var limit = size.width
                for ((week, date) in months.entries.reversed()) {
                    val layout = measurer.measure(date.month.getDisplayName(TextStyle.SHORT, locale), labelStyle, maxLines = 1)
                    val w = layout.size.width
                    val x = min(cellAt(week, 0).x, size.width - w)
                    if (x < 0f || x + w > limit) continue
                    monthLabels += layout to Offset(x, 0f)
                    limit = x - 8.dp.toPx()
                }

                val track = scheme.primary.copy(alpha = 0.07f)
                val ring = Stroke(1.5.dp.toPx())
                val ringOut = 1.25.dp.toPx()

                onDrawBehind {
                    monthLabels.forEach { (layout, at) -> drawText(layout, topLeft = at) }
                    rowLabels.forEach { (row, layout) ->
                        drawText(layout, topLeft = Offset(0f, cellAt(0, row).y + (cell - layout.size.height) / 2))
                    }
                    val p = progress.value
                    for (week in 0 until weeks) {
                        val t = stagger(p, week, weeks)
                        for (row in 0 until 7) {
                            val step = steps[week * 7 + row]
                            if (step < 0) continue
                            drawHeatCell(cellAt(week, row), Size(cell, cell), step / 4f * t, scheme.primary, track, corner)
                        }
                    }
                    if (today != null) {
                        val at = cellAt(weeks - 1, today.dayOfWeek.ordinal) - Offset(ringOut, ringOut)
                        drawRoundRect(
                            scheme.onSurface, at, Size(cell + ringOut * 2, cell + ringOut * 2),
                            CornerRadius(corner.x + ringOut), style = ring, alpha = stagger(p, weeks - 1, weeks),
                        )
                    }
                }
            },
    )
}

private const val EMPTY_WEEKS = 12

/** Widest a week column (cell and gap) grows, so a tablet gets a grid of sensible size rather than a huge one. */
private val MAX_PITCH = 32.dp

/**
 * One heatmap cell: a faint rounded [track] over the whole cell and, for a [level] above zero (up to 1), a centered
 * block of [color] whose size and strength both grow with the level, so levels differ by more than color alone.
 */
fun DrawScope.drawHeatCell(topLeft: Offset, size: Size, level: Float, color: Color, track: Color, corner: CornerRadius) {
    drawRoundRect(track, topLeft, size, corner)
    if (level <= 0f) return
    val l = level.coerceAtMost(1f)
    // Even the lowest level is a clearly stronger block than the faint track, so it is never mistaken for empty.
    val scale = 0.5f + 0.5f * l
    val inner = Size(size.width * scale, size.height * scale)
    val at = topLeft + Offset((size.width - inner.width) / 2, (size.height - inner.height) / 2)
    drawRoundRect(color.copy(alpha = 0.55f + 0.45f * l), at, inner, CornerRadius(corner.x * scale))
}
