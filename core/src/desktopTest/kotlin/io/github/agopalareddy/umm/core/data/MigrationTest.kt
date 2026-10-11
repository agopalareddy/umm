package io.github.agopalareddy.umm.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.sync.SyncMeta
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val schemaDir: Path = if (File("core/schemas").exists()) Path.of("core/schemas") else Path.of("schemas")

    @Test
    fun stampsExistingRows() = runTest {
        val dbFile = tmp.newFile("stamps_test.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = schemaDir,
            databasePath = dbFile.toPath(),
            driver = BundledSQLiteDriver(),
            databaseClass = UmmDatabase::class,
        )

        val v2 = helper.createDatabase(2)
        v2.prepare("INSERT INTO history (id, createdAt, packageName, level, script, language, rawText, cleanText, status, audioPath, error) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use {
            it.bindLong(1, 1)
            it.bindLong(2, 1000)
            it.bindText(3, "com.example.app1")
            it.bindText(4, "LIGHT")
            it.bindText(5, "LATIN")
            it.bindText(6, "auto")
            it.bindText(7, "raw1")
            it.bindText(8, "clean1")
            it.bindText(9, "DONE")
            it.bindNull(10)
            it.bindNull(11)
            it.step()
        }
        v2.prepare("INSERT INTO history (id, createdAt, packageName, level, script, language, rawText, cleanText, status, audioPath, error) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use {
            it.bindLong(1, 2)
            it.bindLong(2, 2000)
            it.bindText(3, "com.example.app2")
            it.bindText(4, "LIGHT")
            it.bindText(5, "LATIN")
            it.bindText(6, "auto")
            it.bindText(7, "raw2")
            it.bindText(8, "clean2")
            it.bindText(9, "DONE")
            it.bindNull(10)
            it.bindNull(11)
            it.step()
        }
        v2.prepare("INSERT INTO dictation_stats (historyId, createdAt, packageName, level, rawWords, cleanWords, fillerWords, audioMs, latencyMs, costUsd, sttModel, cleanupModel, succeeded) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use {
            it.bindLong(1, 1)
            it.bindLong(2, 1000)
            it.bindText(3, "com.example.app1")
            it.bindText(4, "LIGHT")
            it.bindLong(5, 5)
            it.bindLong(6, 4)
            it.bindLong(7, 1)
            it.bindLong(8, 2000)
            it.bindLong(9, 300)
            it.bindDouble(10, 0.001)
            it.bindText(11, "stt-model")
            it.bindText(12, "cleanup-model")
            it.bindLong(13, 1)
            it.step()
        }
        v2.close()

        val v3 = helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3))
        v3.close()

        val db = buildUmmDatabase(dbFile)
        try {
            val deviceId = SyncMeta(db).deviceId()
            val h1 = db.historyDao().get(1)!!
            assertEquals(deviceId, h1.originDevice)
            assertEquals(1L, h1.originId)
            assertNull(h1.appLabel)

            val h2 = db.historyDao().get(2)!!
            assertEquals(deviceId, h2.originDevice)
            assertEquals(2L, h2.originId)
            assertNull(h2.appLabel)

            val s1 = db.statsDao().get(deviceId, 1)!!
            assertEquals(deviceId, s1.originDevice)
            assertEquals(1L, s1.historyId)
            assertNull(s1.appLabel)
        } finally {
            db.close()
        }
    }

    @Test
    fun pendingRowWithAudioSurvives() = runTest {
        val dbFile = tmp.newFile("pending_test.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = schemaDir,
            databasePath = dbFile.toPath(),
            driver = BundledSQLiteDriver(),
            databaseClass = UmmDatabase::class,
        )

        val v2 = helper.createDatabase(2)
        v2.prepare("INSERT INTO history (id, createdAt, packageName, level, script, language, rawText, cleanText, status, audioPath, error) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use {
            it.bindLong(1, 1)
            it.bindLong(2, 1000)
            it.bindText(3, "com.example.app")
            it.bindText(4, "LIGHT")
            it.bindText(5, "LATIN")
            it.bindText(6, "auto")
            it.bindNull(7)
            it.bindNull(8)
            it.bindText(9, "PENDING")
            it.bindText(10, "/tmp/pending-audio.wav")
            it.bindNull(11)
            it.step()
        }
        v2.close()

        val v3 = helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3))
        v3.close()

        val db = buildUmmDatabase(dbFile)
        try {
            val h = db.historyDao().get(1)!!
            assertEquals(HistoryStatus.PENDING, h.status)
            assertEquals("/tmp/pending-audio.wav", h.audioPath)
        } finally {
            db.close()
        }
    }

    @Test
    fun version4AddsTheDeleteRecordsAndKeepsTheRest() = runTest {
        val dbFile = tmp.newFile("deletes_test.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = schemaDir,
            databasePath = dbFile.toPath(),
            driver = BundledSQLiteDriver(),
            databaseClass = UmmDatabase::class,
        )

        val v3 = helper.createDatabase(3)
        v3.prepare("INSERT INTO sync_meta (key, value) VALUES (?, ?)").use {
            it.bindText(1, SyncMeta.KEY_DEVICE_ID)
            it.bindText(2, "0123456789ABCDEFGHJKMNPQRS")
            it.step()
        }
        v3.prepare("INSERT INTO sync_ops (originDevice, seq, hlc, type, payload) VALUES (?, ?, ?, ?, ?)").use {
            it.bindText(1, "0123456789ABCDEFGHJKMNPQRS")
            it.bindLong(2, 1)
            it.bindLong(3, 1000)
            it.bindText(4, "SettingSet")
            it.bindText(5, "{\"t\":\"SettingSet\",\"key\":\"default_level\",\"value\":\"LIGHT\"}")
            it.step()
        }
        v3.close()

        val v4 = helper.runMigrationsAndValidate(4, listOf(MIGRATION_2_3))
        v4.close()

        val db = buildUmmDatabase(dbFile)
        try {
            assertEquals("0123456789ABCDEFGHJKMNPQRS", SyncMeta(db).deviceId())
            assertEquals(1, db.syncDao().allOps().size)
            assertTrue(db.syncDao().allDeletes().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun createsDeviceIdOnce() = runTest {
        val dbFile = tmp.newFile("device_id_test.db")
        val db1 = buildUmmDatabase(dbFile)
        val id1 = try {
            val id = SyncMeta(db1).deviceId()
            assertEquals(26, id.length)
            assertTrue(id.matches(Regex("^[0-9A-HJ-NP-Z]{26}$")))
            id
        } finally {
            db1.close()
        }

        val db2 = buildUmmDatabase(dbFile)
        val id2 = try {
            SyncMeta(db2).deviceId()
        } finally {
            db2.close()
        }
        assertEquals(id1, id2)
    }

    @Test
    fun addsMissingDesktopSeeds() = runTest {
        val dbFile = tmp.newFile("seeds_test.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = schemaDir,
            databasePath = dbFile.toPath(),
            driver = BundledSQLiteDriver(),
            databaseClass = UmmDatabase::class,
        )

        val v2 = helper.createDatabase(2)
        // Insert user assignment for gedit as EMAIL (its default seed is NOTES)
        v2.prepare("INSERT INTO app_assignments (packageName, category) VALUES (?, ?)").use {
            it.bindText(1, "org.gnome.gedit")
            it.bindText(2, Category.EMAIL.name)
            it.step()
        }
        // Ensure kate is NOT present
        v2.close()

        val v3 = helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3))
        v3.close()

        val db = buildUmmDatabase(dbFile)
        try {
            val categoryRepo = CategoryRepository(db)
            assertEquals(Category.NOTES, categoryRepo.categoryFor("org.kde.kate"))
            assertEquals(Category.EMAIL, categoryRepo.categoryFor("org.gnome.gedit"))
        } finally {
            db.close()
        }
    }
}
