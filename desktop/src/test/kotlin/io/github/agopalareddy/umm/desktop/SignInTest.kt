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
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInTest {
    private class FakeApi(var failWith: Exception? = null) : OpenRouterApi {
        val exchanged = mutableListOf<Pair<String, String>>()
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
    private val opened = mutableListOf<String>()
    private val signIn = SignIn(api, keys, openUrl = { opened += it }, keyLabel = { "Umm · test" })

    private fun param(url: String, name: String) =
        url.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=') }
            .first { it.first == name }.second.let { URLDecoder.decode(it, Charsets.UTF_8) }

    @Test fun startOpensTheBrowserWithAPkceChallenge() {
        signIn.start()
        val url = opened.single()
        assertTrue(url, url.startsWith("https://openrouter.ai/auth?"))
        assertEquals("S256", param(url, "code_challenge_method"))
        assertEquals("Umm · test", param(url, "key_label"))
        assertEquals(43, param(url, "code_challenge").length)
    }

    @Test fun finishExchangesTheCodeWithTheMatchingVerifierAndSavesTheKey() = runBlocking {
        signIn.start()
        val challenge = param(opened.single(), "code_challenge")
        assertTrue(signIn.finish("umm://oauth?code=abc"))
        val (code, verifier) = api.exchanged.single()
        assertEquals("abc", code)
        assertEquals(challenge, Pkce.challengeFor(verifier))
        assertEquals("sk-or-from-signin", keys.get())
        assertEquals(KeySource.SIGNED_IN, keys.source.value)
    }

    @Test fun aLinkWithoutAPendingSignInIsRejected() = runBlocking {
        assertFalse(signIn.finish("umm://oauth?code=abc"))
        assertTrue(api.exchanged.isEmpty())
        assertNull(keys.get())
    }

    @Test fun aVerifierWorksOnlyOnce() = runBlocking {
        signIn.start()
        assertTrue(signIn.finish("umm://oauth?code=abc"))
        assertFalse(signIn.finish("umm://oauth?code=abc"))
        assertEquals(1, api.exchanged.size)
    }

    @Test fun notAnAuthLinkIsRejectedAndKeepsThePendingSignIn() = runBlocking {
        signIn.start()
        assertFalse(signIn.finish("https://example.com/?code=abc"))
        assertTrue(signIn.finish("umm://oauth?code=abc"))
    }

    @Test fun anExchangeFailureSavesNothing() = runBlocking {
        api.failWith = OpenRouterException.Timeout
        signIn.start()
        assertFalse(signIn.finish("umm://oauth?code=abc"))
        assertNull(keys.get())
    }
}
