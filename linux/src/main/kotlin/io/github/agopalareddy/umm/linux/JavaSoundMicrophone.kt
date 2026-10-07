package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.core.audio.AudioSource
import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/** Records 16 kHz mono 16-bit WAV from the default input (or the mixer called [mixerName]). */
class JavaSoundMicrophone(private val mixerName: String? = null) : AudioSource {
    override val format = "wav"

    @Volatile private var stopped = false

    private val audioFormat = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
    private val lineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)

    /** True when a matching capture line exists and can be opened. */
    fun available(): Boolean = try {
        openLine().use { true }
    } catch (e: LineUnavailableException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    override fun record(file: File): Flow<Int> = flow {
        stopped = false
        val line = try {
            openLine()
        } catch (e: LineUnavailableException) {
            throw IllegalStateException("Microphone unavailable", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Microphone unavailable", e)
        }
        var dataBytes = 0
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write(WavWriter.header(SAMPLE_RATE, 1, 0))
            try {
                line.start()
                val chunk = ByteArray(CHUNK_BYTES)
                while (!stopped) {
                    val read = line.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    out.write(chunk, 0, read)
                    dataBytes += read
                    emit(WavWriter.peak(chunk, read))
                }
            } finally {
                line.stop()
                line.close()
                out.seek(0)
                out.write(WavWriter.header(SAMPLE_RATE, 1, dataBytes))
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        stopped = true
    }

    private fun openLine(): TargetDataLine {
        val mixer = mixerName?.let { name -> AudioSystem.getMixerInfo().firstOrNull { it.name == name } }
        val line = if (mixer != null) AudioSystem.getMixer(mixer).getLine(lineInfo) else AudioSystem.getLine(lineInfo)
        return (line as TargetDataLine).also { it.open(audioFormat) }
    }

    private inline fun <R> TargetDataLine.use(block: () -> R): R = try {
        block()
    } finally {
        close()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2
    }
}
