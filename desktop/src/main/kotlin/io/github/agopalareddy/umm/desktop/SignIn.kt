package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.AuthUrl
import io.github.agopalareddy.umm.core.auth.KeySource
import io.github.agopalareddy.umm.core.auth.Pkce
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.linux.UmmLink
import kotlin.coroutines.cancellation.CancellationException

/**
 * OpenRouter's PKCE sign-in: [start] opens the browser, the callback page opens `umm://oauth?code=…`, the running
 * instance receives that link and [finish] trades the code for a key. The verifier is kept in memory only; a link
 * that arrives with no sign-in pending (Umm was restarted meanwhile) is refused.
 */
class SignIn(
    private val openRouter: OpenRouterApi,
    private val apiKeyStore: ApiKeyStore,
    private val openUrl: (String) -> Unit,
    private val keyLabel: () -> String,
) {
    @Volatile private var verifier: String? = null

    fun start() {
        val fresh = Pkce.newVerifier()
        verifier = fresh
        openUrl(AuthUrl.build(Pkce.challengeFor(fresh), keyLabel = keyLabel()))
    }

    /** True when [url] completed a pending sign-in and the new key is saved. */
    suspend fun finish(url: String): Boolean {
        val code = UmmLink.authCode(url) ?: return false
        val pending = verifier ?: return false
        verifier = null
        return try {
            apiKeyStore.set(openRouter.exchangeAuthCode(code, pending), KeySource.SIGNED_IN)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
