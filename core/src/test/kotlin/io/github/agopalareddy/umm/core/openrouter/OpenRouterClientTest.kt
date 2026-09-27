package io.github.agopalareddy.umm.core.openrouter

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenRouterClientTest {
    private val server = MockWebServer()
    private var key: String? = "k"

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun client(http: OkHttpClient = OpenRouterClient.defaultHttp()) =
        OpenRouterClient(server.url("/"), http) { key }

    private fun enqueue(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        val b = MockResponse.Builder().code(code).body(body)
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        server.enqueue(b.build())
    }

    private fun RecordedRequest.json(): JsonObject =
        Json.parseToJsonElement(body!!.utf8()).jsonObject

    @Test fun transcribeSendsBase64AudioAndParsesText() = runTest {
        enqueue("""{"text":"hello world","usage":{"cost":0.0005}}""")
        val result = client().transcribe("m/stt", byteArrayOf(1, 2, 3), "m4a", null)
        assertEquals(Transcription("hello world", 0.0005), result)
        val req = server.takeRequest()
        assertEquals("/audio/transcriptions", req.url.encodedPath)
        val body = req.json()
        assertEquals("m/stt", body["model"]!!.jsonPrimitive.content)
        val audio = body["input_audio"]!!.jsonObject
        assertEquals("AQID", audio["data"]!!.jsonPrimitive.content)
        assertEquals("m4a", audio["format"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("language"))
    }

    @Test fun transcribeSendsLanguageWhenFixed() = runTest {
        enqueue("""{"text":"namaste"}""")
        client().transcribe("m/stt", byteArrayOf(1), "m4a", "hi")
        assertEquals("hi", server.takeRequest().json()["language"]!!.jsonPrimitive.content)
    }

    @Test fun completeSendsSystemAndUserMessages() = runTest {
        enqueue("""{"choices":[{"message":{"content":"Clean."}}],"usage":{"cost":0.00012}}""")
        val out = client().complete("m/chat", "sys", "usr", 0.2)
        assertEquals(Completion("Clean.", 0.00012), out)
        val req = server.takeRequest()
        assertEquals("/chat/completions", req.url.encodedPath)
        val body = req.json()
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("sys", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("usr", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun listModelsPassesOutputModalityAndParses() = runTest {
        enqueue("""{"data":[{"id":"a/b","name":"B","created":1750000000,"pricing":{"prompt":"0.1","completion":"0.2"}}]}""")
        val models = client().listModels("transcription")
        assertEquals(listOf(ModelInfo("a/b", "B", 1750000000, "0.1", "0.2")), models)
        val req = server.takeRequest()
        assertEquals("/models", req.url.encodedPath)
        assertEquals("transcription", req.url.queryParameter("output_modalities"))
    }

    @Test fun exchangeAuthCodePostsCodeAndVerifier() = runTest {
        key = null
        enqueue("""{"key":"sk-or-xyz"}""")
        assertEquals("sk-or-xyz", client().exchangeAuthCode("c0de", "verif"))
        val req = server.takeRequest()
        assertEquals("/auth/keys", req.url.encodedPath)
        val body = req.json()
        assertEquals("c0de", body["code"]!!.jsonPrimitive.content)
        assertEquals("verif", body["code_verifier"]!!.jsonPrimitive.content)
        assertEquals("S256", body["code_challenge_method"]!!.jsonPrimitive.content)
        assertNull(req.headers["Authorization"])
    }

    @Test fun keyInfoParsesUsageAndLimit() = runTest {
        enqueue("""{"data":{"label":"Umm","usage":1.25,"usage_monthly":0.5,"limit":10,"limit_remaining":8.75,"limit_reset":"monthly"}}""")
        assertEquals(KeyInfo("Umm", 1.25, 0.5, 10.0, 8.75, "monthly"), client().keyInfo())
        val req = server.takeRequest()
        assertEquals("/key", req.url.encodedPath)
        assertEquals("Bearer k", req.headers["Authorization"])
    }

    @Test fun keyInfoWithoutLimit() = runTest {
        enqueue("""{"data":{"label":"sk-or-v1-abc","usage":0,"limit":null,"limit_remaining":null}}""")
        assertEquals(KeyInfo("sk-or-v1-abc", 0.0, null, null, null, null), client().keyInfo())
    }

    @Test fun sendsAuthAndAttributionHeaders() = runTest {
        enqueue("""{"text":"x"}""")
        client().transcribe("m", byteArrayOf(1), "m4a", null)
        val req = server.takeRequest()
        assertEquals("Bearer k", req.headers["Authorization"])
        assertEquals("https://github.com/agopalareddy/umm", req.headers["HTTP-Referer"])
        assertEquals("Umm", req.headers["X-Title"])
    }

    private suspend fun errorFor(code: Int, vararg headers: Pair<String, String>): OpenRouterException {
        enqueue("""{"error":{"message":"nope"}}""", code, *headers)
        try {
            client().transcribe("m", byteArrayOf(1), "m4a", null)
        } catch (e: OpenRouterException) {
            return e
        }
        fail("expected OpenRouterException for $code")
        error("unreachable")
    }

    @Test fun mapsHttpErrors() = runTest {
        assertEquals(OpenRouterException.Unauthorized, errorFor(401))
        assertEquals(OpenRouterException.InsufficientCredits, errorFor(402))
        assertEquals(OpenRouterException.RateLimited(7), errorFor(429, "Retry-After" to "7"))
        assertEquals(OpenRouterException.RateLimited(null), errorFor(429))
        assertEquals(OpenRouterException.ModelUnavailable(404), errorFor(404))
        assertEquals(OpenRouterException.ModelUnavailable(500), errorFor(500))
        assertEquals(OpenRouterException.ModelUnavailable(503), errorFor(503))
        val bad = errorFor(400)
        assertTrue(bad is OpenRouterException.Unexpected && bad.status == 400 && bad.body.contains("nope"))
    }

    @Test fun missingKeyThrowsWithoutCalling() = runTest {
        key = null
        try {
            client().transcribe("m", byteArrayOf(1), "m4a", null)
            fail("expected MissingKey")
        } catch (e: OpenRouterException) {
            assertEquals(OpenRouterException.MissingKey, e)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun socketTimeoutMapsToTimeout() = runTest {
        server.enqueue(MockResponse.Builder().body("{}").headersDelay(3, TimeUnit.SECONDS).build())
        val http = OpenRouterClient.defaultHttp().newBuilder().readTimeout(1, TimeUnit.SECONDS).build()
        try {
            client(http).transcribe("m", byteArrayOf(1), "m4a", null)
            fail("expected Timeout")
        } catch (e: OpenRouterException) {
            assertEquals(OpenRouterException.Timeout, e)
        }
    }
}
