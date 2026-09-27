package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.data.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Serves the recommended models: fresh cache, else network, else stale cache, else the copy bundled in the APK. */
class RecommendationRepository(
    private val http: OkHttpClient,
    private val url: HttpUrl,
    private val settings: SettingsRepository,
    private val bundled: () -> String,
    private val clock: () -> Long,
) {
    suspend fun current(): Recommendation {
        val cached = settings.readCache(CACHE)?.let { (json, savedAt) -> parse(json)?.let { it to savedAt } }
        if (cached != null && clock() - cached.second < MAX_AGE_MS) return cached.first

        val fetched = fetch()
        if (fetched != null) {
            settings.writeCache(CACHE, fetched.first, clock())
            return fetched.second
        }
        return cached?.first ?: requireNotNull(parse(bundled())) { "bundled recommended.json is invalid" }
    }

    private suspend fun fetch(): Pair<String, Recommendation>? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string()
                parse(body)?.let { body to it }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun parse(json: String): Recommendation? =
        runCatching { lenient.decodeFromString<Recommendation>(json) }.getOrNull()

    companion object {
        const val CACHE = "recommended"
        val DEFAULT_URL: HttpUrl = "https://raw.githubusercontent.com/agopalareddy/umm/main/models/recommended.json".toHttpUrl()
        private const val MAX_AGE_MS = 24L * 3600 * 1000
        private val lenient = Json { ignoreUnknownKeys = true }
    }
}
