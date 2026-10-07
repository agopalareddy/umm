package io.github.agopalareddy.umm.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayMenuTest {
    private fun item(state: TrayState, id: Int) = TrayMenu.items(state).single { it.id == id }

    @Test fun idleOffersStart() {
        assertEquals("Start dictation", item(TrayState.IDLE, TrayMenu.START_STOP).label)
        assertFalse(item(TrayState.IDLE, TrayMenu.CANCEL).visible)
        assertEquals(TrayAction.START, TrayMenu.actionFor(TrayMenu.START_STOP, TrayState.IDLE))
    }

    @Test fun recordingOffersStopAndCancel() {
        assertEquals("Stop dictation", item(TrayState.RECORDING, TrayMenu.START_STOP).label)
        assertTrue(item(TrayState.RECORDING, TrayMenu.CANCEL).visible)
        assertEquals(TrayAction.STOP, TrayMenu.actionFor(TrayMenu.START_STOP, TrayState.RECORDING))
        assertEquals(TrayAction.CANCEL, TrayMenu.actionFor(TrayMenu.CANCEL, TrayState.RECORDING))
    }

    @Test fun busyDisablesStart() {
        assertFalse(item(TrayState.BUSY, TrayMenu.START_STOP).enabled)
        assertNull(TrayMenu.actionFor(TrayMenu.START_STOP, TrayState.BUSY))
    }

    @Test fun openAndQuitAlwaysWork() {
        for (state in TrayState.entries) {
            assertEquals(TrayAction.OPEN, TrayMenu.actionFor(TrayMenu.OPEN, state))
            assertEquals(TrayAction.QUIT, TrayMenu.actionFor(TrayMenu.QUIT, state))
        }
        assertNull(TrayMenu.actionFor(TrayMenu.SEPARATOR, TrayState.IDLE))
        assertNull(TrayMenu.actionFor(99, TrayState.IDLE))
    }
}
