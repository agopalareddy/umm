package io.github.agopalareddy.umm.desktop

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlatformTest {
    private val calls = mutableListOf<List<String>>()
    private val platform = DesktopPlatform(SnackbarHostState(), TestScope(), launcher = { calls += it })

    @Test fun openUrl_and_composeEmail_useXdgOpen() {
        platform.openUrl("https://example.com")
        assertTrue(platform.composeEmail("agr@agreddy.com", "Hi there", "Body & more"))
        assertEquals(listOf("xdg-open", "https://example.com"), calls[0])
        assertEquals(listOf("xdg-open", "mailto:agr@agreddy.com?subject=Hi%20there&body=Body%20%26%20more"), calls[1])
    }

    @Test fun composeEmail_reportsFailureWhenXdgOpenIsMissing() {
        val broken = DesktopPlatform(SnackbarHostState(), TestScope(), launcher = { throw java.io.IOException("no xdg-open") })
        assertEquals(false, broken.composeEmail("a@b.c", "s", "b"))
    }

    @Test fun desktopHasNoAppListOrDynamicColors() {
        assertEquals(emptyList<Any>(), platform.installedApps())
        assertEquals("desktop", platform.appLabel("desktop"))
        assertNull(platform.dynamicColors(dark = true))
        assertTrue(platform.animationsEnabled())
    }
}
