package io.github.agopalareddy.umm.ime

object TextInsertion {
    private const val NO_SPACE_BEFORE = ",.;:!?)]}'\"…"
    private val lineBreaks = Regex("\\s*\\n+\\s*")

    /** Adapts dictated [text] to the field: one line for single-line fields, and a space when it follows a word. */
    fun prepare(text: String, before: CharSequence?, multiLine: Boolean): String {
        val body = if (multiLine) text else text.replace(lineBreaks, " ")
        val needsSpace = !before.isNullOrEmpty() && !before.last().isWhitespace() &&
            body.isNotEmpty() && body.first() !in NO_SPACE_BEFORE && !body.first().isWhitespace()
        return if (needsSpace) " $body" else body
    }
}
