package io.github.agopalareddy.umm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelTest {
    @Test fun `room noise is silent`() {
        assertEquals(0f, VoiceLevel.of(0), 0f)
        assertEquals(0f, VoiceLevel.of(110), 0f)
    }

    @Test fun `normal speech reacts clearly`() {
        assertTrue(VoiceLevel.of(250) in 0.2f..0.4f)
        assertTrue(VoiceLevel.of(550) in 0.4f..0.7f)
    }

    @Test fun `loud speech saturates instead of overflowing`() {
        assertEquals(1f, VoiceLevel.of(VoiceLevel.CEIL), 0f)
        assertEquals(1f, VoiceLevel.of(32767), 0f)
    }

    @Test fun `louder never reads lower`() {
        val levels = (0..3000 step 50).map(VoiceLevel::of)
        assertEquals(levels.sorted(), levels)
    }
}
