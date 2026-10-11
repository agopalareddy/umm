package io.github.agopalareddy.umm

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.agopalareddy.umm.core.auth.KeySource
import io.github.agopalareddy.umm.sync.SyncWorker
import kotlinx.coroutines.launch

class UmmApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        seedDebugKey()
        graph.syncEngine.start()
        schedulePeriodicSync()
        observeForegroundSync()
    }

    private fun schedulePeriodicSync() {
        graph.appScope.launch {
            graph.groupStore.state.collect { state ->
                SyncWorker.schedule(this@UmmApp, isPaired = state != null)
            }
        }
    }

    private fun observeForegroundSync() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (graph.groupStore.state.value != null) {
                        graph.appScope.launch {
                            runCatching { graph.syncEngine.syncNow() }
                        }
                    }
                }
            })
        }
    }

    /** Debug builds start signed in with the developer's key from .env. */
    private fun seedDebugKey() {
        val debugKey = BuildConfig.DEBUG_OPENROUTER_API_KEY
        if (debugKey.isNotEmpty()) {
            runCatching {
                if (graph.apiKeyStore.get() == null) graph.apiKeyStore.set(debugKey, KeySource.DEVELOPER)
            }
        }
    }
}
