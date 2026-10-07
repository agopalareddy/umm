package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeySource
import io.github.agopalareddy.umm.core.auth.Pkce
import io.github.agopalareddy.umm.core.openrouter.Completion
import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import io.github.agopalareddy.umm.core.openrouter.Transcription
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInTest {
    private class FakeApi(var failWith: Exception? = null) : OpenRouterApi {
        val exchanged = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun exchangeAuthCode(code: String, codeVerifier: String): String {
            failWith?.let { throw it }
            exchanged += code to codeVerifier
            return "sk-or-from-signin"
        }
        override suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription = error("unused")
        override suspend fun complete(model: String, system: String, user: String, temperature: Double): Completion = error("unused")
        override suspend fun listModels(outputModalities: String?): List<ModelInfo> = emptyList()
        override suspend fun keyInfo(): KeyInfo = error("unused")
        override suspend fun zdrModels(): Set<String> = emptySet()
        override suspend fun blockedByDataPolicy(sttModel: String): Boolean? = null
    }

    private val api = FakeApi()
    private val keys = ApiKeyStore(InMemoryKeyValueStore(), PassThroughCipher)
    private val opened = CopyOnWriteArrayList<String>()
    private val results = CopyOnWriteArrayList<Boolean>()
    private val resultLatch = CountDownLatch(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun signIn(timeoutMs: Long = 60_000) = SignIn(
        openRouter = api,
        apiKeyStore = keys,
        openUrl = { opened += it },
        keyLabel = { "Umm · test" },
        scope = scope,
        onResult = { results += it; resultLatch.countDown() },
        timeoutMs = timeoutMs,
    )

    @After fun tearDown() = scope.cancel()

    private fun param(url: String, name: String) =
        url.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=') }
            .first { it.first == name }.second.let { URLDecoder.decode(it, Charsets.UTF_8) }

    private fun callbackUrl() = param(opened.last(), "callback_url")

    /** GETs [url]; returns the status code and body, or null when nothing is listening. */
    private fun get(url: String): Pair<Int, String>? = try {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 5000
        val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
        connection.responseCode to stream.readBytes().toString(Charsets.UTF_8)
    } catch (e: java.net.ConnectException) {
        null
    }

    @Test fun startOpensTheBrowserWithAPkceChallengeAndALocalCallback() {
        signIn().start()
        val url = opened.single()
        assertTrue(url, url.startsWith("https://openrouter.ai/auth?"))
        assertEquals("S256", param(url, "code_challenge_method"))
        assertEquals("Umm · test", param(url, "key_label"))
        assertEquals(43, param(url, "code_challenge").length)
        assertTrue(callbackUrl(), Regex("http://localhost:\\d+/callback").matches(callbackUrl()))
    }

    @Test fun theCallbackExchangesTheCodeWithTheMatchingVerifierAndSavesTheKey() {
        signIn().start()
        val challenge = param(opened.single(), "code_challenge")
        val (status, body) = get("${callbackUrl()}?code=abc")!!
        assertEquals(200, status)
        assertTrue(body, body.contains("Signed in"))
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(true), results.toList())
        val (code, verifier) = api.exchanged.single()
        assertEquals("abc", code)
        assertEquals(challenge, Pkce.challengeFor(verifier))
        assertEquals("sk-or-from-signin", keys.get())
        assertEquals(KeySource.SIGNED_IN, keys.source.value)
    }

    @Test fun aCancelledSignInSaysSoAndExchangesNothing() {
        signIn().start()
        val (status, body) = get("${callbackUrl()}?error=access_denied")!!
        assertEquals(200, status)
        assertTrue(body, body.contains("cancelled"))
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(false), results.toList())
        assertTrue(api.exchanged.isEmpty())
        assertNull(keys.get())
    }

    @Test fun anExchangeFailureSavesNothingAndTellsTheBrowser() {
        api.failWith = OpenRouterException.Timeout
        signIn().start()
        val (_, body) = get("${callbackUrl()}?code=abc")!!
        assertTrue(body, body.contains("Couldn't sign in"))
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(false), results.toList())
        assertNull(keys.get())
    }

    @Test fun otherPathsAreIgnoredAndTheSignInStillWaits() {
        signIn().start()
        assertEquals(404, get(callbackUrl().replace("/callback", "/favicon.ico"))!!.first)
        assertTrue(results.isEmpty())
        assertEquals(200, get("${callbackUrl()}?code=abc")!!.first)
    }

    @Test fun theListenerClosesAfterTheCallback() {
        signIn().start()
        val callback = callbackUrl()
        get("$callback?code=abc")
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
        assertNull("a second request should find nothing listening", get("$callback?code=again"))
        assertEquals(1, api.exchanged.size)
    }

    @Test fun theListenerClosesWhenNobodyComes() {
        signIn(timeoutMs = 300).start()
        val callback = callbackUrl()
        Thread.sleep(900)
        assertNull(get("$callback?code=late"))
        assertTrue(api.exchanged.isEmpty())
    }

    @Test fun startingAgainReplacesThePreviousAttempt() {
        val signIn = signIn()
        signIn.start()
        val first = callbackUrl()
        signIn.start()
        assertFalse(first == callbackUrl())
        assertNull(get("$first?code=old"))
        assertEquals(200, get("${callbackUrl()}?code=new")!!.first)
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
        assertEquals("new", api.exchanged.single().first)
    }
}
