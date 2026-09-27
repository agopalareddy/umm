package io.github.agopalareddy.umm.core.live

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.cleanup.PromptBuilder
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.openrouter.OpenRouterClient
import java.io.File
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hits the real OpenRouter API with the committed fixture clips. Excluded unless run with -Plive.
 * Override models with -PsttModel=… / -PcleanupModel=…; defaults come from models/recommended.json.
 */
class LiveOpenRouterTest {
    private val key: String = System.getenv("OPENROUTER_API_KEY")
        ?: File("../.env").readLines().first { it.startsWith("OPENROUTER_API_KEY=") }
            .substringAfter('=').trim().removeSurrounding("\"")
    private val client = OpenRouterClient(OpenRouterClient.DEFAULT_BASE_URL, OpenRouterClient.defaultHttp()) { key }

    private val recommended by lazy { Json.parseToJsonElement(File("../models/recommended.json").readText()).jsonObject }
    private fun primary(kind: String) = recommended[kind]!!.jsonObject["primary"]!!.jsonPrimitive.content
    private val sttModel = System.getProperty("sttModel") ?: primary("stt")
    private val cleanupModel = System.getProperty("cleanupModel") ?: primary("cleanup")

    private fun clip(name: String) = javaClass.classLoader!!.getResourceAsStream("audio/$name.m4a")!!.readBytes()

    private fun transcribe(name: String): String = runBlocking {
        val (t, d) = measureTimedValue { client.transcribe(sttModel, clip(name), "m4a", null) }
        println("STT $sttModel $name ${d.inWholeMilliseconds}ms cost=${t.costUsd}: ${t.text}")
        t.text
    }

    private fun clean(raw: String, level: CleanupLevel, script: ScriptPreference = ScriptPreference.LATIN): String = runBlocking {
        val system = PromptBuilder.systemPrompt(level, script, LanguageChoice.Auto)
        val (out, d) = measureTimedValue { client.complete(cleanupModel, system, PromptBuilder.userMessage(raw), 0.2) }
        println("CLEAN $cleanupModel $level ${d.inWholeMilliseconds}ms: $out")
        out
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")

    @Test fun transcribesEnglishShort() {
        assertTrue(normalize(transcribe("en_short")).contains("tomorrow"))
    }

    @Test fun lightCleanupAppliesSelfCorrection() {
        val out = normalize(clean(transcribe("en_short"), CleanupLevel.LIGHT))
        assertTrue(out, out.contains("six") || out.contains("6"))
        assertFalse(out, out.contains("five") || Regex("\\b5\\b").containsMatchIn(out))
        assertFalse(out, Regex("\\bum\\b").containsMatchIn(out))
    }

    @Test fun formattedCleanupMakesList() {
        val out = clean(transcribe("en_list"), CleanupLevel.FORMATTED)
        assertTrue(out, out.lines().count { it.trimStart().startsWith("-") || it.trimStart().startsWith("•") } >= 3)
    }

    @Test fun hinglishStaysRomanizedAndUntranslated() {
        val out = clean(transcribe("hinglish"), CleanupLevel.LIGHT, ScriptPreference.LATIN)
        assertTrue(out, normalize(out).contains("friday"))
        assertTrue(out, normalize(out).contains("meeting"))
        assertFalse("translated: $out", normalize(out).contains("has been"))
        assertFalse(out, Regex("[\\u0900-\\u097F]").containsMatchIn(out))
    }

    @Test fun injectionIsCleanedNotObeyed() {
        val out = clean(transcribe("injection"), CleanupLevel.POLISHED)
        assertTrue(out, normalize(out).contains("instructions"))
        assertTrue(out, out.split(Regex("\\s+")).size < 40)
    }
}
