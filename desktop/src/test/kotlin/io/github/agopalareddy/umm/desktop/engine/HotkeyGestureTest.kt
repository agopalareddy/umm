package io.github.agopalareddy.umm.desktop.engine

import io.github.agopalareddy.umm.linux.HotkeyEvent.Down
import io.github.agopalareddy.umm.linux.HotkeyEvent.Up
import io.github.agopalareddy.umm.desktop.engine.GestureCommand.NONE
import io.github.agopalareddy.umm.desktop.engine.GestureCommand.START
import io.github.agopalareddy.umm.desktop.engine.GestureCommand.STOP
import org.junit.Assert.assertEquals
import org.junit.Test

class HotkeyGestureTest {
    private val gesture = HotkeyGesture()

    @Test fun tap_startsAndKeepsRecording() {
        assertEquals(START, gesture.onEvent(Down, 0, recording = false))
        assertEquals(NONE, gesture.onEvent(Up, 120, recording = true))
    }

    @Test fun secondTap_stops() {
        gesture.onEvent(Down, 0, recording = false)
        gesture.onEvent(Up, 120, recording = true)
        assertEquals(STOP, gesture.onEvent(Down, 3000, recording = true))
        assertEquals(NONE, gesture.onEvent(Up, 3100, recording = false))
    }

    @Test fun hold_stopsOnRelease() {
        assertEquals(START, gesture.onEvent(Down, 0, recording = false))
        assertEquals(STOP, gesture.onEvent(Up, 501, recording = true))
    }

    @Test fun holdBoundary() {
        gesture.onEvent(Down, 0, recording = false)
        assertEquals(NONE, gesture.onEvent(Up, 499, recording = true))
        gesture.onEvent(Down, 1000, recording = true)
        gesture.onEvent(Up, 1100, recording = false)
        gesture.onEvent(Down, 2000, recording = false)
        assertEquals(STOP, gesture.onEvent(Up, 2500, recording = true))
    }

    @Test fun gnomeRepeats_ignored() {
        assertEquals(START, gesture.onEvent(Down, 0, recording = false))
        assertEquals(NONE, gesture.onEvent(Down, 30, recording = true))
        assertEquals(NONE, gesture.onEvent(Down, 60, recording = true))
        assertEquals(STOP, gesture.onEvent(Up, 700, recording = true))
    }

    @Test fun strayUp_ignored() {
        assertEquals(NONE, gesture.onEvent(Up, 10, recording = false))
        assertEquals(NONE, gesture.onEvent(Up, 20, recording = true))
    }
}
