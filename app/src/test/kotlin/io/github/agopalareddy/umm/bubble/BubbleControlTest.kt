package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.bubble.PipelineView.BUSY
import io.github.agopalareddy.umm.bubble.PipelineView.FAILED
import io.github.agopalareddy.umm.bubble.PipelineView.IDLE
import io.github.agopalareddy.umm.bubble.PipelineView.RECORDING
import io.github.agopalareddy.umm.core.data.BubbleEdge
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.pipeline.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleControlTest {
    private val listening = DictationState.Listening(amplitude = 0, speechDetected = false)
    private val failed = DictationState.Failed(historyId = 7, reason = FailureReason.NETWORK)

    @Test fun pipelineStatesMapToViews() {
        assertEquals(RECORDING, BubbleControl.viewOf(listening))
        assertEquals(RECORDING, BubbleControl.viewOf(listening.copy(continuous = true)))
        assertEquals(BUSY, BubbleControl.viewOf(DictationState.Transcribing))
        assertEquals(BUSY, BubbleControl.viewOf(DictationState.Cleaning))
        assertEquals(FAILED, BubbleControl.viewOf(failed))
        assertEquals(IDLE, BubbleControl.viewOf(DictationState.Idle))
        assertEquals(IDLE, BubbleControl.viewOf(DictationState.Done(historyId = 1, text = "x", cleanupFailed = false)))
        assertEquals(IDLE, BubbleControl.viewOf(DictationState.NoSpeech))
        assertEquals(IDLE, BubbleControl.viewOf(DictationState.EmptyTranscript))
    }

    @Test fun stopAndSilenceChangesNeedARecording() {
        for (view in listOf(IDLE, BUSY, FAILED)) {
            assertFalse(BubbleControl.applies(BubbleCommand.Stop, view))
            assertFalse(BubbleControl.applies(BubbleCommand.SetSilenceDetection(true), view))
            assertFalse(BubbleControl.applies(BubbleCommand.SetSilenceDetection(false), view))
        }
        assertTrue(BubbleControl.applies(BubbleCommand.Stop, RECORDING))
        assertTrue(BubbleControl.applies(BubbleCommand.SetSilenceDetection(true), RECORDING))
        assertTrue(BubbleControl.applies(BubbleCommand.SetSilenceDetection(false), RECORDING))
    }

    @Test fun otherCommandsAlwaysApply() {
        val others = listOf(
            BubbleCommand.Start, BubbleCommand.Cancel, BubbleCommand.Retry, BubbleCommand.Reject,
            BubbleCommand.DragBy(1f, 2f), BubbleCommand.DragEnd(),
        )
        for (command in others) for (view in PipelineView.entries) assertTrue(BubbleControl.applies(command, view))
    }

    @Test fun keyProblemsOpenSetupOnceThenRetry() {
        for (reason in listOf(FailureReason.UNAUTHORIZED, FailureReason.MISSING_KEY)) {
            assertEquals(FailedPress.SETUP, BubbleControl.failedPress(reason, redirected = false))
            // Back from setup with a fixed key: the next press retries instead of sending the user away again.
            assertEquals(FailedPress.RETRY, BubbleControl.failedPress(reason, redirected = true))
        }
    }

    @Test fun noCreditsOpensTheCreditsPageOnceThenRetries() {
        assertEquals(FailedPress.CREDITS, BubbleControl.failedPress(FailureReason.NO_CREDITS, redirected = false))
        assertEquals(FailedPress.RETRY, BubbleControl.failedPress(FailureReason.NO_CREDITS, redirected = true))
    }

    @Test fun otherFailuresRetry() {
        val others = FailureReason.entries - setOf(FailureReason.UNAUTHORIZED, FailureReason.MISSING_KEY, FailureReason.NO_CREDITS)
        assertEquals(5, others.size)
        for (reason in others) for (redirected in listOf(false, true)) {
            assertEquals(FailedPress.RETRY, BubbleControl.failedPress(reason, redirected))
        }
    }

    @Test fun accessibilityClickActsLikeATapOrAStopPress() {
        assertEquals(
            listOf(BubbleCommand.Start, BubbleCommand.SetSilenceDetection(true)),
            BubbleControl.accessibilityClick(IDLE),
        )
        assertEquals(listOf(BubbleCommand.Stop), BubbleControl.accessibilityClick(RECORDING))
        assertEquals(listOf(BubbleCommand.Reject), BubbleControl.accessibilityClick(BUSY))
        assertEquals(listOf(BubbleCommand.Retry), BubbleControl.accessibilityClick(FAILED))
    }

    @Test fun accessibilityDoubleClickRecordsThroughSilence() {
        assertEquals(
            listOf(BubbleCommand.Start, BubbleCommand.SetSilenceDetection(false)),
            BubbleControl.accessibilityDoubleClick(IDLE),
        )
        assertEquals(listOf(BubbleCommand.SetSilenceDetection(false)), BubbleControl.accessibilityDoubleClick(RECORDING))
        assertEquals(listOf(BubbleCommand.Reject), BubbleControl.accessibilityDoubleClick(BUSY))
        assertEquals(listOf(BubbleCommand.Retry), BubbleControl.accessibilityDoubleClick(FAILED))
    }

    @Test fun aRunningSnapToTheSameSpotSurvivesARelayout() {
        assertTrue(BubbleControl.snapCovers(snappingTo = 10 to 20, placed = 10 to 20))
        assertFalse(BubbleControl.snapCovers(snappingTo = 10 to 20, placed = 10 to 21))
        assertFalse(BubbleControl.snapCovers(snappingTo = 10 to 20, placed = 11 to 20))
        // No snap running: lay out as usual.
        assertFalse(BubbleControl.snapCovers(snappingTo = null, placed = 10 to 20))
    }

    @Test fun ringOverrideOnlyWhileListening() {
        assertEquals(true, BubbleControl.continuousRing(listening, doubleTap = true))
        assertEquals(false, BubbleControl.continuousRing(listening.copy(continuous = true), doubleTap = false))
        assertNull(BubbleControl.continuousRing(listening, doubleTap = null))
        assertNull(BubbleControl.continuousRing(DictationState.Idle, doubleTap = true))
        assertNull(BubbleControl.continuousRing(DictationState.Transcribing, doubleTap = true))
        assertNull(BubbleControl.continuousRing(failed, doubleTap = false))
    }

    @Test fun dragClampsInsideTheArea() {
        val area = Area(0, 100, 1000, 2000)
        assertEquals(500f to 800f, BubbleControl.clamp(area, 150, 500f, 800f))
        assertEquals(0f to 100f, BubbleControl.clamp(area, 150, -40f, 20f))
        assertEquals(850f to 1850f, BubbleControl.clamp(area, 150, 990f, 1990f))
        // Exactly at the far edges is still inside.
        assertEquals(850f to 1850f, BubbleControl.clamp(area, 150, 850f, 1850f))
    }

    @Test fun dragInAnAreaSmallerThanTheBoxPinsTopLeft() {
        val area = Area(10, 20, 100, 120)
        assertEquals(10f to 20f, BubbleControl.clamp(area, 150, 60f, 70f))
    }

    @Test fun yFractionFollowsOrientation() {
        val settings = UmmSettings(bubbleYPortrait = 0.25f, bubbleYLandscape = 0.75f)
        assertEquals(0.25f, BubbleControl.yFraction(settings, landscape = false), 0f)
        assertEquals(0.75f, BubbleControl.yFraction(settings, landscape = true), 0f)
    }

    @Test fun dockingSavesEdgeAndOnlyTheCurrentOrientation() {
        val settings = UmmSettings(bubbleEdge = BubbleEdge.RIGHT, bubbleYPortrait = 0.6f, bubbleYLandscape = 0.5f)
        val portrait = BubbleControl.docked(settings, Snap(BubbleEdge.LEFT, 0.1f), landscape = false)
        assertEquals(settings.copy(bubbleEdge = BubbleEdge.LEFT, bubbleYPortrait = 0.1f), portrait)
        val landscape = BubbleControl.docked(settings, Snap(BubbleEdge.LEFT, 0.9f), landscape = true)
        assertEquals(settings.copy(bubbleEdge = BubbleEdge.LEFT, bubbleYLandscape = 0.9f), landscape)
    }
}
