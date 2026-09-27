package io.github.agopalareddy.umm.ime

import io.github.agopalareddy.umm.core.pipeline.DictationState

enum class HideAction { CANCEL, STOP, NONE }

object KeyboardHidden {
    /**
     * Hiding the keyboard before anything was said discards the recording instead of uploading silence,
     * except in continuous mode, where a whisper may never register as speech.
     */
    fun action(state: DictationState): HideAction = when {
        state !is DictationState.Listening -> HideAction.NONE
        state.speechDetected || state.continuous -> HideAction.STOP
        else -> HideAction.CANCEL
    }
}
