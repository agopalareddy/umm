package io.github.agopalareddy.umm.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun notPairedEnqueuesNoPeriodicWork() {
        SyncWorker.schedule(context, isPaired = false)

        val lanWorks = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_LAN).get()
        val relayWorks = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_RELAY).get()

        assertTrue(lanWorks.isEmpty())
        assertTrue(relayWorks.isEmpty())
    }

    @Test
    fun pairedEnqueuesPeriodicWorksWithRequiredConstraints() {
        SyncWorker.schedule(context, isPaired = true)

        val lanWorks = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_LAN).get()
        assertEquals(1, lanWorks.size)
        assertEquals(NetworkType.UNMETERED, lanWorks[0].constraints.requiredNetworkType)

        val relayWorks = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_RELAY).get()
        assertEquals(1, relayWorks.size)
        assertEquals(NetworkType.CONNECTED, relayWorks[0].constraints.requiredNetworkType)
    }

    @Test
    fun leavingCancelsPeriodicWorks() {
        SyncWorker.schedule(context, isPaired = true)

        var lanWorks = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_LAN).get()
        var relayWorks = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_RELAY).get()
        assertEquals(1, lanWorks.size)
        assertEquals(1, relayWorks.size)

        SyncWorker.schedule(context, isPaired = false)

        lanWorks = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_LAN).get()
        relayWorks = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_RELAY).get()
        assertTrue(lanWorks.all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(relayWorks.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun workerReturnsSuccess() = kotlinx.coroutines.test.runTest {
        val worker = androidx.work.testing.TestListenableWorkerBuilder<SyncWorker>(context).build()
        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }
}
