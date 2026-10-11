package io.github.agopalareddy.umm.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class HybridClockTest {
    @Test
    fun monotonicWhenWallStalls() {
        var wall = 1000L
        val clock = HybridClock { wall }
        assertEquals(1000L, clock.tick())
        assertEquals(1001L, clock.tick())
        assertEquals(1002L, clock.tick())
    }

    @Test
    fun futureRemoteKeepsMonotonic() {
        var wall = 1000L
        val clock = HybridClock { wall }
        assertEquals(1000L, clock.tick())
        clock.observe(90_000_000L)
        assertEquals(90_000_001L, clock.tick())
        wall = 2000L
        assertEquals(90_000_002L, clock.tick())
    }
}
