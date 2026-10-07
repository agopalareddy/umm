package io.github.agopalareddy.umm.desktop

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentSchemeTest {
    private val red = 0xFFD32F2F.toInt()
    private val blue = 0xFF1E88E5.toInt()

    @Test fun differentAccentsGiveDifferentPrimaries() {
        assertNotEquals(accentColorScheme(red, dark = false).primary, accentColorScheme(blue, dark = false).primary)
    }

    @Test fun primaryLeansTowardsTheAccent() {
        val primary = accentColorScheme(red, dark = false).primary
        assertTrue("red channel should dominate: $primary", primary.red > primary.blue)
    }

    @Test fun darkSchemeHasDarkSurfaceAndLightText() {
        val dark = accentColorScheme(blue, dark = true)
        assertTrue(dark.surface.luminance() < 0.1f)
        assertTrue(dark.onSurface.luminance() > 0.6f)
        val light = accentColorScheme(blue, dark = false)
        assertTrue(light.surface.luminance() > 0.8f)
    }
}
