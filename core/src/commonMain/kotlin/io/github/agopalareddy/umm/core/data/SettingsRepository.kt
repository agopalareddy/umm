package io.github.agopalareddy.umm.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.stats.DashboardRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ModelMode { RECOMMENDED, NEWEST_STT, MANUAL }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class BubbleShowMode { WHEN_FOCUSED, ALWAYS }

enum class BubbleSize { SMALL, MEDIUM, LARGE }

enum class BubbleEdge { LEFT, RIGHT }

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
    /** Show the stats dashboard on Home. */
    val statsVisible: Boolean = true,
    /** Record a stats row for each dictation. */
    val statsRecording: Boolean = true,
    val dashboardRange: DashboardRange = DashboardRange.D30,
    /** Dashboard card IDs in display order; empty means the default order. */
    val dashboardOrder: List<String> = emptyList(),
    val dashboardHidden: Set<String> = emptySet(),
    val bubbleEnabled: Boolean = false,
    val bubbleVisibility: BubbleShowMode = BubbleShowMode.WHEN_FOCUSED,
    val bubbleSize: BubbleSize = BubbleSize.MEDIUM,
    val bubbleEdge: BubbleEdge = BubbleEdge.RIGHT,
    /** Bubble top edge as a fraction (0..1) of the free screen height, per orientation. */
    val bubbleYPortrait: Float = 0.6f,
    val bubbleYLandscape: Float = 0.5f,
    /** Epoch ms when the bubble disclosure was accepted; 0 means not yet. */
    val disclosureAcceptedAt: Long = 0,
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
            prefs[STATS_VISIBLE] = next.statsVisible
            prefs[STATS_RECORDING] = next.statsRecording
            prefs[DASHBOARD_RANGE] = next.dashboardRange.name
            prefs[DASHBOARD_ORDER] = next.dashboardOrder.joinToString(",")
            prefs[DASHBOARD_HIDDEN] = next.dashboardHidden.joinToString(",")
            prefs[BUBBLE_ENABLED] = next.bubbleEnabled
            prefs[BUBBLE_VISIBILITY] = next.bubbleVisibility.name
            prefs[BUBBLE_SIZE] = next.bubbleSize.name
            prefs[BUBBLE_EDGE] = next.bubbleEdge.name
            prefs[BUBBLE_Y_PORTRAIT] = next.bubbleYPortrait.coerceIn(0f, 1f)
            prefs[BUBBLE_Y_LANDSCAPE] = next.bubbleYLandscape.coerceIn(0f, 1f)
            prefs[DISCLOSURE_ACCEPTED_AT] = next.disclosureAcceptedAt
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
            statsVisible = this[STATS_VISIBLE] ?: defaults.statsVisible,
            statsRecording = this[STATS_RECORDING] ?: defaults.statsRecording,
            dashboardRange = this[DASHBOARD_RANGE]?.let { runCatching { DashboardRange.valueOf(it) }.getOrNull() }
                ?: defaults.dashboardRange,
            dashboardOrder = this[DASHBOARD_ORDER]?.split(",")?.filter { it.isNotBlank() }
                ?: defaults.dashboardOrder,
            dashboardHidden = this[DASHBOARD_HIDDEN]?.split(",")?.filter { it.isNotBlank() }?.toSet()
                ?: defaults.dashboardHidden,
            bubbleEnabled = this[BUBBLE_ENABLED] ?: defaults.bubbleEnabled,
            bubbleVisibility = this[BUBBLE_VISIBILITY]?.let { runCatching { BubbleShowMode.valueOf(it) }.getOrNull() }
                ?: defaults.bubbleVisibility,
            bubbleSize = this[BUBBLE_SIZE]?.let { runCatching { BubbleSize.valueOf(it) }.getOrNull() }
                ?: defaults.bubbleSize,
            bubbleEdge = this[BUBBLE_EDGE]?.let { runCatching { BubbleEdge.valueOf(it) }.getOrNull() }
                ?: defaults.bubbleEdge,
            bubbleYPortrait = this[BUBBLE_Y_PORTRAIT] ?: defaults.bubbleYPortrait,
            bubbleYLandscape = this[BUBBLE_Y_LANDSCAPE] ?: defaults.bubbleYLandscape,
            disclosureAcceptedAt = this[DISCLOSURE_ACCEPTED_AT] ?: defaults.disclosureAcceptedAt,
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
        val STATS_VISIBLE = booleanPreferencesKey("stats_visible")
        val STATS_RECORDING = booleanPreferencesKey("stats_recording")
        val DASHBOARD_RANGE = stringPreferencesKey("dashboard_range")
        val DASHBOARD_ORDER = stringPreferencesKey("dashboard_order")
        val DASHBOARD_HIDDEN = stringPreferencesKey("dashboard_hidden")
        val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
        val BUBBLE_VISIBILITY = stringPreferencesKey("bubble_visibility")
        val BUBBLE_SIZE = stringPreferencesKey("bubble_size")
        val BUBBLE_EDGE = stringPreferencesKey("bubble_edge")
        val BUBBLE_Y_PORTRAIT = floatPreferencesKey("bubble_y_portrait")
        val BUBBLE_Y_LANDSCAPE = floatPreferencesKey("bubble_y_landscape")
        val DISCLOSURE_ACCEPTED_AT = longPreferencesKey("disclosure_accepted_at")
        fun cacheKey(name: String) = stringPreferencesKey("cache_$name")
        fun cacheTimeKey(name: String) = longPreferencesKey("cache_${name}_at")
    }
}
