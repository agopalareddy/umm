package io.github.agopalareddy.umm.core.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.cleanup.PromptBuilder
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.HistoryStatus
import io.github.agopalareddy.umm.core.data.UmmDatabase
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import io.github.agopalareddy.umm.core.policy.ModelPlan
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DictationPipelineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val db = UmmDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val history = HistoryRepository(db)
    private val audio = FakeAudioSource()
    private val api = FakeApi()
    private val plan = ModelPlan(listOf("stt/p", "stt/f"), listOf("chat/p", "chat/f"))

    @After fun tearDown() = db.close()

    private fun request(level: CleanupLevel = CleanupLevel.LIGHT, language: LanguageChoice = LanguageChoice.Auto) =
        DictationRequest("com.whatsapp", level, ScriptPreference.LATIN, language, silenceTimeoutSec = 3)

    private fun rep(amp: Int, n: Int) = List(n) { amp }
    private val speechThenSilence = rep(200, 5) + rep(8000, 10) + rep(200, 40)

    private fun CoroutineScope.pipeline() =
        DictationPipeline(this, audio, api, { plan }, history, tmp.root, retryDelayMs = { 0 })

    private suspend fun DictationPipeline.awaitEnd(): DictationState =
        state.first { it !is DictationState.Idle && it !is DictationState.Listening && it != DictationState.Transcribing && it != DictationState.Cleaning }

    private fun TestScope.recordStates(p: DictationPipeline): List<DictationState> {
        val states = mutableListOf<DictationState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { p.state.collect { states += it } }
        return states
    }

    @Test fun happyPathLight() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += "um so hi there"
        api.completeResults += "Clean."
        val p = backgroundScope.pipeline()
        val states = recordStates(p)
        p.start(request())
        val done = p.awaitEnd() as DictationState.Done
        assertEquals("Clean.", done.text)
        assertFalse(done.cleanupFailed)
        val kinds = states.map { it::class.simpleName }.distinct()
        assertTrue(kinds.toString(), kinds.indexOf("Listening") < kinds.indexOf("Transcribing"))
        assertTrue(kinds.toString(), kinds.indexOf("Transcribing") < kinds.indexOf("Cleaning"))
        val item = history.get(done.historyId)!!
        assertEquals(HistoryStatus.DONE, item.status)
        assertEquals("um so hi there", item.rawText)
        assertEquals("Clean.", item.cleanText)
        assertFalse(audio.recordedFile!!.exists())
        assertEquals(
            Triple("chat/p", PromptBuilder.systemPrompt(CleanupLevel.LIGHT, ScriptPreference.LATIN, LanguageChoice.Auto), PromptBuilder.userMessage("um so hi there")),
            api.completeCalls.single(),
        )
        assertEquals(Triple("stt/p", "m4a", null), api.transcribeCalls.single())
    }

    @Test fun rawSkipsCleanup() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += "raw words"
        val p = backgroundScope.pipeline()
        p.start(request(CleanupLevel.RAW))
        assertEquals("raw words", (p.awaitEnd() as DictationState.Done).text)
        assertTrue(api.completeCalls.isEmpty())
    }

    @Test fun noSpeechCancelsWithoutNetwork() = runTest {
        audio.amplitudes = rep(200, 90)
        val p = backgroundScope.pipeline()
        p.start(request())
        assertEquals(DictationState.NoSpeech, p.awaitEnd())
        assertTrue(api.transcribeCalls.isEmpty())
        assertTrue(history.observeRecent().first().isEmpty())
        assertFalse(audio.recordedFile!!.exists())
    }

    @Test fun stopTapProcessesImmediately() = runTest {
        audio.amplitudes = rep(200, 5) + rep(8000, 5)
        audio.holdOpen = true
        api.transcribeResults += "hello"
        api.completeResults += "Hello."
        val p = backgroundScope.pipeline()
        p.start(request())
        p.state.first { it is DictationState.Listening && it.speechDetected }
        p.stop()
        assertEquals("Hello.", (p.awaitEnd() as DictationState.Done).text)
    }

    @Test fun stopBeforeSpeechDetectedStillProcesses() = runTest {
        audio.amplitudes = rep(200, 3)
        audio.holdOpen = true
        api.transcribeResults += "quiet words"
        api.completeResults += "Quiet words."
        val p = backgroundScope.pipeline()
        p.start(request())
        p.state.first { it is DictationState.Listening }
        p.stop()
        assertEquals("Quiet words.", (p.awaitEnd() as DictationState.Done).text)
    }

    @Test fun cancelDiscards() = runTest {
        audio.amplitudes = rep(200, 5) + rep(8000, 5)
        audio.holdOpen = true
        val p = backgroundScope.pipeline()
        p.start(request())
        p.state.first { it is DictationState.Listening && it.speechDetected }
        p.cancel()
        p.state.first { it == DictationState.Idle }
        assertTrue(api.transcribeCalls.isEmpty())
        assertFalse(audio.recordedFile!!.exists())
        assertTrue(history.observeRecent().first().isEmpty())
    }

    @Test fun emptyTranscript() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += "   "
        val p = backgroundScope.pipeline()
        p.start(request())
        assertEquals(DictationState.EmptyTranscript, p.awaitEnd())
        assertTrue(history.observeRecent().first().isEmpty())
        assertFalse(audio.recordedFile!!.exists())
    }

    @Test fun modelUnavailableTriesFallback() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += OpenRouterException.ModelUnavailable(503)
        api.transcribeResults += "hi"
        api.completeResults += "Hi."
        val p = backgroundScope.pipeline()
        p.start(request())
        assertEquals("Hi.", (p.awaitEnd() as DictationState.Done).text)
        assertEquals(listOf("stt/p", "stt/f"), api.transcribeCalls.map { it.first })
    }

    @Test fun badRequestTriesFallback() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += OpenRouterException.Unexpected(400, "unsupported format")
        api.transcribeResults += "hi"
        api.completeResults += "Hi."
        val p = backgroundScope.pipeline()
        p.start(request())
        assertEquals("Hi.", (p.awaitEnd() as DictationState.Done).text)
        assertEquals(listOf("stt/p", "stt/f"), api.transcribeCalls.map { it.first })
    }

    @Test fun rateLimitedRetriesOnceThenFails() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += OpenRouterException.RateLimited(1)
        api.transcribeResults += OpenRouterException.RateLimited(1)
        val p = backgroundScope.pipeline()
        p.start(request())
        val failed = p.awaitEnd() as DictationState.Failed
        assertEquals(FailureReason.RATE_LIMITED, failed.reason)
        assertEquals(2, api.transcribeCalls.size)
        assertTrue(audio.recordedFile!!.exists())
    }

    @Test fun networkFailureKeepsAudio() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += OpenRouterException.Network(IOException("offline"))
        val p = backgroundScope.pipeline()
        p.start(request())
        val failed = p.awaitEnd() as DictationState.Failed
        assertEquals(FailureReason.NETWORK, failed.reason)
        assertEquals(HistoryStatus.FAILED, history.get(failed.historyId)!!.status)
        assertTrue(audio.recordedFile!!.exists())
    }

    @Test fun unauthorizedDoesNotRetry() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += OpenRouterException.Unauthorized
        val p = backgroundScope.pipeline()
        p.start(request())
        assertEquals(FailureReason.UNAUTHORIZED, (p.awaitEnd() as DictationState.Failed).reason)
        assertEquals(1, api.transcribeCalls.size)
    }

    @Test fun cleanupFailureInsertsRaw() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += "raw words"
        api.completeResults += OpenRouterException.ModelUnavailable(500)
        api.completeResults += OpenRouterException.ModelUnavailable(500)
        val p = backgroundScope.pipeline()
        p.start(request())
        val done = p.awaitEnd() as DictationState.Done
        assertEquals("raw words", done.text)
        assertTrue(done.cleanupFailed)
        assertEquals(HistoryStatus.CLEANUP_FAILED, history.get(done.historyId)!!.status)
    }

    @Test fun retryUsesSavedAudio() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += OpenRouterException.Network(IOException("offline"))
        val p = backgroundScope.pipeline()
        p.start(request())
        val failed = p.awaitEnd() as DictationState.Failed
        p.reset()
        api.transcribeResults += "back online"
        api.completeResults += "Back online."
        p.retry(failed.historyId)
        val done = p.awaitEnd() as DictationState.Done
        assertEquals(failed.historyId, done.historyId)
        assertEquals("Back online.", done.text)
        assertFalse(audio.recordedFile!!.exists())
        assertNull(history.get(done.historyId)!!.audioPath)
    }

    @Test fun interruptionAfterSpeechProcesses() = runTest {
        audio.amplitudes = rep(200, 5) + rep(8000, 5)
        audio.failAtEnd = true
        api.transcribeResults += "partial"
        api.completeResults += "Partial."
        val p = backgroundScope.pipeline()
        p.start(request())
        assertEquals("Partial.", (p.awaitEnd() as DictationState.Done).text)
    }

    @Test fun interruptionBeforeSpeechCancels() = runTest {
        audio.amplitudes = rep(200, 5)
        audio.failAtEnd = true
        val p = backgroundScope.pipeline()
        p.start(request())
        assertEquals(DictationState.NoSpeech, p.awaitEnd())
        assertTrue(api.transcribeCalls.isEmpty())
    }

    @Test fun recleanUsesStoredRaw() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += "raw words"
        api.completeResults += "Raw words."
        val p = backgroundScope.pipeline()
        p.start(request())
        val done = p.awaitEnd() as DictationState.Done
        api.completeResults += "Polished words."
        assertEquals("Polished words.", p.reclean(done.historyId, CleanupLevel.POLISHED, ScriptPreference.LATIN))
        assertEquals(1, api.transcribeCalls.size)
        assertEquals(2, api.completeCalls.size)
        val item = history.get(done.historyId)!!
        assertEquals("Polished words.", item.cleanText)
        assertEquals(CleanupLevel.POLISHED, item.level)
    }

    @Test fun fixedLanguagePassedToTranscribe() = runTest {
        audio.amplitudes = speechThenSilence
        api.transcribeResults += "namaste"
        val p = backgroundScope.pipeline()
        p.start(request(CleanupLevel.RAW, LanguageChoice.Fixed("hi")))
        p.awaitEnd()
        assertEquals("hi", api.transcribeCalls.single().third)
    }

    @Test fun startWhileBusyIsIgnored() = runTest {
        audio.amplitudes = rep(200, 5) + rep(8000, 5)
        audio.holdOpen = true
        val p = backgroundScope.pipeline()
        p.start(request())
        p.state.first { it is DictationState.Listening }
        val file = audio.recordedFile
        p.start(request())
        assertEquals(file, audio.recordedFile)
        p.cancel()
    }
}
