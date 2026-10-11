package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Background
import io.github.agopalareddy.umm.linux.portal.Portal
import io.github.agopalareddy.umm.linux.portal.PortalException
import java.io.File
import org.freedesktop.dbus.types.Variant

/**
 * Starts Umm at login inside Flatpak using the XDG Background portal.
 *
 * Inside Flatpak, applications cannot write desktop files into the user's autostart
 * directory directly. The Background portal manages autostart entries on the host.
 * Because the portal cannot be queried for the current status, [isEnabled] reads a persisted flag.
 */
class BackgroundPortal(
    portal: () -> Portal = { error("Portal not configured") },
    private val flagFile: File = defaultFlagFile(),
    private val request: (autostart: Boolean) -> Map<String, Variant<*>> = { autostart ->
        val p = portal()
        val background = p.portalObject<Background>()
        val options = mapOf(
            "reason" to Variant("Voice dictation"),
            "autostart" to Variant(autostart),
            "commandline" to Variant(arrayOf("umm", "--background")),
        )
        p.request(options) { background.RequestBackground("", it) }
    },
) : LoginStart {

    constructor(portal: Portal, flagFile: File = defaultFlagFile()) : this({ portal }, flagFile)

    override fun isEnabled(): Boolean = flagFile.isFile

    override fun enable(exec: String) {
        val response = request(true)
        if (response["autostart"]?.value == false) {
            throw PortalException(1)
        }
        flagFile.parentFile?.mkdirs()
        flagFile.writeText("true\n")
    }

    override fun disable() {
        request(false)
        flagFile.delete()
    }

    companion object {
        fun defaultFlagFile(
            env: Map<String, String> = emptyMap(),
            home: File = File(System.getProperty("user.home", "/tmp")),
        ): File {
            val configHome = env["XDG_CONFIG_HOME"]?.let(::File)?.takeIf { it.isAbsolute } ?: File(home, ".config")
            return File(configHome, "umm/autostart")
        }
    }
}
