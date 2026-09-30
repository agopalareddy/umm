package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.data.BubbleEdge
import io.github.agopalareddy.umm.core.data.BubbleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePositionTest {
    private val area = Area(left = 0, top = 100, right = 1080, bottom = 2100)
    private val box = 112

    private fun assertInside(area: Area, boxPx: Int, pos: Pair<Int, Int>) {
        val (x, y) = pos
        assertTrue("x=$x in ${area.left}..${area.right - boxPx}", x in area.left..(area.right - boxPx))
        assertTrue("y=$y in ${area.top}..${area.bottom - boxPx}", y in area.top..(area.bottom - boxPx))
    }

    @Test fun sizesAtDensityTwo() {
        assertEquals(88, BubblePosition.sizePx(BubbleSize.SMALL, 2f))
        assertEquals(112, BubblePosition.sizePx(BubbleSize.MEDIUM, 2f))
        assertEquals(144, BubblePosition.sizePx(BubbleSize.LARGE, 2f))
    }

    @Test fun smallSizeStillHasA48DpTouchTarget() {
        assertEquals(88, BubblePosition.sizePx(BubbleSize.SMALL, 2f))
        assertEquals(96, BubblePosition.touchPx(BubbleSize.SMALL, 2f))
        assertEquals(112, BubblePosition.touchPx(BubbleSize.MEDIUM, 2f))
    }

    @Test fun placeRightEdgeAtHalfHeight() {
        // span = 2000 - 112 = 1888; y = 100 + 0.5 * 1888
        assertEquals(1080 - box to 1044, BubblePosition.place(area, box, BubbleEdge.RIGHT, 0.5f))
    }

    @Test fun placeLeftEdgeUsesAreaLeft() {
        val offset = Area(left = 40, top = 0, right = 1000, bottom = 1000)
        assertEquals(40 to 0, BubblePosition.place(offset, box, BubbleEdge.LEFT, 0f))
    }

    @Test fun placeClampsFraction() {
        assertEquals(100, BubblePosition.place(area, box, BubbleEdge.LEFT, -3f).second)
        assertEquals(2100 - box, BubblePosition.place(area, box, BubbleEdge.LEFT, 7f).second)
        assertEquals(100, BubblePosition.place(area, box, BubbleEdge.LEFT, Float.NEGATIVE_INFINITY).second)
        assertEquals(2100 - box, BubblePosition.place(area, box, BubbleEdge.LEFT, Float.POSITIVE_INFINITY).second)
    }

    @Test fun placeTreatsNanFractionAsMiddle() {
        assertEquals(1044, BubblePosition.place(area, box, BubbleEdge.LEFT, Float.NaN).second)
    }

    @Test fun placeInADegenerateAreaStaysAtTopLeft() {
        val tiny = Area(left = 10, top = 20, right = 100, bottom = 120)
        // area smaller than the box in both axes
        assertEquals(10 to 20, BubblePosition.place(tiny, 200, BubbleEdge.RIGHT, 0.5f))
        // exactly as tall as the box: no vertical travel
        assertEquals(10 to 20, BubblePosition.place(tiny, 100, BubbleEdge.LEFT, 0.9f))
        assertEquals(10 to 20, BubblePosition.place(Area(10, 20, 110, 20), 100, BubbleEdge.LEFT, Float.NaN))
    }

    @Test fun snapChoosesNearerEdge() {
        assertEquals(Snap(BubbleEdge.LEFT, 0.5f), BubblePosition.snap(area, box, 200f, 1100f))
        assertEquals(Snap(BubbleEdge.RIGHT, 0.5f), BubblePosition.snap(area, box, 900f, 1100f))
    }

    @Test fun snapTieGoesRight() {
        assertEquals(BubbleEdge.RIGHT, BubblePosition.snap(area, box, 540f, 1100f).edge)
    }

    @Test fun snapUsesTheAreaMidpointNotTheScreenMidpoint() {
        val offset = Area(left = 100, top = 0, right = 500, bottom = 1000)
        assertEquals(BubbleEdge.LEFT, BubblePosition.snap(offset, box, 299f, 500f).edge)
        assertEquals(BubbleEdge.RIGHT, BubblePosition.snap(offset, box, 300f, 500f).edge)
    }

    @Test fun snapClampsYAtTopAndBottom() {
        assertEquals(0f, BubblePosition.snap(area, box, 900f, -500f).yFraction, 0f)
        assertEquals(1f, BubblePosition.snap(area, box, 900f, 9000f).yFraction, 0f)
    }

    @Test fun snapRoundTripsThroughPlace() {
        val (x, y) = BubblePosition.place(area, box, BubbleEdge.LEFT, 0.25f)
        val snap = BubblePosition.snap(area, box, x + box / 2f, y + box / 2f)
        assertEquals(BubbleEdge.LEFT, snap.edge)
        assertEquals(0.25f, snap.yFraction, 0.001f)
    }

    @Test fun snapWithNanInputsStaysInRange() {
        val snap = BubblePosition.snap(area, box, Float.NaN, Float.NaN)
        assertEquals(BubbleEdge.RIGHT, snap.edge)
        assertEquals(0.5f, snap.yFraction, 0f)
    }

    @Test fun snapInADegenerateAreaDoesNotDivideByZero() {
        val flat = Area(left = 0, top = 0, right = 1080, bottom = 112)
        assertEquals(0f, BubblePosition.snap(flat, box, 100f, 56f).yFraction, 0f)
        val tiny = Area(left = 0, top = 0, right = 1080, bottom = 50)
        assertEquals(0f, BubblePosition.snap(tiny, box, 100f, 25f).yFraction, 0f)
    }

    @Test fun placeAfterRotationStaysInsideArea() {
        val portrait = Area(0, 0, 1080, 2000)
        val landscape = Area(0, 0, 2000, 1080)
        for (edge in BubbleEdge.entries) {
            for (fraction in listOf(0f, 0.5f, 0.9f, 1f)) {
                assertInside(portrait, box, BubblePosition.place(portrait, box, edge, fraction))
                assertInside(landscape, box, BubblePosition.place(landscape, box, edge, fraction))
            }
        }
        assertEquals(2000 - box, BubblePosition.place(landscape, box, BubbleEdge.RIGHT, 0.9f).first)
    }

    @Test fun changingSizeKeepsTheDockedEdge() {
        for (size in BubbleSize.entries) {
            val boxPx = BubblePosition.sizePx(size, 2f)
            val (rx, ry) = BubblePosition.place(area, boxPx, BubbleEdge.RIGHT, 0.8f)
            assertEquals(area.right, rx + boxPx)
            assertInside(area, boxPx, rx to ry)
            val (lx, ly) = BubblePosition.place(area, boxPx, BubbleEdge.LEFT, 0.8f)
            assertEquals(area.left, lx)
            assertInside(area, boxPx, lx to ly)
        }
    }
}
