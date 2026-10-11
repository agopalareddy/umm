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
import io.github.agopalareddy.umm.core.auth.SecretCipher
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
import io.github.agopalareddy.umm.core.sync.HybridClock
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.buildSnapshot
import io.github.agopalareddy.umm.sync.FileSyncLog
import io.github.agopalareddy.umm.sync.SyncEngine
import io.github.agopalareddy.umm.sync.SyncSession
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.group.Membership
import io.github.agopalareddy.umm.sync.lan.LanTransport
import io.github.agopalareddy.umm.sync.lan.NsdDiscovery
import io.github.agopalareddy.umm.sync.pair.Pairing
import io.github.agopalareddy.umm.sync.relay.RelayTransport
import io.github.agopalareddy.umm.sync.scanPairingCode
import android.os.Build
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.runBlocking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.agopalareddy.umm.ui.AndroidPlatform
import io.github.agopalareddy.umm.ui.UmmServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.settingsStore by preferencesDataStore("settings")

/** Manual dependency wiring; one instance per process, owned by [UmmApp]. */
class AppGraph(
    private val app: Application,
    apiCipher: SecretCipher = KeystoreCipher(),
    syncCipher: SecretCipher = KeystoreCipher("umm_sync"),
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis

    val apiKeyStore: ApiKeyStore by lazy {
        ApiKeyStore(SharedPreferencesKeyValueStore(app.getSharedPreferences("secure", Context.MODE_PRIVATE)), apiCipher)
    }
    val http by lazy { OpenRouterClient.defaultHttp() }
    val openRouter: OpenRouterApi by lazy {
        OpenRouterClient(OpenRouterClient.DEFAULT_BASE_URL, http) { apiKeyStore.get() }
    }

    val database by lazy { buildUmmDatabase(app) }
    val syncMeta by lazy { SyncMeta(database) }
    val hybridClock by lazy { HybridClock(clock) }
    val groupStore by lazy {
        GroupStore(
            SharedPreferencesKeyValueStore(app.getSharedPreferences("sync", Context.MODE_PRIVATE)),
            syncCipher,
            defaultName = Build.MODEL,
        )
    }
    val opLog by lazy { OpLog(database, syncMeta, hybridClock, active = { groupStore.state.value != null }, onRecorded = { syncEngine.onLocalChange() }) }
    val categories by lazy { CategoryRepository(database, opLog) }
    val history by lazy { HistoryRepository(database, clock, opLog) }
    val settings by lazy { SettingsRepository(app.settingsStore, opLog) }
    val stats by lazy { StatsRepository(database, { settings.settings.first().statsRecording }, opLog) }

    val selfId by lazy { runBlocking { syncMeta.deviceId() } }
    val membership by lazy { Membership(groupStore, opLog, clock) }
    val opApplier by lazy { OpApplier(database, opLog, settings, hybridClock, onGroupOp = { membership.onGroupOp(it) }) }
    val discovery by lazy { NsdDiscovery(app) }
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
            scope = appScope,
            db = database,
        )
    }
    val syncLog by lazy {
        FileSyncLog(File(app.filesDir, "sync.log"), alsoTo = { line -> if (BuildConfig.DEBUG) Log.d("UmmSync", line) })
    }
    val relayTransport by lazy {
        RelayTransport(
            group = groupStore,
            log = opLog,
            applier = opApplier,
            http = http,
            scope = appScope,
            clockSeconds = { clock() / 1000L },
            syncLog = syncLog,
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
            scope = appScope,
            now = clock,
            syncLog = syncLog,
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

/** The shared screens' services, with platform calls made through [context]. */
fun AppGraph.services(context: Context) = UmmServices(
    settings = settings,
    apiKeyStore = apiKeyStore,
    history = history,
    stats = stats,
    categories = categories,
    dataPolicy = dataPolicy,
    modelCatalog = modelCatalog,
    recommendations = recommendations,
    openRouter = openRouter,
    lazyPipeline = lazy { pipeline },
    modelPlan = ::modelPlan,
    platform = AndroidPlatform(context, scanCode = scanPairingCode(context)),
    sync = syncEngine,
)
