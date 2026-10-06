package io.github.agopalareddy.umm.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthUrlTest {
    /** The strings android.net.Uri produced before AuthUrl became shared. */
    @Test fun exactUrl() {
        assertEquals(
            "https://openrouter.ai/auth?callback_url=https%3A%2F%2Fagopalareddy.github.io%2Fumm%2Foauth%2F" +
                "&code_challenge=ab-_C9&code_challenge_method=S256&key_label=Umm",
            AuthUrl.build("ab-_C9"),
        )
        assertEquals(
            "https://openrouter.ai/auth?code_challenge=chal&code_challenge_method=S256&key_label=Umm%20Laptop",
            AuthUrl.build("chal", callback = null, keyLabel = "Umm Laptop"),
        )
    }

    @Test fun headlessOmitsCallback() {
        assertFalse(AuthUrl.build("chal", callback = null).contains("callback_url"))
    }
}
