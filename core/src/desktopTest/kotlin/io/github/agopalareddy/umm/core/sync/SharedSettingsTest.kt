package io.github.agopalareddy.umm.core.sync

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.UmmSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SharedSettingsTest {
    @Test
    fun roundTripsEveryKey() {
        val default = UmmSettings()
        val changed = UmmSettings(
            defaultLevel = CleanupLevel.POLISHED,
            defaultLanguage = "hi",
            keyboardLanguages = listOf("hi", "te"),
            modelMode = ModelMode.MANUAL,
            manualSttModel = "custom/stt",
            manualCleanupModel = "custom/cleanup",
            zdrOnly = true,
            silenceTimeoutSec = 7,
        )

        assertEquals(8, SharedSettings.keys.size)

        for (key in SharedSettings.keys) {

            val encoded = SharedSettings.read(changed, key)
            val updated = SharedSettings.write(default, key, encoded)
            assertEquals(
                "Mismatch on key $key",
                SharedSettings.read(changed, key),
                SharedSettings.read(updated, key),
            )
        }

        // Test silenceTimeoutSec with null (off) as well
        val offSilence = changed.copy(silenceTimeoutSec = null)
        val readOff = SharedSettings.read(offSilence, SharedSettings.KEY_SILENCE_TIMEOUT_SEC)
        val updatedOff = SharedSettings.write(default, SharedSettings.KEY_SILENCE_TIMEOUT_SEC, readOff)
        assertEquals(readOff, SharedSettings.read(updatedOff, SharedSettings.KEY_SILENCE_TIMEOUT_SEC))

        // Verify keys doesn't contain theme, bubble, dashboard or stats keys
        for (key in SharedSettings.keys) {
            val lower = key.lowercase()
            assertFalse("Key $key contains theme", lower.contains("theme"))
            assertFalse("Key $key contains bubble", lower.contains("bubble"))
            assertFalse("Key $key contains dashboard", lower.contains("dashboard"))
            assertFalse("Key $key contains stats", lower.contains("stats"))
        }
    }
}
