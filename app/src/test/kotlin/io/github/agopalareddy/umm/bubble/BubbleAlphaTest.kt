package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.pipeline.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleAlphaTest {
    @Test fun idleUntilThreeSecondsThenDimmed() {
        assertEquals(0.6f, BubbleAlpha.of(active = false, msSinceInteraction = 0), 0f)
        assertEquals(0.6f, BubbleAlpha.of(active = false, msSinceInteraction = 2_999), 0f)
        assertEquals(0.4f, BubbleAlpha.of(active = false, msSinceInteraction = 3_000), 0f)
    }

    @Test fun activeIsOpaqueHoweverLongAgo() {
        assertEquals(1f, BubbleAlpha.of(active = true, msSinceInteraction = 0), 0f)
        assertEquals(1f, BubbleAlpha.of(active = true, msSinceInteraction = 60_000), 0f)
    }

    @Test fun activeMeansRecordingOrProcessing() {
        assertTrue(BubbleAlpha.isActive(DictationState.Listening(amplitude = 0, speechDetected = false)))
        assertTrue(BubbleAlpha.isActive(DictationState.Transcribing))
        assertTrue(BubbleAlpha.isActive(DictationState.Cleaning))
        assertFalse(BubbleAlpha.isActive(DictationState.Idle))
        assertFalse(BubbleAlpha.isActive(DictationState.Done(historyId = 1, text = "x", cleanupFailed = false)))
        assertFalse(BubbleAlpha.isActive(DictationState.Failed(historyId = 1, reason = FailureReason.NETWORK)))
        assertFalse(BubbleAlpha.isActive(DictationState.NoSpeech))
        assertFalse(BubbleAlpha.isActive(DictationState.EmptyTranscript))
    }
}
