package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.data.BubbleShowMode.ALWAYS
import io.github.agopalareddy.umm.core.data.BubbleShowMode.WHEN_FOCUSED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTrackerTest {
    private val tracker = FocusTracker()

    @Test fun nothingFocusedYetHidesWhenFocusedMode() {
        assertFalse(tracker.shouldShow(WHEN_FOCUSED, 0))
        assertTrue(tracker.shouldShow(ALWAYS, 0))
        assertNull(tracker.recheckIn(0))
    }

    @Test fun owningADictationKeepsItShownAfterFocusLeavesOrOnAPasswordField() {
        tracker.update(editable = true, password = false, now = 0)
        tracker.update(editable = false, password = false, now = 100)
        assertFalse(tracker.shouldShow(WHEN_FOCUSED, 5_000))
        assertTrue(tracker.shouldShow(WHEN_FOCUSED, 5_000, ownsDictation = true))
        tracker.update(editable = true, password = true, now = 6_000)
        assertFalse(tracker.shouldShow(ALWAYS, 6_000))
        assertTrue(tracker.shouldShow(ALWAYS, 6_000, ownsDictation = true))
    }

    @Test fun editableFieldShows() {
        tracker.update(editable = true, password = false, now = 0)
        assertTrue(tracker.shouldShow(WHEN_FOCUSED, 0))
        assertNull(tracker.recheckIn(0))
    }

    @Test fun losingFocusHidesAfterTheDelay() {
        tracker.update(editable = true, password = false, now = 1_000)
        tracker.update(editable = false, password = false, now = 2_000)
        assertTrue(tracker.shouldShow(WHEN_FOCUSED, 2_399))
        assertFalse(tracker.shouldShow(WHEN_FOCUSED, 2_400))
        assertEquals(400L, tracker.recheckIn(2_000))
        assertEquals(1L, tracker.recheckIn(2_399))
        assertNull(tracker.recheckIn(2_400))
    }

    @Test fun laterUnfocusedEventsDoNotRestartTheDelay() {
        tracker.update(editable = true, password = false, now = 0)
        tracker.update(editable = false, password = false, now = 100)
        tracker.update(editable = false, password = false, now = 300)
        assertFalse(tracker.shouldShow(WHEN_FOCUSED, 500))
    }

    @Test fun movingToAnotherFieldKeepsItShown() {
        tracker.update(editable = true, password = false, now = 0)
        tracker.update(editable = false, password = false, now = 100)
        tracker.update(editable = true, password = false, now = 200)
        assertTrue(tracker.shouldShow(WHEN_FOCUSED, 5_000))
        assertNull(tracker.recheckIn(5_000))
    }

    @Test fun passwordFieldHidesInBothModes() {
        tracker.update(editable = true, password = true, now = 0)
        assertFalse(tracker.shouldShow(WHEN_FOCUSED, 0))
        assertFalse(tracker.shouldShow(ALWAYS, 0))
    }

    @Test fun passwordFlagClearsOnTheNextField() {
        tracker.update(editable = true, password = true, now = 0)
        tracker.update(editable = true, password = false, now = 100)
        assertFalse(tracker.password)
        assertTrue(tracker.shouldShow(WHEN_FOCUSED, 100))
    }

    @Test fun passwordFlagClearsWhenFocusIsLost() {
        tracker.update(editable = true, password = true, now = 0)
        tracker.update(editable = false, password = false, now = 100)
        assertFalse(tracker.password)
        assertTrue(tracker.shouldShow(ALWAYS, 100))
    }

    @Test fun leavingAPasswordFieldDoesNotFlashTheBubble() {
        tracker.update(editable = true, password = true, now = 0)
        tracker.update(editable = false, password = false, now = 100)
        assertFalse(tracker.shouldShow(WHEN_FOCUSED, 100))
        assertNull(tracker.recheckIn(100))
    }

    @Test fun passwordOnANonEditableNodeIsIgnored() {
        tracker.update(editable = false, password = true, now = 0)
        assertFalse(tracker.password)
        assertTrue(tracker.shouldShow(ALWAYS, 0))
    }
}
