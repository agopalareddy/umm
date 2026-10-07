package io.github.agopalareddy.umm.linux

object Keysyms {
    const val RETURN = 0xff0d
    const val TAB = 0xff09
    const val CONTROL_L = 0xffe3

    /** The X11 keysym that types [codePoint]: Latin-1 keeps its value, everything else uses the Unicode range. */
    fun forChar(codePoint: Int): Int = when {
        codePoint == '\n'.code -> RETURN
        codePoint == '\t'.code -> TAB
        codePoint in 0x20..0x7e || codePoint in 0xa0..0xff -> codePoint
        else -> 0x01000000 + codePoint
    }
}

/** KDE types every character as keysyms; elsewhere (GNOME) only ASCII gets through, the rest is pasted. */
fun capabilityFor(env: Map<String, String>): TypingCapability =
    if (env["XDG_CURRENT_DESKTOP"].orEmpty().contains("KDE", ignoreCase = true)) TypingCapability.ALL else TypingCapability.ASCII
