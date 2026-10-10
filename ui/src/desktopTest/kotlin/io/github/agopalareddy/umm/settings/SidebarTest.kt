package io.github.agopalareddy.umm.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidebarTest {
    private fun item(route: String) = SidebarItem(Icons.Default.Home, route, route)

    private val items = listOf(item(Routes.HOME), item(Routes.STATS), item(Routes.HISTORY), item("settings/desktop"))

    @Test fun selectsExactRoute() {
        assertEquals(Routes.HISTORY, sidebarSelection(items, Routes.HISTORY))
    }

    @Test fun subRouteSelectsParent() {
        assertEquals("settings/desktop", sidebarSelection(items, "settings/desktop/shortcut"))
    }

    @Test fun unknownRouteSelectsNothing() {
        assertNull(sidebarSelection(items, "setup"))
        assertNull(sidebarSelection(items, null))
    }
}
