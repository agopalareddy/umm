package io.github.agopalareddy.umm.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.agopalareddy.umm.UmmApp
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? UmmApp
        val syncEngine = app?.graph?.syncEngine
        if (syncEngine != null) {
            runCatching { syncEngine.syncNow() }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME_LAN = "umm-sync-lan"
        const val WORK_NAME_RELAY = "umm-sync-relay"

        fun schedule(
            context: Context,
            isPaired: Boolean,
            workManager: WorkManager = WorkManager.getInstance(context),
        ) {
            if (isPaired) {
                val lanConstraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
                val lanRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(lanConstraints)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_LAN,
                    ExistingPeriodicWorkPolicy.KEEP,
                    lanRequest,
                )

                val relayConstraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val relayRequest = PeriodicWorkRequestBuilder<SyncWorker>(60, TimeUnit.MINUTES)
                    .setConstraints(relayConstraints)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_RELAY,
                    ExistingPeriodicWorkPolicy.KEEP,
                    relayRequest,
                )
            } else {
                workManager.cancelUniqueWork(WORK_NAME_LAN)
                workManager.cancelUniqueWork(WORK_NAME_RELAY)
            }
        }
    }
}
