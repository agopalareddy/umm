package io.github.agopalareddy.umm.sync.lan

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object FrameCodec {
    const val MAX = 1 shl 20 // 1 MB

    fun write(out: OutputStream, frame: ByteArray) {
        if (frame.size > MAX) {
            throw IOException("Frame length ${frame.size} exceeds MAX ($MAX)")
        }
        val len = frame.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(frame)
        out.flush()
    }

    fun read(input: InputStream): ByteArray {
        val b0 = input.read()
        if (b0 == -1) throw EOFException("End of stream reached")
        val b1 = input.read()
        if (b1 == -1) throw EOFException("Truncated stream while reading length header")
        val b2 = input.read()
        if (b2 == -1) throw EOFException("Truncated stream while reading length header")
        val b3 = input.read()
        if (b3 == -1) throw EOFException("Truncated stream while reading length header")

        val len = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        if (len < 0 || len > MAX) {
            throw IOException("Frame length $len exceeds MAX ($MAX)")
        }

        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val count = input.read(buf, offset, len - offset)
            if (count == -1) {
                throw EOFException("Truncated stream: expected $len bytes, got $offset")
            }
            offset += count
        }
        return buf
    }
}
