package io.github.agopalareddy.umm.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.HistoryStatus
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import io.github.agopalareddy.umm.core.stats.StatsRepository
import io.github.agopalareddy.umm.core.sync.DataKind
import io.github.agopalareddy.umm.core.sync.HybridClock
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.Snapshot
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.SyncOp
import io.github.agopalareddy.umm.core.sync.SyncedHistory
import io.github.agopalareddy.umm.core.sync.buildSnapshot
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val devices = mutableListOf<TestSessionDevice>()

    private class InMemoryWirePair {
        val aToB = Channel<ByteArray>(Channel.UNLIMITED)
        val bToA = Channel<ByteArray>(Channel.UNLIMITED)

        val wireA = object : Wire {
            override suspend fun send(message: ByteArray) = aToB.send(message)
            override suspend fun receive(): ByteArray = bToA.receive()
        }

        val wireB = object : Wire {
            override suspend fun send(message: ByteArray) = bToA.send(message)
            override suspend fun receive(): ByteArray = aToB.receive()
        }
    }

    private class TestSessionDevice(
        val name: String,
        tmpFolder: File,
        scope: CoroutineScope,
        key: ByteArray = ByteArray(32) { 1 },
        epoch: Int = 1,
    ) {
        val db = inMemoryUmmDatabase()
        val meta = SyncMeta(db)
        val clock = HybridClock { 1000L }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmpFolder, "settings_$name.preferences_pb")
        }
        val log = OpLog(db, meta, clock, active = { true })
        val settings = SettingsRepository(dataStore, recorder = log)
        val history = HistoryRepository(db, clock = { 1000L }, recorder = log)
        val stats = StatsRepository(db, recorder = log)
        var cipher = GroupCipher(key, epoch)
        val applier = OpApplier(db, log, settings, clock, onGroupOp = { })
        val session = SyncSession(
            log = log,
            applier = applier,
            snapshot = { buildSnapshot(db, log, settings, members = emptyList()) },
            cipher = { cipher },
            selfId = run {
                kotlinx.coroutines.runBlocking { meta.deviceId() }
            },
        )

        fun close() {
            db.close()
        }
    }

    private fun createDevice(
        name: String,
        scope: CoroutineScope,
        key: ByteArray = ByteArray(32) { 1 },
        epoch: Int = 1,
    ): TestSessionDevice {
        return TestSessionDevice(name, tmp.root, scope, key, epoch).also { devices.add(it) }
    }

    @After
    fun tearDown() {
        devices.forEach { it.close() }
        devices.clear()
    }

    @Test
    fun exchangesBothWays() = runTest {
        val sharedKey = ByteArray(32) { 42 }
        val devA = createDevice("devA", this, key = sharedKey)
        val devB = createDevice("devB", this, key = sharedKey)

        // DevA records one unseen op
        devA.log.record(
            SyncOp.HistoryUpsert(
                SyncedHistory(
                    originDevice = devA.meta.deviceId(),
                    originId = 1L,
                    appLabel = "Notes",
                    createdAt = 1000L,
                    packageName = "org.notes",
                    level = CleanupLevel.POLISHED,
                    script = ScriptPreference.LATIN,
                    language = "en",
                    cleanText = "Hello from A",
                    status = HistoryStatus.DONE,
                )
            )
        )

        // DevB records one unseen op
        devB.log.record(
            SyncOp.HistoryUpsert(
                SyncedHistory(
                    originDevice = devB.meta.deviceId(),
                    originId = 1L,
                    appLabel = "Notes",
                    createdAt = 1001L,
                    packageName = "org.notes",
                    level = CleanupLevel.POLISHED,
                    script = ScriptPreference.LATIN,
                    language = "en",
                    cleanText = "Hello from B",
                    status = HistoryStatus.DONE,
                )
            )
        )

        val wires = InMemoryWirePair()

        val aJob = async { devA.session.run(wires.wireA, initiator = true) }
        val bJob = async { devB.session.run(wires.wireB, initiator = false) }

        val resA = aJob.await()
        val resB = bJob.await()

        assertEquals(SessionResult(1, 1), resA)
        assertEquals(SessionResult(1, 1), resB)

        // Both end with both ops
        val allOpsA = devA.log.since(emptyMap())
        val allOpsB = devB.log.since(emptyMap())
        assertEquals(2, allOpsA.size)
        assertEquals(2, allOpsB.size)
    }

    @Test
    fun newPeerGetsSnapshot() = runTest {
        val sharedKey = ByteArray(32) { 42 }
        val devA = createDevice("devA", this, key = sharedKey)
        val devC = createDevice("devC", this, key = sharedKey)

        devA.log.record(
            SyncOp.HistoryUpsert(
                SyncedHistory(
                    originDevice = devA.meta.deviceId(),
                    originId = 1L,
                    appLabel = "Notes",
                    createdAt = 1000L,
                    packageName = "org.notes",
                    level = CleanupLevel.POLISHED,
                    script = ScriptPreference.LATIN,
                    language = "en",
                    cleanText = "Hello from A",
                    status = HistoryStatus.DONE,
                )
            )
        )

        val wires = InMemoryWirePair()
        val cJob = async { devC.session.run(wires.wireA, initiator = true) }
        val aJob = async { devA.session.run(wires.wireB, initiator = false) }

        cJob.await()
        aJob.await()

        val snapA = buildSnapshot(devA.db, devA.log, devA.settings, members = emptyList())
        val snapC = buildSnapshot(devC.db, devC.log, devC.settings, members = emptyList())

        assertEquals(snapA.history, snapC.history)
    }

    @Test
    fun wrongGroupKeyFailsCleanly() = runTest {
        val keyA = ByteArray(32) { 1 }
        val keyB = ByteArray(32) { 2 }
        val devA = createDevice("devA", this, key = keyA)
        val devB = createDevice("devB", this, key = keyB)

        devA.log.record(
            SyncOp.HistoryUpsert(
                SyncedHistory(
                    originDevice = devA.meta.deviceId(),
                    originId = 1L,
                    appLabel = "Notes",
                    createdAt = 1000L,
                    packageName = "org.notes",
                    level = CleanupLevel.POLISHED,
                    script = ScriptPreference.LATIN,
                    language = "en",
                    cleanText = "Hello from A",
                    status = HistoryStatus.DONE,
                )
            )
        )

        val wires = InMemoryWirePair()
        kotlinx.coroutines.supervisorScope {
            val aJob = async { devA.session.run(wires.wireA, initiator = true) }
            val bJob = async { devB.session.run(wires.wireB, initiator = false) }

            try {
                bJob.await()
                org.junit.Assert.fail("Expected SyncAuthException")
            } catch (e: SyncAuthException) {
                // expected
            }
            aJob.cancel()
        }

        // Nothing applied on B
        assertEquals(0, devB.log.since(emptyMap()).size)
    }

}
