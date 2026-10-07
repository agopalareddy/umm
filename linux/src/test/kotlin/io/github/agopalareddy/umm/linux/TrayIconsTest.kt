package io.github.agopalareddy.umm.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayIconsTest {
    /** ARGB of the pixel at ([x], [y]) of a square [size] icon. */
    private fun pixel(data: ByteArray, size: Int, x: Int, y: Int): List<Int> =
        (0 until 4).map { data[(y * size + x) * 4 + it].toInt() and 0xff }

    @Test fun pixmapIsSquareArgb() {
        for (state in TrayState.entries) assertEquals(22 * 22 * 4, TrayIcons.pixmap(state, 22).size)
    }

    @Test fun cornersAreTransparentAndTheBubbleBodyIsOpaque() {
        val data = TrayIcons.pixmap(TrayState.IDLE, 44)
        assertEquals(0, pixel(data, 44, 0, 0)[0])
        assertEquals(255, pixel(data, 44, 8, 22)[0])
    }

    @Test fun eachStateHasItsOwnColor() {
        val colors = TrayState.entries.map { pixel(TrayIcons.pixmap(it, 44), 44, 8, 22) }
        assertEquals(3, colors.toSet().size)
        assertTrue(colors.all { it[0] == 255 })
        assertNotEquals(colors[0], colors[1])
    }

    @Test fun barsAreCutOutOfTheBubble() {
        val data = TrayIcons.pixmap(TrayState.IDLE, 54)
        // At 54 px the mark is scaled 1.125x; the tallest bar's centre (50.5, 51 in mark units) lands on (23, 22).
        assertEquals(0, pixel(data, 54, 23, 22)[0])
        assertEquals(255, pixel(data, 54, 8, 22)[0])
    }
}
