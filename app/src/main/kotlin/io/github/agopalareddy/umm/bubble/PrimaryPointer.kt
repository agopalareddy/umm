package io.github.agopalareddy.umm.bubble

import android.view.MotionEvent

/**
 * Reduces a touch stream to its first finger, so the gesture machine sees one down, that finger's moves and
 * exactly one up: a cancel counts as an up, other fingers are ignored, and a down that arrives while a press
 * is still open closes it first.
 */
class PrimaryPointer {
    enum class Step { DOWN, MOVE, UP }

    /** The tracked finger's pointer id, or null between presses. */
    var id: Int? = null
        private set

    /** [actionMasked] is MotionEvent.getActionMasked(); [pointerId] is the id at getActionIndex(). */
    fun onEvent(actionMasked: Int, pointerId: Int): List<Step> = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val lost = id != null
            id = pointerId
            if (lost) listOf(Step.UP, Step.DOWN) else listOf(Step.DOWN)
        }
        MotionEvent.ACTION_MOVE -> if (id != null) listOf(Step.MOVE) else emptyList()
        MotionEvent.ACTION_POINTER_UP -> if (id == pointerId) release() else emptyList()
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (id != null) release() else emptyList()
        else -> emptyList()
    }

    private fun release(): List<Step> {
        id = null
        return listOf(Step.UP)
    }
}
