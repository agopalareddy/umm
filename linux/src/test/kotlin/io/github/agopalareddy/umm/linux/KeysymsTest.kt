package io.github.agopalareddy.umm.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class KeysymsTest {
    @Test fun latinKeepsItsCodePoint() {
        assertEquals(0x61, Keysyms.forChar('a'.code))
        assertEquals(0xe9, Keysyms.forChar('é'.code))
    }

    @Test fun newlineAndTabAreReturnAndTab() {
        assertEquals(0xff0d, Keysyms.forChar('\n'.code))
        assertEquals(0xff09, Keysyms.forChar('\t'.code))
    }

    @Test fun otherScriptsUseTheUnicodeKeysymRange() {
        assertEquals(0x01000915, Keysyms.forChar('क'.code))
        assertEquals(0x0101F44D, Keysyms.forChar(0x1F44D))
    }

    @Test fun kdeTypesEverythingAndOthersOnlyAscii() {
        assertEquals(TypingCapability.ALL, capabilityFor(mapOf("XDG_CURRENT_DESKTOP" to "KDE")))
        assertEquals(TypingCapability.ALL, capabilityFor(mapOf("XDG_CURRENT_DESKTOP" to "KDE:Plasma")))
        assertEquals(TypingCapability.ASCII, capabilityFor(mapOf("XDG_CURRENT_DESKTOP" to "GNOME")))
        assertEquals(TypingCapability.ASCII, capabilityFor(emptyMap()))
    }
}
