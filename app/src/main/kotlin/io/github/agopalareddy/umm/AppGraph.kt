package io.github.agopalareddy.umm

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.preferencesDataStore
import io.github.agopalareddy.umm.core.audio.MediaRecorderAudioSource
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeystoreCipher
import io.github.agopalareddy.umm.core.auth.SharedPreferencesKeyValueStore
import io.github.agopalareddy.umm.core.data.CategoryRepository
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.buildUmmDatabase
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterClient
import io.github.agopalareddy.umm.core.pipeline.DictationPipeline
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.ime.DeliveryRouter
import io.github.agopalareddy.umm.core.policy.DataPolicyRepository
import io.github.agopalareddy.umm.core.policy.ModelCatalog
import io.github.agopalareddy.umm.core.policy.ModelPlan
import io.github.agopalareddy.umm.core.policy.ModelSelector
import io.github.agopalareddy.umm.core.policy.RecommendationRepository
import io.github.agopalareddy.umm.core.stats.StatsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.settingsStore by preferencesDataStore("settings")

/** Manual dependency wiring; one instance per process, owned by [UmmApp]. */
class AppGraph(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis

    val apiKeyStore: ApiKeyStore by lazy {
        ApiKeyStore(SharedPreferencesKeyValueStore(app.getSharedPreferences("secure", Context.MODE_PRIVATE)), KeystoreCipher())
    }
    val http by lazy { OpenRouterClient.defaultHttp() }
    val openRouter: OpenRouterApi by lazy {
        OpenRouterClient(OpenRouterClient.DEFAULT_BASE_URL, http) { apiKeyStore.get() }
    }

    val database by lazy { buildUmmDatabase(app) }
    val categories by lazy { CategoryRepository(database) }
    val history by lazy { HistoryRepository(database, clock) }
    val settings by lazy { SettingsRepository(app.settingsStore) }
    val stats by lazy { StatsRepository(database) { settings.settings.first().statsRecording } }

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
    val dataPolicy by lazy { DataPolicyRepository(openRouter, settings, clock) }

    suspend fun modelPlan(): ModelPlan {
        val current = settings.settings.first()
        val liveStt = if (current.modelMode == ModelMode.NEWEST_STT) modelCatalog.sttModels() else null
        return ModelSelector.plan(current, recommendations.current(), liveStt, dataPolicy.current())
    }

    init {
        // Re-check the account's zero data retention setting daily, and right away when the key changes.
        appScope.launch {
            var previous: String? = null
            apiKeyStore.key.collect { key ->
                if (key != null) {
                    runCatching { dataPolicy.refresh(recommendations.current(), force = previous != null && previous != key) }
                }
                previous = key
            }
        }
    }

    /** Routes finished dictations to the field they came from, or to the clipboard. */
    val delivery = DeliveryRouter { text ->
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Umm", text))
        Toast.makeText(app, "Copied — field changed", Toast.LENGTH_SHORT).show()
    }

    val pipeline by lazy {
        DictationPipeline(
            scope = appScope,
            audio = MediaRecorderAudioSource(app),
            api = openRouter,
            plan = ::modelPlan,
            history = history,
            audioDir = File(app.filesDir, "audio"),
            stats = stats,
            onDataPolicyBlocked = { dataPolicy.markEnforced() },
        ).also(::deliverResults)
    }

    /** One process-wide collector, so a result still lands (on the clipboard) after the keyboard has closed. */
    private fun deliverResults(pipeline: DictationPipeline) {
        appScope.launch {
            pipeline.state.collect { state ->
                if (BuildConfig.DEBUG && state is DictationState.Listening) Log.d("Umm", "amplitude=${state.amplitude}")
                if (state is DictationState.Done && pipeline.acknowledge(state)) {
                    withContext(Dispatchers.Main) {
                        delivery.deliver(state)
                        if (state.cleanupFailed) {
                            Toast.makeText(app, "Cleanup failed; inserted the raw transcript", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as UmmApp).graph
