package io.github.agopalareddy.umm.core.pipeline

import io.github.agopalareddy.umm.core.audio.AudioSource
import io.github.agopalareddy.umm.core.openrouter.Completion
import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.Transcription
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/** Emits [amplitudes] (one per 100 ms of virtual recording), then waits for stop() if [holdOpen], else ends. */
class FakeAudioSource(
    var amplitudes: List<Int> = emptyList(),
    var holdOpen: Boolean = false,
    var failAtEnd: Boolean = false,
    override var format: String = "m4a",
) : AudioSource {
    private var stopSignal = CompletableDeferred<Unit>()
    var recordedFile: File? = null

    override fun record(file: File): Flow<Int> = flow {
        stopSignal = CompletableDeferred()
        recordedFile = file
        file.writeBytes(byteArrayOf(1, 2, 3))
        for (amp in amplitudes) {
            if (stopSignal.isCompleted) return@flow
            emit(amp)
            yield()
        }
        if (failAtEnd) throw IOException("mic taken by a phone call")
        if (holdOpen) stopSignal.await()
    }

    override fun stop() {
        stopSignal.complete(Unit)
    }
}

/** Each call pops the next scripted result: a String result or a Throwable to throw. */
class FakeApi : OpenRouterApi {
    var transcribeCost: Double? = null
    var completeCost: Double? = null
    val transcribeResults = ArrayDeque<Any>()
    val completeResults = ArrayDeque<Any>()
    val transcribeCalls = mutableListOf<Triple<String, String, String?>>() // model, format, language
    val completeCalls = mutableListOf<Triple<String, String, String>>()    // model, system, user

    override suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription {
        transcribeCalls += Triple(model, format, language)
        return when (val r = transcribeResults.removeFirst()) {
            is Throwable -> throw r
            else -> Transcription(r as String, transcribeCost)
        }
    }

    override suspend fun complete(model: String, system: String, user: String, temperature: Double): Completion {
        completeCalls += Triple(model, system, user)
        return when (val r = completeResults.removeFirst()) {
            is Throwable -> throw r
            else -> Completion(r as String, completeCost)
        }
    }

    override suspend fun listModels(outputModalities: String?): List<ModelInfo> = error("unused")
    override suspend fun exchangeAuthCode(code: String, codeVerifier: String): String = error("unused")
    override suspend fun keyInfo(): KeyInfo = error("unused")
    var zdrList: Set<String> = emptySet()
    var probeResult: Boolean? = null
    val probedModels = mutableListOf<String>()
    override suspend fun zdrModels(): Set<String> = zdrList
    override suspend fun blockedByDataPolicy(sttModel: String): Boolean? {
        probedModels += sttModel
        return probeResult
    }
}
