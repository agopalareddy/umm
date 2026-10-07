package io.github.agopalareddy.umm.linux

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object WavWriter {
    const val HEADER_SIZE = 44

    fun header(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray {
        val blockAlign = channels * 2
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    /** The largest sample magnitude in the first [length] bytes of little-endian 16-bit PCM. */
    fun peak(pcm16le: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm16le[i].toInt() and 0xff) or (pcm16le[i + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
            i += 2
        }
        return minOf(peak, Short.MAX_VALUE.toInt())
    }
}
