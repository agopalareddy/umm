package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.auth.KeySource
import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.openrouter.Completion
import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.Transcription
import io.github.agopalareddy.umm.core.platform.XdgPaths
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopGraphKeyTest {
    @get:Rule val tmp = TemporaryFolder()

    private object UnusedApi : OpenRouterApi {
        override suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription = error("unused")
        override suspend fun complete(model: String, system: String, user: String, temperature: Double): Completion = error("unused")
        override suspend fun listModels(outputModalities: String?): List<ModelInfo> = emptyList()
        override suspend fun exchangeAuthCode(code: String, codeVerifier: String): String = error("unused")
        override suspend fun keyInfo(): KeyInfo = error("unused")
        override suspend fun zdrModels(): Set<String> = emptySet()
        override suspend fun blockedByDataPolicy(sttModel: String): Boolean? = null
    }

    private class RecordingStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        var writes = 0
        override fun getString(key: String) = values[key]
        override fun putString(key: String, value: String) { values[key] = value; writes++ }
        override fun remove(vararg keys: String) { keys.forEach(values::remove); writes++ }
    }

    private val graphs = mutableListOf<DesktopGraph>()

    @After fun tearDown() = graphs.forEach { it.close() }

    private fun graph(env: Map<String, String> = emptyMap(), secretStore: KeyValueStore? = null) =
        DesktopGraph(XdgPaths(env = emptyMap(), home = tmp.root), env, UnusedApi, secretStore = secretStore).also { graphs += it }

    private fun filesContaining(text: String) =
        tmp.root.walkTopDown().filter(File::isFile).filter { runCatching { it.readText() }.getOrDefault("").contains(text) }.toList()

    @Test fun noKeyring_keepsKeyInMemory() {
        val g = graph()
        assertFalse(g.keyringAvailable)
        g.apiKeyStore.set("sk-or-v1-secret-value")
        assertEquals("sk-or-v1-secret-value", g.apiKeyStore.get())
        assertEquals(emptyList<File>(), filesContaining("sk-or-v1-secret-value"))
        // Base64 of the key must not be on disk either.
        assertEquals(
            emptyList<File>(),
            filesContaining(java.util.Base64.getEncoder().encodeToString("sk-or-v1-secret-value".toByteArray())),
        )
    }

    @Test fun envKey_winsAndIsNotSaved() {
        val keyring = RecordingStore()
        val g = graph(mapOf("OPENROUTER_API_KEY" to "sk-or-env"), secretStore = keyring)
        assertEquals("sk-or-env", g.apiKeyStore.get())
        assertEquals(KeySource.DEVELOPER, g.apiKeyStore.source.value)
        assertEquals(0, keyring.writes)
        assertTrue(keyring.values.isEmpty())
    }

    @Test fun keyringStoresPastedKey() {
        val keyring = RecordingStore()
        val g = graph(secretStore = keyring)
        assertTrue(g.keyringAvailable)
        g.apiKeyStore.set("sk-or-pasted", KeySource.PASTED)
        assertNotNull(keyring.values["openrouter_key"])
        assertEquals("PASTED", keyring.values["openrouter_key_source"])
    }

    @Test fun keyringKeySurvivesANewGraph() {
        val keyring = RecordingStore()
        graph(secretStore = keyring).apiKeyStore.set("sk-or-persisted")
        assertEquals("sk-or-persisted", graph(secretStore = keyring).apiKeyStore.get())
    }

    @Test fun blankEnvKeyIsIgnored() {
        assertNull(graph(mapOf("OPENROUTER_API_KEY" to "  ")).apiKeyStore.get())
    }
}
