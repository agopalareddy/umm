package io.github.agopalareddy.umm.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthUrlTest {
    @Test fun includesChallengeAndCallback() {
        val uri = AuthUrl.build("chal")
        assertEquals("openrouter.ai", uri.host)
        assertEquals("/auth", uri.path)
        assertEquals("chal", uri.getQueryParameter("code_challenge"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals("https://agopalareddy.github.io/umm/oauth/", uri.getQueryParameter("callback_url"))
        assertEquals("Umm", uri.getQueryParameter("key_label"))
    }

    @Test fun headlessOmitsCallback() {
        assertNull(AuthUrl.build("chal", callback = null).getQueryParameter("callback_url"))
    }
}
