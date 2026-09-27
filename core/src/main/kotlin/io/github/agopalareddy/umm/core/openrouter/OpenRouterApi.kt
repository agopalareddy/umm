package io.github.agopalareddy.umm.core.openrouter

import kotlinx.serialization.Serializable

/** The OpenRouter operations Umm needs. Implemented by [OpenRouterClient]; faked in tests. */
interface OpenRouterApi {
    suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription
    suspend fun complete(model: String, system: String, user: String, temperature: Double): Completion
    suspend fun listModels(outputModalities: String? = null): List<ModelInfo>

    /** Exchanges an OAuth PKCE authorization code for a user-controlled API key. */
    suspend fun exchangeAuthCode(code: String, codeVerifier: String): String

    /** Details of the key in use, from OpenRouter's GET /key. */
    suspend fun keyInfo(): KeyInfo
}

data class KeyInfo(
    val label: String,
    val usageUsd: Double,
    val usageMonthlyUsd: Double?,
    val limitUsd: Double?,
    val limitRemainingUsd: Double?,
    /** e.g. "monthly"; null when the limit never resets or there is none. */
    val limitReset: String?,
)

data class Transcription(val text: String, val costUsd: Double?)

data class Completion(val text: String, val costUsd: Double?)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val createdEpochSec: Long,
    val promptPrice: String?,
    val completionPrice: String?,
)
