package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.audio.AudioSource
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeySource
import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.auth.SecretCipher
import io.github.agopalareddy.umm.core.data.CategoryRepository
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.buildSettingsStore
import io.github.agopalareddy.umm.core.data.buildUmmDatabase
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterClient
import io.github.agopalareddy.umm.core.pipeline.DictationPipeline
import io.github.agopalareddy.umm.core.platform.XdgPaths
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl

/** Desktop counterpart of the Android AppGraph. The key comes from OPENROUTER_API_KEY and stays in memory. */
class DesktopGraph(
    private val paths: XdgPaths,
    env: Map<String, String>,
    api: OpenRouterApi? = null,
    recommendationsUrl: HttpUrl = RecommendationRepository.DEFAULT_URL,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis

    val apiKeyStore = ApiKeyStore(InMemoryKeyValueStore(), PassThroughCipher).apply {
        env["OPENROUTER_API_KEY"]?.takeIf { it.isNotBlank() }?.let { set(it, KeySource.DEVELOPER) }
    }
    val http by lazy { OpenRouterClient.defaultHttp() }
    val openRouter: OpenRouterApi = api ?: OpenRouterClient(OpenRouterClient.DEFAULT_BASE_URL, http) { apiKeyStore.get() }

    val database by lazy { buildUmmDatabase(File(paths.dataDir, "umm.db")) }
    val categories by lazy { CategoryRepository(database) }
    val history by lazy { HistoryRepository(database, clock) }
    val settings by lazy { SettingsRepository(buildSettingsStore(File(paths.configDir, "settings.preferences_pb"))) }
    val stats by lazy { StatsRepository(database) { settings.settings.first().statsRecording } }

    val recommendations by lazy {
        RecommendationRepository(
            http,
            recommendationsUrl,
            settings,
            bundled = { javaClass.getResource("/recommended.json")!!.readText() },
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

    /** A pipeline recording from [audio]; desktop has no microphone yet, so each dictation brings its own source. */
    fun pipeline(audio: AudioSource) = DictationPipeline(
        scope = scope,
        audio = audio,
        api = openRouter,
        plan = ::modelPlan,
        history = history,
        audioDir = File(paths.dataDir, "audio"),
        stats = stats,
        onDataPolicyBlocked = { dataPolicy.markEnforced() },
    )

    fun close() {
        scope.cancel()
        database.close()
    }
}

private class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String) { values[key] = value }
    override fun remove(vararg keys: String) { keys.forEach(values::remove) }
}

/** The key never leaves memory on desktop, so there is nothing to encrypt. */
private object PassThroughCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(blob: ByteArray) = blob
}
