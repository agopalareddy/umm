package io.github.agopalareddy.umm.ime

import io.github.agopalareddy.umm.core.pipeline.DictationState
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHiddenTest {
    @Test fun hidingBeforeSpeechCancels() {
        assertEquals(HideAction.CANCEL, KeyboardHidden.action(DictationState.Listening(0, speechDetected = false)))
    }

    @Test fun hidingAfterSpeechStopsAndProcesses() {
        assertEquals(HideAction.STOP, KeyboardHidden.action(DictationState.Listening(9000, speechDetected = true)))
    }

    @Test fun hidingWhileProcessingDoesNothing() {
        assertEquals(HideAction.NONE, KeyboardHidden.action(DictationState.Transcribing))
        assertEquals(HideAction.NONE, KeyboardHidden.action(DictationState.Idle))
    }
}
