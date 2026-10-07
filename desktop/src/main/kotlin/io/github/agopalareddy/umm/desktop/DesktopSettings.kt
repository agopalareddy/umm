package io.github.agopalareddy.umm.desktop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class OrbPosition { BOTTOM_CENTER, BOTTOM_LEFT, BOTTOM_RIGHT, TOP_CENTER }

/** Settings that only make sense on the desktop; the shared ones live in `settings.preferences_pb`. */
data class DesktopPrefs(
    val orbPosition: OrbPosition = OrbPosition.BOTTOM_CENTER,
    val sounds: Boolean = false,
    val startAtLogin: Boolean = false,
    /** Name of the input device (a javax.sound mixer); null is the system default. */
    val microphone: String? = null,
    val setupDone: Boolean = false,
)

/** Stored in `~/.config/umm/desktop.preferences_pb`. */
class DesktopSettings(private val store: DataStore<Preferences>) {
    val prefs: Flow<DesktopPrefs> = store.data.map { it.toPrefs() }

    suspend fun update(transform: (DesktopPrefs) -> DesktopPrefs) {
        store.edit { prefs ->
            val next = transform(prefs.toPrefs())
            prefs[ORB_POSITION] = next.orbPosition.name
            prefs[SOUNDS] = next.sounds
            prefs[START_AT_LOGIN] = next.startAtLogin
            if (next.microphone == null) prefs.remove(MICROPHONE) else prefs[MICROPHONE] = next.microphone
            prefs[SETUP_DONE] = next.setupDone
        }
    }

    private fun Preferences.toPrefs(): DesktopPrefs {
        val defaults = DesktopPrefs()
        return DesktopPrefs(
            orbPosition = this[ORB_POSITION]?.let { name -> OrbPosition.entries.firstOrNull { it.name == name } }
                ?: defaults.orbPosition,
            sounds = this[SOUNDS] ?: defaults.sounds,
            startAtLogin = this[START_AT_LOGIN] ?: defaults.startAtLogin,
            microphone = this[MICROPHONE],
            setupDone = this[SETUP_DONE] ?: defaults.setupDone,
        )
    }

    private companion object {
        val ORB_POSITION = stringPreferencesKey("orb_position")
        val SOUNDS = booleanPreferencesKey("sounds")
        val START_AT_LOGIN = booleanPreferencesKey("start_at_login")
        val MICROPHONE = stringPreferencesKey("microphone")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
    }
}
