package io.github.agopalareddy.umm.core.sync

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.UmmSettings

object SharedSettings {
    const val KEY_DEFAULT_LEVEL = "default_level"
    const val KEY_DEFAULT_LANGUAGE = "default_language"
    const val KEY_KEYBOARD_LANGUAGES = "keyboard_languages"
    const val KEY_MODEL_MODE = "model_mode"
    const val KEY_MANUAL_STT_MODEL = "manual_stt_model"
    const val KEY_MANUAL_CLEANUP_MODEL = "manual_cleanup_model"
    const val KEY_ZDR_ONLY = "zdr_only"
    const val KEY_SILENCE_TIMEOUT_SEC = "silence_timeout_sec"

    val keys: List<String> = listOf(
        KEY_DEFAULT_LEVEL,
        KEY_DEFAULT_LANGUAGE,
        KEY_KEYBOARD_LANGUAGES,
        KEY_MODEL_MODE,
        KEY_MANUAL_STT_MODEL,
        KEY_MANUAL_CLEANUP_MODEL,
        KEY_ZDR_ONLY,
        KEY_SILENCE_TIMEOUT_SEC,
    )

    fun read(s: UmmSettings, key: String): String = when (key) {
        KEY_DEFAULT_LEVEL -> s.defaultLevel.name
        KEY_DEFAULT_LANGUAGE -> s.defaultLanguage
        KEY_KEYBOARD_LANGUAGES -> s.keyboardLanguages.joinToString(",")
        KEY_MODEL_MODE -> s.modelMode.name
        KEY_MANUAL_STT_MODEL -> s.manualSttModel ?: ""
        KEY_MANUAL_CLEANUP_MODEL -> s.manualCleanupModel ?: ""
        KEY_ZDR_ONLY -> s.zdrOnly.toString()
        KEY_SILENCE_TIMEOUT_SEC -> (s.silenceTimeoutSec ?: 0).toString()
        else -> throw IllegalArgumentException("Unknown shared settings key: $key")
    }

    fun write(s: UmmSettings, key: String, value: String): UmmSettings = when (key) {
        KEY_DEFAULT_LEVEL -> s.copy(defaultLevel = CleanupLevel.valueOf(value))
        KEY_DEFAULT_LANGUAGE -> s.copy(defaultLanguage = value)
        KEY_KEYBOARD_LANGUAGES -> s.copy(keyboardLanguages = value.split(",").filter { it.isNotBlank() })
        KEY_MODEL_MODE -> s.copy(modelMode = ModelMode.valueOf(value))
        KEY_MANUAL_STT_MODEL -> s.copy(manualSttModel = value.ifEmpty { null })
        KEY_MANUAL_CLEANUP_MODEL -> s.copy(manualCleanupModel = value.ifEmpty { null })
        KEY_ZDR_ONLY -> s.copy(zdrOnly = value.toBooleanStrictOrNull() ?: false)
        KEY_SILENCE_TIMEOUT_SEC -> {
            val sec = value.toIntOrNull()?.takeIf { it in 1..10 }
            s.copy(silenceTimeoutSec = sec)
        }
        else -> throw IllegalArgumentException("Unknown shared settings key: $key")
    }
}
