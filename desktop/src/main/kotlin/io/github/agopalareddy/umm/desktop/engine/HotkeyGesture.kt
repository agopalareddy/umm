package io.github.agopalareddy.umm.desktop.engine

import io.github.agopalareddy.umm.linux.HotkeyEvent

enum class GestureCommand { START, STOP, NONE }

/**
 * Turns hotkey Down/Up events into start and stop commands: a tap toggles, holding for [holdMs] or longer is
 * hold-to-talk (release stops). Repeated Down events while the key is held (GNOME sends them) are ignored.
 */
class HotkeyGesture(private val holdMs: Long = 500) {
    private var held = false
    private var pressedAtMs = 0L
    private var startedOnThisPress = false

    fun onEvent(event: HotkeyEvent, nowMs: Long, recording: Boolean): GestureCommand = when (event) {
        HotkeyEvent.Down -> when {
            held -> GestureCommand.NONE
            else -> {
                held = true
                pressedAtMs = nowMs
                startedOnThisPress = !recording
                if (recording) GestureCommand.STOP else GestureCommand.START
            }
        }
        HotkeyEvent.Up -> when {
            !held -> GestureCommand.NONE
            else -> {
                held = false
                val holdToTalk = startedOnThisPress && nowMs - pressedAtMs >= holdMs
                startedOnThisPress = false
                if (holdToTalk) GestureCommand.STOP else GestureCommand.NONE
            }
        }
    }
}
