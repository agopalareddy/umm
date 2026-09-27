package io.github.agopalareddy.umm.core.pipeline

import io.github.agopalareddy.umm.core.openrouter.OpenRouterException

enum class FailureReason { NETWORK, TIMEOUT, UNAUTHORIZED, NO_CREDITS, RATE_LIMITED, MODEL_UNAVAILABLE, MISSING_KEY, UNKNOWN;

    companion object {
        fun of(e: Throwable): FailureReason = when (e) {
            is OpenRouterException.Network -> NETWORK
            OpenRouterException.Timeout -> TIMEOUT
            OpenRouterException.Unauthorized -> UNAUTHORIZED
            OpenRouterException.InsufficientCredits -> NO_CREDITS
            is OpenRouterException.RateLimited -> RATE_LIMITED
            is OpenRouterException.ModelUnavailable -> MODEL_UNAVAILABLE
            OpenRouterException.MissingKey -> MISSING_KEY
            else -> UNKNOWN
        }
    }
}

sealed interface DictationState {
    data object Idle : DictationState
    data class Listening(val amplitude: Int, val speechDetected: Boolean) : DictationState
    data object Transcribing : DictationState
    data object Cleaning : DictationState
    data class Done(val historyId: Long, val text: String, val cleanupFailed: Boolean, val origin: Long = 0) : DictationState
    data class Failed(val historyId: Long, val reason: FailureReason, val origin: Long = 0) : DictationState
    data object NoSpeech : DictationState
    data object EmptyTranscript : DictationState
}

fun DictationState.isFinished(): Boolean = this is DictationState.Done || this is DictationState.Failed ||
    this == DictationState.NoSpeech || this == DictationState.EmptyTranscript
