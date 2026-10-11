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
        /** A complete exchange, as a sync session does it: the ops, then the marks they bring this device to. */
        suspend fun apply(envelopes: List<OpEnvelope>): Int = applier.apply(envelopes).also {
            log.markComplete(envelopes.groupBy { it.origin }.mapValues { (_, ops) -> ops.maxOf { it.seq } })
        }

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

    @Test
    fun lateArrivalWithOlderCreatedAt_orderedByTime_andTrimmedWhenOverLimit() = runTest {
        val a = createDevice("A")
        val b = createDevice("B")

        // Device A creates two newer local entries at t=2000 and t=3000
        a.wall = 2000
        val audioA1 = File(tmp.root, "a1.wav").apply { writeText("audio") }
        val idA1 = a.history.createPending("com.app", level = CleanupLevel.LIGHT, script = ScriptPreference.LATIN, language = "en", audioPath = audioA1.absolutePath)
        a.history.markDone(idA1, "two", "Two")

        a.wall = 3000
        val audioA2 = File(tmp.root, "a2.wav").apply { writeText("audio") }
        val idA2 = a.history.createPending("com.app", level = CleanupLevel.LIGHT, script = ScriptPreference.LATIN, language = "en", audioPath = audioA2.absolutePath)
        a.history.markDone(idA2, "three", "Three")

        // Device B creates an older entry at t=1000
        b.wall = 1000
        val audioB = File(tmp.root, "b.wav").apply { writeText("audio") }
        val idB = b.history.createPending("com.app", level = CleanupLevel.LIGHT, script = ScriptPreference.LATIN, language = "en", audioPath = audioB.absolutePath)
        b.history.markDone(idB, "one", "One")

        // Device A applies B's older entry (inserted into A with higher id)
        a.apply(b.since(a.marks))

        val recent = a.history.observeRecent().first()
        assertEquals(3, recent.size)
        // Must be in time order: 3000, 2000, 1000
        assertEquals(listOf(3000L, 2000L, 1000L), recent.map { it.createdAt })

        // Now test trimming for origin B: fill origin B up to 50 entries with t=1001..1050
        for (i in 1..50) {
            b.wall = 1000L + i
            val audioBi = File(tmp.root, "b_$i.wav").apply { writeText("audio") }
            val idBi = b.history.createPending("com.app", level = CleanupLevel.LIGHT, script = ScriptPreference.LATIN, language = "en", audioPath = audioBi.absolutePath)
            b.history.markDone(idBi, "msg$i", "Msg$i")
        }
        // Device A applies B's new entries; now B has 51 total entries (t=1000 and t=1001..1050)
        a.apply(b.since(a.marks))

        val bItemsOnA = a.history.observeRecent(origin = b.deviceId()).first()
        assertEquals(50, bItemsOnA.size)
        // Oldest item (t=1000) was trimmed, newest 50 (t=1001..1050) remain
        assertEquals((1050L downTo 1001L).toList(), bItemsOnA.map { it.createdAt })
        assertFalse(bItemsOnA.any { it.createdAt == 1000L })
    }

    @Test
    fun devicesApplyingSameOpsInDifferentOrder_endWithSameRowsInSameOrder() = runTest {
        val devA = createDevice("DEV_A")
        val devB = createDevice("DEV_B")

        // Create 3 ops from different origins/times
        val op1 = OpEnvelope(
            origin = "origin_X",
            seq = 1,
            hlc = 100,
            op = SyncOp.HistoryUpsert(
                SyncedHistory(
                    originDevice = "origin_X",
                    originId = 1,
                    appLabel = null,
                    createdAt = 1000L,
                    packageName = "com.x",
                    level = CleanupLevel.LIGHT,
                    script = ScriptPreference.LATIN,
                    language = "en",
                    status = HistoryStatus.DONE,
                    rawText = "x1",
                    cleanText = "Clean X1",
                    error = null,
                ),
            ),
        )

        val op2 = OpEnvelope(
            origin = "origin_Y",
            seq = 1,
            hlc = 200,
            op = SyncOp.HistoryUpsert(
                SyncedHistory(
                    originDevice = "origin_Y",
                    originId = 1,
                    appLabel = null,
                    createdAt = 3000L,
                    packageName = "com.y",
                    level = CleanupLevel.LIGHT,
                    script = ScriptPreference.LATIN,
                    language = "en",
                    status = HistoryStatus.DONE,
                    rawText = "y1",
                    cleanText = "Clean Y1",
                    error = null,
                ),
            ),
        )

        val op3 = OpEnvelope(
            origin = "origin_X",
            seq = 2,
            hlc = 300,
            op = SyncOp.HistoryUpsert(
                SyncedHistory(
                    originDevice = "origin_X",
                    originId = 2,
                    appLabel = null,
                    createdAt = 2000L,
                    packageName = "com.x",
                    level = CleanupLevel.LIGHT,
                    script = ScriptPreference.LATIN,
                    language = "en",
                    status = HistoryStatus.DONE,
                    rawText = "x2",
                    cleanText = "Clean X2",
                    error = null,
                ),
            ),
        )

        // Device A applies in order [op1, op2, op3]
        devA.apply(listOf(op1, op2, op3))

        // Device B applies in order [op3, op2, op1]
        // Note: OpApplier.apply internally sorts incoming batch by (origin, seq), so let's apply in separate batches
        devB.apply(listOf(op3))
        devB.apply(listOf(op2))
        devB.apply(listOf(op1))

        val listA = devA.history.observeRecent().first()
        val listB = devB.history.observeRecent().first()

        assertEquals(3, listA.size)
        assertEquals(3, listB.size)
        // Both devices must have the exact same rows in the exact same time order (3000, 2000, 1000)
        assertEquals(listOf(3000L, 2000L, 1000L), listA.map { it.createdAt })
        assertEquals(listOf(3000L, 2000L, 1000L), listB.map { it.createdAt })
        assertEquals(
            listA.map { Triple(it.originDevice, it.originId, it.cleanText) },
            listB.map { Triple(it.originDevice, it.originId, it.cleanText) },
        )
    }
}

