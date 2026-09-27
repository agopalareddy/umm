package io.github.agopalareddy.umm.core.openrouter

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenRouterClient(
    private val baseUrl: HttpUrl,
    private val http: OkHttpClient,
    private val apiKey: suspend () -> String?,
) : OpenRouterApi {

    override suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription {
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("input_audio") {
                put("data", Base64.getEncoder().encodeToString(audio))
                put("format", format)
            }
            if (language != null) put("language", language)
        }
        val response = json.decodeFromString<TranscriptionResponse>(post("audio/transcriptions", body, authenticated = true))
        return Transcription(response.text, response.usage?.cost)
    }

    override suspend fun complete(model: String, system: String, user: String, temperature: Double): Completion {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("temperature", temperature)
        }
        val response = json.decodeFromString<ChatResponse>(post("chat/completions", body, authenticated = true))
        return Completion(response.choices.firstOrNull()?.message?.content.orEmpty(), response.usage?.cost)
    }

    override suspend fun listModels(outputModalities: String?): List<ModelInfo> {
        val url = endpoint("models").newBuilder()
            .apply { if (outputModalities != null) addQueryParameter("output_modalities", outputModalities) }
            .build()
        val response = json.decodeFromString<ModelsResponse>(execute(Request.Builder().url(url).get(), authenticated = true))
        return response.data.map {
            ModelInfo(it.id, it.name, it.created, it.pricing?.prompt, it.pricing?.completion)
        }
    }

    override suspend fun exchangeAuthCode(code: String, codeVerifier: String): String {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", codeVerifier)
            put("code_challenge_method", "S256")
        }
        return json.decodeFromString<AuthKeyResponse>(post("auth/keys", body, authenticated = false)).key
    }

    override suspend fun keyInfo(): KeyInfo {
        val data = json.decodeFromString<KeyResponse>(execute(Request.Builder().url(endpoint("key")).get(), authenticated = true)).data
        return KeyInfo(data.label, data.usage, data.usageMonthly, data.limit, data.limitRemaining, data.limitReset)
    }

    private fun endpoint(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    private suspend fun post(path: String, body: JsonObject, authenticated: Boolean): String =
        execute(
            Request.Builder().url(endpoint(path)).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)),
            authenticated,
        )

    private suspend fun execute(builder: Request.Builder, authenticated: Boolean): String {
        if (authenticated) {
            val key = apiKey() ?: throw OpenRouterException.MissingKey
            builder.header("Authorization", "Bearer $key")
        }
        builder.header("HTTP-Referer", REFERER).header("X-Title", TITLE)
        val request = builder.build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        throw OpenRouterException.forStatus(response.code, text, response.header("Retry-After"))
                    }
                    text
                }
            } catch (e: SocketTimeoutException) {
                throw OpenRouterException.Timeout
            } catch (e: IOException) {
                throw OpenRouterException.Network(e)
            }
        }
    }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://openrouter.ai/api/v1".toHttpUrl()
        private const val REFERER = "https://github.com/agopalareddy/umm"
        private const val TITLE = "Umm"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
