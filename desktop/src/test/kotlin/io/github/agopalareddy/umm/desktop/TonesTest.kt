package io.github.agopalareddy.umm.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TonesTest {
    private fun samples(pcm: ByteArray) = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { b ->
        ShortArray(b.remaining()).also(b::get)
    }

    @Test fun lengthMatchesTheDuration() {
        assertEquals(Tones.SAMPLE_RATE * 120 / 1000 * 2, Tones.chirp(660.0, 880.0, 120).size)
    }

    @Test fun startsAndEndsSilentSoItDoesNotClick() {
        val s = samples(Tones.chirp(660.0, 880.0, 120))
        assertEquals(0, s.first().toInt())
        assertTrue(abs(s.last().toInt()) < 400)
    }

    @Test fun isAudibleButQuiet() {
        val peak = samples(Tones.chirp(880.0, 550.0, 120)).maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 3000..16000)
    }
}
