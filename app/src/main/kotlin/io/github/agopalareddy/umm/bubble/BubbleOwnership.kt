package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.pipeline.DictationState

/**
 * Whether the pipeline is running a dictation the bubble started, which the bubble must stay on screen to
 * control whatever has focus now. The pipeline states carry no origin while recording or processing, so the
 * bubble remembers the origin of its last start or retry and gives it up when that dictation ends.
 */
object BubbleOwnership {
    /**
     * [starting]: a start is still reading the app's category. [ownedOrigin]: the origin of the bubble's last
     * start or retry, null once given up. [view] is the pipeline as the gesture machine sees it, which counts a
     * start or retry the pipeline has not shown yet; [state] is the pipeline's own state.
     */
    fun owns(starting: Boolean, ownedOrigin: Long?, view: PipelineView, state: DictationState): Boolean {
        if (starting) return true
        if (ownedOrigin == null) return false
        return when (view) {
            PipelineView.RECORDING, PipelineView.BUSY -> true
            PipelineView.FAILED -> (state as? DictationState.Failed)?.origin == ownedOrigin
            PipelineView.IDLE -> false
        }
    }

    /** [ownedOrigin] while the bubble still owns its dictation, else null: it finished, failed elsewhere, or ended. */
    fun keep(starting: Boolean, ownedOrigin: Long?, view: PipelineView, state: DictationState): Long? =
        ownedOrigin.takeIf { owns(starting, ownedOrigin, view, state) }
}
