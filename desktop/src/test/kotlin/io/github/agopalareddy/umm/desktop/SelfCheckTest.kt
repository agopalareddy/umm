package io.github.agopalareddy.umm.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfCheckTest {
    @Test
    fun passesInTheTestJvm() {
        assertEquals(emptyList<String>(), SelfCheck.run())
    }
}
