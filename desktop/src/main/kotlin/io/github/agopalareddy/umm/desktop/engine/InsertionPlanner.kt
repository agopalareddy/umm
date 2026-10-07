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
        return foldSeparators(parts)
    }

    /**
     * Each paste costs a clipboard round trip, and an app may take the pasted text after the next typed key. So
     * spaces and punctuation between two pasted runs ("कल, मिलते हैं") are pasted with them instead of typed. Newlines
     * and tabs stay typed (they are Return and Tab), as do runs with letters or digits.
     */
    private fun foldSeparators(parts: List<InsertPart>): List<InsertPart> {
        val folded = mutableListOf<InsertPart>()
        var i = 0
        while (i < parts.size) {
            val part = parts[i]
            val before = folded.lastOrNull()
            val after = parts.getOrNull(i + 1)
            if (part is InsertPart.Type && before is InsertPart.Paste && after is InsertPart.Paste && isOnlySeparators(part.text)) {
                folded[folded.lastIndex] = InsertPart.Paste(before.text + part.text + after.text)
                i += 2
            } else {
                folded += part
                i++
            }
        }
        return folded
    }

    private fun isOnlySeparators(text: String) = text.none { it == '\n' || it == '\t' || it.isLetterOrDigit() }

    private fun isTypableAscii(codePoint: Int) = codePoint in 0x20..0x7E || codePoint == '\n'.code || codePoint == '\t'.code
}
