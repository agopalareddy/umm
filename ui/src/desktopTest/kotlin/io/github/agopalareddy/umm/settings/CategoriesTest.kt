package io.github.agopalareddy.umm.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesTest {
    private val seeds = setOf("com.google.android.keep", "org.kde.kate", "org.gnome.TextEditor")

    @Test fun hidesSeedsForAppsNotInstalledHere() {
        val assigned = listOf("com.google.android.keep", "org.kde.kate", "org.gnome.TextEditor")
        assertEquals(listOf("org.kde.kate"), visibleAssignments(assigned, setOf("org.kde.kate", "firefox"), seeds))
    }

    @Test fun keepsAssignmentsTheUserMadeEvenWithoutALauncherEntry() {
        // The keyboard can assign packages with no launcher icon, such as notification replies in System UI.
        val assigned = listOf("com.android.systemui", "com.google.android.keep")
        assertEquals(listOf("com.android.systemui"), visibleAssignments(assigned, emptySet(), seeds))
    }
}
