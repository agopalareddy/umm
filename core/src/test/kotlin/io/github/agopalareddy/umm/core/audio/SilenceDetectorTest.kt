package io.github.agopalareddy.umm.core.audio

import io.github.agopalareddy.umm.core.audio.SilenceEvent.CANCEL_NO_SPEECH
import io.github.agopalareddy.umm.core.audio.SilenceEvent.SPEECH_STARTED
import io.github.agopalareddy.umm.core.audio.SilenceEvent.STOP_FOR_MAX_DURATION
import io.github.agopalareddy.umm.core.audio.SilenceEvent.STOP_FOR_SILENCE
import org.junit.Assert.assertEquals
import org.junit.Test

class SilenceDetectorTest {
    private val terminal = setOf(STOP_FOR_SILENCE, CANCEL_NO_SPEECH, STOP_FOR_MAX_DURATION)

    /** Feeds one sample every 100 ms starting at t=0; returns (timeMs, event) pairs up to the first terminal event. */
    private fun feed(amps: List<Int>, timeoutMs: Long? = 3_000): List<Pair<Long, SilenceEvent>> {
        val detector = SilenceDetector(SilenceConfig(silenceTimeoutMs = timeoutMs))
        val events = mutableListOf<Pair<Long, SilenceEvent>>()
        for ((i, amp) in amps.withIndex()) {
            val t = i * 100L
            val e = detector.onSample(amp, t) ?: continue
            events += t to e
            if (e in terminal) break
        }
        return events
    }

    private fun rep(amp: Int, n: Int) = List(n) { amp }

    @Test fun cancelsWhenNoSpeechWithinGrace() {
        assertEquals(listOf(8_000L to CANCEL_NO_SPEECH), feed(rep(200, 81)))
    }

    @Test fun speechThenSilenceStopsAfterTimeout() {
        // Last loud sample at t=1400 → stop at 4400.
        val events = feed(rep(200, 5) + rep(8000, 10) + rep(200, 60))
        assertEquals(listOf(600L to SPEECH_STARTED, 4_400L to STOP_FOR_SILENCE), events)
    }

    @Test fun shortPausesDoNotStop() {
        // Loud until 1400, 2 s pause, loud 3500..3900, then quiet → stop at 6900.
        val events = feed(rep(200, 5) + rep(8000, 10) + rep(200, 20) + rep(8000, 5) + rep(200, 60))
        assertEquals(listOf(600L to SPEECH_STARTED, 6_900L to STOP_FOR_SILENCE), events)
    }

    @Test fun offNeverStopsForSilence() {
        val events = feed(rep(200, 5) + rep(8000, 10) + rep(200, 600), timeoutMs = null)
        assertEquals(listOf(600L to SPEECH_STARTED), events)
    }

    @Test fun capsAtMaxDuration() {
        // Speech-like: bursts of loud samples with short dips.
        val speechLike = List(3_001) { if (it % 4 == 3) 1000 else 8000 }
        val events = feed(speechLike)
        assertEquals(STOP_FOR_MAX_DURATION, events.last().second)
        assertEquals(300_000L, events.last().first)
    }

    @Test fun shortBurstStillCountsAsSpeech() {
        val events = feed(rep(200, 5) + rep(9000, 3) + rep(200, 60))
        assertEquals(listOf(600L to SPEECH_STARTED, 3_700L to STOP_FOR_SILENCE), events)
    }

    @Test fun stopsAfterTimeoutAboveNoiseFloor() {
        // Steady fan noise at 3000; speech at 14000 until t=1400.
        val events = feed(rep(3000, 5) + rep(14000, 10) + rep(3000, 60))
        assertEquals(listOf(600L to SPEECH_STARTED, 4_400L to STOP_FOR_SILENCE), events)
    }

    @Test fun steadyNoiseAloneIsNotSpeech() {
        assertEquals(listOf(8_000L to CANCEL_NO_SPEECH), feed(rep(3000, 81)))
    }

    @Test fun initialZeroReadingDoesNotLowerNoiseFloor() {
        // MediaRecorder.getMaxAmplitude() returns 0 on its first call.
        assertEquals(listOf(8_000L to CANCEL_NO_SPEECH), feed(listOf(0) + rep(3000, 80)))
    }

    @Test fun speakingImmediatelyStillDetected() {
        val speech = List(20) { if (it % 3 == 2) 1000 else 9000 }
        val events = feed(speech + rep(200, 60))
        assertEquals(SPEECH_STARTED, events.first().second)
        assertEquals(STOP_FOR_SILENCE, events.last().second)
    }

    @Test fun speechRightAfterCalibrationDetected() {
        // Reviewer trace with a realistic 200 ms lead before the first word.
        val events = feed(listOf(0, 300, 300, 6000, 7000, 5000) + rep(200, 60))
        assertEquals(listOf(400L to SPEECH_STARTED, 3_500L to STOP_FOR_SILENCE), events)
    }

    @Test fun continuousSpeechWithShallowDipsIsNotSilence() {
        // 6 s of speech whose dips never fall to the room's noise level.
        val speech = List(60) { if (it % 2 == 0) 9000 else 5000 }
        val events = feed(rep(200, 5) + speech + rep(200, 40))
        assertEquals(listOf(600L to SPEECH_STARTED, 9_400L to STOP_FOR_SILENCE), events)
    }

    // Real traces from a Pixel 9 Pro (MediaRecorder VOICE_RECOGNITION), 2026-09-27.

    /** The keyboard opened in a quiet room; only clicks and handling noise. */
    private val pixelQuietRoom = listOf(
        0, 4, 40, 45, 0, 25, 55, 1190, 165, 119, 64, 74, 69, 471, 126, 101, 93, 75, 94, 85,
        98, 106, 95, 106, 75, 73, 81, 61, 92, 85, 90, 67, 90, 91, 96, 99, 88, 94, 77, 99,
        68, 210, 226, 85, 111, 101, 671, 95, 85, 98, 114, 88, 90, 105, 62, 71, 104, 137, 146, 154,
        148, 336, 299, 118, 140, 142, 150, 132, 110, 111, 91, 67, 72, 73, 63, 77, 91, 83, 90, 84,
        69,
    )

    /** Normal voice (~1.5 s), a pause, then a whisper; ended by tapping Stop. */
    private val pixelVoiceThenWhisper = listOf(
        0, 28, 76, 96, 117, 98, 105, 192, 490, 553, 420, 377, 255, 406, 251, 266, 258, 237, 238, 384,
        269, 242, 154, 83, 89, 62, 83, 47, 75, 84, 67, 87, 88, 257, 101, 75, 91, 109, 70, 408,
        67, 738, 344, 215, 181, 211, 400, 96, 108, 66, 79, 60, 223, 406, 614, 219, 350, 101, 189, 163,
        196, 260, 99, 68, 108, 65, 77, 105, 116,
    )

    @Test fun pixelQuietRoomWithClicksIsNotSpeech() {
        assertEquals(listOf(8_000L to CANCEL_NO_SPEECH), feed(pixelQuietRoom + rep(90, 10)))
    }

    @Test fun pixelNormalVoiceAndWhisperAreSpeech() {
        val events = feed(pixelVoiceThenWhisper)
        assertEquals(SPEECH_STARTED, events.first().second)
        // The 1 s pause between voice and whisper must not end the dictation.
        assertEquals(listOf(SPEECH_STARTED), events.map { it.second })
    }

    @Test fun continuousModeIgnoresSilence() {
        val detector = SilenceDetector(SilenceConfig(silenceTimeoutMs = 3_000))
        detector.continuous = true
        val events = (rep(90, 5) + rep(8000, 5) + rep(90, 200)).mapIndexedNotNull { i, a -> detector.onSample(a, i * 100L) }
        assertEquals(listOf(SPEECH_STARTED), events)
    }

    @Test fun continuousModeStillCapsDuration() {
        val detector = SilenceDetector(SilenceConfig(silenceTimeoutMs = 3_000))
        detector.continuous = true
        assertEquals(STOP_FOR_MAX_DURATION, detector.onSample(90, 300_000))
    }
}
