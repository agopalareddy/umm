package io.github.agopalareddy.umm.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.auth.SecretCipher
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import io.github.agopalareddy.umm.core.sync.DataKind
import io.github.agopalareddy.umm.core.sync.HybridClock
import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.Snapshot
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.SyncOp
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.group.Membership
import io.github.agopalareddy.umm.sync.lan.Discovery
import io.github.agopalareddy.umm.sync.lan.LanPeer
import io.github.agopalareddy.umm.sync.lan.LanTransport
import io.github.agopalareddy.umm.sync.pair.Pairing
import io.github.agopalareddy.umm.sync.relay.RelayTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyValueStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(vararg keys: String) { keys.forEach { map.remove(it) } }
    }

    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(blob: ByteArray): ByteArray = blob
    }

    private class FakeDiscovery : Discovery {
        private val _peers = MutableSharedFlow<LanPeer>()
        override fun advertise(port: Int, groupHash: String, deviceId: String) = AutoCloseable { }
        override fun browse(groupHash: String): Flow<LanPeer> = _peers
    }

    private class FakeLanTransport(
        session: SyncSession,
        group: GroupStore,
        selfId: String,
        scope: CoroutineScope,
    ) : LanTransport(
        session = session,
        discovery = FakeDiscovery(),
        group = group,
        selfId = selfId,
        scope = scope,
    ) {
        var started = false
        var stopped = false
        var syncCount = 0
        var shouldThrow = false
        private val _fakePeers = MutableStateFlow<List<LanPeer>>(emptyList())
        override val peers: StateFlow<List<LanPeer>> = _fakePeers.asStateFlow()

        fun setPeers(list: List<LanPeer>) {
            _fakePeers.value = list
        }

        override suspend fun start(): Int {
            started = true
            stopped = false
            return 1234
        }

        override fun stop() {
            stopped = true
            started = false
        }

        override suspend fun syncWith(peer: LanPeer): SessionResult? {
            if (shouldThrow) throw IOException("LAN sync error")
            syncCount++
            return SessionResult(1, 1)
        }
    }

    private class FakeRelayTransport(
        group: GroupStore,
        log: OpLog,
        applier: OpApplier,
        scope: CoroutineScope,
    ) : RelayTransport(
        group = group,
        log = log,
        applier = applier,
        http = OkHttpClient(),
        scope = scope,
    ) {
        var started = false
        var stopped = false
        var publishCount = 0
        var shouldThrow = false

        override fun start() {
            started = true
            stopped = false
        }

        override fun stop() {
            stopped = true
            started = false
        }

        override suspend fun publishPending() {
            if (shouldThrow) throw IOException("Relay publish error")
            publishCount++
        }
    }

    private class TestEnv(
        val scope: CoroutineScope,
        tmpFolder: File,
        var currentTime: Long = 1_000_000L,
        var isLogActive: (() -> Boolean) = { true },
    ) {
        val db = inMemoryUmmDatabase(scope.coroutineContext)
        val meta = SyncMeta(db)
        val clock = HybridClock { currentTime }
        val dataStore = PreferenceDataStoreFactory.create { File(tmpFolder, "test.preferences_pb") }
        val store = FakeKeyValueStore()
        val groupStore = GroupStore(store, FakeCipher())
        val apiKeyStore = ApiKeyStore(store, FakeCipher())
        val log = OpLog(db, meta, clock, active = { isLogActive() })
        val settings = SettingsRepository(dataStore, recorder = log)
        val membership = Membership(groupStore, log, now = { currentTime })
        val applier = OpApplier(db, log, settings, clock, onGroupOp = { membership.onGroupOp(it) })
        val cipher = GroupCipher(ByteArray(32) { 1 }, epoch = 0)
        val dummySnapshot: suspend () -> Snapshot = {
            Snapshot(
                from = "test-device",
                marks = emptyMap(),
                history = emptyList(),
                stats = emptyList(),
                settings = emptyMap(),
                categories = emptyList(),
                apps = emptyList(),
                clears = emptyList(),
                members = emptyList(),
            )
        }
        val session = SyncSession(
            log = log,
            applier = applier,
            snapshot = dummySnapshot,
            cipher = { cipher },
            selfId = "self-device",
        )
        val lan = FakeLanTransport(session, groupStore, "self-device", scope)
        val relay = FakeRelayTransport(groupStore, log, applier, scope)
        val pairing = Pairing(
            group = groupStore,
            apiKey = apiKeyStore,
            lan = lan,
            relay = relay,
            applier = applier,
            snapshot = dummySnapshot,
            now = { currentTime },
        )

        val engine = SyncEngine(
            group = groupStore,
            lan = lan,
            relay = relay,
            pairing = pairing,
            membership = membership,
            log = log,
            scope = scope,
            now = { currentTime },
        )

        fun close() {
            engine.stop()
            lan.stop()
            relay.stop()
            db.close()
        }
    }

    @Test
    fun notPairedUntilGroupExists() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder())
        try {
            val engine = env.engine

            assertEquals(SyncStatus.NotPaired, engine.status.value)
            assertTrue(engine.devices.value.isEmpty())

            engine.start()
            runCurrent()
            assertEquals(SyncStatus.NotPaired, engine.status.value)

            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            val groupState = GroupState(
                groupId = "grp-1",
                key = "key1",
                epoch = 0,
                previousKey = null,
                previousUntil = 0L,
                members = listOf(
                    Member("d1", "My Device", "desktop", myPubkey),
                    Member("d2", "Phone", "android", "peerpubkey"),
                ),
            )
            env.groupStore.save(groupState)
            runCurrent()

            assertTrue(engine.status.value is SyncStatus.Idle)
            val devices = engine.devices.value
            assertEquals(2, devices.size)
            val selfDevice = devices.find { it.member.deviceId == "d1" }
            val peerDevice = devices.find { it.member.deviceId == "d2" }
            assertNotNull(selfDevice)
            assertNotNull(peerDevice)
            assertTrue(selfDevice!!.isSelf)
            assertFalse(peerDevice!!.isSelf)

            env.groupStore.clear()
            runCurrent()
            assertEquals(SyncStatus.NotPaired, engine.status.value)
            assertTrue(engine.devices.value.isEmpty())
        } finally {
            env.close()
        }
    }

    @Test
    fun localChangeTriggersOneDebouncedSync() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder())
        try {
            val engine = env.engine
            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            env.groupStore.save(
                GroupState(
                    groupId = "grp-1",
                    key = "key1",
                    epoch = 0,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member("d1", "My Device", "desktop", myPubkey)),
                ),
            )
            engine.start()
            runCurrent()

            assertEquals(0, env.relay.publishCount)

            // Three changes within 2 s -> only one sync
            engine.onLocalChange()
            advanceTimeBy(500)
            engine.onLocalChange()
            advanceTimeBy(500)
            engine.onLocalChange()
            advanceTimeBy(500)

            // Only 1500 ms elapsed, not yet 2000 ms since last onLocalChange
            assertEquals(0, env.relay.publishCount)

            advanceTimeBy(1600)
            // Now > 2000 ms elapsed since the last change
            assertEquals(1, env.relay.publishCount)
        } finally {
            env.close()
        }
    }

    @Test
    fun prunesOnStart() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder(), currentTime = 100_000_000_000L)
        try {
            val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000L

            // Record old op > 90 days ago
            env.currentTime = 100_000_000_000L - ninetyDaysMs - 1_000_000L
            env.log.record(SyncOp.CategorySet(Category.NOTES, CleanupLevel.POLISHED, ScriptPreference.LATIN))

            // Record recent op < 90 days ago
            env.currentTime = 100_000_000_000L - 1000L
            env.log.record(SyncOp.CategorySet(Category.EMAIL, CleanupLevel.LIGHT, ScriptPreference.NATIVE))

            assertEquals(2, env.db.syncDao().allOps().size)

            env.currentTime = 100_000_000_000L
            env.engine.start()
            runCurrent()

            val remaining = env.db.syncDao().allOps()
            assertEquals(1, remaining.size)
            assertTrue(remaining.first().payload.contains("EMAIL"))
        } finally {
            env.close()
        }
    }

    @Test
    fun errorKeepsLastSyncTime() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder(), currentTime = 50_000L)
        try {
            val engine = env.engine
            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            env.groupStore.save(
                GroupState(
                    groupId = "grp-1",
                    key = "key1",
                    epoch = 0,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member("d1", "My Device", "desktop", myPubkey)),
                ),
            )
            engine.start()
            runCurrent()

            // 1. Successful sync at t = 50_000
            engine.syncNow()
            val idleStatus = engine.status.value as SyncStatus.Idle
            assertEquals(50_000L, idleStatus.lastSync)
            assertEquals("relay", idleStatus.via)

            // 2. Next sync fails at t = 60_000
            env.currentTime = 60_000L
            env.relay.shouldThrow = true
            engine.syncNow()

            val errStatus = engine.status.value as SyncStatus.Error
            assertEquals("Relay publish error", errStatus.message)
            assertEquals(50_000L, errStatus.lastSync)
        } finally {
            env.close()
        }
    }

    @Test
    fun transportSwitchesStopTransports() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder())
        try {
            val engine = env.engine
            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            env.groupStore.save(
                GroupState(
                    groupId = "grp-1",
                    key = "key1",
                    epoch = 0,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member("d1", "My Device", "desktop", myPubkey)),
                    lanEnabled = true,
                    relayEnabled = true,
                ),
            )
            engine.start()
            runCurrent()

            assertTrue(env.lan.started)
            assertTrue(env.relay.started)

            // Turn both transports off
            engine.setTransports(lan = false, relay = false)
            runCurrent()

            assertTrue(env.lan.stopped)
            assertTrue(env.relay.stopped)
            assertFalse(env.groupStore.state.value!!.lanEnabled)
            assertFalse(env.groupStore.state.value!!.relayEnabled)

            // Turn both back on
            engine.setTransports(lan = true, relay = true)
            runCurrent()

            assertTrue(env.lan.started)
            assertTrue(env.relay.started)
            assertTrue(env.groupStore.state.value!!.lanEnabled)
            assertTrue(env.groupStore.state.value!!.relayEnabled)
        } finally {
            env.close()
        }
    }

    @Test
    fun recordHistoryDeleteTriggersSyncNowAfterDebounce() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder())
        try {
            val engine = env.engine
            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            env.groupStore.save(
                GroupState(
                    groupId = "grp-1",
                    key = "key1",
                    epoch = 0,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member("d1", "My Device", "desktop", myPubkey)),
                ),
            )
            engine.start()
            runCurrent()

            assertEquals(0, env.relay.publishCount)

            env.log.record(SyncOp.HistoryDelete("dev1", 1L))
            runCurrent()
            assertEquals(0, env.relay.publishCount)

            advanceTimeBy(2100)
            assertEquals(1, env.relay.publishCount)
        } finally {
            env.close()
        }
    }

    @Test
    fun recordClearBeforeTriggersSyncNowAfterDebounce() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder())
        try {
            val engine = env.engine
            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            env.groupStore.save(
                GroupState(
                    groupId = "grp-1",
                    key = "key1",
                    epoch = 0,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member("d1", "My Device", "desktop", myPubkey)),
                ),
            )
            engine.start()
            runCurrent()

            assertEquals(0, env.relay.publishCount)

            env.log.record(SyncOp.ClearBefore(DataKind.HISTORY, 1000L, null))
            runCurrent()
            assertEquals(0, env.relay.publishCount)

            advanceTimeBy(2100)
            assertEquals(1, env.relay.publishCount)
        } finally {
            env.close()
        }
    }

    @Test
    fun recordSettingSetTriggersSyncNowAfterDebounce() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder())
        try {
            val engine = env.engine
            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            env.groupStore.save(
                GroupState(
                    groupId = "grp-1",
                    key = "key1",
                    epoch = 0,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member("d1", "My Device", "desktop", myPubkey)),
                ),
            )
            engine.start()
            runCurrent()

            assertEquals(0, env.relay.publishCount)

            env.log.record(SyncOp.SettingSet("default_level", "POLISHED"))
            runCurrent()
            assertEquals(0, env.relay.publishCount)

            advanceTimeBy(2100)
            assertEquals(1, env.relay.publishCount)
        } finally {
            env.close()
        }
    }

    @Test
    fun tenOpsInBurstLeadToOneSync() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder())
        try {
            val engine = env.engine
            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            env.groupStore.save(
                GroupState(
                    groupId = "grp-1",
                    key = "key1",
                    epoch = 0,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member("d1", "My Device", "desktop", myPubkey)),
                ),
            )
            engine.start()
            runCurrent()

            assertEquals(0, env.relay.publishCount)

            for (i in 1..10) {
                env.log.record(SyncOp.SettingSet("key$i", "val$i"))
            }
            runCurrent()
            assertEquals(0, env.relay.publishCount)

            advanceTimeBy(2100)
            assertEquals(1, env.relay.publishCount)
        } finally {
            env.close()
        }
    }

    @Test
    fun opAppliedFromPeerLeadsToNoSync() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder())
        try {
            val engine = env.engine
            val myPubkey = env.groupStore.device.publicKey.joinToString("") { "%02x".format(it) }
            env.groupStore.save(
                GroupState(
                    groupId = "grp-1",
                    key = "key1",
                    epoch = 0,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member("d1", "My Device", "desktop", myPubkey)),
                ),
            )
            engine.start()
            runCurrent()

            assertEquals(0, env.relay.publishCount)

            env.applier.apply(
                listOf(
                    OpEnvelope(
                        origin = "peer-device",
                        seq = 1L,
                        hlc = 2000L,
                        op = SyncOp.SettingSet("default_level", "RAW"),
                    ),
                ),
            )
            runCurrent()
            advanceTimeBy(5000)

            assertEquals(0, env.relay.publishCount)
        } finally {
            env.close()
        }
    }

    @Test
    fun syncInactiveRecordsNothingAndTriggersNothing() = runTest(timeout = 20.seconds) {
        val env = TestEnv(this, tmp.newFolder(), isLogActive = { false })
        try {
            val engine = env.engine
            engine.start()
            runCurrent()

            assertEquals(0, env.relay.publishCount)

            env.log.record(SyncOp.HistoryDelete("dev1", 1L))
            runCurrent()
            advanceTimeBy(5000)

            assertEquals(0, env.relay.publishCount)
            assertTrue(env.log.since(emptyMap()).isEmpty())
        } finally {
            env.close()
        }
    }
}
