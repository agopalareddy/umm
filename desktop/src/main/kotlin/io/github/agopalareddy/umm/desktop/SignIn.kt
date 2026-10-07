package io.github.agopalareddy.umm.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.AuthUrl
import io.github.agopalareddy.umm.core.auth.KeySource
import io.github.agopalareddy.umm.core.auth.Pkce
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * OpenRouter's PKCE sign-in for the desktop. [start] listens on a free local port and opens the browser; OpenRouter
 * sends the browser back to `http://localhost:<port>/callback?code=…` (it allows localhost callbacks on any port),
 * and the one-time code is traded for a key with the verifier that never left this process. The listener serves one
 * request and closes, or closes after [timeoutMs] if nobody comes back.
 */
class SignIn(
    private val openRouter: OpenRouterApi,
    private val apiKeyStore: ApiKeyStore,
    private val openUrl: (String) -> Unit,
    private val keyLabel: () -> String,
    private val scope: CoroutineScope,
    /** Called once per attempt that reached the callback: true when a key was saved. */
    private val onResult: (Boolean) -> Unit,
    private val timeoutMs: Long = 5 * 60_000,
) {
    private var pending: HttpServer? = null

    @Synchronized
    fun start() {
        stopPending()
        val verifier = Pkce.newVerifier()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/callback") { exchange -> onCallback(exchange, server, verifier) }
        server.start()
        pending = server
        openUrl(
            AuthUrl.build(
                challenge = Pkce.challengeFor(verifier),
                callback = "http://localhost:${server.address.port}/callback",
                keyLabel = keyLabel(),
            ),
        )
        scope.launch {
            delay(timeoutMs)
            expire(server)
        }
    }

    @Synchronized
    private fun expire(server: HttpServer) {
        if (pending === server) stopPending()
    }

    private fun stopPending() {
        pending?.stop(0)
        pending = null
    }

    private fun onCallback(exchange: HttpExchange, server: HttpServer, verifier: String) {
        val query = exchange.requestURI.rawQuery.orEmpty().split('&')
            .filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
        val code = query["code"]?.takeIf { it.isNotEmpty() }
        val signedIn = code != null && runBlocking { exchange(code, verifier) }
        val message = when {
            signedIn -> "Signed in. You can close this tab and return to Umm."
            code == null -> "Sign-in was cancelled. Go back to Umm and try again."
            else -> "Couldn't sign in. Go back to Umm and try again."
        }
        respond(exchange, message)
        // The listener has served its one request.
        synchronized(this) { if (pending === server) pending = null }
        server.stop(0)
        onResult(signedIn)
    }

    private suspend fun exchange(code: String, verifier: String): Boolean = try {
        apiKeyStore.set(openRouter.exchangeAuthCode(code, verifier), KeySource.SIGNED_IN)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun respond(exchange: HttpExchange, message: String) {
        val body = PAGE.replace("@MESSAGE@", message).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private companion object {
        const val PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Umm sign-in</title>
<style>body{font:17px/1.5 system-ui,sans-serif;margin:0;min-height:100vh;display:grid;place-items:center;text-align:center;color-scheme:light dark}</style>
</head><body><main><h1>Umm</h1><p>@MESSAGE@</p></main></body></html>"""
    }
}
