package io.github.agopalareddy.umm.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypeTestCommandTest {
    @Test fun textAndDefaultDelay() {
        assertEquals(TypeTestArgs("héllo 👍", 8), parseTypeTestArgs(arrayOf("--type-test", "héllo 👍")))
    }

    @Test fun delayCanBeGiven() {
        assertEquals(TypeTestArgs("hi", 3), parseTypeTestArgs(arrayOf("--type-test", "hi", "--delay", "3")))
    }

    @Test fun notThisCommandOrMalformedIsNull() {
        assertNull(parseTypeTestArgs(emptyArray()))
        assertNull(parseTypeTestArgs(arrayOf("--background")))
        assertNull(parseTypeTestArgs(arrayOf("--type-test")))
        assertNull(parseTypeTestArgs(arrayOf("--type-test", "hi", "--delay", "soon")))
        assertNull(parseTypeTestArgs(arrayOf("--type-test", "--delay", "3")))
    }
}
