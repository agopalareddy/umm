package io.github.agopalareddy.umm.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes a 16 kHz mono PCM-16 WAV made of square-wave segments: (amplitude, milliseconds). */
fun writeWav(file: File, vararg segments: Pair<Int, Int>): File {
    val rate = 16_000
    val samples = segments.flatMap { (amp, ms) -> List(rate * ms / 1000) { i -> if (i % 2 == 0) amp else -amp } }
    val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { data.putShort(it.toShort()) }
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(data.capacity())
    }
    file.writeBytes(header.array() + data.array())
    return file
}
