package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import kotlinx.serialization.json.Json

/** OpenRouter's live model lists, cached for 24 hours. Returns null when nothing is available. */
class ModelCatalog(
    private val api: OpenRouterApi,
    private val settings: SettingsRepository,
    private val clock: () -> Long,
) {
    suspend fun sttModels(): List<ModelInfo>? = cached("models_stt", "transcription")

    suspend fun chatModels(): List<ModelInfo>? = cached("models_chat", null)

    private suspend fun cached(name: String, modality: String?): List<ModelInfo>? {
        val cache = settings.readCache(name)?.let { (json, savedAt) ->
            runCatching { Json.decodeFromString<List<ModelInfo>>(json) }.getOrNull()?.let { it to savedAt }
        }
        if (cache != null && clock() - cache.second < MAX_AGE_MS) return cache.first
        return try {
            api.listModels(modality).also { settings.writeCache(name, Json.encodeToString(it), clock()) }
        } catch (e: OpenRouterException) {
            cache?.first
        }
    }

    private companion object {
        const val MAX_AGE_MS = 24L * 3600 * 1000
    }
}
