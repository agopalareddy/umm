package io.github.agopalareddy.umm.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.data.CategoryRepository
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.pipeline.DictationPipeline
import io.github.agopalareddy.umm.core.policy.DataPolicyRepository
import io.github.agopalareddy.umm.core.policy.ModelCatalog
import io.github.agopalareddy.umm.core.policy.ModelPlan
import io.github.agopalareddy.umm.core.policy.RecommendationRepository
import io.github.agopalareddy.umm.core.stats.StatsRepository

/** What the shared screens need from the app: core objects plus the [Platform]. */
class UmmServices(
    val settings: SettingsRepository,
    val apiKeyStore: ApiKeyStore,
    val history: HistoryRepository,
    val stats: StatsRepository,
    val categories: CategoryRepository,
    val dataPolicy: DataPolicyRepository,
    val modelCatalog: ModelCatalog,
    val recommendations: RecommendationRepository,
    val openRouter: OpenRouterApi,
    /** Retries and re-cleans History items; lazy because creating the pipeline starts its work. */
    lazyPipeline: Lazy<DictationPipeline>,
    val modelPlan: suspend () -> ModelPlan,
    val platform: Platform,
) {
    val pipeline by lazyPipeline
}

val LocalUmm = staticCompositionLocalOf<UmmServices> { error("No UmmServices") }
