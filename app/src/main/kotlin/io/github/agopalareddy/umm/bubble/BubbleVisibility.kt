package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.data.BubbleShowMode

/** When the floating bubble is on screen and when it may record. */
object BubbleVisibility {
    /** Grace period after focus leaves an editable field, so moving between fields doesn't flicker the bubble. */
    const val HIDE_DELAY_MS = 400L

    /**
     * [msSinceFocusLost] is null when no editable field has held focus yet. Password fields hide the bubble in
     * every mode.
     */
    fun shouldShow(
        mode: BubbleShowMode,
        focusedEditable: Boolean,
        isPassword: Boolean,
        msSinceFocusLost: Long?,
    ): Boolean {
        if (isPassword) return false
        return when (mode) {
            BubbleShowMode.ALWAYS -> true
            BubbleShowMode.WHEN_FOCUSED ->
                focusedEditable || (msSinceFocusLost != null && msSinceFocusLost < HIDE_DELAY_MS)
        }
    }

    /** Recording needs somewhere to put the text, so Always mode with no field focused can't record. */
    fun canRecord(focusedEditable: Boolean, isPassword: Boolean): Boolean = focusedEditable && !isPassword
}
