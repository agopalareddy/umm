package io.github.agopalareddy.umm.sync.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class FrameCodecTest {

    @Test
    fun roundTrip() {
        val payload = "hello frame codec over lan".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        FrameCodec.write(out, payload)

        val input = ByteArrayInputStream(out.toByteArray())
        val read = FrameCodec.read(input)
        assertArrayEquals(payload, read)
    }

    @Test
    fun lengthExceedsMaxThrowsIOException() {
        val tooBig = FrameCodec.MAX + 1
        val out = ByteArrayOutputStream()
        out.write((tooBig ushr 24) and 0xFF)
        out.write((tooBig ushr 16) and 0xFF)
        out.write((tooBig ushr 8) and 0xFF)
        out.write(tooBig and 0xFF)

        val input = ByteArrayInputStream(out.toByteArray())
        assertThrows(IOException::class.java) {
            FrameCodec.read(input)
        }
    }

    @Test
    fun truncatedStreamThrowsEOFException() {
        // Truncated length header
        val truncatedHeader = ByteArrayInputStream(byteArrayOf(0x00, 0x00))
        assertThrows(EOFException::class.java) {
            FrameCodec.read(truncatedHeader)
        }

        // Truncated payload
        val out = ByteArrayOutputStream()
        val len = 100
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(ByteArray(10)) // Only 10 bytes instead of 100

        val input = ByteArrayInputStream(out.toByteArray())
        assertThrows(EOFException::class.java) {
            FrameCodec.read(input)
        }
    }
}
