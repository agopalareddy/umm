package io.github.agopalareddy.umm.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortalAppearanceTest {
    @Test fun colorSchemeMapping() {
        assertNull(AppearanceMapping.dark(0))
        assertEquals(true, AppearanceMapping.dark(1))
        assertEquals(false, AppearanceMapping.dark(2))
        assertNull(AppearanceMapping.dark(7))
    }

    @Test fun accentMapping() {
        assertEquals(0xFFFF0000.toInt(), AppearanceMapping.accent(1.0, 0.0, 0.0))
        assertEquals(0xFF336699.toInt(), AppearanceMapping.accent(0.2, 0.4, 0.6))
        assertNull(AppearanceMapping.accent(-1.0, 0.5, 0.5))
        assertNull(AppearanceMapping.accent(1.2, 0.0, 0.0))
    }
}
