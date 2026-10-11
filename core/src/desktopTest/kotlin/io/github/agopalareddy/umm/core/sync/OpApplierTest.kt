package io.github.agopalareddy.umm.core.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.HistoryStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpApplierTest {

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
        val groupOps = mutableListOf<OpEnvelope>()
        val applier = OpApplier(db, log, settings, clock, onGroupOp = { groupOps.add(it) })

        val marks: Map<String, Long> get() = runBlocking { log.highWaterMarks() }

        suspend fun deviceId(): String = meta.deviceId()
        suspend fun since(marks: Map<String, Long>): List<OpEnvelope> = log.since(marks)
        suspend fun apply(envelopes: List<OpEnvelope>): Int = applier.apply(envelopes)

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
    fun historyFromAAppearsOnB() = runTest {
        val a = createDevice("A")
        val b = createDevice("B")

        val audio = File(tmp.root, "a.wav").apply { writeText("audio") }
        val id = a.history.createPending(
            packageName = "com.example.app",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = audio.absolutePath,
        )
        a.history.markDone(id, raw = "hello world", clean = "Hello world.")

        val applied = b.apply(a.since(b.marks))
        assertEquals(1, applied)

        val bRecent = b.history.observeRecent().first()
        assertEquals(1, bRecent.size)
        assertEquals(a.deviceId(), bRecent.single().originDevice)
        assertEquals("Hello world.", bRecent.single().cleanText)
    }

    @Test
    fun sameOpFromTwoTransportsAppliesOnce() = runTest {
        val a = createDevice("A")
        val b = createDevice("B")

        val audio = File(tmp.root, "a.wav").apply { writeText("audio") }
        val id = a.history.createPending(
            packageName = "com.example.app",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = audio.absolutePath,
        )
        a.history.markDone(id, raw = "test", clean = "Test.")

        val ops = a.since(emptyMap())
        assertEquals(1, ops.size)

        val first = b.apply(ops)
        assertEquals(1, first)

        val second = b.apply(ops)
        assertEquals(0, second)

        val bRecent = b.history.observeRecent().first()
        assertEquals(1, bRecent.size)
    }

    @Test
    fun lastWriterWinsPerSetting() = runTest {
        val a = createDevice("A", initialWall = 10L)
        val b = createDevice("B", initialWall = 20L)

        a.settings.update { it.copy(defaultLevel = CleanupLevel.FORMATTED) }
        b.settings.update { it.copy(defaultLevel = CleanupLevel.POLISHED) }
        b.settings.update { it.copy(defaultLevel = CleanupLevel.LIGHT) }

        a.apply(b.since(a.marks))
        b.apply(a.since(b.marks))

        assertEquals(CleanupLevel.LIGHT, a.settings.settings.first().defaultLevel)
        assertEquals(CleanupLevel.LIGHT, b.settings.settings.first().defaultLevel)
    }

    @Test
    fun correctedFastClockLosesToLaterEdit() = runTest {
        val a = createDevice("A", initialWall = 90_000_000L)
        val b = createDevice("B", initialWall = 1_000L)

        a.settings.update { it.copy(defaultLevel = CleanupLevel.FORMATTED) }

        b.apply(a.since(b.marks))

        b.settings.update { it.copy(defaultLevel = CleanupLevel.POLISHED) }

        a.apply(b.since(a.marks))

        assertEquals(CleanupLevel.POLISHED, a.settings.settings.first().defaultLevel)
        assertEquals(CleanupLevel.POLISHED, b.settings.settings.first().defaultLevel)
    }

    @Test
    fun tieBreaksByDeviceId() = runTest {
        val a = createDevice("DEV_A", initialWall = 100L)
        val b = createDevice("DEV_B", initialWall = 100L)

        a.db.syncDao().putMeta(SyncMetaEntity(SyncMeta.KEY_DEVICE_ID, "DEV_A"))
        b.db.syncDao().putMeta(SyncMetaEntity(SyncMeta.KEY_DEVICE_ID, "DEV_B"))

        val opA = OpEnvelope(
            origin = "DEV_A",
            seq = 1L,
            hlc = 100L,
            op = SyncOp.SettingSet("default_level", "FORMATTED"),
        )
        val opB = OpEnvelope(
            origin = "DEV_B",
            seq = 1L,
            hlc = 100L,
            op = SyncOp.SettingSet("default_level", "POLISHED"),
        )

        a.apply(listOf(opA, opB))
        b.apply(listOf(opB, opA))

        assertEquals(CleanupLevel.POLISHED, a.settings.settings.first().defaultLevel)
        assertEquals(CleanupLevel.POLISHED, b.settings.settings.first().defaultLevel)
    }

    @Test
    fun clearBeatsLateInsert() = runTest {
        val b = createDevice("B")

        val clearOp = OpEnvelope(
            origin = "DEV_A",
            seq = 1L,
            hlc = 10L,
            op = SyncOp.ClearBefore(DataKind.HISTORY, 100L, null),
        )
        val lateRow = SyncedHistory(
            originDevice = "DEV_A",
            originId = 1L,
            createdAt = 50L,
            packageName = "pkg",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "en",
            status = HistoryStatus.DONE,
            cleanText = "Late",
        )
        val lateOp = OpEnvelope(
            origin = "DEV_A",
            seq = 2L,
            hlc = 20L,
            op = SyncOp.HistoryUpsert(lateRow),
        )
        val newRow = SyncedHistory(
            originDevice = "DEV_A",
            originId = 2L,
            createdAt = 150L,
            packageName = "pkg",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "en",
            status = HistoryStatus.DONE,
            cleanText = "New",
        )
        val newOp = OpEnvelope(
            origin = "DEV_A",
            seq = 3L,
            hlc = 30L,
            op = SyncOp.HistoryUpsert(newRow),
        )

        b.apply(listOf(clearOp))
        b.apply(listOf(lateOp))
        b.apply(listOf(newOp))

        val recent = b.history.observeRecent().first()
        assertEquals(1, recent.size)
        assertEquals(2L, recent.single().originId)
        assertEquals("New", recent.single().cleanText)
    }

    @Test
    fun scopedClearKeepsOtherOrigins() = runTest {
        val b = createDevice("B")

        val statA = SyncedStats(
            originDevice = "A",
            historyId = 1L,
            createdAt = 50L,
            packageName = "pkg",
            level = CleanupLevel.LIGHT,
            rawWords = 5,
            cleanWords = 5,
            fillerWords = 0,
            audioMs = 1000L,
            succeeded = true,
        )
        val statB = SyncedStats(
            originDevice = "B",
            historyId = 1L,
            createdAt = 50L,
            packageName = "pkg",
            level = CleanupLevel.LIGHT,
            rawWords = 5,
            cleanWords = 5,
            fillerWords = 0,
            audioMs = 1000L,
            succeeded = true,
        )

        b.apply(listOf(
            OpEnvelope("A", 1L, 10L, SyncOp.StatsUpsert(statA)),
            OpEnvelope("B", 1L, 11L, SyncOp.StatsUpsert(statB)),
        ))

        b.apply(listOf(
            OpEnvelope("A", 2L, 20L, SyncOp.ClearBefore(DataKind.STATS, 100L, "A")),
        ))

        val allStats = b.stats.all()
        assertEquals(1, allStats.size)
        assertEquals("B", allStats.single().originDevice)
    }

    @Test
    fun relaysThroughMiddleDevice() = runTest {
        val a = createDevice("A")
        val b = createDevice("B")
        val c = createDevice("C")

        val audio = File(tmp.root, "a.wav").apply { writeText("audio") }
        val id = a.history.createPending(
            packageName = "com.example.app",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = audio.absolutePath,
        )
        a.history.markDone(id, raw = "relayed", clean = "Relayed text.")

        b.apply(a.since(b.marks))
        c.apply(b.since(c.marks))

        val cRecent = c.history.observeRecent().first()
        assertEquals(1, cRecent.size)
        assertEquals(a.deviceId(), cRecent.single().originDevice)
        assertEquals("Relayed text.", cRecent.single().cleanText)
        assertTrue(c.marks.containsKey(a.deviceId()))
    }

    @Test
    fun deleteRemovesEverywhere() = runTest {
        val a = createDevice("A")
        val b = createDevice("B")

        val audio = File(tmp.root, "a.wav").apply { writeText("audio") }
        val id = a.history.createPending(
            packageName = "com.example.app",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = audio.absolutePath,
        )
        a.history.markDone(id, raw = "to be deleted", clean = "To be deleted.")

        b.apply(a.since(b.marks))
        assertEquals(1, b.history.observeRecent().first().size)

        a.history.delete(id)
        b.apply(a.since(b.marks))

        assertEquals(0, b.history.observeRecent().first().size)
    }

    @Test
    fun remoteOpsAreNotReRecorded() = runTest {
        val a = createDevice("A")
        val b = createDevice("B")

        val audio = File(tmp.root, "b.wav").apply { writeText("audio") }
        val id = b.history.createPending(
            packageName = "com.example.app",
            level = CleanupLevel.LIGHT,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = audio.absolutePath,
        )
        b.history.markDone(id, raw = "b op", clean = "B op clean.")

        val initialOpsForA = a.log.since(emptyMap()).filter { it.origin == a.deviceId() }
        assertEquals(0, initialOpsForA.size)

        a.apply(b.since(a.marks))

        val opsForAAfter = a.log.since(emptyMap()).filter { it.origin == a.deviceId() }
        assertEquals(0, opsForAAfter.size)
    }
}
