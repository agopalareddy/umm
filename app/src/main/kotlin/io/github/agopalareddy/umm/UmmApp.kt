package io.github.agopalareddy.umm

import android.app.Application

class UmmApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        seedDebugKey()
    }

    /** Debug builds start signed in with the developer's key from .env. */
    private fun seedDebugKey() {
        val debugKey = BuildConfig.DEBUG_OPENROUTER_API_KEY
        if (debugKey.isNotEmpty() && graph.apiKeyStore.get() == null) graph.apiKeyStore.set(debugKey)
    }
}
