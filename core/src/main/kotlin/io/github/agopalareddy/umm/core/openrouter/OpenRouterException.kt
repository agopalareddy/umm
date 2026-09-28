package io.github.agopalareddy.umm.core.openrouter

import java.io.IOException

sealed class OpenRouterException(message: String? = null) : Exception(message) {
    data object MissingKey : OpenRouterException("No OpenRouter API key")
    data object Unauthorized : OpenRouterException("OpenRouter rejected the API key")
    data object InsufficientCredits : OpenRouterException("OpenRouter account is out of credits")
    data class RateLimited(val retryAfterSec: Long?) : OpenRouterException("Rate limited")
    /** [blockedByDataPolicy]: the account's zero data retention (ZDR) setting rules out every endpoint of this model. */
    data class ModelUnavailable(val status: Int, val blockedByDataPolicy: Boolean = false) :
        OpenRouterException("Model unavailable (HTTP $status)")
    data class Network(override val cause: IOException) : OpenRouterException(cause.message)
    data object Timeout : OpenRouterException("OpenRouter timed out")
    data class Unexpected(val status: Int, val body: String) : OpenRouterException("HTTP $status")

    companion object {
        /** OpenRouter explains exclusions in the 404 body, e.g. "ZDR violation (account settings): 1 endpoint excluded". */
        fun isZdrBlock(body: String): Boolean = body.contains("ZDR violation", ignoreCase = true)

        fun forStatus(status: Int, body: String, retryAfter: String?): OpenRouterException = when {
            status == 401 -> Unauthorized
            status == 402 -> InsufficientCredits
            status == 429 -> RateLimited(retryAfter?.trim()?.toLongOrNull())
            status == 404 || status >= 500 -> ModelUnavailable(status, blockedByDataPolicy = isZdrBlock(body))
            else -> Unexpected(status, body)
        }
    }
}
