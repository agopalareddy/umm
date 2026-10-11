package io.github.agopalareddy.umm.core.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.data.CategoryRepository
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import io.github.agopalareddy.umm.core.stats.StatsEntry
import io.github.agopalareddy.umm.core.stats.StatsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val devices = mutableListOf<TestDevice>()

    private class TestDevice(
        val name: String,
        tmpFolder: File,
        scope: CoroutineScope,
        initialWall: Long = 1_000_000L,
    ) {
        var wall: Long = initialWall
        val db = inMemoryUmmDatabase()
        val meta = SyncMeta(db)
        val clock = HybridClock { wall }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmpFolder, "settings_$name.preferences_pb")
        }
        val log = OpLog(db, meta, clock, active = { true })
        val settings = SettingsRepository(dataStore, recorder = log)
        val history = HistoryRepository(db, clock = { wall }, recorder = log)
        val stats = StatsRepository(db, recorder = log)
        val categories = CategoryRepository(db, recorder = log)
        val groupOps = mutableListOf<OpEnvelope>()
        val applier = OpApplier(db, log, settings, clock, onGroupOp = { groupOps.add(it) })

        val marks: Map<String, Long> get() = runBlocking { log.highWaterMarks() }

        suspend fun deviceId(): String = meta.deviceId()
        suspend fun since(marks: Map<String, Long>): List<OpEnvelope> = log.since(marks)
        /** A complete exchange, as a sync session does it: the ops, then the marks they bring this device to. */
        suspend fun apply(envelopes: List<OpEnvelope>): Int = applier.apply(envelopes).also {
            log.markComplete(envelopes.groupBy { it.origin }.mapValues { (_, ops) -> ops.maxOf { it.seq } })
        }
        suspend fun applySnapshot(snapshot: Snapshot) = applier.applySnapshot(snapshot)

        fun close() {
            db.close()
        }
    }

    private fun TestScope.createDevice(name: String, initialWall: Long = 1_000_000L): TestDevice {
        val dev = TestDevice(name, tmp.root, backgroundScope, initialWall)
        devices.add(dev)
        return dev
    }

    @After
    fun tearDown() {
        devices.forEach { it.close() }
    }

    @Test
    fun snapshotThenOps() = runTest {
        val a = createDevice("A", initialWall = 100L)
        val c = createDevice("C", initialWall = 200L)

        // A creates initial history, stats, settings, categories
        val audio1 = File(tmp.root, "a1.wav").apply { writeText("audio1") }
        val h1 = a.history.createPending(
            packageName = "app.one",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = audio1.absolutePath,
        )
        a.history.markDone(h1, raw = "one", clean = "One.")

        a.stats.record(
            StatsEntry(
                originDevice = a.deviceId(),
                historyId = h1,
                createdAt = 101L,
                packageName = "app.one",
                level = CleanupLevel.LIGHT,
                rawWords = 1,
                cleanWords = 1,
                fillerWords = 0,
                audioMs = 500L,
                latencyMs = null,
                costUsd = null,
                sttModel = null,
                cleanupModel = null,
                succeeded = true,
            )
        )

        a.settings.update { it.copy(defaultLevel = CleanupLevel.FORMATTED) }
        a.categories.assign("app.one", Category.EMAIL)

        // A builds snapshot
        val snapshot = buildSnapshot(a.db, a.log, a.settings, members = emptyList())

        // A performs later operations
        val audio2 = File(tmp.root, "a2.wav").apply { writeText("audio2") }
        val h2 = a.history.createPending(
            packageName = "app.two",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = audio2.absolutePath,
        )
        a.history.markDone(h2, raw = "two", clean = "Two.")

        a.stats.record(
            StatsEntry(
                originDevice = a.deviceId(),
                historyId = h2,
                createdAt = 102L,
                packageName = "app.two",
                level = CleanupLevel.POLISHED,
                rawWords = 1,
                cleanWords = 1,
                fillerWords = 0,
                audioMs = 600L,
                latencyMs = null,
                costUsd = null,
                sttModel = null,
                cleanupModel = null,
                succeeded = true,
            )
        )

        // C applies A's snapshot
        c.applySnapshot(snapshot)

        // C applies A's later ops
        val laterOps = a.since(snapshot.marks)
        c.apply(laterOps)

        // Verify same rows
        val aRecent = a.history.observeRecent().first()
        val cRecent = c.history.observeRecent().first()
        assertEquals(aRecent.size, cRecent.size)
        assertEquals(aRecent.map { it.cleanText }, cRecent.map { it.cleanText })

        val aStats = a.stats.all()
        val cStats = c.stats.all()
        assertEquals(aStats.size, cStats.size)
        assertEquals(aStats.map { it.historyId to it.originDevice }, cStats.map { it.historyId to it.originDevice })

        // Verify same settings
        assertEquals(a.settings.settings.first().defaultLevel, c.settings.settings.first().defaultLevel)

        // Verify same marks
        assertEquals(a.marks, c.marks)

        // Verify sync_peers was seeded
        val peer = c.db.syncDao().getPeer(a.deviceId())
        assertNotNull(peer)
    }

    @Test
    fun snapshotRespectsNewerLocalSetting() = runTest {
        val a = createDevice("A", initialWall = 50L)
        val c = createDevice("C", initialWall = 100L)

        // A sets defaultLevel = FORMATTED at hlc ~50
        a.settings.update { it.copy(defaultLevel = CleanupLevel.FORMATTED) }
        val snapshot = buildSnapshot(a.db, a.log, a.settings, members = emptyList())

        // C sets defaultLevel = POLISHED at hlc ~100
        c.settings.update { it.copy(defaultLevel = CleanupLevel.POLISHED) }

        // C applies A's snapshot
        c.applySnapshot(snapshot)

        // C's setting with higher hlc survives
        assertEquals(CleanupLevel.POLISHED, c.settings.settings.first().defaultLevel)
    }
}
