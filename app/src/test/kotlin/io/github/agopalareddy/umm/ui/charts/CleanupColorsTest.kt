package io.github.agopalareddy.umm.ui.charts

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupColorsTest {
    // The app's brand schemes (UmmTheme.kt) on top of the Material defaults for everything they leave out.
    private val lightBrand = lightColorScheme(primary = Color(0xFF5B4FD6), tertiary = Color(0xFF7B5266))
    private val darkBrand = darkColorScheme(primary = Color(0xFFC6BFFF), tertiary = Color(0xFFEBB8CF))

    // What wallpaper colors can do: every role landing on nearly the same tone.
    private val collapsedLight = lightColorScheme(
        primary = Color(0xFF3F5F90), secondary = Color(0xFF3F5F90), tertiary = Color(0xFF3F5F91), error = Color(0xFF3F5F92),
        outline = Color(0xFF3F5F93),
    )
    private val collapsedDark = darkColorScheme(
        primary = Color(0xFFA8C8FF), secondary = Color(0xFFA8C8FF), tertiary = Color(0xFFA8C8FE), error = Color(0xFFA8C8FD),
        outline = Color(0xFFA8C8FC),
    )

    private val light = listOf(lightBrand, collapsedLight)
    private val dark = listOf(darkBrand, collapsedDark)

    /** Distance in sRGB channel space, 0 to about 441. Crude, but two slices under 80 read as one. */
    private fun distance(a: Color, b: Color): Double {
        fun d(x: Float, y: Float) = ((x - y) * 255.0).let { it * it }
        return sqrt(d(a.red, b.red) + d(a.green, b.green) + d(a.blue, b.blue))
    }

    private fun surfaces(s: ColorScheme) = listOf(s.surface, s.surfaceContainer, s.surfaceContainerHighest, s.background)

    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    @Test fun oneColorPerLevelInEnumOrder() {
        for (s in light + dark) assertEquals(CleanupLevel.entries.size, CleanupColors.palette(s).size)
    }

    @Test fun colorsAreOpaque() {
        for (s in light + dark) CleanupColors.palette(s).forEach { assertEquals(1f, it.alpha, 0f) }
    }

    @Test fun colorsAreDistinctFromEachOtherInEveryTheme() {
        for (s in light + dark) {
            val p = CleanupColors.palette(s)
            for (i in p.indices) for (j in i + 1 until p.size) {
                val d = distance(p[i], p[j])
                assertTrue("${CleanupLevel.entries[i]} vs ${CleanupLevel.entries[j]} only $d apart: ${hex(p[i])} ${hex(p[j])}", d >= MIN_DISTANCE)
            }
        }
    }

    @Test fun colorsStandOffTheCardSurfaces() {
        for (s in light + dark) {
            val p = CleanupColors.palette(s)
            for (surface in surfaces(s)) for ((i, c) in p.withIndex()) {
                assertTrue("${CleanupLevel.entries[i]} ${hex(c)} sits on surface ${hex(surface)}", c != surface && distance(c, surface) >= MIN_DISTANCE)
            }
        }
    }

    @Test fun rawStaysTheNeutral() {
        for (s in light + dark) {
            val raw = CleanupColors.palette(s)[CleanupLevel.RAW.ordinal]
            val spread = maxOf(raw.red, raw.green, raw.blue) - minOf(raw.red, raw.green, raw.blue)
            assertTrue("RAW ${hex(raw)} is not grey", spread < 0.06f)
        }
    }

    @Test fun paletteIgnoresTheSchemeRolesItUsedToReadFrom() {
        // Same result whatever primary, tertiary and error a wallpaper produces, so nothing can collapse.
        assertEquals(CleanupColors.palette(lightBrand), CleanupColors.palette(collapsedLight))
        assertEquals(CleanupColors.palette(darkBrand), CleanupColors.palette(collapsedDark))
    }

    @Test fun lightAndDarkPalettesDiffer() {
        assertTrue(CleanupColors.palette(lightBrand) != CleanupColors.palette(darkBrand))
    }

    private companion object {
        const val MIN_DISTANCE = 80.0
    }
}
