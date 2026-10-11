package io.github.agopalareddy.umm.sync.relay

import io.github.agopalareddy.umm.sync.crypto.DeviceKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest

const val KIND_APP_DATA = 30078
const val KIND_PAIRING = 21078
const val EXPIRATION_SECONDS = 30 * 86_400L
const val MAX_CONTENT = 32_000

@Serializable
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    val createdAt: Long get() = created_at

    fun verify(): Boolean {
        val serialized = serializeForId(pubkey, created_at, kind, tags, content)
        val hash = sha256(serialized.encodeToByteArray())
        val expectedId = hash.toHex()
        if (!id.equals(expectedId, ignoreCase = true)) return false

        return try {
            val pubkeyBytes = hexToBytes(pubkey)
            val sigBytes = hexToBytes(sig)
            if (pubkeyBytes.size != 32 || sigBytes.size != 64) return false
            DeviceKeys.verify(pubkeyBytes, hash, sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun sign(
            keys: DeviceKeys,
            kind: Int,
            tags: List<List<String>>,
            content: String,
            createdAt: Long,
        ): NostrEvent {
            val pubkeyHex = keys.publicKey.toHex()
            val serialized = serializeForId(pubkeyHex, createdAt, kind, tags, content)
            val hash = sha256(serialized.encodeToByteArray())
            val id = hash.toHex()
            val sig = keys.sign(hash).toHex()
            return NostrEvent(
                id = id,
                pubkey = pubkeyHex,
                created_at = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = sig,
            )
        }

        private fun serializeForId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): String {
            val array = buildJsonArray {
                add(0)
                add(pubkey.lowercase())
                add(createdAt)
                add(kind)
                add(buildJsonArray {
                    for (tag in tags) {
                        add(buildJsonArray {
                            for (elem in tag) {
                                add(elem)
                            }
                        })
                    }
                })
                add(content)
            }
            return Json.encodeToString(JsonElement.serializer(), array)
        }

        private fun sha256(data: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-256").digest(data)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            val len = clean.length
            if (len % 2 != 0) throw IllegalArgumentException("Invalid hex length: $len")
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                val high = Character.digit(clean[i], 16)
                val low = Character.digit(clean[i + 1], 16)
                if (high == -1 || low == -1) throw IllegalArgumentException("Invalid hex char at $i")
                data[i / 2] = ((high shl 4) + low).toByte()
                i += 2
            }
            return data
        }
    }
}
