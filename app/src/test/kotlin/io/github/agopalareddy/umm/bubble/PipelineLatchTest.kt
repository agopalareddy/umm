package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.bubble.PipelineView.BUSY
import io.github.agopalareddy.umm.bubble.PipelineView.FAILED
import io.github.agopalareddy.umm.bubble.PipelineView.IDLE
import io.github.agopalareddy.umm.bubble.PipelineView.RECORDING
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.pipeline.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineLatchTest {
    private val latch = PipelineLatch(holdMs = 2_000)
    private val listening = DictationState.Listening(amplitude = 0, speechDetected = false)

    @Test fun withoutAnExpectationTheStateDecides() {
        assertEquals(IDLE, latch.view(DictationState.Idle, 0))
        assertEquals(RECORDING, latch.view(listening, 0))
        assertEquals(BUSY, latch.view(DictationState.Transcribing, 0))
    }

    @Test fun startCountsAsRecordingBeforeThePipelineCatchesUp() {
        latch.expect(RECORDING, now = 1_000)
        assertEquals(RECORDING, latch.view(DictationState.Idle, 1_000))
        assertEquals(RECORDING, latch.view(DictationState.Idle, 1_200))
        assertEquals(RECORDING, latch.view(DictationState.Idle, 2_999))
    }

    @Test fun anIgnoredStartExpiresAfterTheHold() {
        latch.expect(RECORDING, now = 1_000)
        assertEquals(IDLE, latch.view(DictationState.Idle, 3_000))
        // Expired for good, not just for that read.
        assertEquals(IDLE, latch.view(DictationState.Idle, 3_001))
    }

    @Test fun seeingThePipelineMoveEndsTheExpectation() {
        latch.expect(RECORDING, now = 0)
        assertEquals(RECORDING, latch.view(listening, 100))
        // The recording ended (cancel, no speech): idle again, not the stale expectation.
        assertEquals(IDLE, latch.view(DictationState.Idle, 200))
        assertEquals(IDLE, latch.view(DictationState.NoSpeech, 300))
    }

    @Test fun retryCountsAsBusyUntilProcessingShows() {
        latch.expect(BUSY, now = 0)
        assertEquals(BUSY, latch.view(DictationState.Idle, 50))
        assertEquals(BUSY, latch.view(DictationState.Transcribing, 60))
        assertEquals(FAILED, latch.view(DictationState.Failed(1, FailureReason.NETWORK), 70))
        assertEquals(IDLE, latch.view(DictationState.Idle, 80))
    }

    @Test fun doubleTapBeforeThePipelineListensDoesNotStartTwice() {
        val gesture = BubbleGesture(slopPx = 12f)
        assertEquals(emptyList<BubbleCommand>(), gesture.onDown(0, 0f, 0f, latch.view(DictationState.Idle, 0)))
        assertEquals(listOf(BubbleCommand.Start, BubbleCommand.SetSilenceDetection(true)), gesture.onUp(100))
        latch.expect(RECORDING, now = 100)
        // The pipeline still reads Idle, but the second press is the double-tap, not a second Start.
        assertEquals(
            listOf(BubbleCommand.SetSilenceDetection(false)),
            gesture.onDown(200, 0f, 0f, latch.view(DictationState.Idle, 200)),
        )
    }

    @Test fun clearDropsTheExpectation() {
        latch.expect(RECORDING, now = 0)
        latch.clear()
        assertEquals(IDLE, latch.view(DictationState.Idle, 10))
    }
}
