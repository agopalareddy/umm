package io.github.agopalareddy.umm.core.audio

import kotlin.math.max

data class SilenceConfig(
    /** Stop after this much silence following speech; null means Off (tap to stop only). */
    val silenceTimeoutMs: Long?,
    val noSpeechGraceMs: Long = 8_000,
    val maxDurationMs: Long = 300_000,
    /**
     * On MediaRecorder.getMaxAmplitude()'s 0..32767 scale. VOICE_RECOGNITION has no gain control: on a Pixel 9 Pro a
     * quiet room reads ~60-110, normal speech ~250-550, and a whisper ~200-700.
     */
    val minSpeechAmplitude: Int = 200,
    val noiseMultiplier: Double = 4.5,
    /** Noise floor = 25th percentile of this many recent non-speech samples. */
    val noiseWindowSamples: Int = 30,
    /** The first non-zero samples only measure the room; they cannot start speech. */
    val calibrationSamples: Int = 2,
    /** Consecutive loud samples needed to start speech, so single clicks and taps are ignored. */
    val onsetSamples: Int = 2,
)

enum class SilenceEvent { SPEECH_STARTED, STOP_FOR_SILENCE, CANCEL_NO_SPEECH, STOP_FOR_MAX_DURATION }

/**
 * Decides when a recording should stop, from amplitude samples alone.
 * A sample is loud when it is well above the recent noise floor, so steady background noise reads as silence.
 * Only quiet samples feed the floor, so long stretches of speech never raise it.
 */
class SilenceDetector(private val config: SilenceConfig) {
    private val window = ArrayDeque<Int>()
    private var lastSpeechAtMs = 0L
    private var terminal: SilenceEvent? = null
    private var calibrated = 0
    private var loudRun = 0

    var speechDetected: Boolean = false
        private set

    /** When set, only the duration cap ends the recording: no silence stop and no "no speech" cancel. */
    var continuous: Boolean = false

    /** Returns at most one event; once a terminal event is returned, it is returned again for every later sample. */
    fun onSample(amplitude: Int, elapsedMs: Long): SilenceEvent? {
        terminal?.let { return it }

        val calibrating = amplitude > 0 && calibrated < config.calibrationSamples
        val threshold = max(config.minSpeechAmplitude, (noiseFloor() * config.noiseMultiplier).toInt())
        val loud = !calibrating && amplitude > threshold
        if (amplitude > 0 && !loud) {
            if (calibrating) calibrated++
            window.addLast(amplitude)
            if (window.size > config.noiseWindowSamples) window.removeFirst()
        }
        loudRun = if (loud) loudRun + 1 else 0

        var started = false
        if (loud && (speechDetected || loudRun >= config.onsetSamples)) {
            lastSpeechAtMs = elapsedMs
            if (!speechDetected) {
                speechDetected = true
                started = true
            }
        }

        val end = when {
            elapsedMs >= config.maxDurationMs -> SilenceEvent.STOP_FOR_MAX_DURATION
            continuous -> null
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

    private fun noiseFloor(): Int {
        if (window.isEmpty()) return 0
        val sorted = window.sorted()
        return sorted[(sorted.size - 1) / 4]
    }
}
