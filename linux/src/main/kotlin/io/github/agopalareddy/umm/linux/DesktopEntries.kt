package io.github.agopalareddy.umm.linux

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** An installed app: its desktop-file ID (what KWin reports as `desktopFileName`) and display name. */
data class DesktopEntry(val id: String, val name: String)

/**
 * The apps in the XDG `applications` directories, for the per-app category picker. [dirs] are searched in order and
 * the first file with a given ID wins, including a `Hidden=true` one, which hides that app (XDG menu rules).
 * Scanning reads files, so call from a background thread.
 */
class DesktopEntries(
    private val dirs: List<File>,
    private val locale: Locale = Locale.getDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var index: Map<String, DesktopEntry>? = null
    @Volatile private var scannedAt = 0L

    /** Visible apps, sorted by name, without Umm. Rescans, since Umm runs for days and apps come and go. */
    fun all(): List<DesktopEntry> = scan().values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * The display name for [id], or null when no visible app has it. Uses the last scan; an unknown ID rescans at most
     * every [RESCAN_MS], so a newly installed app gets its name without History rows rescanning on every draw.
     */
    fun label(id: String): String? {
        val current = index ?: scan()
        current[id]?.let { return it.name }
        if (clock() - scannedAt < RESCAN_MS) return null
        return scan()[id]?.name
    }

    private fun scan(): Map<String, DesktopEntry> = build().also {
        index = it
        scannedAt = clock()
    }

    private fun build(): Map<String, DesktopEntry> {
        val seen = mutableSetOf<String>()
        val found = mutableMapOf<String, DesktopEntry>()
        for (dir in dirs) {
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".desktop") }.forEach { file ->
                val id = file.relativeTo(dir).path.removeSuffix(".desktop").replace(File.separatorChar, '-')
                if (!seen.add(id)) return@forEach
                parse(file)?.let { name -> if (id != UMM_ID) found[id] = DesktopEntry(id, name) }
            }
        }
        return found
    }

    /** The localized name of a visible application entry, or null for anything else. */
    private fun parse(file: File): String? {
        val text = readUtf8(file) ?: return null
        val keys = mutableMapOf<String, String>()
        var inEntry = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inEntry || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            keys.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
        }
        if (keys["Type"] != "Application" || keys["NoDisplay"] == "true" || keys["Hidden"] == "true") return null
        val country = locale.country.takeIf { it.isNotEmpty() }?.let { "Name[${locale.language}_$it]" }
        return listOfNotNull(country, "Name[${locale.language}]", "Name")
            .firstNotNullOfOrNull { keys[it]?.takeIf(String::isNotEmpty) }
    }

    private fun readUtf8(file: File): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(file.readBytes()))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    companion object {
        private const val UMM_ID = "io.github.agopalareddy.Umm"
        private const val RESCAN_MS = 30_000L

        /** `$XDG_DATA_HOME` (or `~/.local/share`) then each `$XDG_DATA_DIRS` entry (or the defaults), plus `applications`. */
        fun directoriesFor(env: Map<String, String>, home: File): List<File> {
            val dataHome = env["XDG_DATA_HOME"]?.takeIf { it.startsWith("/") }?.let(::File) ?: File(home, ".local/share")
            val dataDirs = env["XDG_DATA_DIRS"]?.split(':')?.filter { it.startsWith("/") }?.takeIf { it.isNotEmpty() }
                ?: listOf("/usr/local/share", "/usr/share")
            return (listOf(dataHome) + dataDirs.map(::File)).map { File(it, "applications") }
        }

        fun forEnvironment(env: Map<String, String>, home: File): DesktopEntries = DesktopEntries(directoriesFor(env, home))
    }
}
