package io.github.agopalareddy.umm.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UmmLinkTest {
    @Test fun codeIsExtracted() {
        assertEquals("abc123", UmmLink.authCode("umm://oauth?code=abc123"))
    }

    @Test fun urlEncodedCodeIsDecoded() {
        assertEquals("a+b/c", UmmLink.authCode("umm://oauth?code=a%2Bb%2Fc"))
    }

    @Test fun otherParametersAreIgnored() {
        assertEquals("x", UmmLink.authCode("umm://oauth?state=1&code=x"))
    }

    @Test fun wrongSchemeHostOrMissingCodeIsNull() {
        assertNull(UmmLink.authCode("https://oauth?code=abc"))
        assertNull(UmmLink.authCode("umm://other?code=abc"))
        assertNull(UmmLink.authCode("umm://oauth"))
        assertNull(UmmLink.authCode("umm://oauth?code="))
        assertNull(UmmLink.authCode("not a url"))
    }
}
