package io.github.agopalareddy.umm.core.policy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.openrouter.Completion
import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import io.github.agopalareddy.umm.core.openrouter.Transcription
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelCatalogTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 100L * DAY
    private val calls = mutableListOf<String?>()
    private var fail = false
    private val models = listOf(ModelInfo("a/b", "B", 1, "0.1", "0.2"))

    private val api = object : OpenRouterApi {
        override suspend fun listModels(outputModalities: String?): List<ModelInfo> {
            calls += outputModalities
            if (fail) throw OpenRouterException.Timeout
            return models
        }
        override suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription = error("unused")
        override suspend fun complete(model: String, system: String, user: String, temperature: Double): Completion = error("unused")
        override suspend fun exchangeAuthCode(code: String, codeVerifier: String): String = error("unused")
        override suspend fun keyInfo(): KeyInfo = error("unused")
        override suspend fun zdrModels(): Set<String> = error("unused")
        override suspend fun blockedByDataPolicy(sttModel: String): Boolean? = error("unused")
    }

    private fun TestScope.catalog() = ModelCatalog(
        api,
        SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }),
    ) { now }

    @Test fun cachesFor24Hours() = runTest {
        val catalog = catalog()
        assertEquals(models, catalog.sttModels())
        now += DAY - 1
        assertEquals(models, catalog.sttModels())
        assertEquals(listOf<String?>("transcription"), calls)
        now += 2
        catalog.sttModels()
        assertEquals(2, calls.size)
    }

    @Test fun chatModelsUseSeparateCache() = runTest {
        val catalog = catalog()
        catalog.sttModels()
        catalog.chatModels()
        assertEquals(listOf<String?>("transcription", null), calls)
    }

    @Test fun failureReturnsStaleCacheOrNull() = runTest {
        val catalog = catalog()
        fail = true
        assertNull(catalog.sttModels())
        fail = false
        catalog.sttModels()
        now += 2 * DAY
        fail = true
        assertEquals(models, catalog.sttModels())
    }

    private companion object {
        const val DAY = 24L * 3600 * 1000
    }
}
