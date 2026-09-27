package io.github.agopalareddy.umm.core.openrouter

/** The OpenRouter operations Umm needs. Implemented by [OpenRouterClient]; faked in tests. */
interface OpenRouterApi {
    suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription
    suspend fun complete(model: String, system: String, user: String, temperature: Double): String
    suspend fun listModels(outputModalities: String? = null): List<ModelInfo>

    /** Exchanges an OAuth PKCE authorization code for a user-controlled API key. */
    suspend fun exchangeAuthCode(code: String, codeVerifier: String): String
}

data class Transcription(val text: String, val costUsd: Double?)

data class ModelInfo(
    val id: String,
    val name: String,
    val createdEpochSec: Long,
    val promptPrice: String?,
    val completionPrice: String?,
)
