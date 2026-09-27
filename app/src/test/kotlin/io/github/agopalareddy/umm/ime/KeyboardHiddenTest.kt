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

    @Test fun hidingInContinuousModeKeepsTheRecording() {
        // A whisper may never be detected as speech, but the user chose to keep recording.
        assertEquals(HideAction.STOP, KeyboardHidden.action(DictationState.Listening(90, speechDetected = false, continuous = true)))
    }
}
