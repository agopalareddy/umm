package io.github.agopalareddy.umm.ime

object TextInsertion {
    private const val NO_SPACE_BEFORE = ",.;:!?)]}'\"…"
    private val lineBreaks = Regex("\\s*\\n+\\s*")

    /**
     * Adapts dictated [text] to the field: one line for single-line fields, a space when it follows a word,
     * and a space when a word follows it.
     */
    fun prepare(text: String, before: CharSequence?, after: CharSequence?, multiLine: Boolean): String {
        val body = if (multiLine) text else text.replace(lineBreaks, " ")
        if (body.isEmpty()) return body
        val leading = !before.isNullOrEmpty() && !before.last().isWhitespace() &&
            body.first() !in NO_SPACE_BEFORE && !body.first().isWhitespace()
        val trailing = !after.isNullOrEmpty() && !after.first().isWhitespace() &&
            after.first() !in NO_SPACE_BEFORE && !body.last().isWhitespace()
        return (if (leading) " " else "") + body + (if (trailing) " " else "")
    }
}
