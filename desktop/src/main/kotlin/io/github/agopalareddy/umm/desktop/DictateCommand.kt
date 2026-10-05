package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.pipeline.DictationRequest
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.pipeline.isFinished
import java.io.File
import java.io.PrintStream
import java.util.Locale
import kotlinx.coroutines.flow.first

data class DictateArgs(val file: File, val level: CleanupLevel?)

const val USAGE = "Usage: umm --dictate <file.wav> [--level raw|light|formatted|polished]"

/** Parses `--dictate <file> [--level <level>]`; null when `--dictate` is absent or an argument is invalid. */
fun parseArgs(args: Array<String>): DictateArgs? {
    val file = args.valueAfter("--dictate") ?: return null
    val level = if ("--level" in args) {
        val name = args.valueAfter("--level") ?: return null
        CleanupLevel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
    } else {
        null
    }
    return DictateArgs(File(file), level)
}

private fun Array<String>.valueAfter(flag: String): String? =
    indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }?.takeUnless { it.startsWith("--") }

/** Runs one dictation of a WAV file through the shared pipeline and prints the result; returns the exit code. */
suspend fun dictate(args: DictateArgs, graph: DesktopGraph, out: PrintStream): Int {
    if (graph.apiKeyStore.get() == null) {
        out.println("Set OPENROUTER_API_KEY to your OpenRouter API key.")
        return 2
    }
    if (!args.file.isFile) {
        out.println("File not found: ${args.file.path}")
        return 2
    }
    val audio = try {
        WavFileAudioSource(args.file)
    } catch (e: IllegalArgumentException) {
        out.println("Can't use ${args.file.path}: ${e.message}. Use a 16-bit PCM WAV file.")
        return 2
    }

    val settings = graph.settings.settings.first()
    val pipeline = graph.pipeline(audio)
    pipeline.start(
        DictationRequest(
            packageName = "desktop",
            level = args.level ?: settings.defaultLevel,
            script = ScriptPreference.LATIN,
            language = LanguageChoice.decode(settings.defaultLanguage),
            silenceTimeoutSec = null,
        ),
    )
    return when (val end = pipeline.state.first { it.isFinished() }) {
        is DictationState.Done -> {
            val item = graph.history.get(end.historyId)
            val cost = graph.stats.get(end.historyId)?.costUsd
            out.println("Raw:     ${item?.rawText.orEmpty()}")
            out.println("Cleaned: ${end.text}")
            out.println("Cost:    ${cost?.let { String.format(Locale.ROOT, "$%.4f", it) } ?: "unknown"}")
            if (end.cleanupFailed) out.println("Cleanup failed, so the cleaned text is the raw transcript.")
            0
        }
        is DictationState.Failed -> {
            out.println("Dictation failed: ${end.reason}")
            1
        }
        DictationState.NoSpeech -> {
            out.println("No speech found in ${args.file.path}.")
            1
        }
        else -> {
            out.println("The transcript was empty.")
            1
        }
    }
}
