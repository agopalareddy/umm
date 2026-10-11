package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.data.buildSettingsStore
import io.github.agopalareddy.umm.linux.LoginStart
import io.github.agopalareddy.umm.linux.portal.PortalException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartAtLoginTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun settings(): DesktopSettings =
        DesktopSettings(buildSettingsStore(tmp.root.resolve("desktop.preferences_pb")))

    @Test
    fun failingLoginStartLeavesStartAtLoginUnchangedAndReturnsFalse() = runBlocking {
        val settings = settings()
        assertFalse(settings.prefs.first().startAtLogin)

        val failingAutostart = object : LoginStart {
            override fun isEnabled() = false
            override fun enable(exec: String) = throw PortalException(1)
            override fun disable() = throw PortalException(1)
        }

        val enabled = updateStartAtLogin(failingAutostart, settings, launcher = "/opt/umm/bin/Umm", on = true)
        assertFalse(enabled)
        assertFalse(settings.prefs.first().startAtLogin)

        // Stored value remains true if disable fails
        settings.update { it.copy(startAtLogin = true) }
        val disabled = updateStartAtLogin(failingAutostart, settings, launcher = "/opt/umm/bin/Umm", on = false)
        assertFalse(disabled)
        assertTrue(settings.prefs.first().startAtLogin)
    }

    @Test
    fun workingLoginStartStoresTheNewValueAndReturnsTrue() = runBlocking {
        val settings = settings()
        assertFalse(settings.prefs.first().startAtLogin)

        var passedExec: String? = null
        var disabledCalled = false
        val workingAutostart = object : LoginStart {
            override fun isEnabled() = false
            override fun enable(exec: String) {
                passedExec = exec
            }
            override fun disable() {
                disabledCalled = true
            }
        }

        val enabled = updateStartAtLogin(workingAutostart, settings, launcher = "/opt/umm/bin/Umm", on = true)
        assertTrue(enabled)
        assertEquals("/opt/umm/bin/Umm", passedExec)
        assertTrue(settings.prefs.first().startAtLogin)

        val disabled = updateStartAtLogin(workingAutostart, settings, launcher = "/opt/umm/bin/Umm", on = false)
        assertTrue(disabled)
        assertTrue(disabledCalled)
        assertFalse(settings.prefs.first().startAtLogin)
    }
}
