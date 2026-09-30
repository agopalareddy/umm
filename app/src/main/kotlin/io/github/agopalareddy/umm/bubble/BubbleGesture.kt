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

    /** The drag ended; [vx] and [vy] are the finger's velocity at release in px/s, 0 when it had stopped. */
    data class DragEnd(val vx: Float = 0f, val vy: Float = 0f) : BubbleCommand
}

/**
 * Pure state machine behind the floating bubble: raw pointer events in,
 * commands out. It has no timers; every decision compares the `nowMs`
 * values it is given.
 */
class BubbleGesture(
    private val slopPx: Float,
    private val holdMs: Long = HOLD_MS,
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

    private class Sample(val timeMs: Long, val x: Float, val y: Float)

    private var state = State.IDLE
    private var downMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastUpMs = 0L

    /** Positions seen while dragging, for the release velocity. */
    private val samples = ArrayDeque<Sample>()

    fun onDown(nowMs: Long, x: Float, y: Float, pipeline: PipelineView): List<BubbleCommand> {
        val previous = state
        downMs = nowMs
        downX = x
        downY = y
        lastX = x
        lastY = y
        samples.clear()
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
                sample(nowMs, x, y)
                listOf(BubbleCommand.Cancel, drag)
            } else {
                emptyList()
            }
        State.DRAGGING -> {
            val drag = BubbleCommand.DragBy(x - lastX, y - lastY)
            lastX = x
            lastY = y
            sample(nowMs, x, y)
            listOf(drag)
        }
        else -> emptyList()
    }

    /**
     * The press ended. A cancelled press (ACTION_CANCEL, or a finger lost when a new down arrives) comes here
     * too and counts as a release: a cancelled push-to-talk stops and processes what was said instead of
     * discarding it, and a cancelled drag still docks.
     */
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
            val (vx, vy) = velocity(nowMs)
            samples.clear()
            listOf(BubbleCommand.DragEnd(vx, vy))
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

    private fun sample(nowMs: Long, x: Float, y: Float) {
        samples.add(Sample(nowMs, x, y))
        dropOld(nowMs)
    }

    private fun dropOld(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().timeMs > VELOCITY_WINDOW_MS) samples.removeFirst()
    }

    /** Over the drag samples of the last [VELOCITY_WINDOW_MS]; zero if the finger stopped before lifting. */
    private fun velocity(nowMs: Long): Pair<Float, Float> {
        dropOld(nowMs)
        val oldest = samples.firstOrNull() ?: return 0f to 0f
        val newest = samples.last()
        if (nowMs - newest.timeMs > STOPPED_MS) return 0f to 0f
        val dtMs = newest.timeMs - oldest.timeMs
        if (dtMs <= MIN_SPAN_MS) return 0f to 0f
        return (newest.x - oldest.x) * 1000f / dtMs to (newest.y - oldest.y) * 1000f / dtMs
    }

    companion object {
        /** A press held this long is push-to-talk; a drag must start before it. */
        const val HOLD_MS = 250L

        private const val VELOCITY_WINDOW_MS = 120L
        private const val STOPPED_MS = 50L

        /** Samples closer together than this give no usable velocity. */
        private const val MIN_SPAN_MS = 5L
    }
}
