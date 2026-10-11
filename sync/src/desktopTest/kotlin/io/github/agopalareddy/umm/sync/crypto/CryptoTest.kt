package io.github.agopalareddy.umm.sync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {

    @Test
    fun bip340Vector() {
        val secretKey = hexToByteArray("0000000000000000000000000000000000000000000000000000000000000003")
        val expectedPub = hexToByteArray("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9")
        val msg = ByteArray(32)
        val expectedSig = hexToByteArray("E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0")

        val keys = DeviceKeys(secretKey)
        assertArrayEquals(expectedPub, keys.publicKey)

        val sig = keys.sign(msg)
        assertArrayEquals(expectedSig, sig)
        assertTrue(DeviceKeys.verify(keys.publicKey, msg, sig))
    }

    @Test
    fun ecdhIsSymmetric() {
        val a = DeviceKeys.generate()
        val b = DeviceKeys.generate()
        val secretAB = a.agree(b.publicKey)
        val secretBA = b.agree(a.publicKey)
        assertEquals(32, secretAB.size)
        assertTrue(secretAB.contentEquals(secretBA))
    }

    @Test
    fun hkdfRfc5869Case1() {
        val ikm = hexToByteArray("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hexToByteArray("000102030405060708090a0b0c")
        val info = hexToByteArray("f0f1f2f3f4f5f6f7f8f9")
        val expectedOkm = hexToByteArray("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

        val okm = Hkdf.derive(ikm = ikm, salt = salt, info = info, length = 42)
        assertArrayEquals(expectedOkm, okm)
    }

    @Test
    fun groupCipherRoundTripsAndRejectsTampering() {
        val key = ByteArray(32) { 7 }
        val cipher = GroupCipher(key, epoch = 42)
        val plain = "hello umm sync".encodeToByteArray()
        val sealed = cipher.seal(plain)

        assertEquals(42, GroupCipher.epochOf(sealed))
        assertArrayEquals(plain, cipher.open(sealed))

        // Flipping any byte throws
        for (i in sealed.indices) {
            val tampered = sealed.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            assertThrows(Exception::class.java) {
                cipher.open(tampered)
            }
        }
    }

    @Test
    fun memberSealOnlyOpensForRecipient() {
        val alice = DeviceKeys.generate()
        val bob = DeviceKeys.generate()
        val eve = DeviceKeys.generate()
        val plain = "shared secret payload".encodeToByteArray()

        val sealed = MemberSeal.seal(sender = alice, recipient = bob.publicKey, plain = plain)
        val opened = MemberSeal.open(recipient = bob, sender = alice.publicKey, sealed = sealed)
        assertArrayEquals(plain, opened)

        assertThrows(Exception::class.java) {
            MemberSeal.open(recipient = eve, sender = alice.publicKey, sealed = sealed)
        }
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
