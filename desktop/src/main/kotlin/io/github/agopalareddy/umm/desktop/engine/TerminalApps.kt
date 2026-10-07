package io.github.agopalareddy.umm.desktop.engine

/** Terminal emulators, where Ctrl+V is not paste, so text has to be typed. */
object TerminalApps {
    private val TOKENS = listOf(
        "konsole", "terminal", "kitty", "alacritty", "foot", "wezterm", "xterm", "rxvt", "tilix", "terminator",
        "yakuake", "ghostty", "ptyxis", "guake", "tabby",
    )

    /** [appId] is a desktop-file name or window class as KWin reports it; null (unknown) is not a terminal. */
    fun isTerminal(appId: String?): Boolean {
        val id = appId?.lowercase().orEmpty()
        return id.isNotEmpty() && TOKENS.any { it in id }
    }
}
