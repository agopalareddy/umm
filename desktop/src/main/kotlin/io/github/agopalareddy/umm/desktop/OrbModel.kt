package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.desktop.engine.DesktopState
import java.awt.Insets
import java.awt.Rectangle

/** What the orb says, how long it stays, and where it sits: the logic behind [OrbWindow]. */
internal object OrbModel {
    private const val MARGIN = 48

    /** How long a final state stays visible; null while the dictation is still going (or nothing is happening). */
    fun hideAfterMs(state: DesktopState): Long? = when (state) {
        DesktopState.Idle, is DesktopState.Listening, DesktopState.Processing -> null
        is DesktopState.Inserted -> if (state.pastedOnAsciiDesktop) 4000 else 1000
        DesktopState.NoSpeech -> 1200
        is DesktopState.Copied, is DesktopState.Failed, DesktopState.NeedsKey -> 4500
    }

    /** The text in the orb's pill, or null for the states that show the animated orb (or nothing). */
    fun message(state: DesktopState): String? = when (state) {
        DesktopState.Idle, is DesktopState.Listening, DesktopState.Processing -> null
        is DesktopState.Inserted ->
            if (state.pastedOnAsciiDesktop) "Done · if nothing appeared, press Ctrl+Shift+V" else "Done"
        DesktopState.NoSpeech -> "No speech"
        is DesktopState.Copied -> "Copied to the clipboard · ${state.reason}"
        is DesktopState.Failed -> state.reason
        DesktopState.NeedsKey -> "Connect OpenRouter · click to open"
    }

    /** The top-left corner for a [width] x [height] window on [screen], clear of the panels in [insets]. */
    fun place(screen: Rectangle, insets: Insets, position: OrbPosition, width: Int, height: Int): Pair<Int, Int> {
        val left = screen.x + insets.left
        val right = screen.x + screen.width - insets.right
        val top = screen.y + insets.top
        val bottom = screen.y + screen.height - insets.bottom
        val x = when (position) {
            OrbPosition.BOTTOM_LEFT -> left + MARGIN
            OrbPosition.BOTTOM_RIGHT -> right - width - MARGIN
            OrbPosition.BOTTOM_CENTER, OrbPosition.TOP_CENTER -> (left + right - width) / 2
        }
        val y = if (position == OrbPosition.TOP_CENTER) top + MARGIN else bottom - height - MARGIN
        return x to y
    }
}
