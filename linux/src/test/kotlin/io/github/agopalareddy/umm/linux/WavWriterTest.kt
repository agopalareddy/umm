package io.github.agopalareddy.umm.linux

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavWriterTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    @Test fun headerDescribesSixteenKilohertzMonoPcm() {
        val header = WavWriter.header(16000, 1, 32000)
        assertEquals(44, header.size)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(header, at, 4, Charsets.US_ASCII)
        assertEquals("RIFF", tag(0))
        assertEquals(36 + 32000, b.getInt(4))
        assertEquals("WAVE", tag(8))
        assertEquals("fmt ", tag(12))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))
        assertEquals(2, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", tag(36))
        assertEquals(32000, b.getInt(40))
    }

    @Test fun peakIsTheLargestMagnitude() {
        val data = pcm(0, 1000, -2000, 500)
        assertEquals(2000, WavWriter.peak(data, data.size))
    }

    @Test fun peakOfMostNegativeSampleIsClamped() {
        val data = pcm(-32768)
        assertEquals(32767, WavWriter.peak(data, data.size))
    }

    @Test fun peakOnlyReadsTheGivenLength() {
        val data = pcm(100, 9000)
        assertEquals(100, WavWriter.peak(data, 2))
    }
}
