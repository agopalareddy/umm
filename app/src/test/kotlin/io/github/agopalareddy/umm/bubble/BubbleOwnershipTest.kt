package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.bubble.PipelineView.BUSY
import io.github.agopalareddy.umm.bubble.PipelineView.FAILED
import io.github.agopalareddy.umm.bubble.PipelineView.IDLE
import io.github.agopalareddy.umm.bubble.PipelineView.RECORDING
import io.github.agopalareddy.umm.core.data.BubbleShowMode
import io.github.agopalareddy.umm.core.data.BubbleShowMode.ALWAYS
import io.github.agopalareddy.umm.core.data.BubbleShowMode.WHEN_FOCUSED
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.pipeline.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleOwnershipTest {
    private val listening = DictationState.Listening(amplitude = 0, speechDetected = true)

    private fun owns(owned: Long?, view: PipelineView, state: DictationState, starting: Boolean = false) =
        BubbleOwnership.owns(starting, owned, view, state)

    @Test fun ownsWhileItsCategoryIsReadBeforeAnyOrigin() {
        assertTrue(owns(owned = null, view = IDLE, state = DictationState.Idle, starting = true))
    }

    @Test fun ownsWhileListeningTranscribingOrCleaning() {
        assertTrue(owns(owned = 5, view = RECORDING, state = listening))
        assertTrue(owns(owned = 5, view = BUSY, state = DictationState.Transcribing))
        assertTrue(owns(owned = 5, view = BUSY, state = DictationState.Cleaning))
    }

    @Test fun aStartOrRetryThePipelineHasNotShownYetIsOwned() {
        // The latch reports the expected view while the pipeline still reads Idle.
        assertTrue(owns(owned = 5, view = RECORDING, state = DictationState.Idle))
        assertTrue(owns(owned = 5, view = BUSY, state = DictationState.Idle))
    }

    @Test fun withoutAnOriginNothingIsOwned() {
        // The keyboard's dictation: the bubble never started one.
        assertFalse(owns(owned = null, view = RECORDING, state = listening))
        assertFalse(owns(owned = null, view = BUSY, state = DictationState.Transcribing))
        assertFalse(owns(owned = null, view = FAILED, state = DictationState.Failed(1, FailureReason.NETWORK, origin = 0)))
    }

    @Test fun ownsAFailureOnlyForItsOwnOrigin() {
        assertTrue(owns(owned = 5, view = FAILED, state = DictationState.Failed(1, FailureReason.NETWORK, origin = 5)))
        assertFalse(owns(owned = 5, view = FAILED, state = DictationState.Failed(1, FailureReason.NETWORK, origin = 4)))
    }

    @Test fun finishedAndIdleAreNotOwned() {
        assertFalse(owns(owned = 5, view = IDLE, state = DictationState.Done(1, "Hi.", cleanupFailed = false, origin = 5)))
        assertFalse(owns(owned = 5, view = IDLE, state = DictationState.Idle))
        assertFalse(owns(owned = 5, view = IDLE, state = DictationState.NoSpeech))
        assertFalse(owns(owned = 5, view = IDLE, state = DictationState.EmptyTranscript))
    }

    @Test fun ownershipEndsWithItsDictation() {
        assertEquals(5L, BubbleOwnership.keep(starting = false, ownedOrigin = 5, view = RECORDING, state = listening))
        assertEquals(
            5L,
            BubbleOwnership.keep(false, 5, FAILED, DictationState.Failed(1, FailureReason.NETWORK, origin = 5)),
        )
        assertNull(BubbleOwnership.keep(false, 5, IDLE, DictationState.Done(1, "Hi.", cleanupFailed = false, origin = 5)))
        assertNull(BubbleOwnership.keep(false, 5, FAILED, DictationState.Failed(1, FailureReason.NETWORK, origin = 4)))
        assertNull(BubbleOwnership.keep(false, 5, IDLE, DictationState.Idle))
        // A start still reading its category keeps the origin it was given.
        assertEquals(5L, BubbleOwnership.keep(starting = true, ownedOrigin = 5, view = IDLE, state = DictationState.Idle))
    }

    // --- Visibility while owning ---

    private fun show(mode: BubbleShowMode, focused: Boolean, password: Boolean, lost: Long?, owns: Boolean) =
        BubbleVisibility.shouldShow(mode, focused, password, lost, ownsDictation = owns)

    @Test fun ownedListeningStaysShownInWhenFocusedModeWithNoFocus() {
        val owned = owns(owned = 5, view = RECORDING, state = listening)
        assertTrue(show(WHEN_FOCUSED, focused = false, password = false, lost = null, owns = owned))
        assertTrue(show(WHEN_FOCUSED, focused = false, password = false, lost = 5_000, owns = owned))
    }

    @Test fun passwordFocusWhileOwningStaysShown() {
        assertTrue(show(WHEN_FOCUSED, focused = true, password = true, lost = null, owns = true))
        assertTrue(show(ALWAYS, focused = true, password = true, lost = null, owns = true))
    }

    @Test fun passwordFocusWithoutOwningHides() {
        assertFalse(show(WHEN_FOCUSED, focused = true, password = true, lost = null, owns = false))
        assertFalse(show(ALWAYS, focused = true, password = true, lost = null, owns = false))
    }

    @Test fun notOwningFallsBackToTheFocusRules() {
        assertFalse(show(WHEN_FOCUSED, focused = false, password = false, lost = 400, owns = false))
        assertTrue(show(WHEN_FOCUSED, focused = false, password = false, lost = 399, owns = false))
        assertTrue(show(WHEN_FOCUSED, focused = true, password = false, lost = null, owns = false))
        assertTrue(show(ALWAYS, focused = false, password = false, lost = null, owns = false))
    }

    @Test fun aFinishedDictationNoLongerHoldsTheBubbleUp() {
        val done = DictationState.Done(1, "Hi.", cleanupFailed = false, origin = 5)
        assertFalse(show(WHEN_FOCUSED, focused = false, password = false, lost = 5_000, owns = owns(5, IDLE, done)))
    }
}
