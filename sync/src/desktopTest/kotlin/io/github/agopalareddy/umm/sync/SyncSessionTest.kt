package io.github.agopalareddy.umm.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.HistoryStatus
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import io.github.agopalareddy.umm.core.stats.StatsEntry
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

        suspend fun deviceId(): String = meta.deviceId()

        suspend fun insertPreExisting(
            historyCount: Int,
            statsCount: Int,
            baseTime: Long = 1000L,
        ): List<Long> {
            var currentClock = baseTime
            val unrecordedHistory = HistoryRepository(db, clock = { currentClock }, recorder = io.github.agopalareddy.umm.core.sync.ChangeRecorder.None)
            val unrecordedStats = StatsRepository(db, recorder = io.github.agopalareddy.umm.core.sync.ChangeRecorder.None)
            val insertedIds = mutableListOf<Long>()
            for (i in 1..historyCount) {
                currentClock = baseTime + i * 10L
                val hid = unrecordedHistory.createPending(
                    packageName = "app.pkg$i",
                    appLabel = "App$i",
                    level = CleanupLevel.POLISHED,
                    script = ScriptPreference.LATIN,
                    language = "en",
                    audioPath = "",
                )
                unrecordedHistory.markDone(hid, raw = "Raw $i", clean = "Clean $i")
                insertedIds.add(hid)
            }
            val devId = meta.deviceId()
            for (i in 1..statsCount) {
                unrecordedStats.record(
                    StatsEntry(
                        originDevice = devId,
                        historyId = i.toLong(),
                        appLabel = "App$i",
                        createdAt = baseTime + i * 10L,
                        packageName = "app.pkg$i",
                        level = CleanupLevel.POLISHED,
                        rawWords = 5,
                        cleanWords = 5,
                        fillerWords = 0,
                        audioMs = 1000L,
                        latencyMs = 200L,
                        costUsd = 0.001,
                        sttModel = null,
                        cleanupModel = null,
                        succeeded = true,
                    )
                )
            }
            return insertedIds
        }

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

    @Test
    fun twoDevicesWithPreExistingRowsSyncAndSecondSessionSendsZero() = runTest {
        val sharedKey = ByteArray(32) { 42 }
        val devA = createDevice("devA", this, key = sharedKey)
        val devB = createDevice("devB", this, key = sharedKey)

        devA.insertPreExisting(50, 62, baseTime = 1000L)
        devB.insertPreExisting(17, 17, baseTime = 2000L)

        // Op logs are empty on both
        assertEquals(0, devA.log.since(emptyMap()).size)
        assertEquals(0, devB.log.since(emptyMap()).size)

        // Session 1: both exchange pre-existing rows
        val wires1 = InMemoryWirePair()
        val aJob1 = async { devA.session.run(wires1.wireA, initiator = true) }
        val bJob1 = async { devB.session.run(wires1.wireB, initiator = false) }
        val resA1 = aJob1.await()
        val resB1 = bJob1.await()

        // Both hold the union
        assertEquals(67, devA.history.all().size)
        assertEquals(79, devA.stats.all().size)
        assertEquals(67, devB.history.all().size)
        assertEquals(79, devB.stats.all().size)

        // Second session right after: transfers zero rows in both directions
        val wires2 = InMemoryWirePair()
        val aJob2 = async { devA.session.run(wires2.wireA, initiator = true) }
        val bJob2 = async { devB.session.run(wires2.wireB, initiator = false) }
        val resA2 = aJob2.await()
        val resB2 = bJob2.await()

        assertEquals(SessionResult(0, 0), resA2)
        assertEquals(SessionResult(0, 0), resB2)
    }

    @Test
    fun threeDevicesWithPreExistingRowsSyncThroughIntermediate() = runTest {
        val sharedKey = ByteArray(32) { 42 }
        val devA = createDevice("devA", this, key = sharedKey)
        val devB = createDevice("devB", this, key = sharedKey)
        val devC = createDevice("devC", this, key = sharedKey)

        devA.insertPreExisting(10, 10, baseTime = 1000L)
        devB.insertPreExisting(20, 20, baseTime = 2000L)
        devC.insertPreExisting(30, 30, baseTime = 3000L)

        // Step 1: A and B sync
        val wiresAB1 = InMemoryWirePair()
        val aJob1 = async { devA.session.run(wiresAB1.wireA, initiator = true) }
        val bJob1 = async { devB.session.run(wiresAB1.wireB, initiator = false) }
        aJob1.await()
        bJob1.await()

        assertEquals(30, devA.history.all().size)
        assertEquals(30, devB.history.all().size)

        // Step 2: C joins/syncs with B only
        val wiresBC = InMemoryWirePair()
        val cJob = async { devC.session.run(wiresBC.wireA, initiator = true) }
        val bJob2 = async { devB.session.run(wiresBC.wireB, initiator = false) }
        cJob.await()
        bJob2.await()

        // C ends with A's and B's rows (total 60 history, 60 stats)
        assertEquals(60, devC.history.all().size)
        assertEquals(60, devC.stats.all().size)
        assertEquals(60, devB.history.all().size)
        assertEquals(60, devB.stats.all().size)

        // Step 3: A and B sync again
        val wiresAB2 = InMemoryWirePair()
        val aJob2 = async { devA.session.run(wiresAB2.wireA, initiator = true) }
        val bJob3 = async { devB.session.run(wiresAB2.wireB, initiator = false) }
        aJob2.await()
        bJob3.await()

        // A ends with C's old rows once A and B sync again
        assertEquals(60, devA.history.all().size)
        assertEquals(60, devA.stats.all().size)
    }

    @Test
    fun preExistingRowsPlusNewOpsDeliveredOnce() = runTest {
        val sharedKey = ByteArray(32) { 42 }
        val devA = createDevice("devA", this, key = sharedKey)
        val devB = createDevice("devB", this, key = sharedKey)

        devA.insertPreExisting(5, 5, baseTime = 1000L)

        // DevA records new ops via its normal (recorded) history and stats
        val h6 = devA.history.createPending(
            packageName = "org.notes",
            appLabel = "Notes",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "",
        )
        devA.history.markDone(h6, raw = "Raw 6", clean = "New op history row")
        devA.stats.record(
            StatsEntry(
                originDevice = devA.deviceId(),
                historyId = h6,
                appLabel = "Notes",
                createdAt = 2000L,
                packageName = "org.notes",
                level = CleanupLevel.POLISHED,
                rawWords = 4,
                cleanWords = 4,
                fillerWords = 0,
                audioMs = 800L,
                latencyMs = 100L,
                costUsd = null,
                sttModel = null,
                cleanupModel = null,
                succeeded = true,
            )
        )

        val wires = InMemoryWirePair()
        val aJob = async { devA.session.run(wires.wireA, initiator = true) }
        val bJob = async { devB.session.run(wires.wireB, initiator = false) }
        aJob.await()
        bJob.await()

        val bHistory = devB.history.all()
        val bStats = devB.stats.all()
        assertEquals(6, bHistory.size)
        assertEquals(6, bStats.size)
        // Verify distinct originIds (each row once, no duplicate)
        assertEquals(6, bHistory.map { it.originId }.toSet().size)
        assertEquals(6, bStats.map { it.historyId }.toSet().size)
    }

    @Test
    fun historyRowDeletedBeforeFirstSessionDoesNotComeBack() = runTest {
        val sharedKey = ByteArray(32) { 42 }
        val devA = createDevice("devA", this, key = sharedKey)
        val devB = createDevice("devB", this, key = sharedKey)

        val devAIds = devA.insertPreExisting(2, 2, baseTime = 1000L)
        val id1 = devAIds[0]
        val id2 = devAIds[1]

        // DevB also has copies of Row 1 and Row 2 from DevA (e.g. from joining snapshot)
        val snapA = buildSnapshot(devA.db, devA.log, devA.settings, members = emptyList())
        devB.applier.applySnapshot(snapA)

        assertEquals(2, devB.history.all().size)

        // Before the first sync session, Row 1 is deleted on devA
        devA.history.delete(id1)

        assertEquals(listOf(id2), devA.history.all().map { it.id })

        // Now run first sync session
        val wires = InMemoryWirePair()
        val aJob = async { devA.session.run(wires.wireA, initiator = true) }
        val bJob = async { devB.session.run(wires.wireB, initiator = false) }
        aJob.await()
        bJob.await()

        // Row 1 does not come back to A, and is deleted on B
        val aOrigins = devA.history.all().map { it.id }
        val bOrigins = devB.history.all().map { it.originId }
        assertEquals(listOf(id2), aOrigins)
        assertEquals(listOf(id2), bOrigins)
    }

    @Test
    fun perOriginTrimmingLeavesAtMostLimitNewestByTime() = runTest {
        val sharedKey = ByteArray(32) { 42 }
        val devA = createDevice("devA", this, key = sharedKey)
        val devB = createDevice("devB", this, key = sharedKey)

        // Insert 55 pre-existing rows on devA (limit is 50)
        devA.insertPreExisting(55, 55, baseTime = 1000L)

        val wires = InMemoryWirePair()
        val aJob = async { devA.session.run(wires.wireA, initiator = true) }
        val bJob = async { devB.session.run(wires.wireB, initiator = false) }
        aJob.await()
        bJob.await()

        val bHistory = devB.history.all()
        assertEquals(50, bHistory.size)
        // Oldest 5 (ids 1..5) trimmed; newest 50 (ids 6..55) kept
        val bIds = bHistory.map { it.originId }.sorted()
        assertEquals((6L..55L).toList(), bIds)
    }
}

