package io.github.agopalareddy.umm.desktop.engine

/** What the orb and tray show about the current (or just finished) dictation. */
sealed interface DesktopState {
    data object Idle : DesktopState
    data object NeedsKey : DesktopState
    data class Listening(val level: Int) : DesktopState
    data object Processing : DesktopState
    data class Inserted(val pastedOnAsciiDesktop: Boolean) : DesktopState
    data class Copied(val reason: String) : DesktopState
    data class Failed(val reason: String) : DesktopState
    data object NoSpeech : DesktopState
}
