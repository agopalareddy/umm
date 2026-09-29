package io.github.agopalareddy.umm.core.stats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.UmmDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    @Test fun recordDoesNothingWhenDisabled() = runTest {
        val disabled = StatsRepository(db, enabled = { false })
        disabled.record(entry(1, ok = true))
        assertEquals(emptyList<StatsEntry>(), disabled.all())
    }

    @Test fun deleteAllEmptiesTheTable() = runTest {
        repo.record(entry(1, ok = true))
        repo.record(entry(2, ok = true))
        repo.deleteAll()
        assertEquals(emptyList<StatsEntry>(), repo.all())
    }

    @Test fun deleteAllWorksWhileDisabled() = runTest {
        repo.record(entry(1, ok = true))
        val disabled = StatsRepository(db, enabled = { false })
        disabled.deleteAll()
        assertEquals(emptyList<StatsEntry>(), repo.all())
    }

    @Test fun observeAllEmitsEmptyAfterDelete() = runTest {
        repo.record(entry(1, ok = true))
        assertEquals(1, repo.observeAll().first().size)
        repo.deleteAll()
        assertEquals(0, repo.observeAll().first().size)
    }

    @Test fun observeDashboardUpdatesAfterRecordAndDelete() = runTest {
        val seen = mutableListOf<Boolean>()
        // One long-lived subscription, so re-emission after each write is what is proven.
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeDashboard(DashboardRange.ALL).collect { seen += it.hasData }
        }
        awaitSize(seen, 1)
        repo.record(entry(1, ok = true))
        awaitSize(seen, 2)
        repo.deleteAll()
        awaitSize(seen, 3)
        job.cancel()
        assertEquals(listOf(false, true, false), seen)
    }

    // The flow computes on Dispatchers.Default, so real time passes; poll instead of advancing the virtual clock.
    private suspend fun awaitSize(list: List<*>, size: Int) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (list.size < size) delay(10) }
    }
}
