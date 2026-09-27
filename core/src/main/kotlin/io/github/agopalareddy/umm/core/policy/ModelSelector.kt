package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import kotlinx.serialization.Serializable

@Serializable
data class ModelPair(val primary: String, val fallback: String)

/** The remote recommendation list (models/recommended.json). */
@Serializable
data class Recommendation(val schema: Int, val stt: ModelPair, val cleanup: ModelPair)

/** Models to try, in order, for each pipeline step. */
data class ModelPlan(val stt: List<String>, val cleanup: List<String>)

object ModelSelector {
    fun plan(settings: UmmSettings, rec: Recommendation, liveStt: List<ModelInfo>?): ModelPlan {
        val recommendedStt = listOf(rec.stt.primary, rec.stt.fallback)
        val recommendedCleanup = listOf(rec.cleanup.primary, rec.cleanup.fallback)
        val stt = when (settings.modelMode) {
            ModelMode.RECOMMENDED -> recommendedStt
            ModelMode.NEWEST_STT -> liveStt?.maxByOrNull { it.createdEpochSec }
                ?.let { listOf(it.id) + recommendedStt }
                ?: recommendedStt
            ModelMode.MANUAL -> settings.manualSttModel?.let { listOf(it) + recommendedStt } ?: recommendedStt
        }
        val cleanup = if (settings.modelMode == ModelMode.MANUAL && settings.manualCleanupModel != null) {
            listOf(settings.manualCleanupModel) + recommendedCleanup
        } else {
            recommendedCleanup
        }
        return ModelPlan(stt.distinct().take(2), cleanup.distinct().take(2))
    }
}
