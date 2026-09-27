package io.github.agopalareddy.umm.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionTest {
    @Test fun addsSpaceAfterWord() {
        assertEquals(" how are you", TextInsertion.prepare("how are you", "Hello", null, true))
    }

    @Test fun noSpaceAfterWhitespaceOrStart() {
        assertEquals("how are you", TextInsertion.prepare("how are you", "Hello ", null, true))
        assertEquals("how are you", TextInsertion.prepare("how are you", "Hello\n", null, true))
        assertEquals("how are you", TextInsertion.prepare("how are you", null, null, true))
        assertEquals("how are you", TextInsertion.prepare("how are you", "", null, true))
    }

    @Test fun noSpaceBeforePunctuation() {
        assertEquals(", right?", TextInsertion.prepare(", right?", "Hello", null, true))
        assertEquals(". Next", TextInsertion.prepare(". Next", "Done", null, true))
    }

    @Test fun flattensNewlinesForSingleLineField() {
        assertEquals("- eggs - milk", TextInsertion.prepare("- eggs\n- milk", "", null, false))
        assertEquals("One. Two.", TextInsertion.prepare("One.\n\nTwo.", "", null, false))
    }

    @Test fun keepsNewlinesForMultiLineField() {
        assertEquals("- eggs\n- milk", TextInsertion.prepare("- eggs\n- milk", "", null, true))
    }

    @Test fun addsSpaceBeforeFollowingWord() {
        assertEquals("Okay. ", TextInsertion.prepare("Okay.", "", "see you", true))
        assertEquals(" okay ", TextInsertion.prepare("okay", "Well", "then", true))
    }

    @Test fun noTrailingSpaceBeforeSpaceOrPunctuation() {
        assertEquals("Okay.", TextInsertion.prepare("Okay.", "", " see you", true))
        assertEquals("okay", TextInsertion.prepare("okay", "", ", then", true))
        assertEquals("okay", TextInsertion.prepare("okay", "", null, true))
    }
}
