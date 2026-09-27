package io.github.agopalareddy.umm.core.stats

object TextStats {
    private val whitespace = Regex("\\s+")
    private val filler = Regex("^(u+m+|u+h+m*|e+r+m*|a+h+|h+m+|m{2,})$")

    /** Tokens that contain a letter or digit, so list bullets and dashes don't count. */
    fun words(text: String): Int = tokens(text).count { token -> token.any(Char::isLetterOrDigit) }

    /** Hesitation sounds such as um, uh, erm, hmm. */
    fun fillers(text: String): Int = tokens(text).count { filler.matches(it.lowercase().filter(Char::isLetter)) }

    private fun tokens(text: String) = text.split(whitespace).filter { it.isNotEmpty() }
}
