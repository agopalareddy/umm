package io.github.agopalareddy.umm.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutostartTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(home: File) = File(home, ".config/autostart/io.github.agopalareddy.Umm.desktop")

    @Test fun enableWritesTheAutostartEntry() {
        val autostart = Autostart.forEnvironment(emptyMap(), tmp.root)
        assertFalse(autostart.isEnabled())
        autostart.enable("/opt/umm/bin/Umm")
        assertTrue(autostart.isEnabled())
        val lines = entry(tmp.root).readLines()
        assertTrue(lines.toString(), "Type=Application" in lines)
        assertTrue(lines.toString(), "Name=Umm" in lines)
        assertTrue(lines.toString(), "Exec=/opt/umm/bin/Umm --background" in lines)
        assertTrue(lines.toString(), "X-GNOME-Autostart-enabled=true" in lines)
    }

    @Test fun disableDeletesTheEntry() {
        val autostart = Autostart.forEnvironment(emptyMap(), tmp.root)
        autostart.enable("/opt/umm/bin/Umm")
        autostart.disable()
        assertFalse(entry(tmp.root).exists())
        assertFalse(autostart.isEnabled())
        autostart.disable()
    }

    @Test fun xdgConfigHomeWins() {
        val custom = tmp.newFolder("custom")
        val autostart = Autostart.forEnvironment(mapOf("XDG_CONFIG_HOME" to custom.path), tmp.root)
        autostart.enable("/opt/umm/bin/Umm")
        assertEquals(true, File(custom, "autostart/io.github.agopalareddy.Umm.desktop").isFile)
        assertFalse(entry(tmp.root).exists())
    }

    @Test fun execPathsWithSpacesAreQuoted() {
        val autostart = Autostart.forEnvironment(emptyMap(), tmp.root)
        autostart.enable("/home/a b/umm/bin/Umm")
        assertTrue(entry(tmp.root).readLines().contains("Exec=\"/home/a b/umm/bin/Umm\" --background"))
    }
}
