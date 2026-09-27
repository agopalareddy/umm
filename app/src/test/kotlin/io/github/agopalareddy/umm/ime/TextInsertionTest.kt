package io.github.agopalareddy.umm.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {
    @Test fun addsSpaceAfterWord() {
        assertEquals(" how are you", TextInsertion.prepare("how are you", "Hello", true))
    }

    @Test fun noSpaceAfterWhitespaceOrStart() {
        assertEquals("how are you", TextInsertion.prepare("how are you", "Hello ", true))
        assertEquals("how are you", TextInsertion.prepare("how are you", "Hello\n", true))
        assertEquals("how are you", TextInsertion.prepare("how are you", null, true))
        assertEquals("how are you", TextInsertion.prepare("how are you", "", true))
    }

    @Test fun noSpaceBeforePunctuation() {
        assertEquals(", right?", TextInsertion.prepare(", right?", "Hello", true))
        assertEquals(". Next", TextInsertion.prepare(". Next", "Done", true))
    }

    @Test fun flattensNewlinesForSingleLineField() {
        assertEquals("- eggs - milk", TextInsertion.prepare("- eggs\n- milk", "", false))
        assertEquals("One. Two.", TextInsertion.prepare("One.\n\nTwo.", "", false))
    }

    @Test fun keepsNewlinesForMultiLineField() {
        assertEquals("- eggs\n- milk", TextInsertion.prepare("- eggs\n- milk", "", true))
    }
}
