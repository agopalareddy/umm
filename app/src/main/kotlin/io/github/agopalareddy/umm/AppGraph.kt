package io.github.agopalareddy.umm

import android.app.Application
import android.content.Context
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeystoreCipher
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterClient

/** Manual dependency wiring; one instance per process, owned by [UmmApp]. */
class AppGraph(private val app: Application) {
    val apiKeyStore: ApiKeyStore by lazy {
        ApiKeyStore(app.getSharedPreferences("secure", Context.MODE_PRIVATE), KeystoreCipher())
    }
    val http by lazy { OpenRouterClient.defaultHttp() }
    val openRouter: OpenRouterApi by lazy {
        OpenRouterClient(OpenRouterClient.DEFAULT_BASE_URL, http) { apiKeyStore.get() }
    }
}

val Context.graph: AppGraph get() = (applicationContext as UmmApp).graph
