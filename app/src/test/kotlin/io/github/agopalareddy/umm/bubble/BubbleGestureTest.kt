package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.bubble.BubbleCommand.Cancel
import io.github.agopalareddy.umm.bubble.BubbleCommand.DragBy
import io.github.agopalareddy.umm.bubble.BubbleCommand.DragEnd
import io.github.agopalareddy.umm.bubble.BubbleCommand.Reject
import io.github.agopalareddy.umm.bubble.BubbleCommand.Retry
import io.github.agopalareddy.umm.bubble.BubbleCommand.SetSilenceDetection
import io.github.agopalareddy.umm.bubble.BubbleCommand.Start
import io.github.agopalareddy.umm.bubble.BubbleCommand.Stop
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleGestureTest {
    private val g = BubbleGesture(slopPx = 12f)
    private val none = emptyList<BubbleCommand>()

    private fun tap(downAt: Long, upAt: Long) {
        g.onDown(downAt, 0f, 0f, PipelineView.IDLE)
        g.onUp(upAt)
    }

    @Test
    fun holdThenReleaseAtHoldMsStops() {
        assertEquals(listOf(Start), g.onDown(0, 0f, 0f, PipelineView.IDLE))
        assertEquals(listOf(Stop), g.onUp(250))
    }

    @Test
    fun releaseJustBeforeHoldMsIsATap() {
        assertEquals(listOf(Start), g.onDown(0, 0f, 0f, PipelineView.IDLE))
        assertEquals(listOf(SetSilenceDetection(true)), g.onUp(249))
    }

    @Test
    fun doubleTapTurnsSilenceDetectionOff() {
        tap(downAt = 0, upAt = 100)
        // 250 ms after the release: inside the 300 ms window
        assertEquals(
            listOf(SetSilenceDetection(false)),
            g.onDown(350, 0f, 0f, PipelineView.RECORDING),
        )
        assertEquals(none, g.onUp(400))
        // continuous recording: a later press stops on release
        assertEquals(none, g.onDown(2000, 0f, 0f, PipelineView.RECORDING))
        assertEquals(listOf(Stop), g.onUp(2050))
    }

    @Test
    fun secondPressAfterWindowStopsOnRelease() {
        tap(downAt = 0, upAt = 100)
        assertEquals(none, g.onDown(500, 0f, 0f, PipelineView.RECORDING))
        assertEquals(listOf(Stop), g.onUp(520))
    }

    @Test
    fun secondPressExactlyAtWindowEdgeIsNotADoubleTap() {
        tap(downAt = 0, upAt = 100)
        assertEquals(none, g.onDown(400, 0f, 0f, PipelineView.RECORDING))
        assertEquals(listOf(Stop), g.onUp(420))
    }

    @Test
    fun secondPressJustInsideWindowIsADoubleTap() {
        tap(downAt = 0, upAt = 100)
        assertEquals(
            listOf(SetSilenceDetection(false)),
            g.onDown(399, 0f, 0f, PipelineView.RECORDING),
        )
    }

    @Test
    fun dragBeforeHoldCancelsAndMoves() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(listOf(Cancel, DragBy(20f, 0f)), g.onMove(100, 20f, 0f))
        assertEquals(listOf(DragBy(5f, 3f)), g.onMove(120, 25f, 3f))
        assertEquals(listOf(DragEnd(0f, 0f)), g.onUp(200))
    }

    @Test
    fun dragOpensNoTapWindow() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        g.onUp(150)
        assertEquals(none, g.onDown(200, 0f, 0f, PipelineView.RECORDING))
        assertEquals(listOf(Stop), g.onUp(220))
    }

    @Test
    fun dragEmitsCancelOnlyOnce() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(50, 20f, 0f)
        assertEquals(listOf(DragBy(10f, 0f)), g.onMove(60, 30f, 0f))
        assertEquals(listOf(DragBy(-40f, 0f)), g.onMove(70, -10f, 0f))
    }

    @Test
    fun dragContinuesAfterHoldMsOnceStarted() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        assertEquals(listOf(DragBy(10f, 0f)), g.onMove(400, 30f, 0f))
        assertEquals(listOf(DragEnd(0f, 0f)), g.onUp(500))
    }

    @Test
    fun moveJustBeforeHoldMsIsADrag() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(listOf(Cancel, DragBy(20f, 0f)), g.onMove(249, 20f, 0f))
    }

    @Test
    fun moveAtHoldMsIsIgnored() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(none, g.onMove(250, 20f, 0f))
        assertEquals(listOf(Stop), g.onUp(300))
    }

    @Test
    fun wobbleAfterHoldIsIgnored() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(none, g.onMove(300, 30f, 0f))
        assertEquals(listOf(Stop), g.onUp(400))
    }

    @Test
    fun smallMoveWithinSlopIsNotADrag() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(none, g.onMove(50, 8f, 8f))
        assertEquals(listOf(SetSilenceDetection(true)), g.onUp(100))
    }

    @Test
    fun moveExactlyAtSlopIsNotADrag() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(none, g.onMove(50, 12f, 0f))
        assertEquals(listOf(Cancel, DragBy(13f, 0f)), g.onMove(60, 13f, 0f))
    }

    @Test
    fun slopIsMeasuredFromTheDownPoint() {
        g.onDown(0, 100f, 100f, PipelineView.IDLE)
        assertEquals(none, g.onMove(10, 108f, 100f))
        // 20 px from the down point; the drag delta is from the down point too
        assertEquals(listOf(Cancel, DragBy(20f, 0f)), g.onMove(20, 120f, 100f))
    }

    @Test
    fun movesDuringStopPressAreIgnored() {
        tap(downAt = 0, upAt = 100)
        g.onDown(1000, 0f, 0f, PipelineView.RECORDING)
        assertEquals(none, g.onMove(1050, 50f, 0f))
        assertEquals(listOf(Stop), g.onUp(1100))
    }

    @Test
    fun movesDuringDoubleTapPressAreIgnored() {
        tap(downAt = 0, upAt = 100)
        g.onDown(200, 0f, 0f, PipelineView.RECORDING)
        assertEquals(none, g.onMove(220, 50f, 0f))
        assertEquals(none, g.onUp(260))
    }

    @Test
    fun busyPressIsRejected() {
        assertEquals(listOf(Reject), g.onDown(0, 0f, 0f, PipelineView.BUSY))
        assertEquals(none, g.onUp(50))
    }

    @Test
    fun failedPressRetries() {
        assertEquals(listOf(Retry), g.onDown(0, 0f, 0f, PipelineView.FAILED))
        assertEquals(none, g.onUp(50))
    }

    @Test
    fun windowResetsWhenPipelineNoLongerRecording() {
        tap(downAt = 0, upAt = 100)
        assertEquals(listOf(Start), g.onDown(200, 0f, 0f, PipelineView.IDLE))
        // a fresh press: a quick release is a tap, not a double-tap release
        assertEquals(listOf(SetSilenceDetection(true)), g.onUp(300))
    }

    @Test
    fun busyPressResetsTapWindow() {
        tap(downAt = 0, upAt = 100)
        g.onDown(150, 0f, 0f, PipelineView.BUSY)
        g.onUp(160)
        assertEquals(none, g.onDown(200, 0f, 0f, PipelineView.RECORDING))
        assertEquals(listOf(Stop), g.onUp(220))
    }

    @Test
    fun failedPressResetsTapWindow() {
        tap(downAt = 0, upAt = 100)
        g.onDown(150, 0f, 0f, PipelineView.FAILED)
        g.onUp(160)
        assertEquals(none, g.onDown(200, 0f, 0f, PipelineView.RECORDING))
        assertEquals(listOf(Stop), g.onUp(220))
    }

    @Test
    fun upWithoutDownIsNoOp() {
        assertEquals(none, g.onUp(100))
    }

    @Test
    fun moveWithoutDownIsNoOp() {
        assertEquals(none, g.onMove(100, 50f, 50f))
    }

    @Test
    fun secondUpIsNoOp() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onUp(300)
        assertEquals(none, g.onUp(310))
    }

    // --- Cancelled presses (ACTION_CANCEL reaches onUp through PrimaryPointer) ---

    @Test
    fun cancelledPushToTalkStopsLikeARelease() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(300, 30f, 0f) // a wobble first, as a real held finger would
        // The system cancelled the press (the overlay lost the touch stream) after HOLD_MS: what was said is kept.
        assertEquals(listOf(Stop), g.onUp(400))
    }

    // --- Release velocity, for flinging the bubble to an edge ---

    @Test
    fun dragEndCarriesTheReleaseVelocity() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        g.onMove(120, 80f, 20f)
        g.onMove(130, 140f, 30f)
        // 120 px right and 30 px down over the last 30 ms
        assertEquals(listOf(DragEnd(4000f, 1000f)), g.onUp(135))
    }

    @Test
    fun velocityUsesOnlyTheLast120Ms() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(20, 100f, 0f) // fast start, then slow
        g.onMove(200, 110f, 0f)
        g.onMove(300, 130f, 0f)
        // the 20 ms sample is 300 ms old and dropped: 20 px over 100 ms
        assertEquals(listOf(DragEnd(200f, 0f)), g.onUp(310))
    }

    @Test
    fun fingerStoppedBeforeLiftingHasNoVelocity() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        g.onMove(110, 80f, 0f)
        // last move 51 ms before the release
        assertEquals(listOf(DragEnd(0f, 0f)), g.onUp(161))
    }

    @Test
    fun fingerStillMovingAt50MsKeepsItsVelocity() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        g.onMove(110, 80f, 0f)
        assertEquals(listOf(DragEnd(6000f, 0f)), g.onUp(160))
    }

    @Test
    fun singleDragSampleHasNoVelocity() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        assertEquals(listOf(DragEnd(0f, 0f)), g.onUp(110))
    }

    @Test
    fun movesBeforeTheDragDoNotCountTowardVelocity() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(10, 10f, 0f) // inside the slop: not a drag yet
        g.onMove(40, 20f, 0f) // the drag starts here
        g.onMove(50, 30f, 0f)
        // only the two drag samples: 10 px over 10 ms
        assertEquals(listOf(DragEnd(1000f, 0f)), g.onUp(55))
    }

    @Test
    fun earlierDragsDoNotLeakIntoTheNextVelocity() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(20, 50f, 0f)
        g.onMove(30, 150f, 0f)
        g.onUp(35)
        g.onDown(60, 0f, 0f, PipelineView.IDLE)
        g.onMove(80, 20f, 0f)
        assertEquals(listOf(DragEnd(0f, 0f)), g.onUp(85))
    }

    @Test
    fun lostUpDocksWithTheLostFingersVelocity() {
        // PrimaryPointer turns a down that arrives mid-press into UP then DOWN; the UP carries no position, so the
        // new finger's coordinates can't leak into the old drag's release.
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        g.onMove(120, 60f, 0f)
        assertEquals(listOf(DragEnd(2000f, 0f)), g.onUp(125))
        assertEquals(listOf(Start), g.onDown(125, 900f, 900f, PipelineView.IDLE))
    }
}
