package io.github.agopalareddy.umm.sync.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object MemberSeal {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_LEN = 12
    private const val TAG_LEN_BITS = 128
    private const val TAG_LEN_BYTES = 16
    private const val HKDF_INFO = "umm-member"

    fun seal(sender: DeviceKeys, recipient: ByteArray, plain: ByteArray): String {
        val ecdhSecret = sender.agree(recipient)
        val key = Hkdf.derive(ikm = ecdhSecret, salt = ByteArray(32), info = HKDF_INFO, length = 32)
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN_BITS, nonce))
        val ciphertext = cipher.doFinal(plain)

        val payload = nonce + ciphertext
        return Base64.UrlSafe.encode(payload)
    }

    fun open(recipient: DeviceKeys, sender: ByteArray, sealed: String): ByteArray {
        val payload = Base64.UrlSafe.decode(sealed)
        require(payload.size >= NONCE_LEN + TAG_LEN_BYTES) { "Sealed data too short" }

        val ecdhSecret = recipient.agree(sender)
        val key = Hkdf.derive(ikm = ecdhSecret, salt = ByteArray(32), info = HKDF_INFO, length = 32)

        val nonce = payload.copyOfRange(0, NONCE_LEN)
        val ciphertext = payload.copyOfRange(NONCE_LEN, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }
}
