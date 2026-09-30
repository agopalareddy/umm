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
import io.github.agopalareddy.umm.core.openrouter.Completion
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import io.github.agopalareddy.umm.core.policy.ModelPlan
import io.github.agopalareddy.umm.core.stats.StatsEntry
import io.github.agopalareddy.umm.core.stats.StatsRepository
import io.github.agopalareddy.umm.core.stats.TextStats
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
    private val stats: StatsRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onDataPolicyBlocked: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var job: Job? = null

    // Per-dictation controls. They are set before the recorder may have started, so the loop re-checks them.
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var recording = false
    @Volatile private var levelOverride: CleanupLevel? = null
    @Volatile private var continuous = false

    init {
        scope.launch { history.purgeExpiredAudio() }
    }

    fun start(request: DictationRequest) {
        if (job?.isActive == true) return
        resetControls()
        continuous = request.continuous
        recording = true
        job = scope.launch { record(request) }
    }

    /** Ends recording and processes what was captured, even if the recorder has not started yet. */
    fun stop() {
        stopRequested = true
        audio.stop()
    }

    /** Discards the current recording. Has no effect once processing has started. */
    fun cancel() {
        if (!recording) return
        cancelRequested = true
        audio.stop()
    }

    /** Turns silence detection off (or back on) for the current recording; while off it runs until stop() or the duration cap. */
    fun setContinuous(on: Boolean = true) {
        continuous = on
    }

    /** Changes the cleanup level of the current dictation, if cleanup has not started yet. */
    fun setLevel(level: CleanupLevel) {
        levelOverride = level
    }

    /** Marks a finished state as handled; returns false if someone else already handled it. */
    fun acknowledge(state: DictationState): Boolean = _state.compareAndSet(state, DictationState.Idle)

    fun retry(historyId: Long, origin: Long = 0) {
        if (job?.isActive == true) return
        resetControls()
        job = scope.launch {
            val item = history.get(historyId)
            val file = item?.audioPath?.let(::File)?.takeIf { it.exists() }
            if (item == null || file == null) {
                _state.value = DictationState.Failed(historyId, FailureReason.UNKNOWN, origin)
                return@launch
            }
            val audioMs = stats?.get(historyId)?.audioMs ?: 0
            process(historyId, file, item.level, item.script, LanguageChoice.decode(item.language), origin, item.packageName, audioMs)
        }
    }

    /** Re-runs cleanup on a stored raw transcript at another level; throws OpenRouterException on failure. */
    suspend fun reclean(historyId: Long, level: CleanupLevel, script: ScriptPreference): String {
        val item = requireNotNull(history.get(historyId)) { "no history item $historyId" }
        val raw = requireNotNull(item.rawText) { "history item $historyId has no transcript" }
        val clean = if (level == CleanupLevel.RAW) raw else cleanup(plan().cleanup, raw, level, script, LanguageChoice.decode(item.language)).second.text.trim()
        history.updateClean(historyId, level, clean)
        return clean
    }

    /** Returns to Idle from any finished state. */
    fun reset() {
        val current = _state.value
        if (current.isFinished()) _state.compareAndSet(current, DictationState.Idle)
    }

    private fun resetControls() {
        stopRequested = false
        cancelRequested = false
        levelOverride = null
        continuous = false
    }

    private suspend fun record(request: DictationRequest) {
        audioDir.mkdirs()
        val file = File(audioDir, "${UUID.randomUUID()}.m4a")
        val detector = SilenceDetector(SilenceConfig(silenceTimeoutMs = request.silenceTimeoutSec?.let { it * 1000L }))
        var elapsedMs = 0L
        var noSpeech = false
        var interrupted = false
        if (cancelRequested) {
            recording = false
            _state.value = DictationState.Idle
            return
        }
        _state.value = DictationState.Listening(0, false, continuous)
        try {
            audio.record(file).collect { amplitude ->
                if (stopRequested || cancelRequested) audio.stop()
                detector.continuous = continuous
                val event = detector.onSample(amplitude, elapsedMs)
                elapsedMs += SAMPLE_MS
                _state.value = DictationState.Listening(amplitude, detector.speechDetected, continuous)
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

        recording = false
        when {
            cancelRequested -> discard(file, DictationState.Idle)
            noSpeech || (interrupted && !detector.speechDetected) -> discard(file, DictationState.NoSpeech)
            else -> {
                val id = history.createPending(
                    request.packageName, request.level, request.script, request.language.encode(), file.path,
                )
                process(id, file, request.level, request.script, request.language, request.origin, request.packageName, elapsedMs)
            }
        }
    }

    private suspend fun process(
        id: Long,
        file: File,
        requestedLevel: CleanupLevel,
        script: ScriptPreference,
        language: LanguageChoice,
        origin: Long,
        packageName: String,
        audioMs: Long,
    ) {
        _state.value = DictationState.Transcribing
        val startedAt = clock()
        val models: ModelPlan
        val sttModel: String
        var costUsd: Double? = null
        val raw = try {
            models = plan()
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val code = (language as? LanguageChoice.Fixed)?.iso639_1
            val (model, transcription) = withFallback(models.stt) { model -> api.transcribe(model, bytes, AUDIO_FORMAT, code) }
            sttModel = model
            costUsd = transcription.costUsd
            transcription.text.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = FailureReason.of(e)
            history.markFailed(id, reason.name)
            recordStats(id, packageName, requestedLevel, raw = "", clean = "", audioMs, latencyMs = null, costUsd, null, null, succeeded = false)
            _state.value = DictationState.Failed(id, reason, origin)
            return
        }

        if (raw.isEmpty()) {
            history.delete(id)
            _state.value = DictationState.EmptyTranscript
            return
        }
        val level = levelOverride ?: requestedLevel
        if (level == CleanupLevel.RAW) {
            finish(id, raw, null, level, requestedLevel)
            recordStats(id, packageName, level, raw, raw, audioMs, clock() - startedAt, costUsd, sttModel, null, succeeded = true)
            _state.value = DictationState.Done(id, raw, cleanupFailed = false, origin = origin)
            return
        }

        _state.value = DictationState.Cleaning
        val (cleanupModel, completion) = try {
            cleanup(models.cleanup, raw, level, script, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            history.markCleanupFailed(id, raw, FailureReason.of(e).name)
            recordStats(id, packageName, level, raw, raw, audioMs, clock() - startedAt, costUsd, sttModel, null, succeeded = true)
            _state.value = DictationState.Done(id, raw, cleanupFailed = true, origin = origin)
            return
        }
        val clean = completion.text.trim()
        if (clean.isEmpty()) {
            // The cleanup model found only filler.
            history.delete(id)
            _state.value = DictationState.EmptyTranscript
            return
        }
        finish(id, raw, clean, level, requestedLevel)
        val totalCost = if (costUsd == null && completion.costUsd == null) null else (costUsd ?: 0.0) + (completion.costUsd ?: 0.0)
        recordStats(id, packageName, level, raw, clean, audioMs, clock() - startedAt, totalCost, sttModel, cleanupModel, succeeded = true)
        _state.value = DictationState.Done(id, clean, cleanupFailed = false, origin = origin)
    }

    private suspend fun recordStats(
        id: Long,
        packageName: String,
        level: CleanupLevel,
        raw: String,
        clean: String,
        audioMs: Long,
        latencyMs: Long?,
        costUsd: Double?,
        sttModel: String?,
        cleanupModel: String?,
        succeeded: Boolean,
    ) {
        stats?.record(
            StatsEntry(
                historyId = id,
                createdAt = clock(),
                packageName = packageName,
                level = level,
                rawWords = TextStats.words(raw),
                cleanWords = TextStats.words(clean),
                fillerWords = TextStats.fillers(raw),
                audioMs = audioMs,
                latencyMs = latencyMs,
                costUsd = costUsd,
                sttModel = sttModel,
                cleanupModel = cleanupModel,
                succeeded = succeeded,
            ),
        )
    }

    private suspend fun finish(id: Long, raw: String, clean: String?, level: CleanupLevel, requestedLevel: CleanupLevel) {
        history.markDone(id, raw, clean)
        if (level != requestedLevel) history.updateClean(id, level, clean ?: raw)
    }

    private suspend fun cleanup(
        models: List<String>,
        raw: String,
        level: CleanupLevel,
        script: ScriptPreference,
        language: LanguageChoice,
    ): Pair<String, Completion> {
        val system = PromptBuilder.systemPrompt(level, script, language)
        return withFallback(models) { model ->
            api.complete(model, system, PromptBuilder.userMessage(raw), TEMPERATURE)
        }
    }

    /**
     * Tries each model in order. A rate limit retries the same model once; a model-level error moves to the next model;
     * anything else (auth, credits, network) fails immediately.
     */
    private suspend fun <T> withFallback(models: List<String>, call: suspend (String) -> T): Pair<String, T> {
        var last: OpenRouterException? = null
        for (model in models) {
            try {
                return model to try {
                    call(model)
                } catch (e: OpenRouterException.RateLimited) {
                    delay(retryDelayMs(e.retryAfterSec))
                    call(model)
                }
            } catch (e: OpenRouterException) {
                when (e) {
                    is OpenRouterException.ModelUnavailable -> {
                        if (e.blockedByDataPolicy) onDataPolicyBlocked()
                        last = e
                    }
                    is OpenRouterException.Unexpected -> last = e
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
