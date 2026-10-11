package io.github.agopalareddy.umm.sync.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PairingCodeTest {

    private val sampleCode = PairingCode(
        deviceId = "dev-123",
        name = "Laptop",
        publicKey = "02" + "a".repeat(64),
        secret = "s".repeat(32),
        lan = listOf("192.168.1.50", "127.0.0.1"),
        port = 45678,
        relays = listOf("wss://relay.damus.io"),
        expiresAt = 100_000L,
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val now = 50_000L
        val encoded = sampleCode.encode()
        val decoded = PairingCode.decode(encoded, now)
        assertEquals(sampleCode, decoded)
    }

    @Test
    fun expiredThrows() {
        val encoded = sampleCode.encode()
        try {
            PairingCode.decode(encoded, now = 100_001L)
            fail("Expected PairingException(EXPIRED)")
        } catch (e: PairingException) {
            assertEquals(PairingError.EXPIRED, e.error)
        }
    }

    @Test
    fun garbageThrows() {
        try {
            PairingCode.decode("this-is-not-a-valid-code", now = 50_000L)
            fail("Expected PairingException(MALFORMED)")
        } catch (e: PairingException) {
            assertEquals(PairingError.MALFORMED, e.error)
        }

        try {
            PairingCode.decode("umm-pair:1:!!!invalid-base64!!!", now = 50_000L)
            fail("Expected PairingException(MALFORMED)")
        } catch (e: PairingException) {
            assertEquals(PairingError.MALFORMED, e.error)
        }
    }

    @Test
    fun wrongPrefixThrows() {
        try {
            PairingCode.decode("other-pair:1:eyJkZXZpY2VJZCI6IjEyMyJ9", now = 50_000L)
            fail("Expected PairingException(MALFORMED)")
        } catch (e: PairingException) {
            assertEquals(PairingError.MALFORMED, e.error)
        }
    }
}
