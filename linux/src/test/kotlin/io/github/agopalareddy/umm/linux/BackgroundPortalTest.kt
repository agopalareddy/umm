package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.PortalException
import java.io.File
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackgroundPortalTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun disableAsksThePortalToStopAutostart() {
        val flag = tmp.newFile("autostart")
        var requestedAutostart: Boolean? = null
        val portal = BackgroundPortal(
            flagFile = flag,
            request = { autostart ->
                requestedAutostart = autostart
                emptyMap()
            },
        )

        assertTrue(portal.isEnabled())
        portal.disable()
        assertEquals(false, requestedAutostart)
        assertFalse(portal.isEnabled())
        assertFalse(flag.exists())
    }

    @Test
    fun disableKeepsTheFlagWhenThePortalFails() {
        val flag = tmp.newFile("autostart")
        val portal = BackgroundPortal(
            flagFile = flag,
            request = { throw PortalException(1) },
        )

        assertTrue(portal.isEnabled())
        try {
            portal.disable()
            fail("Expected PortalException")
        } catch (_: PortalException) {
        }
        assertTrue(portal.isEnabled())
        assertTrue(flag.exists())
    }

    @Test
    fun enableWritesTheFlagOnlyWhenThePortalAgrees() {
        val flag = File(tmp.root, "autostart")
        var requestedAutostart: Boolean? = null
        val portal = BackgroundPortal(
            flagFile = flag,
            request = { autostart ->
                requestedAutostart = autostart
                mapOf("autostart" to Variant(true))
            },
        )

        assertFalse(portal.isEnabled())
        portal.enable("/opt/umm/bin/Umm")
        assertEquals(true, requestedAutostart)
        assertTrue(portal.isEnabled())
        assertTrue(flag.exists())
    }

    @Test
    fun enableDoesNotWriteTheFlagWhenAutostartIsRefused() {
        val flag = File(tmp.root, "autostart")
        val portal = BackgroundPortal(
            flagFile = flag,
            request = { mapOf("autostart" to Variant(false)) },
        )

        assertFalse(portal.isEnabled())
        try {
            portal.enable("/opt/umm/bin/Umm")
            fail("Expected PortalException")
        } catch (_: PortalException) {
        }
        assertFalse(portal.isEnabled())
        assertFalse(flag.exists())
    }
}
