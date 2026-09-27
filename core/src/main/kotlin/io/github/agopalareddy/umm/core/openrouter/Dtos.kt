package io.github.agopalareddy.umm.core.openrouter

import kotlinx.serialization.Serializable

@Serializable
internal data class TranscriptionResponse(val text: String, val usage: Usage? = null)

@Serializable
internal data class Usage(val cost: Double? = null)

@Serializable
internal data class ChatResponse(val choices: List<Choice>, val usage: Usage? = null)

@Serializable
internal data class Choice(val message: ChatMessage)

@Serializable
internal data class ChatMessage(val content: String? = null)

@Serializable
internal data class ModelsResponse(val data: List<ModelDto>)

@Serializable
internal data class ModelDto(
    val id: String,
    val name: String = id,
    val created: Long = 0,
    val pricing: Pricing? = null,
)

@Serializable
internal data class Pricing(val prompt: String? = null, val completion: String? = null)

@Serializable
internal data class AuthKeyResponse(val key: String)

@Serializable
internal data class KeyResponse(val data: KeyData)

@Serializable
internal data class KeyData(
    val label: String = "",
    val usage: Double = 0.0,
    @kotlinx.serialization.SerialName("usage_monthly") val usageMonthly: Double? = null,
    val limit: Double? = null,
    @kotlinx.serialization.SerialName("limit_remaining") val limitRemaining: Double? = null,
    @kotlinx.serialization.SerialName("limit_reset") val limitReset: String? = null,
)
