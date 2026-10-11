package io.github.agopalareddy.umm.sync.lan

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.auth.SecretCipher
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.HistoryStatus
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import io.github.agopalareddy.umm.core.stats.StatsRepository
import io.github.agopalareddy.umm.core.sync.HybridClock
import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.buildSnapshot
import io.github.agopalareddy.umm.sync.SyncEngine
import io.github.agopalareddy.umm.sync.SyncSession
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.group.Membership
import io.github.agopalareddy.umm.sync.pair.Pairing
import io.github.agopalareddy.umm.sync.relay.RelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

class LanTransportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val toClose = mutableListOf<() -> Unit>()

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
        private val _peers = MutableSharedFlow<LanPeer>(replay = 10, extraBufferCapacity = 10)

        override fun advertise(port: Int, groupHash: String, deviceId: String): AutoCloseable {
            return AutoCloseable { }
        }

        override fun browse(groupHash: String): Flow<LanPeer> = _peers

        fun emitPeer(peer: LanPeer) {
            _peers.tryEmit(peer)
        }
    }

    private class FullStackDevice(
        val name: String,
        tmpFolder: File,
        sharedGroupKey: ByteArray,
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 15_000,
        sessionDeadlineMs: Long = 60_000L,
    ) {
        val deviceScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val db = inMemoryUmmDatabase()
        val meta = SyncMeta(db)
        val clock = HybridClock { 2000L }
        val dataStore = PreferenceDataStoreFactory.create(scope = deviceScope) {
            File(tmpFolder, "settings_$name.preferences_pb")
        }
        val log = OpLog(db, meta, clock, active = { true })
        val settings = SettingsRepository(dataStore, recorder = log)
        val history = HistoryRepository(db, clock = { 2000L }, recorder = log)
        val stats = StatsRepository(db, recorder = log)
        val cipher = GroupCipher(sharedGroupKey, epoch = 1)
        val applier = OpApplier(db, log, settings, clock, onGroupOp = { })

        val kv = FakeKeyValueStore()
        val groupStore = GroupStore(kv, FakeCipher())
        val apiKeyStore = ApiKeyStore(kv, FakeCipher())
        val selfId = kotlinx.coroutines.runBlocking { meta.deviceId() }

        init {
            groupStore.save(
                GroupState(
                    groupId = "test-group",
                    key = "shared-key",
                    epoch = 1,
                    previousKey = null,
                    previousUntil = 0L,
                    members = listOf(Member(selfId, name, "desktop", "pubkey")),
                    relays = emptyList(),
                )
            )
        }

        val session = SyncSession(
            log = log,
            applier = applier,
            snapshot = { buildSnapshot(db, log, settings, members = emptyList()) },
            cipher = { cipher },
            selfId = selfId,
        )

        val discovery = FakeDiscovery()
        val transport = LanTransport(
            session = session,
            discovery = discovery,
            group = groupStore,
            selfId = selfId,
            scope = deviceScope,
            db = db,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            sessionDeadlineMs = sessionDeadlineMs,
        )

        val relayTransport = RelayTransport(
            group = groupStore,
            log = log,
            applier = applier,
            http = OkHttpClient(),
            scope = deviceScope,
        )

        val pairing = Pairing(
            group = groupStore,
            apiKey = apiKeyStore,
            lan = transport,
            relay = relayTransport,
            applier = applier,
            snapshot = { buildSnapshot(db, log, settings, members = emptyList()) },
            now = { 2000L },
        )

        val membership = Membership(groupStore, log, now = { 2000L })

        val engine = SyncEngine(
            group = groupStore,
            lan = transport,
            relay = relayTransport,
            pairing = pairing,
            membership = membership,
            log = log,
            scope = deviceScope,
            now = { 2000L },
        )

        fun close() {
            engine.stop()
            transport.stop()
            relayTransport.stop()
            db.close()
            deviceScope.cancel()
        }
    }

    @After
    fun tearDown() {
        toClose.forEach { it() }
        toClose.clear()
    }

    @Test
    fun twoDevicesOverLoopback() = runTest {
        val sharedKey = ByteArray(32) { 99 }
        val devA = FullStackDevice("DeviceA", tmp.root, sharedKey)
        val devB = FullStackDevice("DeviceB", tmp.root, sharedKey)

        toClose.add { devA.close() }
        toClose.add { devB.close() }

        val portA = devA.transport.start()
        val portB = devB.transport.start()

        val peerB = LanPeer(devB.selfId, InetAddress.getByName("127.0.0.1"), portB)
        devA.discovery.emitPeer(peerB)

        // Insert history row on A
        val id = devA.history.createPending(
            appLabel = "Notes",
            packageName = "org.notes",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "dummy.wav",
        )
        devA.history.markDone(id, raw = "Hello over LAN", clean = "Hello over LAN")

        val result = devA.transport.syncWith(peerB)
        assertNotNull(result)
        // A device that has nothing yet gets the row in the snapshot and the op that made it.
        assertEquals(2, result!!.sent)

        val bHistory = devB.history.observeRecent().first()
        assertEquals(1, bHistory.size)
        assertEquals("Hello over LAN", bHistory[0].cleanText)
    }

    @Test
    fun peerAcceptsAndNeverWritesSyncNowReturnsWithinDeadlineAndSecondSyncWorks() = runTest(timeout = 20.seconds) {
        val sharedKey = ByteArray(32) { 99 }
        val devA = FullStackDevice("DeviceA", tmp.root, sharedKey, readTimeoutMs = 200, sessionDeadlineMs = 500L)
        val devB = FullStackDevice("DeviceB", tmp.root, sharedKey)

        toClose.add { devA.close() }
        toClose.add { devB.close() }

        val portA = devA.transport.start()
        val portB = devB.transport.start()
        devA.engine.start()
        devB.engine.start()

        // Silent server: accepts connection, never writes
        val silentServer = ServerSocket(0)
        toClose.add { runCatching { silentServer.close() } }
        val silentPort = silentServer.localPort

        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val sock = silentServer.accept()
                toClose.add { runCatching { sock.close() } }
                // Hold open without sending anything
            } catch (_: Exception) {}
        }

        val silentPeer = LanPeer("silent-device", InetAddress.getByName("127.0.0.1"), silentPort)
        devA.discovery.emitPeer(silentPeer)

        // Wait on the flow itself: a delay() loop here takes no real time under runTest, so on a slow machine the
        // peer was not listed yet when the sync ran.
        devA.transport.peers.first { peers -> peers.any { it.deviceId == "silent-device" } }

        // syncNow() to silent peer returns within deadline without throwing
        devA.engine.syncNow()

        // Now emit good peerB and insert history on A
        val id = devA.history.createPending(
            appLabel = "Notes",
            packageName = "org.notes",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "dummy.wav",
        )
        devA.history.markDone(id, raw = "Hello good peer", clean = "Hello good peer")

        val peerB = LanPeer(devB.selfId, InetAddress.getByName("127.0.0.1"), portB)
        devA.discovery.emitPeer(peerB)

        devA.transport.peers.first { peers -> peers.any { it.deviceId == devB.selfId } }

        // Second syncNow() succeeds
        devA.engine.syncNow()

        val bHistory = devB.history.observeRecent().first()
        assertEquals(1, bHistory.size)
        assertEquals("Hello good peer", bHistory[0].cleanText)
    }
}
