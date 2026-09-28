package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import kotlinx.serialization.Serializable

@Serializable
data class ModelPair(val primary: String, val fallback: String)

@Serializable
data class ModelSet(val stt: ModelPair, val cleanup: ModelPair)

/**
 * The remote recommendation list (models/recommended.json). [zdr] is the set to use when the account only allows
 * zero data retention (ZDR) endpoints.
 */
@Serializable
data class Recommendation(val schema: Int, val stt: ModelPair, val cleanup: ModelPair, val zdr: ModelSet? = null)

/** What the account's data policy allows. An empty [zdrModels] means the list is unknown. */
data class DataPolicy(val enforced: Boolean, val zdrModels: Set<String>) {
    fun allows(modelId: String): Boolean = !enforced || zdrModels.isEmpty() || modelId in zdrModels
}

/** Models to try, in order, for each pipeline step. */
data class ModelPlan(val stt: List<String>, val cleanup: List<String>)

object ModelSelector {
    fun plan(settings: UmmSettings, rec: Recommendation, liveStt: List<ModelInfo>?, policy: DataPolicy? = null): ModelPlan {
        val plan = unrestricted(settings, rec, liveStt)
        if (policy == null || !policy.enforced) return ModelPlan(plan.stt.take(2), plan.cleanup.take(2))
        return ModelPlan(
            stt = restrict(plan.stt, rec.zdr?.stt, policy),
            cleanup = restrict(plan.cleanup, rec.zdr?.cleanup, policy),
        )
    }

    /** Keeps the models the policy allows, then fills in from the curated ZDR pair. */
    private fun restrict(models: List<String>, zdrPair: ModelPair?, policy: DataPolicy): List<String> {
        val curated = listOfNotNull(zdrPair?.primary, zdrPair?.fallback)
        val allowed = if (policy.zdrModels.isEmpty()) curated else (models + curated).filter { it in policy.zdrModels }
        return allowed.distinct().take(2).ifEmpty { curated.ifEmpty { models.take(2) } }
    }

    private fun unrestricted(settings: UmmSettings, rec: Recommendation, liveStt: List<ModelInfo>?): ModelPlan {
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
        // Full candidate lists, best first; plan() trims them after applying the data policy.
        return ModelPlan(stt.distinct(), cleanup.distinct())
    }
}
