package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.openrouter.Completion
import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.Transcription
import io.github.agopalareddy.umm.core.platform.XdgPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictateCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeApi : OpenRouterApi {
        val formats = mutableListOf<String>()
        override suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription {
            formats += format
            return Transcription("um hello", 0.0003)
        }
        override suspend fun complete(model: String, system: String, user: String, temperature: Double) = Completion("Hello", 0.0001)
        override suspend fun listModels(outputModalities: String?): List<ModelInfo> = emptyList()
        override suspend fun exchangeAuthCode(code: String, codeVerifier: String): String = error("unused")
        override suspend fun keyInfo(): KeyInfo = error("unused")
        override suspend fun zdrModels(): Set<String> = emptySet()
        override suspend fun blockedByDataPolicy(sttModel: String): Boolean? = null
    }

    private val api = FakeApi()
    private val bytes = ByteArrayOutputStream()
    private val out = PrintStream(bytes, true)
    private val graphs = mutableListOf<DesktopGraph>()

    @After fun tearDown() = graphs.forEach { it.close() }

    private fun graph(key: String? = "sk-or-test"): DesktopGraph {
        val env = buildMap { if (key != null) put("OPENROUTER_API_KEY", key) }
        return DesktopGraph(XdgPaths(env = emptyMap(), home = tmp.root), env, api, recommendationsUrl = UNREACHABLE)
            .also { graphs += it }
    }

    private fun speech() = writeWav(tmp.newFile("speech.wav"), 200 to 500, 8000 to 1000, 200 to 4000)

    @Test fun missingKey_exitsWithMessage() = runBlocking {
        assertEquals(2, dictate(DictateArgs(speech(), null), graph(key = null), out))
        assertTrue(bytes.toString(), bytes.toString().contains("OPENROUTER_API_KEY"))
    }

    @Test fun missingFile_exitsWithMessage() = runBlocking {
        val missing = File(tmp.root, "nope.wav")
        assertEquals(2, dictate(DictateArgs(missing, null), graph(), out))
        assertTrue(bytes.toString(), bytes.toString().contains(missing.path))
    }

    @Test fun unreadableWav_exitsWithMessage() = runBlocking {
        val text = tmp.newFile("notes.wav").apply { writeText("not audio") }
        assertEquals(2, dictate(DictateArgs(text, null), graph(), out))
        assertTrue(bytes.toString(), bytes.toString().contains(text.path))
    }

    @Test fun silentWav_reportsNoSpeech() = runBlocking {
        val silent = writeWav(tmp.newFile("silent.wav"), 0 to 2000)
        assertEquals(1, dictate(DictateArgs(silent, null), graph(), out))
        assertTrue(bytes.toString(), bytes.toString().contains("No speech"))
        assertTrue(api.formats.isEmpty())
    }

    @Test fun success_printsRawCleanedAndCost() = runBlocking {
        val g = graph()
        assertEquals(0, dictate(DictateArgs(speech(), CleanupLevel.LIGHT), g, out))
        val text = bytes.toString()
        assertTrue(text, text.contains("um hello"))
        assertTrue(text, text.contains("Hello"))
        assertTrue(text, text.contains("0.0004"))
        assertEquals(listOf("wav"), api.formats)
        val item = g.history.get(1)!!
        assertEquals("Hello", item.cleanText)
    }

    @Test fun parseArgs_levelAndAbsence() {
        assertEquals(DictateArgs(File("a.wav"), CleanupLevel.RAW), parseArgs(arrayOf("--dictate", "a.wav", "--level", "raw")))
        assertEquals(DictateArgs(File("a.wav"), null), parseArgs(arrayOf("--dictate", "a.wav")))
        assertNull(parseArgs(emptyArray()))
        assertNull(parseArgs(arrayOf("--dictate", "a.wav", "--level", "loud")))
        assertNull(parseArgs(arrayOf("--dictate")))
    }

    private companion object {
        val UNREACHABLE = "http://127.0.0.1:9/recommended.json".toHttpUrl()
    }
}
