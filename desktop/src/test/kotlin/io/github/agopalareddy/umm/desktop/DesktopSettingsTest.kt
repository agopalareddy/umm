package io.github.agopalareddy.umm.desktop

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.agopalareddy.umm.core.data.buildSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = tmp.root.resolve("desktop.preferences_pb")

    @Test fun defaults() = runBlocking {
        val prefs = DesktopSettings(buildSettingsStore(file())).prefs.first()
        assertEquals(
            DesktopPrefs(
                orbPosition = OrbPosition.BOTTOM_CENTER,
                sounds = false,
                startAtLogin = false,
                microphone = null,
                setupDone = false,
            ),
            prefs,
        )
    }

    @Test fun everyFieldRoundTrips() = runBlocking {
        val changed = DesktopPrefs(
            orbPosition = OrbPosition.TOP_CENTER,
            sounds = true,
            startAtLogin = true,
            microphone = "Built-in Audio",
            setupDone = true,
        )
        val settings = DesktopSettings(buildSettingsStore(file()))
        settings.update { changed }
        assertEquals(changed, settings.prefs.first())
        settings.update { it.copy(microphone = null) }
        assertEquals(changed.copy(microphone = null), settings.prefs.first())
    }

    @Test fun unknownOrbPositionFallsBackToTheDefault() = runBlocking {
        val store = buildSettingsStore(file())
        store.edit { it[stringPreferencesKey("orb_position")] = "MIDDLE_OF_NOWHERE" }
        assertEquals(OrbPosition.BOTTOM_CENTER, DesktopSettings(store).prefs.first().orbPosition)
    }
}
