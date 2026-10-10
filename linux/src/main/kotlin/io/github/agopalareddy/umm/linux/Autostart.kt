package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Portal
import java.io.File

/** Controls whether Umm starts automatically in the background at user login. */
interface LoginStart {
    fun isEnabled(): Boolean
    fun enable(exec: String)
    fun disable()
}

/** Starts Umm in the background at login, through an XDG autostart entry. */
class Autostart(configHome: File) : LoginStart {
    private val entry = File(configHome, "autostart/$APP_ID.desktop")

    override fun isEnabled() = entry.isFile

    override fun enable(exec: String) {
        entry.parentFile.mkdirs()
        val command = if (exec.any(Char::isWhitespace)) "\"$exec\"" else exec
        entry.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Umm
            Comment=Voice dictation
            Exec=$command --background
            Icon=$APP_ID
            Terminal=false
            X-GNOME-Autostart-enabled=true
            """.trimIndent() + "\n",
        )
    }

    override fun disable() {
        entry.delete()
    }

    companion object {
        const val APP_ID = "io.github.agopalareddy.Umm"

        /** XDG_CONFIG_HOME when it is an absolute path, else `~/.config`. */
        fun forEnvironment(env: Map<String, String>, home: File): Autostart =
            Autostart(env["XDG_CONFIG_HOME"]?.let(::File)?.takeIf { it.isAbsolute } ?: File(home, ".config"))
    }
}

/** Selects BackgroundPortal inside Flatpak (detected by [flatpakInfo]), or Autostart outside. */
fun loginStartFor(
    env: Map<String, String>,
    home: File,
    flatpakInfo: File = File("/.flatpak-info"),
    portal: () -> Portal,
): LoginStart =
    if (flatpakInfo.exists()) {
        val configHome = env["XDG_CONFIG_HOME"]?.let(::File)?.takeIf { it.isAbsolute } ?: File(home, ".config")
        BackgroundPortal(portal, File(configHome, "umm/autostart"))
    } else {
        Autostart.forEnvironment(env, home)
    }

