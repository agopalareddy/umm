package io.github.agopalareddy.umm.sync.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hkdf {
    private const val HASH_LEN = 32
    private const val HMAC_ALGORITHM = "HmacSHA256"

    fun derive(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        return derive(ikm, salt, info.encodeToByteArray(), length)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length <= 255 * HASH_LEN) { "Length too large for HKDF-SHA256" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt

        // Step 1: Extract
        val macExtract = Mac.getInstance(HMAC_ALGORITHM)
        macExtract.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        val prk = macExtract.doFinal(ikm)

        // Step 2: Expand
        val macExpand = Mac.getInstance(HMAC_ALGORITHM)
        macExpand.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val n = (length + HASH_LEN - 1) / HASH_LEN
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            macExpand.update(t)
            macExpand.update(info)
            macExpand.update(i.toByte())
            t = macExpand.doFinal()

            val toCopy = minOf(HASH_LEN, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
        }

        return okm
    }
}
