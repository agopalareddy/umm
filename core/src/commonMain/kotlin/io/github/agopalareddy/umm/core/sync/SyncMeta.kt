package io.github.agopalareddy.umm.core.sync

import io.github.agopalareddy.umm.core.data.UmmDatabase
import java.security.SecureRandom

class SyncMeta(private val db: UmmDatabase) {
    @Volatile private var cachedDeviceId: String? = null

    suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val dao = db.syncDao()
        val existing = dao.getMeta(KEY_DEVICE_ID)
        if (existing != null) {
            cachedDeviceId = existing
            return existing
        }
        val generated = generateDeviceId()
        dao.putMeta(SyncMetaEntity(KEY_DEVICE_ID, generated))
        cachedDeviceId = generated
        return generated
    }

    companion object {
        const val KEY_DEVICE_ID = "device_id"

        fun generateDeviceId(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return encodeCrockford(bytes)
        }

        fun encodeCrockford(bytes: ByteArray): String {
            require(bytes.size == 16) { "expected 16 bytes, got ${bytes.size}" }
            val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            val chars = CharArray(26)
            val b0 = bytes[0].toInt() and 0xFF
            chars[0] = alphabet[b0 ushr 5]
            chars[1] = alphabet[b0 and 0x1F]
            for (block in 0 until 3) {
                val offset = 1 + block * 5
                val outOffset = 2 + block * 8
                val b1 = bytes[offset].toLong() and 0xFF
                val b2 = bytes[offset + 1].toLong() and 0xFF
                val b3 = bytes[offset + 2].toLong() and 0xFF
                val b4 = bytes[offset + 3].toLong() and 0xFF
                val b5 = bytes[offset + 4].toLong() and 0xFF
                val v = (b1 shl 32) or (b2 shl 24) or (b3 shl 16) or (b4 shl 8) or b5
                for (j in 0 until 8) {
                    chars[outOffset + j] = alphabet[((v ushr (5 * (7 - j))) and 0x1F).toInt()]
                }
            }
            return String(chars)
        }
    }
}
