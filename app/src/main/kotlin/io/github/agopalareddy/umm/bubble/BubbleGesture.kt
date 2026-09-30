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
    data class DragEnd(val vx: Float = 0f, val vy: Float = 0f) : BubbleCommand
}

/**
 * Pure state machine behind the floating bubble: raw pointer events in,
 * commands out. It has no timers; every decision compares the `nowMs`
 * values it is given, or reacts to an explicit [onHold] when held still.
 */
class BubbleGesture(
    private val slopPx: Float,
    private val holdMs: Long = 250,
    private val doubleTapMs: Long = 300,
    private val flingThresholdPx: Float = 1000f,
) {
    private enum class State {
        IDLE,

        /** Fresh press: could become a tap, a hold, or a drag. */
        PRESSED,

        /** Held long enough to be push-to-talk. Actively recording while finger stays down. */
        HOLDING,

        /** Dragging the bubble window across the screen. */
        DRAGGING,

        /** A tap was released; a second press within doubleTapMs is a double-tap. */
        TAP_WINDOW,

        /** Second press of a double-tap; its release is silent. */
        PRESSED_DOUBLE,

        /** Recording continues until the next press ends it. */
        CONTINUOUS,

        /** A press while recording; tapping stops recording, moving drags. */
        PRESSED_STOP,

        /** Press while pipeline is failed; tapping retries, moving drags. */
        PRESSED_FAILED,

        /** Press while pipeline is busy; tapping rejects, moving drags. */
        PRESSED_BUSY,
    }

    private var state = State.IDLE
    private var downMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastUpMs = 0L

    val isDragging: Boolean get() = state == State.DRAGGING

    private data class Sample(val timeMs: Long, val x: Float, val y: Float)
    private val samples = ArrayDeque<Sample>()

    private fun recordSample(timeMs: Long, x: Float, y: Float) {
        while (samples.isNotEmpty() && timeMs - samples.first().timeMs > 120L) {
            samples.removeFirst()
        }
        samples.add(Sample(timeMs, x, y))
    }

    private fun computeVelocity(nowMs: Long): Pair<Float, Float> {
        while (samples.isNotEmpty() && nowMs - samples.first().timeMs > 120L) {
            samples.removeFirst()
        }
        val oldest = samples.firstOrNull() ?: return 0f to 0f
        val newest = samples.lastOrNull() ?: return 0f to 0f
        if (nowMs - newest.timeMs > 50L) return 0f to 0f
        val dt = (newest.timeMs - oldest.timeMs) / 1000f
        if (dt <= 0.005f) return 0f to 0f
        val vx = (newest.x - oldest.x) / dt
        val vy = (newest.y - oldest.y) / dt
        return vx to vy
    }

    fun onDown(nowMs: Long, x: Float, y: Float, pipeline: PipelineView): List<BubbleCommand> {
        val previous = state
        downMs = nowMs
        downX = x
        downY = y
        lastX = x
        lastY = y
        samples.clear()
        samples.add(Sample(nowMs, x, y))

        return when (pipeline) {
            PipelineView.IDLE -> {
                state = State.PRESSED
                emptyList()
            }
            PipelineView.BUSY -> {
                state = State.PRESSED_BUSY
                emptyList()
            }
            PipelineView.FAILED -> {
                state = State.PRESSED_FAILED
                emptyList()
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

    fun onHold(nowMs: Long): List<BubbleCommand> {
        if (state != State.PRESSED) return emptyList()
        state = State.HOLDING
        return listOf(BubbleCommand.Start)
    }

    fun onMove(nowMs: Long, x: Float, y: Float): List<BubbleCommand> {
        recordSample(nowMs, x, y)
        return when (state) {
            State.PRESSED, State.PRESSED_FAILED, State.PRESSED_BUSY ->
                if (hypot(x - downX, y - downY) > slopPx) {
                    state = State.DRAGGING
                    val drag = BubbleCommand.DragBy(x - downX, y - downY)
                    lastX = x
                    lastY = y
                    listOf(drag)
                } else {
                    emptyList()
                }
            State.HOLDING ->
                if (hypot(x - downX, y - downY) > slopPx) {
                    state = State.DRAGGING
                    val drag = BubbleCommand.DragBy(x - downX, y - downY)
                    lastX = x
                    lastY = y
                    listOf(BubbleCommand.Cancel, drag)
                } else {
                    emptyList()
                }
            State.PRESSED_STOP ->
                if (hypot(x - downX, y - downY) > slopPx) {
                    state = State.DRAGGING
                    val drag = BubbleCommand.DragBy(x - downX, y - downY)
                    lastX = x
                    lastY = y
                    listOf(drag)
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
    }

    fun onUp(nowMs: Long, x: Float? = null, y: Float? = null, cancel: Boolean = false): List<BubbleCommand> {
        if (x != null && y != null) {
            recordSample(nowMs, x, y)
        }
        val (vx, vy) = computeVelocity(nowMs)
        val moved = if (x != null && y != null) hypot(x - downX, y - downY) > slopPx else false
        val flicked = hypot(vx, vy) > flingThresholdPx

        val was = state
        samples.clear()

        if (cancel) {
            state = State.IDLE
            return when (was) {
                State.HOLDING -> listOf(BubbleCommand.Cancel)
                State.DRAGGING -> listOf(BubbleCommand.DragEnd(vx, vy))
                else -> emptyList()
            }
        }

        return when (was) {
            State.PRESSED -> {
                if (moved || flicked) {
                    state = State.IDLE
                    listOf(BubbleCommand.DragEnd(vx, vy))
                } else {
                    // Tap to talk
                    state = State.TAP_WINDOW
                    lastUpMs = nowMs
                    listOf(BubbleCommand.Start, BubbleCommand.SetSilenceDetection(true))
                }
            }
            State.HOLDING -> {
                state = State.IDLE
                listOf(BubbleCommand.Stop)
            }
            State.PRESSED_FAILED -> {
                state = State.IDLE
                if (moved || flicked) {
                    listOf(BubbleCommand.DragEnd(vx, vy))
                } else {
                    listOf(BubbleCommand.Retry)
                }
            }
            State.PRESSED_BUSY -> {
                state = State.IDLE
                if (moved || flicked) {
                    listOf(BubbleCommand.DragEnd(vx, vy))
                } else {
                    listOf(BubbleCommand.Reject)
                }
            }
            State.PRESSED_STOP -> {
                state = State.IDLE
                if (moved || flicked) {
                    listOf(BubbleCommand.DragEnd(vx, vy))
                } else {
                    listOf(BubbleCommand.Stop)
                }
            }
            State.DRAGGING -> {
                state = State.IDLE
                listOf(BubbleCommand.DragEnd(vx, vy))
            }
            State.PRESSED_DOUBLE -> {
                state = State.CONTINUOUS
                emptyList()
            }
            State.IDLE, State.TAP_WINDOW, State.CONTINUOUS -> {
                emptyList()
            }
        }
    }
}
