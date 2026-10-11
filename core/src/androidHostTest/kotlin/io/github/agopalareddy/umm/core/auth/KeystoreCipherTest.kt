package io.github.agopalareddy.umm.core.auth

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyStoreException

@RunWith(RobolectricTestRunner::class)
class KeystoreCipherTest {
    @Test(expected = KeyStoreException::class)
    fun throwsWhenAndroidKeyStoreUnavailable() {
        val cipher = KeystoreCipher()
        cipher.encrypt("secret".toByteArray())
    }
}
