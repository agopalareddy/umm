package io.github.agopalareddy.umm.core.pipeline

import io.github.agopalareddy.umm.core.audio.AudioSource
import io.github.agopalareddy.umm.core.audio.SilenceConfig
import io.github.agopalareddy.umm.core.audio.SilenceDetector
import io.github.agopalareddy.umm.core.audio.SilenceEvent
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.cleanup.PromptBuilder
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import io.github.agopalareddy.umm.core.policy.ModelPlan
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Records, transcribes, and cleans one dictation at a time. Front-ends only call these methods and observe [state]. */
class DictationPipeline(
    private val scope: CoroutineScope,
    private val audio: AudioSource,
    private val api: OpenRouterApi,
    private val plan: suspend () -> ModelPlan,
    private val history: HistoryRepository,
    private val audioDir: File,
    private val retryDelayMs: (retryAfterSec: Long?) -> Long = { (it ?: 2) * 1000 },
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var cancelRequested = false

    init {
        scope.launch { history.purgeExpiredAudio() }
    }

    fun start(request: DictationRequest) {
        if (job?.isActive == true) return
        cancelRequested = false
        job = scope.launch { record(request) }
    }

    /** Ends recording and processes what was captured. */
    fun stop() = audio.stop()

    /** Discards the current recording. Has no effect once processing has started. */
    fun cancel() {
        if (_state.value !is DictationState.Listening) return
        cancelRequested = true
        audio.stop()
    }

    fun retry(historyId: Long) {
        if (job?.isActive == true) return
        job = scope.launch {
            val item = history.get(historyId)
            val file = item?.audioPath?.let(::File)?.takeIf { it.exists() }
            if (item == null || file == null) {
                _state.value = DictationState.Failed(historyId, FailureReason.UNKNOWN)
                return@launch
            }
            process(historyId, file, item.level, item.script, LanguageChoice.decode(item.language))
        }
    }

    /** Re-runs cleanup on a stored raw transcript at another level; throws OpenRouterException on failure. */
    suspend fun reclean(historyId: Long, level: CleanupLevel, script: ScriptPreference): String {
        val item = requireNotNull(history.get(historyId)) { "no history item $historyId" }
        val raw = requireNotNull(item.rawText) { "history item $historyId has no transcript" }
        val clean = if (level == CleanupLevel.RAW) raw else cleanup(raw, level, script, LanguageChoice.decode(item.language))
        history.updateClean(historyId, level, clean)
        return clean
    }

    /** Returns to Idle after the front-end has handled a finished state. */
    fun reset() {
        if (job?.isActive != true) _state.value = DictationState.Idle
    }

    private suspend fun record(request: DictationRequest) {
        audioDir.mkdirs()
        val file = File(audioDir, "${UUID.randomUUID()}.m4a")
        val detector = SilenceDetector(SilenceConfig(silenceTimeoutMs = request.silenceTimeoutSec?.let { it * 1000L }))
        var elapsedMs = 0L
        var noSpeech = false
        var interrupted = false
        _state.value = DictationState.Listening(0, false)
        try {
            audio.record(file).collect { amplitude ->
                val event = detector.onSample(amplitude, elapsedMs)
                elapsedMs += SAMPLE_MS
                _state.value = DictationState.Listening(amplitude, detector.speechDetected)
                when (event) {
                    SilenceEvent.CANCEL_NO_SPEECH -> { noSpeech = true; audio.stop() }
                    SilenceEvent.STOP_FOR_SILENCE, SilenceEvent.STOP_FOR_MAX_DURATION -> audio.stop()
                    SilenceEvent.SPEECH_STARTED, null -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            interrupted = true
        }

        when {
            cancelRequested -> discard(file, DictationState.Idle)
            noSpeech || (interrupted && !detector.speechDetected) -> discard(file, DictationState.NoSpeech)
            else -> {
                val id = history.createPending(
                    request.packageName, request.level, request.script, request.language.encode(), file.path,
                )
                process(id, file, request.level, request.script, request.language)
            }
        }
    }

    private suspend fun process(id: Long, file: File, level: CleanupLevel, script: ScriptPreference, language: LanguageChoice) {
        _state.value = DictationState.Transcribing
        val raw = try {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val code = (language as? LanguageChoice.Fixed)?.iso639_1
            withFallback(plan().stt) { model -> api.transcribe(model, bytes, AUDIO_FORMAT, code) }.text.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = FailureReason.of(e)
            history.markFailed(id, reason.name)
            _state.value = DictationState.Failed(id, reason)
            return
        }

        if (raw.isEmpty()) {
            history.delete(id)
            _state.value = DictationState.EmptyTranscript
            return
        }
        if (level == CleanupLevel.RAW) {
            history.markDone(id, raw, null)
            _state.value = DictationState.Done(id, raw, cleanupFailed = false)
            return
        }

        _state.value = DictationState.Cleaning
        val clean = try {
            cleanup(raw, level, script, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            history.markCleanupFailed(id, raw, FailureReason.of(e).name)
            _state.value = DictationState.Done(id, raw, cleanupFailed = true)
            return
        }
        if (clean.isEmpty()) {
            // The cleanup model found only filler.
            history.delete(id)
            _state.value = DictationState.EmptyTranscript
            return
        }
        history.markDone(id, raw, clean)
        _state.value = DictationState.Done(id, clean, cleanupFailed = false)
    }

    private suspend fun cleanup(raw: String, level: CleanupLevel, script: ScriptPreference, language: LanguageChoice): String {
        val system = PromptBuilder.systemPrompt(level, script, language)
        return withFallback(plan().cleanup) { model ->
            api.complete(model, system, PromptBuilder.userMessage(raw), TEMPERATURE)
        }.trim()
    }

    /**
     * Tries each model in order. A rate limit retries the same model once; a model-level error moves to the next model;
     * anything else (auth, credits, network) fails immediately.
     */
    private suspend fun <T> withFallback(models: List<String>, call: suspend (String) -> T): T {
        var last: OpenRouterException? = null
        for (model in models) {
            try {
                return try {
                    call(model)
                } catch (e: OpenRouterException.RateLimited) {
                    delay(retryDelayMs(e.retryAfterSec))
                    call(model)
                }
            } catch (e: OpenRouterException) {
                when (e) {
                    is OpenRouterException.ModelUnavailable, is OpenRouterException.Unexpected -> last = e
                    else -> throw e
                }
            }
        }
        throw last ?: OpenRouterException.ModelUnavailable(0)
    }

    private fun discard(file: File, state: DictationState) {
        file.delete()
        _state.value = state
    }

    private companion object {
        const val SAMPLE_MS = 100L
        const val AUDIO_FORMAT = "m4a"
        const val TEMPERATURE = 0.2
    }
}
