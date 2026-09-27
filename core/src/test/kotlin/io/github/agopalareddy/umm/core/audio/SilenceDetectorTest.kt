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
        assertEquals(listOf(500L to SPEECH_STARTED, 4_400L to STOP_FOR_SILENCE), events)
    }

    @Test fun shortPausesDoNotStop() {
        // Loud until 1400, 2 s pause, loud 3500..3900, then quiet → stop at 6900.
        val events = feed(rep(200, 5) + rep(8000, 10) + rep(200, 20) + rep(8000, 5) + rep(200, 60))
        assertEquals(listOf(500L to SPEECH_STARTED, 6_900L to STOP_FOR_SILENCE), events)
    }

    @Test fun offNeverStopsForSilence() {
        val events = feed(rep(200, 5) + rep(8000, 10) + rep(200, 600), timeoutMs = null)
        assertEquals(listOf(500L to SPEECH_STARTED), events)
    }

    @Test fun capsAtMaxDuration() {
        val speechLike = List(3_001) { if (it % 2 == 0) 8000 else 1000 }
        val events = feed(speechLike)
        assertEquals(STOP_FOR_MAX_DURATION, events.last().second)
        assertEquals(300_000L, events.last().first)
    }

    @Test fun shortBurstStillCountsAsSpeech() {
        val events = feed(rep(200, 5) + rep(9000, 3) + rep(200, 60))
        assertEquals(listOf(500L to SPEECH_STARTED, 3_700L to STOP_FOR_SILENCE), events)
    }

    @Test fun stopsAfterTimeoutAboveNoiseFloor() {
        // Steady fan noise at 3000; speech at 14000 until t=1400.
        val events = feed(rep(3000, 5) + rep(14000, 10) + rep(3000, 60))
        assertEquals(listOf(500L to SPEECH_STARTED, 4_400L to STOP_FOR_SILENCE), events)
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
}
