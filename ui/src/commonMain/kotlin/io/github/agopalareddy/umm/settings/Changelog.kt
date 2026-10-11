package io.github.agopalareddy.umm.settings

/** One release in CHANGELOG.md: its `## version - date` heading and its `- ` lines. */
data class Release(val version: String, val date: String?, val changes: List<String>)

/**
 * Reads the releases out of CHANGELOG.md, in the file's order (newest first). Only `## ` headings and `- ` lines
 * count; the title, the note to writers and blank lines are skipped. A release without changes is left out.
 */
fun parseChangelog(lines: List<String>): List<Release> {
    val releases = mutableListOf<Release>()
    var heading: String? = null
    var changes = mutableListOf<String>()

    fun close() {
        val title = heading ?: return
        if (changes.isEmpty()) return
        val version = title.substringBefore(" - ").trim()
        val date = title.substringAfter(" - ", "").trim().ifEmpty { null }
        releases += Release(version, date, changes)
    }

    for (line in lines) {
        when {
            line.startsWith("## ") -> {
                close()
                heading = line.removePrefix("## ").trim()
                changes = mutableListOf()
            }
            line.startsWith("- ") && heading != null -> changes += line.removePrefix("- ").trim()
        }
    }
    close()
    return releases
}

/** A change split at its `**bold**` marks: (text, bold) pieces in order. */
fun boldParts(change: String): List<Pair<String, Boolean>> =
    change.split("**").mapIndexedNotNull { index, part ->
        if (part.isEmpty()) null else part to (index % 2 == 1)
    }
