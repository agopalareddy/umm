package io.github.agopalareddy.umm.sync.relay

import io.github.agopalareddy.umm.sync.crypto.DeviceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventTest {

    @Test
    fun idMatchesNip01Serialization() {
        val secretKey = hexToByteArray("0000000000000000000000000000000000000000000000000000000000000003")
        val keys = DeviceKeys(secretKey)
        val kind = 1
        val tags = listOf(listOf("p", "91c9a5e1a9744114c32d3861771204007f667b1fa6570ca5777323b57cc77b48"))
        val content = "Hello Nostr! With \"quotes\" and \n newline."
        val createdAt = 1712000000L

        val event = NostrEvent.sign(keys, kind, tags, content, createdAt)

        assertEquals("5cab22ac3a72c0407b06c59220f130a5432e5290d277311784b1c9f3d927add1", event.id)
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", event.pubkey)
        assertEquals(createdAt, event.created_at)
        assertEquals(kind, event.kind)
        assertEquals(tags, event.tags)
        assertEquals(content, event.content)
        assertTrue(event.verify())
    }

    @Test
    fun tamperedContentFailsVerify() {
        val keys = DeviceKeys.generate()
        val event = NostrEvent.sign(
            keys = keys,
            kind = KIND_APP_DATA,
            tags = listOf(listOf("d", "umm:test:1-1")),
            content = "original content",
            createdAt = 1712000000L,
        )
        assertTrue(event.verify())

        assertFalse(event.copy(content = "tampered content").verify())
        assertFalse(event.copy(id = "00".repeat(32)).verify())
        assertFalse(event.copy(sig = "00".repeat(64)).verify())
        assertFalse(event.copy(pubkey = "00".repeat(32)).verify())
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
