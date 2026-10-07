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

    @Test fun blankFallsBackToTheDefault() {
        assertEquals("Super+Alt+Space", TriggerLabel.format("  "))
    }
}
