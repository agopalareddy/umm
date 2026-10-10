package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Background
import io.github.agopalareddy.umm.linux.portal.Portal
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
    private val portal: () -> Portal,
    private val flagFile: File = defaultFlagFile(),
) : LoginStart {

    constructor(portal: Portal, flagFile: File = defaultFlagFile()) : this({ portal }, flagFile)

    override fun isEnabled(): Boolean = flagFile.isFile

    override fun enable(exec: String) {
        val background = portal().portalObject<Background>()
        val options = mapOf(
            "reason" to Variant("Voice dictation"),
            "autostart" to Variant(true),
            "commandline" to Variant(arrayOf("umm", "--background")),
        )
        portal().request(options) { background.RequestBackground("", it) }
        flagFile.parentFile?.mkdirs()
        flagFile.writeText("true\n")
    }

    override fun disable() {
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
