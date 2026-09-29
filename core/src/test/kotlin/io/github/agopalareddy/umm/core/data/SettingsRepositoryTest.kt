package io.github.agopalareddy.umm.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.agopalareddy.umm.core.stats.DashboardRange
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.repo() = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") },
    )

    @Test fun defaultsMatchSpec() = runTest {
        val s = repo().settings.first()
        assertEquals(UmmSettings(), s)
        assertEquals(io.github.agopalareddy.umm.core.cleanup.CleanupLevel.LIGHT, s.defaultLevel)
        assertEquals(3, s.silenceTimeoutSec)
        assertEquals(true, s.switchBackAfterInsert)
        assertEquals("auto", s.defaultLanguage)
        assertEquals(listOf("auto", "en"), s.keyboardLanguages)
        assertEquals(ModelMode.RECOMMENDED, s.modelMode)
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(true, s.dynamicColor)
        assertEquals(false, s.zdrOnly)
        assertEquals(true, s.statsVisible)
        assertEquals(true, s.statsRecording)
        assertEquals(DashboardRange.D30, s.dashboardRange)
        assertEquals(emptyList<String>(), s.dashboardOrder)
        assertEquals(emptySet<String>(), s.dashboardHidden)
    }

    @Test fun roundTripsEveryField() = runTest {
        val repo = repo()
        val changed = UmmSettings(
            defaultLevel = io.github.agopalareddy.umm.core.cleanup.CleanupLevel.POLISHED,
            silenceTimeoutSec = 7,
            switchBackAfterInsert = false,
            defaultLanguage = "hi",
            keyboardLanguages = listOf("auto", "en", "hi"),
            modelMode = ModelMode.MANUAL,
            manualSttModel = "a/stt",
            manualCleanupModel = "b/chat",
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            zdrOnly = true,
            statsVisible = false,
            statsRecording = false,
            dashboardRange = DashboardRange.D90,
            dashboardOrder = listOf("budget", "summary"),
            dashboardHidden = setOf("fun_facts"),
        )
        repo.update { changed }
        assertEquals(changed, repo.settings.first())
    }

    @Test fun unknownRangeFallsBackToDefault() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(tmp.root, "settings.preferences_pb")
        }
        dataStore.edit { it[stringPreferencesKey("dashboard_range")] = "bogus" }
        assertEquals(DashboardRange.D30, SettingsRepository(dataStore).settings.first().dashboardRange)
    }

    @Test fun clampsSilenceTimeout() = runTest {
        val repo = repo()
        repo.update { it.copy(silenceTimeoutSec = 0) }
        assertEquals(1, repo.settings.first().silenceTimeoutSec)
        repo.update { it.copy(silenceTimeoutSec = 42) }
        assertEquals(10, repo.settings.first().silenceTimeoutSec)
        repo.update { it.copy(silenceTimeoutSec = null) }
        assertNull(repo.settings.first().silenceTimeoutSec)
    }

    @Test fun cacheRoundTrip() = runTest {
        val repo = repo()
        assertNull(repo.readCache("recommended"))
        repo.writeCache("recommended", "{}", 5)
        assertEquals("{}" to 5L, repo.readCache("recommended"))
    }
}
