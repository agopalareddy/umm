package io.github.agopalareddy.umm.desktop.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAppsTest {
    @Test fun commonTerminalsAreRecognized() {
        listOf(
            "org.kde.konsole", "konsole", "kitty", "Alacritty", "foot", "org.wezfurlong.wezterm", "xterm",
            "org.gnome.Terminal", "com.mitchellh.ghostty", "org.gnome.Ptyxis", "yakuake", "com.gexperts.Tilix",
        ).forEach { assertTrue(it, TerminalApps.isTerminal(it)) }
    }

    @Test fun ordinaryAppsAreNot() {
        listOf("org.kde.kate", "firefox", "org.mozilla.firefox", "com.anthropic.Claude", "code", "org.kde.dolphin", "python3")
            .forEach { assertFalse(it, TerminalApps.isTerminal(it)) }
    }

    @Test fun anUnknownWindowIsNotATerminal() {
        assertFalse(TerminalApps.isTerminal(null))
        assertFalse(TerminalApps.isTerminal(""))
    }
}
