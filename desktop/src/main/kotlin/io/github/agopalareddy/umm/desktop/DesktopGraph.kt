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
import io.github.agopalareddy.umm.core.sync.HybridClock
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.buildSnapshot
import io.github.agopalareddy.umm.sync.SyncEngine
import io.github.agopalareddy.umm.sync.SyncSession
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.group.Membership
import io.github.agopalareddy.umm.sync.lan.JmdnsDiscovery
import io.github.agopalareddy.umm.sync.lan.LanTransport
import io.github.agopalareddy.umm.sync.pair.Pairing
import io.github.agopalareddy.umm.sync.relay.RelayTransport
import io.github.agopalareddy.umm.ui.Platform
import io.github.agopalareddy.umm.ui.UmmServices
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl

/**
 * Desktop counterpart of the Android AppGraph. The key lives in [secretStore] (the system keyring), or in memory
 * when there is none; OPENROUTER_API_KEY, when set, wins and is never saved.
 */
class DesktopGraph(
    private val paths: XdgPaths,
    env: Map<String, String>,
    api: OpenRouterApi? = null,
    recommendationsUrl: HttpUrl = RecommendationRepository.DEFAULT_URL,
    secretStore: KeyValueStore? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis

    /** False when there is no keyring: the key then only lasts until Umm quits. */
    val keyringAvailable = secretStore != null

    private val keyring = secretStore?.let(::ResilientKeyValueStore)

    /** True once the keyring has refused to save the key: it is then only in memory until Umm quits. */
    val keyringFailed: StateFlow<Boolean> = keyring?.failed ?: MutableStateFlow(false)

    val apiKeyStore = run {
        val envKey = env["OPENROUTER_API_KEY"]?.takeIf { it.isNotBlank() }
        val store = if (envKey == null) keyring ?: InMemoryKeyValueStore() else InMemoryKeyValueStore()
        ApiKeyStore(store, PassThroughCipher).apply { envKey?.let { set(it, KeySource.DEVELOPER) } }
    }
    val http by lazy { OpenRouterClient.defaultHttp() }
    val openRouter: OpenRouterApi = api ?: OpenRouterClient(OpenRouterClient.DEFAULT_BASE_URL, http) { apiKeyStore.get() }

    val database by lazy { buildUmmDatabase(File(paths.dataDir, "umm.db")) }
    val syncMeta by lazy { SyncMeta(database) }
    val hybridClock by lazy { HybridClock(clock) }
    val groupStore by lazy {
        val store = keyring ?: InMemoryKeyValueStore()
        GroupStore(store, PassThroughCipher)
    }
    val opLog by lazy { OpLog(database, syncMeta, hybridClock, active = { groupStore.state.value != null }, onRecorded = { syncEngine.onLocalChange() }) }
    val categories by lazy { CategoryRepository(database, opLog) }
    val history by lazy { HistoryRepository(database, clock, opLog) }
    val settings by lazy { SettingsRepository(buildSettingsStore(File(paths.configDir, "settings.preferences_pb")), opLog) }
    val desktopSettings by lazy { DesktopSettings(buildSettingsStore(File(paths.configDir, "desktop.preferences_pb"))) }
    val stats by lazy { StatsRepository(database, { settings.settings.first().statsRecording }, opLog) }

    val selfId by lazy { runBlocking { syncMeta.deviceId() } }
    val membership by lazy { Membership(groupStore, opLog, clock) }
    val opApplier by lazy { OpApplier(database, opLog, settings, hybridClock, onGroupOp = { membership.onGroupOp(it) }) }
    val discovery by lazy { JmdnsDiscovery() }
    val syncSession by lazy {
        SyncSession(
            log = opLog,
            applier = opApplier,
            snapshot = { buildSnapshot(database, opLog, settings, groupStore.state.value?.members ?: emptyList()) },
            cipher = ::groupCipher,
            selfId = selfId,
        )
    }
    val lanTransport by lazy {
        LanTransport(
            session = syncSession,
            discovery = discovery,
            group = groupStore,
            selfId = selfId,
            scope = scope,
            db = database,
        )
    }
    val relayTransport by lazy {
        RelayTransport(
            group = groupStore,
            log = opLog,
            applier = opApplier,
            http = http,
            scope = scope,
            clockSeconds = { clock() / 1000L },
        )
    }
    val pairing by lazy {
        Pairing(
            group = groupStore,
            apiKey = apiKeyStore,
            lan = lanTransport,
            relay = relayTransport,
            applier = opApplier,
            snapshot = { buildSnapshot(database, opLog, settings, groupStore.state.value?.members ?: emptyList()) },
            now = clock,
        )
    }
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            group = groupStore,
            lan = lanTransport,
            relay = relayTransport,
            pairing = pairing,
            membership = membership,
            log = opLog,
            scope = scope,
            now = clock,
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun groupCipher(): GroupCipher {
        val st = groupStore.state.value ?: throw IllegalStateException("No group")
        val keyBytes = decodeKey(st.key)
        val prevKeyBytes = st.previousKey?.let { decodeKey(it) }
        return GroupCipher(
            key = keyBytes,
            epoch = st.epoch,
            previousKey = prevKeyBytes,
            previousUntil = st.previousUntil,
            clock = clock,
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeKey(str: String): ByteArray {
        return runCatching { Base64.UrlSafe.decode(str) }.getOrNull()
            ?: runCatching { Base64.decode(str) }.getOrNull()
            ?: str.encodeToByteArray().copyOf(32)
    }

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

    /** The shared screens' services; History's retry and re-clean use a pipeline that never records. */
    fun services(platform: Platform) = UmmServices(
        settings = settings,
        apiKeyStore = apiKeyStore,
        history = history,
        stats = stats,
        categories = categories,
        dataPolicy = dataPolicy,
        modelCatalog = modelCatalog,
        recommendations = recommendations,
        openRouter = openRouter,
        lazyPipeline = lazy { pipeline(NoMicrophone) },
        modelPlan = ::modelPlan,
        platform = platform,
        sync = syncEngine,
    )

    fun close() {
        runCatching { syncEngine.stop() }
        runCatching { discovery.close() }
        scope.cancel()
        database.close()
    }
}

/** Desktop has no microphone until the dictation engine lands (sub-project 2). */
private object NoMicrophone : AudioSource {
    override val format = "wav"
    override fun record(file: File): Flow<Int> = flow { throw IllegalStateException("No microphone on desktop yet") }
    override fun stop() = Unit
}

internal class InMemoryKeyValueStore : KeyValueStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String) { values[key] = value }
    override fun remove(vararg keys: String) { keys.forEach(values::remove) }
}

/** The keyring (or memory) holds the key, and the keyring encrypts at rest, so there is nothing to encrypt here. */
internal object PassThroughCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(blob: ByteArray) = blob
}
