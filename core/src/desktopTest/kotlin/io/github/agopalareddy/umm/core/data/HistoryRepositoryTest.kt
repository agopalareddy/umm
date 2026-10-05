package io.github.agopalareddy.umm.core.data

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val db = inMemoryUmmDatabase()
    private var now = 1_000_000_000L
    private val repo = HistoryRepository(db) { now }
    private val day = 24L * 3600 * 1000

    @After fun tearDown() = db.close()

    private fun audio(): File = tmp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private suspend fun create(file: File = audio()) =
        repo.createPending("com.whatsapp", CleanupLevel.LIGHT, ScriptPreference.LATIN, "auto", file.path)

    @Test fun keepsOnly50() = runTest {
        val files = List(55) { audio() }
        val ids = files.map { create(it) }
        val recent = repo.observeRecent().first()
        assertEquals(50, recent.size)
        assertEquals(ids.takeLast(50).reversed(), recent.map { it.id })
        ids.take(5).forEach { assertNull(repo.get(it)) }
        files.take(5).forEach { assertFalse(it.exists()) }
        files.drop(5).forEach { assertTrue(it.exists()) }
    }

    @Test fun markDoneDeletesAudio() = runTest {
        val file = audio()
        val id = create(file)
        repo.markDone(id, "raw text", "Clean text.")
        val item = repo.get(id)!!
        assertEquals(HistoryStatus.DONE, item.status)
        assertEquals("raw text", item.rawText)
        assertEquals("Clean text.", item.cleanText)
        assertNull(item.audioPath)
        assertFalse(file.exists())
    }

    @Test fun markFailedKeepsAudio() = runTest {
        val file = audio()
        val id = create(file)
        repo.markFailed(id, "NETWORK")
        val item = repo.get(id)!!
        assertEquals(HistoryStatus.FAILED, item.status)
        assertEquals(file.path, item.audioPath)
        assertTrue(file.exists())
    }

    @Test fun markCleanupFailedKeepsRawAndDeletesAudio() = runTest {
        val file = audio()
        val id = create(file)
        repo.markCleanupFailed(id, "raw", "MODEL_UNAVAILABLE")
        val item = repo.get(id)!!
        assertEquals(HistoryStatus.CLEANUP_FAILED, item.status)
        assertEquals("raw", item.rawText)
        assertFalse(file.exists())
    }

    @Test fun updateCleanMarksDone() = runTest {
        val id = create()
        repo.markCleanupFailed(id, "raw", "x")
        repo.updateClean(id, CleanupLevel.POLISHED, "Polished.")
        val item = repo.get(id)!!
        assertEquals(HistoryStatus.DONE, item.status)
        assertEquals(CleanupLevel.POLISHED, item.level)
        assertEquals("Polished.", item.cleanText)
    }

    @Test fun purgeDeletesAudioOlderThan7Days() = runTest {
        val oldFile = audio()
        now -= 8 * day
        val old = create(oldFile)
        repo.markFailed(old, "NETWORK")
        now += 2 * day
        val recentFile = audio()
        val recent = create(recentFile)
        repo.markFailed(recent, "NETWORK")
        now += 6 * day

        repo.purgeExpiredAudio()

        assertFalse(oldFile.exists())
        assertNull(repo.get(old)!!.audioPath)
        assertTrue(recentFile.exists())
        assertEquals(recentFile.path, repo.get(recent)!!.audioPath)
    }

    @Test fun deleteAndClearAllRemoveAudio() = runTest {
        val a = audio(); val b = audio()
        val idA = create(a); create(b)
        repo.delete(idA)
        assertNull(repo.get(idA))
        assertFalse(a.exists())
        repo.clearAll()
        assertEquals(0, repo.observeRecent().first().size)
        assertFalse(b.exists())
    }
}
