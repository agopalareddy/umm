package io.github.agopalareddy.umm.bubble

import kotlin.math.hypot

enum class PipelineView { IDLE, RECORDING, BUSY, FAILED }

sealed interface BubbleCommand {
    /** Begin recording with silence detection off. */
    data object Start : BubbleCommand
    data object Stop : BubbleCommand
    data object Cancel : BubbleCommand
    data class SetSilenceDetection(val on: Boolean) : BubbleCommand
    data object Retry : BubbleCommand

    /** Pipeline busy: the bubble shakes. */
    data object Reject : BubbleCommand

    /** Move the bubble by this much since the previous reported position. */
    data class DragBy(val dx: Float, val dy: Float) : BubbleCommand
    data object DragEnd : BubbleCommand
}

/**
 * Pure state machine behind the floating bubble: raw pointer events in,
 * commands out. It has no timers; every decision compares the `nowMs`
 * values it is given.
 */
class BubbleGesture(
    private val slopPx: Float,
    private val holdMs: Long = 250,
    private val doubleTapMs: Long = 300,
) {
    private enum class State {
        IDLE,

        /** Fresh press that started a recording. */
        PRESSED,
        DRAGGING,

        /** A tap was released; a second press within doubleTapMs is a double-tap. */
        TAP_WINDOW,

        /** Second press of a double-tap; its release is silent. */
        PRESSED_DOUBLE,

        /** Recording continues until the next press ends it. */
        CONTINUOUS,

        /** A press that stops the recording on release. */
        PRESSED_STOP,
    }

    private var state = State.IDLE
    private var downMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastUpMs = 0L

    fun onDown(nowMs: Long, x: Float, y: Float, pipeline: PipelineView): List<BubbleCommand> {
        val previous = state
        downMs = nowMs
        downX = x
        downY = y
        lastX = x
        lastY = y
        // Only a press while recording can continue a tap window; every other
        // pipeline state resets it.
        return when (pipeline) {
            PipelineView.IDLE -> {
                state = State.PRESSED
                listOf(BubbleCommand.Start)
            }
            PipelineView.BUSY -> {
                state = State.IDLE
                listOf(BubbleCommand.Reject)
            }
            PipelineView.FAILED -> {
                state = State.IDLE
                listOf(BubbleCommand.Retry)
            }
            PipelineView.RECORDING ->
                if (previous == State.TAP_WINDOW && nowMs - lastUpMs < doubleTapMs) {
                    state = State.PRESSED_DOUBLE
                    listOf(BubbleCommand.SetSilenceDetection(false))
                } else {
                    state = State.PRESSED_STOP
                    emptyList()
                }
        }
    }

    fun onMove(nowMs: Long, x: Float, y: Float): List<BubbleCommand> = when (state) {
        State.PRESSED ->
            if (nowMs - downMs < holdMs && hypot(x - downX, y - downY) > slopPx) {
                state = State.DRAGGING
                val drag = BubbleCommand.DragBy(x - downX, y - downY)
                lastX = x
                lastY = y
                listOf(BubbleCommand.Cancel, drag)
            } else {
                emptyList()
            }
        State.DRAGGING -> {
            val drag = BubbleCommand.DragBy(x - lastX, y - lastY)
            lastX = x
            lastY = y
            listOf(drag)
        }
        else -> emptyList()
    }

    fun onUp(nowMs: Long): List<BubbleCommand> = when (state) {
        State.PRESSED ->
            if (nowMs - downMs >= holdMs) {
                state = State.IDLE
                listOf(BubbleCommand.Stop)
            } else {
                state = State.TAP_WINDOW
                lastUpMs = nowMs
                listOf(BubbleCommand.SetSilenceDetection(true))
            }
        State.DRAGGING -> {
            state = State.IDLE
            listOf(BubbleCommand.DragEnd)
        }
        State.PRESSED_DOUBLE -> {
            state = State.CONTINUOUS
            emptyList()
        }
        State.PRESSED_STOP -> {
            state = State.IDLE
            listOf(BubbleCommand.Stop)
        }
        // No press in progress: nothing to release.
        State.IDLE, State.TAP_WINDOW, State.CONTINUOUS -> emptyList()
    }
}
