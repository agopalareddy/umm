package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.pipeline.DictationState

/** How see-through the bubble is: quiet when idle, quieter when left alone, solid while dictating. */
object BubbleAlpha {
    const val IDLE = 0.6f
    const val DIMMED = 0.4f
    const val ACTIVE = 1f
    const val DIM_AFTER_MS = 3_000L

    fun isActive(state: DictationState): Boolean =
        state is DictationState.Listening || state == DictationState.Transcribing || state == DictationState.Cleaning

    fun of(active: Boolean, msSinceInteraction: Long): Float = when {
        active -> ACTIVE
        msSinceInteraction >= DIM_AFTER_MS -> DIMMED
        else -> IDLE
    }
}
