package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.data.BubbleShowMode
import io.github.agopalareddy.umm.core.data.BubbleShowMode.ALWAYS
import io.github.agopalareddy.umm.core.data.BubbleShowMode.WHEN_FOCUSED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleVisibilityTest {
    private fun show(
        mode: BubbleShowMode,
        focused: Boolean,
        password: Boolean = false,
        lost: Long? = null,
    ) = BubbleVisibility.shouldShow(mode, focused, password, lost)

    @Test fun passwordHidesInBothModes() {
        assertFalse(show(ALWAYS, focused = true, password = true))
        assertFalse(show(ALWAYS, focused = false, password = true))
        assertFalse(show(WHEN_FOCUSED, focused = true, password = true))
        assertFalse(show(WHEN_FOCUSED, focused = false, password = true, lost = 0))
    }

    @Test fun alwaysShowsWithoutFocus() {
        assertTrue(show(ALWAYS, focused = false))
        assertTrue(show(ALWAYS, focused = false, lost = 10_000))
        assertTrue(show(ALWAYS, focused = true))
    }

    @Test fun whenFocusedShowsWhileFocused() {
        assertTrue(show(WHEN_FOCUSED, focused = true))
    }

    @Test fun whenFocusedKeepsShowingFor399Ms() {
        assertTrue(show(WHEN_FOCUSED, focused = false, lost = 0))
        assertTrue(show(WHEN_FOCUSED, focused = false, lost = 399))
    }

    @Test fun whenFocusedHidesAt400Ms() {
        assertFalse(show(WHEN_FOCUSED, focused = false, lost = 400))
        assertFalse(show(WHEN_FOCUSED, focused = false, lost = 5_000))
    }

    @Test fun neverFocusedHidesInWhenFocusedMode() {
        assertFalse(show(WHEN_FOCUSED, focused = false, lost = null))
    }

    @Test fun hideDelayIs400Ms() {
        assertEquals(400L, BubbleVisibility.HIDE_DELAY_MS)
    }

    @Test fun canRecordNeedsAnEditableNonPasswordField() {
        assertTrue(BubbleVisibility.canRecord(focusedEditable = true, isPassword = false))
        assertFalse(BubbleVisibility.canRecord(focusedEditable = false, isPassword = false))
        assertFalse(BubbleVisibility.canRecord(focusedEditable = true, isPassword = true))
        assertFalse(BubbleVisibility.canRecord(focusedEditable = false, isPassword = true))
    }
}
