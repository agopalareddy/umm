package io.github.agopalareddy.umm.desktop.engine

import io.github.agopalareddy.umm.linux.InsertPart
import io.github.agopalareddy.umm.linux.TypingCapability

object InsertionPlanner {
    /**
     * Splits [text] into parts to type as key presses and parts to paste. Where everything can be typed that is one
     * part; where only ASCII can, printable ASCII plus newline and tab are typed and everything else is pasted.
     */
    fun plan(text: String, capability: TypingCapability): List<InsertPart> {
        if (text.isEmpty()) return emptyList()
        if (capability == TypingCapability.ALL) return listOf(InsertPart.Type(text))

        val parts = mutableListOf<InsertPart>()
        val run = StringBuilder()
        var runIsTyped = true

        fun flush() {
            if (run.isEmpty()) return
            parts += if (runIsTyped) InsertPart.Type(run.toString()) else InsertPart.Paste(run.toString())
            run.setLength(0)
        }

        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val typed = isTypableAscii(codePoint)
            if (typed != runIsTyped) {
                flush()
                runIsTyped = typed
            }
            run.appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
        flush()
        return parts
    }

    private fun isTypableAscii(codePoint: Int) = codePoint in 0x20..0x7E || codePoint == '\n'.code || codePoint == '\t'.code
}
