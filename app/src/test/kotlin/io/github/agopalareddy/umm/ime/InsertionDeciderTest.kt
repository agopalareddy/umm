package io.github.agopalareddy.umm.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionDeciderTest {
    @Test fun sameActiveSessionCommits() {
        assertEquals(Insertion.Commit, InsertionDecider.decide(startedSession = 3, currentSession = 3, inputActive = true))
    }

    @Test fun differentSessionGoesToClipboard() {
        assertEquals(Insertion.Clipboard, InsertionDecider.decide(3, 4, true))
        assertEquals(Insertion.Clipboard, InsertionDecider.decide(3, 3, false))
    }
}
