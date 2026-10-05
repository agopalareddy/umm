package io.github.agopalareddy.umm.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test fun challengeMatchesKnownVector() {
        assertEquals(
            "ZKSd08Sc6g7c7YOer8C_uRITMPDfR47H5Mj7yQuNITY",
            Pkce.challengeFor("umm-test-verifier-0123456789-abcdefghijklmnopqrstuvwxyz"),
        )
    }

    @Test fun verifierIsUrlSafeAndLongEnough() {
        val v = Pkce.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(Regex("^[A-Za-z0-9._~-]+$").matches(v))
        assertNotEquals(v, Pkce.newVerifier())
    }
}
