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
        g.onUp(upAt, 0f, 0f)
    }

    @Test
    fun holdThenReleaseStops() {
        assertEquals(none, g.onDown(0, 0f, 0f, PipelineView.IDLE))
        assertEquals(listOf(Start), g.onHold(250))
        assertEquals(listOf(Stop), g.onUp(300))
    }

    @Test
    fun tapStartsAndSetsSilenceDetection() {
        assertEquals(none, g.onDown(0, 0f, 0f, PipelineView.IDLE))
        assertEquals(listOf(Start, SetSilenceDetection(true)), g.onUp(100, 0f, 0f))
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
    fun dragDoesNotActivateRecording() {
        assertEquals(none, g.onDown(0, 0f, 0f, PipelineView.IDLE))
        assertEquals(listOf(DragBy(20f, 0f)), g.onMove(100, 20f, 0f))
        assertEquals(listOf(DragBy(5f, 3f)), g.onMove(120, 25f, 3f))
        assertEquals(listOf(DragEnd(50f, 30f)), g.onUp(200, 25f, 3f))
    }

    @Test
    fun dragAfterHoldCancelsAndMoves() {
        assertEquals(none, g.onDown(0, 0f, 0f, PipelineView.IDLE))
        assertEquals(listOf(Start), g.onHold(250))
        assertEquals(listOf(Cancel, DragBy(20f, 0f)), g.onMove(300, 20f, 0f))
        assertEquals(listOf(DragEnd()), g.onUp(350, 20f, 0f))
    }

    @Test
    fun dragOpensNoTapWindow() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        g.onUp(150, 20f, 0f)
        assertEquals(none, g.onDown(200, 0f, 0f, PipelineView.RECORDING))
        assertEquals(listOf(Stop), g.onUp(220))
    }

    @Test
    fun dragContinuesAfterHoldMsOnceStarted() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        assertEquals(listOf(DragBy(10f, 0f)), g.onMove(400, 30f, 0f))
        assertEquals(listOf(DragEnd()), g.onUp(500, 30f, 0f))
    }

    @Test
    fun dragFlickEmitsVelocity() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onMove(100, 20f, 0f)
        g.onMove(120, 80f, 20f)
        assertEquals(listOf(DragEnd(4000f, 1000f)), g.onUp(130, 140f, 30f))
    }

    @Test
    fun quickFlickWithoutMoveIsDragEndNotTap() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        // Up with displacement > slop and flick velocity
        assertEquals(listOf(DragEnd(1400f, 400f)), g.onUp(50, 70f, 20f))
    }

    @Test
    fun dragCanStartAfterPause() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        // User moves after 400ms without hold firing: starts drag
        assertEquals(listOf(DragBy(20f, 0f)), g.onMove(400, 20f, 0f))
        assertEquals(listOf(DragEnd()), g.onUp(450, 20f, 0f))
    }

    @Test
    fun smallMoveWithinSlopIsATap() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(none, g.onMove(50, 8f, 8f))
        assertEquals(listOf(Start, SetSilenceDetection(true)), g.onUp(100, 8f, 8f))
    }

    @Test
    fun moveExactlyAtSlopIsNotADrag() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(none, g.onMove(50, 12f, 0f))
        assertEquals(listOf(DragBy(13f, 0f)), g.onMove(60, 13f, 0f))
    }

    @Test
    fun slopIsMeasuredFromTheDownPoint() {
        g.onDown(0, 100f, 100f, PipelineView.IDLE)
        assertEquals(none, g.onMove(10, 108f, 100f))
        assertEquals(listOf(DragBy(20f, 0f)), g.onMove(20, 120f, 100f))
    }

    @Test
    fun dragDuringRecordingMovesWithoutStopping() {
        tap(downAt = 0, upAt = 100)
        g.onDown(1000, 0f, 0f, PipelineView.RECORDING)
        assertEquals(listOf(DragBy(50f, 0f)), g.onMove(1050, 50f, 0f))
        assertEquals(listOf(DragEnd(500f, 0f)), g.onUp(1100, 50f, 0f))
    }

    @Test
    fun tapDuringRecordingStops() {
        tap(downAt = 0, upAt = 100)
        g.onDown(1000, 0f, 0f, PipelineView.RECORDING)
        assertEquals(listOf(Stop), g.onUp(1050, 0f, 0f))
    }

    @Test
    fun busyTapIsRejected() {
        assertEquals(none, g.onDown(0, 0f, 0f, PipelineView.BUSY))
        assertEquals(listOf(Reject), g.onUp(50, 0f, 0f))
    }

    @Test
    fun busyMoveDragsInsteadOfRejecting() {
        assertEquals(none, g.onDown(0, 0f, 0f, PipelineView.BUSY))
        assertEquals(listOf(DragBy(20f, 0f)), g.onMove(20, 20f, 0f))
        assertEquals(listOf(DragEnd(400f, 0f)), g.onUp(50, 20f, 0f))
    }

    @Test
    fun failedTapRetries() {
        assertEquals(none, g.onDown(0, 0f, 0f, PipelineView.FAILED))
        assertEquals(listOf(Retry), g.onUp(50, 0f, 0f))
    }

    @Test
    fun failedMoveDragsInsteadOfRetrying() {
        assertEquals(none, g.onDown(0, 0f, 0f, PipelineView.FAILED))
        assertEquals(listOf(DragBy(20f, 0f)), g.onMove(20, 20f, 0f))
        assertEquals(listOf(DragEnd(400f, 0f)), g.onUp(50, 20f, 0f))
    }

    @Test
    fun cancelDoesNotTriggerTap() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        assertEquals(none, g.onUp(50, 0f, 0f, cancel = true))
    }

    @Test
    fun cancelWhileHoldingCancelsRecording() {
        g.onDown(0, 0f, 0f, PipelineView.IDLE)
        g.onHold(250)
        assertEquals(listOf(Cancel), g.onUp(300, 0f, 0f, cancel = true))
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
        g.onUp(300, 0f, 0f)
        assertEquals(none, g.onUp(310))
    }
}
