package io.github.agopalareddy.umm.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressFeedbackTest {
    private val feedback = PressFeedback()

    @Test fun aPressIsPendingUntilConfirmed() {
        feedback.pressed()
        assertTrue(feedback.pending)
        feedback.confirmed()
        assertFalse(feedback.pending)
    }

    @Test fun buzzesWhenConfirmedAfterTheRecordingBegan() {
        feedback.pressed()
        assertFalse(feedback.begun())
        assertTrue(feedback.confirmed())
    }

    @Test fun buzzesWhenTheRecordingBeginsAfterConfirmation() {
        feedback.pressed()
        assertFalse(feedback.confirmed())
        assertTrue(feedback.begun())
    }

    @Test fun buzzesOncePerPress() {
        feedback.pressed()
        feedback.begun()
        assertTrue(feedback.confirmed())
        assertFalse(feedback.confirmed())
    }

    @Test fun aDragNeverBuzzes() {
        feedback.pressed()
        feedback.clear()
        assertFalse(feedback.pending)
        assertFalse(feedback.begun())
        assertFalse(feedback.confirmed())
    }

    @Test fun aNewPressStartsOver() {
        feedback.pressed()
        feedback.begun()
        feedback.confirmed()
        feedback.pressed()
        assertTrue(feedback.pending)
        assertFalse(feedback.confirmed())
        assertTrue(feedback.begun())
    }
}
