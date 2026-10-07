package io.github.agopalareddy.umm.linux

/** Turns a portal's trigger description into a label people read, e.g. GNOME's "Press <Alt><Super>space". */
object TriggerLabel {
    private val ORDER = listOf("Super", "Ctrl", "Alt", "Shift")
    private val NAMES = mapOf(
        "super" to "Super", "meta" to "Super", "logo" to "Super",
        "control" to "Ctrl", "ctrl" to "Ctrl", "primary" to "Ctrl",
        "alt" to "Alt", "shift" to "Shift",
    )

    fun format(description: String): String {
        val text = description.trim()
        if (text.isEmpty()) return GlobalShortcutsHotkey.DEFAULT_TRIGGER_LABEL
        // KDE already says "Meta+Alt+Space"; only GTK accelerator syntax needs rewriting.
        if ('<' !in text) return text
        val accelerator = text.substring(text.indexOf('<'))
        val modifiers = Regex("<([^>]+)>").findAll(accelerator).map { it.groupValues[1] }
            .map { NAMES[it.lowercase()] ?: it }.distinct().sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) ORDER.size else i } }
        val key = accelerator.substringAfterLast('>').trim().replaceFirstChar { it.uppercase() }
        return (modifiers + key).joinToString("+")
    }
}
