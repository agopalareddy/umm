package io.github.agopalareddy.umm.core.stats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.UmmDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatsRepositoryTest {
    private val db = UmmDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val repo = StatsRepository(db)

    @After fun tearDown() = db.close()

    private fun entry(id: Long, ok: Boolean) = StatsEntry(id, 1_000, "com.whatsapp", CleanupLevel.LIGHT, 5, 4, 1, 3_000,
        if (ok) 900 else null, if (ok) 0.001 else null, "stt", "chat", ok)

    @Test fun recordsAndReplacesByHistoryId() = runTest {
        repo.record(entry(1, ok = false))
        repo.record(entry(1, ok = true)) // a successful retry replaces the failure
        repo.record(entry(2, ok = true))
        val all = repo.all()
        assertEquals(2, all.size)
        assertEquals(true, all.first { it.historyId == 1L }.succeeded)
        assertEquals(3_000L, repo.get(1)!!.audioMs)
    }
}
