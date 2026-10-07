package io.github.agopalareddy.umm.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerLabelTest {
    @Test fun gnomeAcceleratorBecomesReadable() {
        assertEquals("Super+Alt+Space", TriggerLabel.format("Press <Alt><Super>space"))
        assertEquals("Super+Alt+Space", TriggerLabel.format("<Super><Alt>space"))
    }

    @Test fun otherModifiersAndKeys() {
        assertEquals("Ctrl+Shift+D", TriggerLabel.format("<Control><Shift>d"))
        assertEquals("Super+Ctrl+F5", TriggerLabel.format("<Primary><Super>F5"))
    }

    @Test fun kdeStyleLabelsAreKept() {
        assertEquals("Meta+Alt+Space", TriggerLabel.format("Meta+Alt+Space"))
    }

    // GNOME 50's portal never fires a shortcut that includes Super (measured), so GNOME gets a Ctrl+Alt default.
    @Test fun defaultTriggerDependsOnTheDesktop() {
        assertEquals("LOGO+ALT+space", GlobalShortcutsHotkey.defaultTriggerFor(mapOf("XDG_CURRENT_DESKTOP" to "KDE")))
        assertEquals("CTRL+ALT+space", GlobalShortcutsHotkey.defaultTriggerFor(mapOf("XDG_CURRENT_DESKTOP" to "GNOME")))
        assertEquals("CTRL+ALT+space", GlobalShortcutsHotkey.defaultTriggerFor(emptyMap()))
    }

    @Test fun blankFallsBackToTheDefault() {
        assertEquals("Super+Alt+Space", TriggerLabel.format("  "))
    }
}
