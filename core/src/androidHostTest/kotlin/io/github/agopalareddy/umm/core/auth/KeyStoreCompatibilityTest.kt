package io.github.agopalareddy.umm.core.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A key saved by v1.2.0 (android.util.Base64, NO_WRAP) must still load after the update. */
@RunWith(RobolectricTestRunner::class)
class KeyStoreCompatibilityTest {
    private class XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray) = encrypt(blob)
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun readsAKeyWrittenByV120() {
        val prefs = context.getSharedPreferences("compat", Context.MODE_PRIVATE)
        val cipher = XorCipher()
        val blob = cipher.encrypt("sk-or-v1-abcdef1234567890".toByteArray())
        prefs.edit().putString("openrouter_key", android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
            .putString("openrouter_key_source", "SIGNED_IN").commit()
        val store = ApiKeyStore(SharedPreferencesKeyValueStore(prefs), cipher)
        assertEquals("sk-or-v1-abcdef1234567890", store.get())
        assertEquals(KeySource.SIGNED_IN, store.source.value)
    }

    @Test fun writesWhatV120CanRead() {
        val prefs = context.getSharedPreferences("compat2", Context.MODE_PRIVATE)
        val cipher = XorCipher()
        ApiKeyStore(SharedPreferencesKeyValueStore(prefs), cipher).set("sk-or-v1-abcdef1234567890", KeySource.PASTED)
        val stored = android.util.Base64.decode(prefs.getString("openrouter_key", null), android.util.Base64.NO_WRAP)
        assertEquals("sk-or-v1-abcdef1234567890", String(cipher.decrypt(stored)))
        assertEquals("PASTED", prefs.getString("openrouter_key_source", null))
    }
}
