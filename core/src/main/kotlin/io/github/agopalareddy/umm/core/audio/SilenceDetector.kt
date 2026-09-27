package io.github.agopalareddy.umm.core.audio

import kotlin.math.max

data class SilenceConfig(
    /** Stop after this much silence following speech; null means Off (tap to stop only). */
    val silenceTimeoutMs: Long?,
    val noSpeechGraceMs: Long = 8_000,
    val maxDurationMs: Long = 300_000,
    /** On MediaRecorder.getMaxAmplitude()'s 0..32767 scale. */
    val minSpeechAmplitude: Int = 1_500,
    val noiseMultiplier: Double = 3.0,
    /** Noise floor = quietest of this many recent non-speech samples. */
    val noiseWindowSamples: Int = 30,
    /** The first non-zero samples only measure the room; they cannot start speech. */
    val calibrationSamples: Int = 2,
)

enum class SilenceEvent { SPEECH_STARTED, STOP_FOR_SILENCE, CANCEL_NO_SPEECH, STOP_FOR_MAX_DURATION }

/**
 * Decides when a recording should stop, from amplitude samples alone.
 * A sample is speech when it is well above the recent noise floor, so steady background noise reads as silence.
 * Only non-speech samples feed the floor, so long stretches of speech never raise it.
 */
class SilenceDetector(private val config: SilenceConfig) {
    private val window = ArrayDeque<Int>()
    private var lastSpeechAtMs = 0L
    private var terminal: SilenceEvent? = null
    private var calibrated = 0

    var speechDetected: Boolean = false
        private set

    /** Returns at most one event; once a terminal event is returned, it is returned again for every later sample. */
    fun onSample(amplitude: Int, elapsedMs: Long): SilenceEvent? {
        terminal?.let { return it }

        val calibrating = amplitude > 0 && calibrated < config.calibrationSamples
        val noiseFloor = window.minOrNull() ?: 0
        val threshold = max(config.minSpeechAmplitude, (noiseFloor * config.noiseMultiplier).toInt())
        val isSpeech = !calibrating && amplitude > threshold
        if (amplitude > 0 && !isSpeech) {
            if (calibrating) calibrated++
            window.addLast(amplitude)
            if (window.size > config.noiseWindowSamples) window.removeFirst()
        }

        var started = false
        if (isSpeech) {
            lastSpeechAtMs = elapsedMs
            if (!speechDetected) {
                speechDetected = true
                started = true
            }
        }

        val end = when {
            elapsedMs >= config.maxDurationMs -> SilenceEvent.STOP_FOR_MAX_DURATION
            !speechDetected && elapsedMs >= config.noSpeechGraceMs -> SilenceEvent.CANCEL_NO_SPEECH
            speechDetected && config.silenceTimeoutMs != null &&
                elapsedMs - lastSpeechAtMs >= config.silenceTimeoutMs -> SilenceEvent.STOP_FOR_SILENCE
            else -> null
        }
        if (end != null) {
            terminal = end
            return end
        }
        return if (started) SilenceEvent.SPEECH_STARTED else null
    }
}
