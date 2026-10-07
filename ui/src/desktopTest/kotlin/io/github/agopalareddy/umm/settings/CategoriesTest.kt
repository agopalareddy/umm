package io.github.agopalareddy.umm.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesTest {
    @Test fun listsOnlyAppsInstalledHere() {
        val assigned = listOf("com.google.android.keep", "org.kde.kate", "org.gnome.TextEditor")
        assertEquals(listOf("org.kde.kate"), installedOnly(assigned, setOf("org.kde.kate", "firefox")))
    }

    @Test fun nothingInstalledListsNothing() {
        assertEquals(emptyList<String>(), installedOnly(listOf("notion.id"), emptySet()))
    }
}
