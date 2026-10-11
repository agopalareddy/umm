package io.github.agopalareddy.umm.sync.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class GroupCipher(
    key: ByteArray,
    val epoch: Int,
    private val previousKey: ByteArray? = null,
    private val previousUntil: Long = 0L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val previousKeySpec = previousKey?.let { SecretKeySpec(it, "AES") }

    fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val epochBytes = ByteArray(4).apply { ByteBuffer.wrap(this).putInt(epoch) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LEN_BITS, nonce))
        cipher.updateAAD(epochBytes)
        val ciphertext = cipher.doFinal(plain)

        return epochBytes + nonce + ciphertext
    }

    fun open(sealed: ByteArray): ByteArray {
        require(sealed.size >= 4 + NONCE_LEN + TAG_LEN_BYTES) { "Sealed data too short" }
        val sealedEpoch = epochOf(sealed)
        val targetSpec = when {
            sealedEpoch == epoch -> keySpec
            sealedEpoch == epoch - 1 && previousKeySpec != null && clock() <= previousUntil -> previousKeySpec
            else -> throw IllegalArgumentException("Epoch mismatch: expected $epoch, got $sealedEpoch")
        }

        val epochBytes = sealed.copyOfRange(0, 4)
        val nonce = sealed.copyOfRange(4, 4 + NONCE_LEN)
        val ciphertext = sealed.copyOfRange(4 + NONCE_LEN, sealed.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, targetSpec, GCMParameterSpec(TAG_LEN_BITS, nonce))
        cipher.updateAAD(epochBytes)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_LEN = 12
        private const val TAG_LEN_BITS = 128
        private const val TAG_LEN_BYTES = 16

        fun epochOf(sealed: ByteArray): Int {
            require(sealed.size >= 4) { "Sealed data too short to read epoch" }
            return ByteBuffer.wrap(sealed, 0, 4).int
        }
    }
}
