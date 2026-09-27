package io.github.agopalareddy.umm.core.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiKeyStoreTest {
    /** Reversible stand-in for the Keystore cipher, which Robolectric cannot provide. */
    private val xor = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray) = encrypt(blob)
    }
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("test_keys", Context.MODE_PRIVATE)

    @Test fun persistsAcrossInstancesWithoutPlaintext() {
        ApiKeyStore(prefs, xor).set("sk-or-1")
        val reopened = ApiKeyStore(prefs, xor)
        assertEquals("sk-or-1", reopened.get())
        assertEquals("sk-or-1", reopened.key.value)
        assertFalse(prefs.all.values.any { it.toString().contains("sk-or-1") })
    }

    @Test fun clearRemovesKey() {
        val store = ApiKeyStore(prefs, xor)
        store.set("sk-or-1")
        store.clear()
        assertNull(store.get())
        assertNull(store.key.value)
        assertNull(ApiKeyStore(prefs, xor).get())
    }
}
