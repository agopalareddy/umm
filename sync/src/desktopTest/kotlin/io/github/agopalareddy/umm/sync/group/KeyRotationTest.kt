package io.github.agopalareddy.umm.sync.group

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
import io.github.agopalareddy.umm.core.sync.DataKind
import io.github.agopalareddy.umm.core.sync.HybridClock
import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.SyncOp
import io.github.agopalareddy.umm.core.sync.SyncedHistory
import io.github.agopalareddy.umm.core.sync.buildSnapshot
import io.github.agopalareddy.umm.sync.SyncSession
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.lan.Discovery
import io.github.agopalareddy.umm.sync.lan.LanPeer
import io.github.agopalareddy.umm.sync.lan.LanTransport
import io.github.agopalareddy.umm.sync.relay.KIND_APP_DATA
import io.github.agopalareddy.umm.sync.relay.NostrEvent
import io.github.agopalareddy.umm.sync.relay.RelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalEncodingApi::class)
class KeyRotationTest {

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
        override fun advertise(port: Int, groupHash: String, deviceId: String): AutoCloseable = AutoCloseable { }
        override fun browse(groupHash: String): Flow<LanPeer> = emptyFlow()
    }

    private class TestNode(
        val name: String,
        tmpFolder: File,
        var nowMs: Long = 100_000L,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = inMemoryUmmDatabase()
        val meta = SyncMeta(db)
        val clock = HybridClock { nowMs }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmpFolder, "settings_$name.preferences_pb")
        }
        val log = OpLog(db, meta, clock, active = { true })
        val settings = SettingsRepository(dataStore, recorder = log)
        val history = HistoryRepository(db, clock = { nowMs }, recorder = log)
        val stats = StatsRepository(db, recorder = log)
        var onGroupOpCallback: (suspend (OpEnvelope) -> Unit)? = null
        val applier = OpApplier(db, log, settings, clock, onGroupOp = { onGroupOpCallback?.invoke(it) })

        val kv = FakeKeyValueStore()
        val cipher = FakeCipher()
        val groupStore = GroupStore(kv, cipher)
        val apiKeyStore = ApiKeyStore(kv, cipher)
        val selfId = runBlocking { meta.deviceId() }

        val membership = Membership(
            group = groupStore,
            log = log,
            now = { nowMs },
        )

        init {
            onGroupOpCallback = { membership.onGroupOp(it) }
        }

        val session = SyncSession(
            log = log,
            applier = applier,
            snapshot = { buildSnapshot(db, log, settings, groupStore.state.value?.members ?: emptyList()) },
            cipher = {
                val st = groupStore.state.value ?: throw IllegalStateException("No group")
                GroupCipher(
                    key = Base64.UrlSafe.decode(st.key),
                    epoch = st.epoch,
                    previousKey = st.previousKey?.let { Base64.UrlSafe.decode(it) },
                    previousUntil = st.previousUntil,
                    clock = { nowMs },
                )
            },
            selfId = selfId,
        )

        val lanTransport = LanTransport(
            session = session,
            discovery = FakeDiscovery(),
            group = groupStore,
            selfId = selfId,
            scope = scope,
            db = db,
        )

        val relayTransport = RelayTransport(
            group = groupStore,
            log = log,
            applier = applier,
            http = OkHttpClient(),
            scope = scope,
            clockSeconds = { nowMs / 1000L },
        )

        fun close() {
            lanTransport.stop()
            relayTransport.stop()
            db.close()
            scope.cancel()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun setupThreeNodes(now: Long = 100_000L): Triple<TestNode, TestNode, TestNode> {
        val nodeA = TestNode("NodeA", tmp.newFolder(), nowMs = now).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder(), nowMs = now).also { toClose.add { it.close() } }
        val nodeC = TestNode("NodeC", tmp.newFolder(), nowMs = now).also { toClose.add { it.close() } }

        val initialKey = Base64.UrlSafe.encode(ByteArray(32) { (it + 1).toByte() })
        val members = listOf(
            Member(nodeA.selfId, "NodeA", "desktop", nodeA.groupStore.device.publicKey.toHex()),
            Member(nodeB.selfId, "NodeB", "desktop", nodeB.groupStore.device.publicKey.toHex()),
            Member(nodeC.selfId, "NodeC", "desktop", nodeC.groupStore.device.publicKey.toHex()),
        )
        val initialGroup = GroupState(
            groupId = "group-123",
            key = initialKey,
            epoch = 0,
            previousKey = null,
            previousUntil = 0L,
            members = members,
            relays = emptyList(),
        )
        nodeA.groupStore.save(initialGroup)
        nodeB.groupStore.save(initialGroup)
        nodeC.groupStore.save(initialGroup)

        return Triple(nodeA, nodeB, nodeC)
    }

    @After
    fun tearDown() {
        toClose.forEach { runCatching { it() } }
        toClose.clear()
    }

    @Test
    fun removeRotatesForRemaining() = runTest(timeout = 20.seconds) {
        val (nodeA, nodeB, nodeC) = setupThreeNodes()

        nodeA.membership.remove(nodeC.selfId)

        val opsFromA = nodeA.log.since(emptyMap())
        nodeB.applier.apply(opsFromA)

        val stateA = nodeA.groupStore.state.value
        val stateB = nodeB.groupStore.state.value

        assertNotNull(stateA)
        assertNotNull(stateB)
        assertEquals(1, stateA!!.epoch)
        assertEquals(1, stateB!!.epoch)
        assertEquals(stateA.key, stateB.key)

        assertEquals(2, stateA.members.size)
        assertEquals(2, stateB.members.size)
        assertTrue(stateA.members.none { it.deviceId == nodeC.selfId })
        assertTrue(stateB.members.none { it.deviceId == nodeC.selfId })

        // C cannot open epoch-1 payloads
        val keyBytes = Base64.UrlSafe.decode(stateA.key)
        val cipherA = GroupCipher(keyBytes, epoch = 1)
        val sealedPayload = cipherA.seal("epoch-1-secret".encodeToByteArray())

        val stateC = nodeC.groupStore.state.value!!
        val cipherC = GroupCipher(Base64.UrlSafe.decode(stateC.key), epoch = stateC.epoch)
        try {
            cipherC.open(sealedPayload)
            fail("C should not be able to open epoch-1 payload")
        } catch (_: Exception) {
            // Expected
        }
    }

    @Test
    fun removedDeviceIsIgnoredAfterGrace() = runTest(timeout = 20.seconds) {
        val t0 = 100_000L
        val (nodeA, nodeB, nodeC) = setupThreeNodes(now = t0)

        nodeA.membership.remove(nodeC.selfId)
        val stateA = nodeA.groupStore.state.value!!
        val epoch0KeyBytes = Base64.UrlSafe.decode(stateA.previousKey!!)

        // C generates an epoch-0 op
        fun createSealedEventFromC(seq: Long, cleanText: String): NostrEvent {
            val history = SyncedHistory(
                originDevice = nodeC.selfId,
                originId = seq,
                appLabel = "App",
                createdAt = t0,
                packageName = "com.c.app",
                level = CleanupLevel.POLISHED,
                script = ScriptPreference.LATIN,
                language = "en",
                cleanText = cleanText,
                status = HistoryStatus.DONE,
            )
            val envelope = OpEnvelope(nodeC.selfId, seq, t0, SyncOp.HistoryUpsert(history))
            val jsonString = Json { ignoreUnknownKeys = true }.encodeToString(
                ListSerializer(OpEnvelope.serializer()),
                listOf(envelope)
            )
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(jsonString.encodeToByteArray()) }
            val cipher0 = GroupCipher(epoch0KeyBytes, epoch = 0)
            val sealed = cipher0.seal(bos.toByteArray())
            val content = Base64.encode(sealed)

            return NostrEvent.sign(
                keys = nodeC.groupStore.device,
                kind = KIND_APP_DATA,
                tags = listOf(listOf("d", "umm:hash:1-1")),
                content = content,
                createdAt = nodeA.nowMs / 1000L,
            )
        }

        // 1 hour after rotation (< 24 h grace): C's epoch-0 event is applied
        nodeA.nowMs = t0 + 3600 * 1000L
        val eventWithinGrace = createSealedEventFromC(1L, "Applied within grace")
        nodeA.relayTransport.handleIncomingEvent(eventWithinGrace, nodeA.groupStore.state.value!!, "relay")
        val historyWithin = nodeA.history.observeRecent().first()
        assertEquals(1, historyWithin.size)
        assertEquals("Applied within grace", historyWithin[0].cleanText)

        // 25 hours after rotation (> 24 h grace): C's epoch-0 event is dropped
        nodeA.nowMs = t0 + 25 * 3600 * 1000L
        val eventAfterGrace = createSealedEventFromC(2L, "Dropped after grace")
        nodeA.relayTransport.handleIncomingEvent(eventAfterGrace, nodeA.groupStore.state.value!!, "relay")
        val historyAfter = nodeA.history.observeRecent().first()
        assertEquals(1, historyAfter.size)
    }

    @Test
    fun leaveClearsLocalGroupOnly() = runTest(timeout = 20.seconds) {
        val (nodeA, nodeB, nodeC) = setupThreeNodes()

        val hId = nodeB.history.createPending(
            appLabel = "BApp",
            packageName = "com.b.app",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "",
        )
        nodeB.history.markDone(hId, raw = "B local text", clean = "B local text.")

        nodeB.membership.leave()

        // B's state null and data kept
        assertNull(nodeB.groupStore.state.value)
        val bHistory = nodeB.history.observeRecent().first()
        assertEquals(1, bHistory.size)
        assertEquals("B local text.", bHistory[0].cleanText)

        // Ops from B reach remaining nodes A and C
        val opsFromB = nodeB.log.since(emptyMap())
        nodeA.applier.apply(opsFromB)
        nodeC.applier.apply(opsFromB)

        // Rotation ops propagate between remaining nodes
        val opsFromA = nodeA.log.since(emptyMap())
        val opsFromC = nodeC.log.since(emptyMap())
        nodeC.applier.apply(opsFromA)
        nodeA.applier.apply(opsFromC)

        val stateA = nodeA.groupStore.state.value
        val stateC = nodeC.groupStore.state.value

        assertNotNull(stateA)
        assertNotNull(stateC)
        assertEquals(1, stateA!!.epoch)
        assertEquals(1, stateC!!.epoch)
        assertEquals(stateA.key, stateC.key)
        assertEquals(2, stateA.members.size)
        assertEquals(2, stateC.members.size)
        assertTrue(stateA.members.none { it.deviceId == nodeB.selfId })
        assertTrue(stateC.members.none { it.deviceId == nodeB.selfId })
    }
}
