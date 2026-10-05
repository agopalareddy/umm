package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.audio.AudioSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a PCM-16 WAV file as if it were being recorded: copies it to the recording file, then emits the peak
 * sample of each 100 ms, like MediaRecorder's max amplitude on Android.
 */
class WavFileAudioSource(private val source: File) : AudioSource {
    override val format = "wav"

    private val wav: Wav = parse(source)

    @Volatile private var stopped = false

    override fun record(file: File): Flow<Int> = flow {
        stopped = false
        source.copyTo(file, overwrite = true)
        val bytesPer100ms = wav.sampleRate / 10 * wav.channels * 2
        var offset = wav.dataStart
        while (offset < wav.dataEnd && !stopped) {
            val end = minOf(offset + bytesPer100ms, wav.dataEnd)
            var peak = 0
            for (i in offset until end - 1 step 2) peak = maxOf(peak, abs(wav.bytes.getShort(i).toInt()))
            emit(minOf(peak, Short.MAX_VALUE.toInt()))
            offset = end
        }
    }

    override fun stop() {
        stopped = true
    }

    private class Wav(val bytes: ByteBuffer, val sampleRate: Int, val channels: Int, val dataStart: Int, val dataEnd: Int)

    private companion object {
        fun parse(file: File): Wav = try {
            parseChunks(file)
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("truncated WAV file", e)
        }

        fun parseChunks(file: File): Wav {
            require(file.canRead()) { "cannot read the file" }
            val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.limit() >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "not a WAV file" }
            var sampleRate = 0
            var channels = 0
            var offset = 12
            while (offset + 8 <= bytes.limit()) {
                val size = bytes.getInt(offset + 4)
                require(size >= 0) { "corrupt WAV chunk" }
                val body = offset + 8
                when (tag(bytes, offset)) {
                    "fmt " -> {
                        require(bytes.getShort(body).toInt() == 1 && bytes.getShort(body + 14).toInt() == 16) {
                            "not 16-bit PCM audio"
                        }
                        channels = bytes.getShort(body + 2).toInt()
                        sampleRate = bytes.getInt(body + 4)
                    }
                    "data" -> {
                        require(sampleRate > 0 && channels > 0) { "no format chunk before the audio data" }
                        return Wav(bytes, sampleRate, channels, body, minOf(body + size, bytes.limit()))
                    }
                }
                offset = body + size + (size and 1)
            }
            throw IllegalArgumentException("no audio data")
        }

        fun tag(bytes: ByteBuffer, at: Int) = String(ByteArray(4) { bytes.get(at + it) }, Charsets.US_ASCII)
    }
}
