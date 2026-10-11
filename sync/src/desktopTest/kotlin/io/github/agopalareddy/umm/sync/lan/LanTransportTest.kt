package io.github.agopalareddy.umm.sync.lan

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import io.github.agopalareddy.umm.sync.SyncSession
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

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
        )

        fun close() {
            transport.stop()
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
        assertEquals(1, result.sent)

        val bHistory = devB.history.observeRecent().first()
        assertEquals(1, bHistory.size)
        assertEquals("Hello over LAN", bHistory[0].cleanText)
    }
}
