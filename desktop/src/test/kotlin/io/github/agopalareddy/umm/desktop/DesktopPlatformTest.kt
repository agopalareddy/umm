package io.github.agopalareddy.umm.desktop

import androidx.compose.material3.SnackbarHostState
import io.github.agopalareddy.umm.ui.SystemAppearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlatformTest {
    private val calls = mutableListOf<List<String>>()
    private val appearance = MutableStateFlow(SystemAppearance(dark = null, accentArgb = null))
    private val platform = DesktopPlatform(SnackbarHostState(), TestScope(), appearance, launcher = { calls += it })

    @Test fun accentDrivesDynamicColors() {
        assertNull(platform.dynamicColors(dark = false))
        appearance.value = SystemAppearance(dark = true, accentArgb = 0xFF1E88E5.toInt())
        assertNotNull(platform.dynamicColors(dark = true))
        assertEquals(appearance, platform.systemAppearance())
    }

    @Test fun openUrl_and_composeEmail_useXdgOpen() {
        platform.openUrl("https://example.com")
        assertTrue(platform.composeEmail("agr@agreddy.com", "Hi there", "Body & more"))
        assertEquals(listOf("xdg-open", "https://example.com"), calls[0])
        assertEquals(listOf("xdg-open", "mailto:agr@agreddy.com?subject=Hi%20there&body=Body%20%26%20more"), calls[1])
    }

    @Test fun composeEmail_reportsFailureWhenXdgOpenIsMissing() {
        val broken = DesktopPlatform(SnackbarHostState(), TestScope(), appearance, launcher = { throw java.io.IOException("no xdg-open") })
        assertEquals(false, broken.composeEmail("a@b.c", "s", "b"))
    }

    @Test fun desktopHasNoAppListOrDynamicColors() {
        assertEquals(emptyList<Any>(), platform.installedApps())
        assertEquals("desktop", platform.appLabel("desktop"))
        assertNull(platform.dynamicColors(dark = true))
        assertTrue(platform.animationsEnabled())
    }
}
