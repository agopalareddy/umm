package io.github.agopalareddy.umm.core.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class TextStatsTest {
    @Test fun countsWords() {
        assertEquals(0, TextStats.words(""))
        assertEquals(0, TextStats.words("   "))
        assertEquals(5, TextStats.words("Um, so can we meet?"))
        assertEquals(4, TextStats.words("- eggs\n- milk\n- bread\n- coffee"))
    }

    @Test fun countsFillerWords() {
        assertEquals(3, TextStats.fillers("Um, so, uh, can we meet at five? Hmm."))
        assertEquals(2, TextStats.fillers("UMM... erm okay"))
        assertEquals(0, TextStats.fillers("The umbrella is under the hummingbird."))
    }
}
