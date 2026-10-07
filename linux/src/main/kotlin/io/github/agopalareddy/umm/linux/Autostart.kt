package io.github.agopalareddy.umm.linux

import java.io.File

/** Starts Umm in the background at login, through an XDG autostart entry. */
class Autostart(configHome: File) {
    private val entry = File(configHome, "autostart/$APP_ID.desktop")

    fun isEnabled() = entry.isFile

    fun enable(exec: String) {
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

    fun disable() {
        entry.delete()
    }

    companion object {
        const val APP_ID = "io.github.agopalareddy.Umm"

        /** XDG_CONFIG_HOME when it is an absolute path, else `~/.config`. */
        fun forEnvironment(env: Map<String, String>, home: File): Autostart =
            Autostart(env["XDG_CONFIG_HOME"]?.let(::File)?.takeIf { it.isAbsolute } ?: File(home, ".config"))
    }
}
