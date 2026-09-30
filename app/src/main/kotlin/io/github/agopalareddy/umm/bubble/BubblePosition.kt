package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.data.BubbleEdge
import io.github.agopalareddy.umm.core.data.BubbleSize
import kotlin.math.max
import kotlin.math.roundToInt

/** The pixel rectangle the bubble may occupy (screen minus system bars and cutouts). */
data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Where a released bubble docks: a side, and how far down the free travel it sits (0 top .. 1 bottom). */
data class Snap(val edge: BubbleEdge, val yFraction: Float)

/** Pure geometry for the floating bubble: sizes, docking and restoring a position. */
object BubblePosition {
    private const val MIN_TOUCH_DP = 48f
    private const val DEFAULT_FRACTION = 0.5f

    fun sizePx(size: BubbleSize, density: Float): Int {
        val dp = when (size) {
            BubbleSize.SMALL -> 44f
            BubbleSize.MEDIUM -> 56f
            BubbleSize.LARGE -> 72f
        }
        return (dp * density).roundToInt()
    }

    /** The touchable box is never smaller than 48 dp, even when the visible bubble is. */
    fun touchPx(size: BubbleSize, density: Float): Int =
        max(sizePx(size, density), (MIN_TOUCH_DP * density).roundToInt())

    /**
     * Top-left of the bubble docked on [edge]. A stored [yFraction] outside 0..1 is clamped and NaN counts as
     * the middle; an [area] smaller than [boxPx] pins the bubble to its top-left instead of leaving it.
     */
    fun place(area: Area, boxPx: Int, edge: BubbleEdge, yFraction: Float): Pair<Int, Int> {
        val x = when (edge) {
            BubbleEdge.LEFT -> area.left
            BubbleEdge.RIGHT -> max(area.left, area.right - boxPx)
        }
        val span = area.height - boxPx
        val fraction = if (yFraction.isNaN()) DEFAULT_FRACTION else yFraction.coerceIn(0f, 1f)
        val y = if (span > 0) area.top + (fraction * span).roundToInt() else area.top
        return x to y
    }

    /** Docks a bubble released with its centre at ([centerX], [centerY]) on the nearer side; a tie goes right. */
    fun snap(area: Area, boxPx: Int, centerX: Float, centerY: Float): Snap {
        val middle = (area.left + area.right) / 2f
        val edge = if (centerX < middle) BubbleEdge.LEFT else BubbleEdge.RIGHT
        val span = area.height - boxPx
        val fraction = when {
            span <= 0 -> 0f
            centerY.isNaN() -> DEFAULT_FRACTION
            else -> (((centerY - boxPx / 2f) - area.top) / span).coerceIn(0f, 1f)
        }
        return Snap(edge, fraction)
    }
}
