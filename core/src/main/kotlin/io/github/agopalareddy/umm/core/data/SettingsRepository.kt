package io.github.agopalareddy.umm.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ModelMode { RECOMMENDED, NEWEST_STT, MANUAL }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UmmSettings(
    val defaultLevel: CleanupLevel = CleanupLevel.LIGHT,
    /** Seconds of silence before auto-stop, 1..10; null means Off. */
    val silenceTimeoutSec: Int? = 3,
    val switchBackAfterInsert: Boolean = true,
    val defaultLanguage: String = "auto",
    val keyboardLanguages: List<String> = listOf("auto", "en"),
    val modelMode: ModelMode = ModelMode.RECOMMENDED,
    val manualSttModel: String? = null,
    val manualCleanupModel: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colors from the wallpaper (Android 12+). */
    val dynamicColor: Boolean = true,
    /** Use only zero data retention models, even if the OpenRouter account doesn't require it. */
    val zdrOnly: Boolean = false,
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<UmmSettings> = dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (UmmSettings) -> UmmSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[DEFAULT_LEVEL] = next.defaultLevel.name
            // 0 is stored for Off so that "missing" can keep meaning "default".
            prefs[SILENCE_TIMEOUT] = next.silenceTimeoutSec?.coerceIn(1, 10) ?: OFF
            prefs[SWITCH_BACK] = next.switchBackAfterInsert
            prefs[DEFAULT_LANGUAGE] = next.defaultLanguage
            prefs[KEYBOARD_LANGUAGES] = next.keyboardLanguages.joinToString(",")
            prefs[MODEL_MODE] = next.modelMode.name
            next.manualSttModel?.let { prefs[MANUAL_STT] = it } ?: prefs.remove(MANUAL_STT)
            next.manualCleanupModel?.let { prefs[MANUAL_CLEANUP] = it } ?: prefs.remove(MANUAL_CLEANUP)
            prefs[THEME_MODE] = next.themeMode.name
            prefs[DYNAMIC_COLOR] = next.dynamicColor
            prefs[ZDR_ONLY] = next.zdrOnly
        }
    }

    suspend fun readCache(name: String): Pair<String, Long>? {
        val prefs = dataStore.data.first()
        val json = prefs[cacheKey(name)] ?: return null
        return json to (prefs[cacheTimeKey(name)] ?: 0L)
    }

    fun observeCache(name: String): Flow<Pair<String, Long>?> = dataStore.data.map { prefs ->
        prefs[cacheKey(name)]?.let { it to (prefs[cacheTimeKey(name)] ?: 0L) }
    }

    suspend fun writeCache(name: String, json: String, savedAt: Long) {
        dataStore.edit {
            it[cacheKey(name)] = json
            it[cacheTimeKey(name)] = savedAt
        }
    }

    private fun Preferences.toSettings(): UmmSettings {
        val defaults = UmmSettings()
        return UmmSettings(
            defaultLevel = this[DEFAULT_LEVEL]?.let(::levelOrNull) ?: defaults.defaultLevel,
            silenceTimeoutSec = when (val v = this[SILENCE_TIMEOUT]) {
                null -> defaults.silenceTimeoutSec
                OFF -> null
                else -> v
            },
            switchBackAfterInsert = this[SWITCH_BACK] ?: defaults.switchBackAfterInsert,
            defaultLanguage = this[DEFAULT_LANGUAGE] ?: defaults.defaultLanguage,
            keyboardLanguages = this[KEYBOARD_LANGUAGES]?.split(",")?.filter { it.isNotBlank() }
                ?: defaults.keyboardLanguages,
            modelMode = this[MODEL_MODE]?.let { runCatching { ModelMode.valueOf(it) }.getOrNull() } ?: defaults.modelMode,
            manualSttModel = this[MANUAL_STT],
            manualCleanupModel = this[MANUAL_CLEANUP],
            themeMode = this[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.themeMode,
            dynamicColor = this[DYNAMIC_COLOR] ?: defaults.dynamicColor,
            zdrOnly = this[ZDR_ONLY] ?: defaults.zdrOnly,
        )
    }

    private fun levelOrNull(name: String) = runCatching { CleanupLevel.valueOf(name) }.getOrNull()

    private companion object {
        const val OFF = 0
        val DEFAULT_LEVEL = stringPreferencesKey("default_level")
        val SILENCE_TIMEOUT = intPreferencesKey("silence_timeout_sec")
        val SWITCH_BACK = booleanPreferencesKey("switch_back_after_insert")
        val DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
        val KEYBOARD_LANGUAGES = stringPreferencesKey("keyboard_languages")
        val MODEL_MODE = stringPreferencesKey("model_mode")
        val MANUAL_STT = stringPreferencesKey("manual_stt_model")
        val MANUAL_CLEANUP = stringPreferencesKey("manual_cleanup_model")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ZDR_ONLY = booleanPreferencesKey("zdr_only")
        fun cacheKey(name: String) = stringPreferencesKey("cache_$name")
        fun cacheTimeKey(name: String) = longPreferencesKey("cache_${name}_at")
    }
}
