package io.github.agopalareddy.umm

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.agopalareddy.umm.core.audio.MediaRecorderAudioSource
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeystoreCipher
import io.github.agopalareddy.umm.core.data.CategoryRepository
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.UmmDatabase
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterClient
import io.github.agopalareddy.umm.core.pipeline.DictationPipeline
import io.github.agopalareddy.umm.core.policy.ModelCatalog
import io.github.agopalareddy.umm.core.policy.ModelPlan
import io.github.agopalareddy.umm.core.policy.ModelSelector
import io.github.agopalareddy.umm.core.policy.RecommendationRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

private val Context.settingsStore by preferencesDataStore("settings")

/** Manual dependency wiring; one instance per process, owned by [UmmApp]. */
class AppGraph(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis

    val apiKeyStore: ApiKeyStore by lazy {
        ApiKeyStore(app.getSharedPreferences("secure", Context.MODE_PRIVATE), KeystoreCipher())
    }
    val http by lazy { OpenRouterClient.defaultHttp() }
    val openRouter: OpenRouterApi by lazy {
        OpenRouterClient(OpenRouterClient.DEFAULT_BASE_URL, http) { apiKeyStore.get() }
    }

    val database by lazy { UmmDatabase.build(app) }
    val categories by lazy { CategoryRepository(database) }
    val history by lazy { HistoryRepository(database, clock) }
    val settings by lazy { SettingsRepository(app.settingsStore) }

    val recommendations by lazy {
        RecommendationRepository(
            http,
            RecommendationRepository.DEFAULT_URL,
            settings,
            bundled = { app.assets.open("recommended.json").bufferedReader().use { it.readText() } },
            clock = clock,
        )
    }
    val modelCatalog by lazy { ModelCatalog(openRouter, settings, clock) }

    suspend fun modelPlan(): ModelPlan {
        val current = settings.settings.first()
        val liveStt = if (current.modelMode == ModelMode.NEWEST_STT) modelCatalog.sttModels() else null
        return ModelSelector.plan(current, recommendations.current(), liveStt)
    }

    val pipeline by lazy {
        DictationPipeline(
            scope = appScope,
            audio = MediaRecorderAudioSource(app),
            api = openRouter,
            plan = ::modelPlan,
            history = history,
            audioDir = File(app.filesDir, "audio"),
        )
    }
}

val Context.graph: AppGraph get() = (applicationContext as UmmApp).graph
