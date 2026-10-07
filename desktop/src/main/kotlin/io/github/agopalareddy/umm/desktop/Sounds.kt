package io.github.agopalareddy.umm.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import kotlin.math.PI
import kotlin.math.sin

/** Short synthesized tones, so no audio files have to ship. */
object Tones {
    const val SAMPLE_RATE = 22_050
    private const val PEAK = 9000.0
    private const val FADE_MS = 15

    /** A sine sweep from [fromHz] to [toHz] over [ms], with fades so it neither clicks nor startles. */
    fun chirp(fromHz: Double, toHz: Double, ms: Int): ByteArray {
        val count = SAMPLE_RATE * ms / 1000
        val fade = SAMPLE_RATE * FADE_MS / 1000
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        for (i in 0 until count) {
            val frequency = fromHz + (toHz - fromHz) * i / count
            phase += 2 * PI * frequency / SAMPLE_RATE
            val envelope = minOf(1.0, i / fade.toDouble(), (count - 1 - i) / fade.toDouble())
            buffer.putShort((sin(phase) * PEAK * envelope).toInt().toShort())
        }
        return buffer.array()
    }
}

/** The optional start and stop sounds; [enabled] is read each time, so the setting applies immediately. */
class Sounds(private val enabled: () -> Boolean) {
    private val start = Tones.chirp(660.0, 880.0, 120)
    private val stop = Tones.chirp(880.0, 550.0, 140)

    fun start() = play(start)

    fun stop() = play(stop)

    private fun play(pcm: ByteArray) {
        if (!enabled()) return
        Thread {
            try {
                val clip = AudioSystem.getClip()
                clip.addLineListener { if (it.type == LineEvent.Type.STOP) clip.close() }
                clip.open(AudioFormat(Tones.SAMPLE_RATE.toFloat(), 16, 1, true, false), pcm, 0, pcm.size)
                clip.start()
            } catch (e: Exception) {
                // No audio output: the sounds are optional.
            }
        }.apply { isDaemon = true }.start()
    }
}
