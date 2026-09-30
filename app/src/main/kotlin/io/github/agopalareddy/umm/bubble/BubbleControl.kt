package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.pipeline.DictationState

/** Pure decisions between the bubble's gestures and the shared pipeline; [BubbleService] does the calls. */
object BubbleControl {
    fun viewOf(state: DictationState): PipelineView = when (state) {
        is DictationState.Listening -> PipelineView.RECORDING
        DictationState.Transcribing, DictationState.Cleaning -> PipelineView.BUSY
        is DictationState.Failed -> PipelineView.FAILED
        else -> PipelineView.IDLE
    }

    /**
     * Stop and silence changes only mean something while recording: a recording can end (silence, the duration
     * cap, the keyboard) while the finger is still down.
     */
    fun applies(command: BubbleCommand, view: PipelineView): Boolean = when (command) {
        BubbleCommand.Stop, is BubbleCommand.SetSilenceDetection -> view == PipelineView.RECORDING
        else -> true
    }

    /** The ring is the bubble's to decide only while it listens; otherwise the orb follows the state. */
    fun continuousRing(state: DictationState, doubleTap: Boolean?): Boolean? =
        if (state is DictationState.Listening) doubleTap else null

    /** Keeps a dragged bubble's top-left inside [area], pinning it to the top-left when the area is too small. */
    fun clamp(area: Area, boxPx: Int, x: Float, y: Float): Pair<Float, Float> {
        val maxX = maxOf(area.left, area.right - boxPx).toFloat()
        val maxY = maxOf(area.top, area.bottom - boxPx).toFloat()
        return x.coerceIn(area.left.toFloat(), maxX) to y.coerceIn(area.top.toFloat(), maxY)
    }

    fun yFraction(settings: UmmSettings, landscape: Boolean): Float =
        if (landscape) settings.bubbleYLandscape else settings.bubbleYPortrait

    /** Saves where a drag docked: the edge, and the Y fraction of the current orientation only. */
    fun docked(settings: UmmSettings, snap: Snap, landscape: Boolean): UmmSettings =
        if (landscape) {
            settings.copy(bubbleEdge = snap.edge, bubbleYLandscape = snap.yFraction)
        } else {
            settings.copy(bubbleEdge = snap.edge, bubbleYPortrait = snap.yFraction)
        }
}

/**
 * What the gesture machine is told about the pipeline right after the bubble started or retried a dictation,
 * before the pipeline's own state catches up. Without it a second press in that gap would look idle and start
 * again. The expectation ends as soon as the pipeline shows any non-idle state, or after [holdMs] if the
 * pipeline ignored the request.
 */
class PipelineLatch(private val holdMs: Long = 2_000) {
    private var expected: PipelineView? = null
    private var since = 0L

    fun expect(view: PipelineView, now: Long) {
        expected = view
        since = now
    }

    fun clear() {
        expected = null
    }

    fun view(state: DictationState, now: Long): PipelineView {
        val real = BubbleControl.viewOf(state)
        if (real != PipelineView.IDLE) {
            expected = null
            return real
        }
        val expected = expected ?: return real
        if (now - since < holdMs) return expected
        this.expected = null
        return real
    }
}
