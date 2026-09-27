package io.github.agopalareddy.umm.ime

import io.github.agopalareddy.umm.core.pipeline.DictationState

enum class HideAction { CANCEL, STOP, NONE }

object KeyboardHidden {
    /** Hiding the keyboard before anything was said discards the recording instead of uploading silence. */
    fun action(state: DictationState): HideAction = when {
        state !is DictationState.Listening -> HideAction.NONE
        state.speechDetected -> HideAction.STOP
        else -> HideAction.CANCEL
    }
}
