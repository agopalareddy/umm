package io.github.agopalareddy.umm.core.platform

import java.io.File

/** Umm's directories under the XDG base directories, created on first access. */
class XdgPaths(
    private val env: Map<String, String> = System.getenv(),
    private val home: File = File(System.getProperty("user.home")),
) {
    val dataDir: File by lazy { base("XDG_DATA_HOME", ".local/share").resolve("umm").also { it.mkdirs() } }
    val configDir: File by lazy { base("XDG_CONFIG_HOME", ".config").resolve("umm").also { it.mkdirs() } }

    /** Empty or relative values are invalid per the XDG spec and fall back to the default. */
    private fun base(variable: String, default: String): File =
        env[variable]?.let(::File)?.takeIf { it.isAbsolute } ?: File(home, default)
}
