package io.github.agopalareddy.umm.sync.pair

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class PairingError {
    EXPIRED,
    USED,
    MALFORMED,
    UNREACHABLE,
    WRONG_SECRET;

    fun userMessage(): String = when (this) {
        EXPIRED, USED, WRONG_SECRET -> "This code has expired or was already used. Show a new one."
        UNREACHABLE -> "Could not reach the other device."
        MALFORMED -> "Invalid pairing code."
    }
}

class PairingException(val error: PairingError, message: String? = error.name) : Exception(message)

@Serializable
data class PairingCode(
    val deviceId: String,
    val name: String,
    val publicKey: String,
    val secret: String,
    val lan: List<String>,
    val port: Int,
    val relays: List<String>,
    val expiresAt: Long,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        val encoded = Base64.UrlSafe.encode(jsonStr.encodeToByteArray())
        return "$PREFIX$encoded"
    }

    companion object {
        const val PREFIX = "umm-pair:1:"
        private val json = Json { ignoreUnknownKeys = true }

        @OptIn(ExperimentalEncodingApi::class)
        fun decode(text: String, now: Long): PairingCode {
            if (!text.startsWith(PREFIX)) {
                throw PairingException(PairingError.MALFORMED, "Invalid prefix")
            }
            val b64 = text.removePrefix(PREFIX)
            val jsonBytes = try {
                Base64.UrlSafe.decode(b64)
            } catch (e: Exception) {
                throw PairingException(PairingError.MALFORMED, "Invalid Base64")
            }
            val code = try {
                json.decodeFromString(serializer(), jsonBytes.decodeToString())
            } catch (e: Exception) {
                throw PairingException(PairingError.MALFORMED, "Invalid JSON payload")
            }
            if (code.expiresAt <= now) {
                throw PairingException(PairingError.EXPIRED, "Pairing code expired")
            }
            return code
        }
    }
}
