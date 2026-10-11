package io.github.agopalareddy.umm.sync.crypto

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

class DeviceKeys(privateKey: ByteArray) {
    val privateKey: ByteArray
    val publicKey: ByteArray

    init {
        val secp = Secp256k1.get()
        require(secp.secKeyVerify(privateKey)) { "Invalid secp256k1 private key" }
        val uncompressed = secp.pubkeyCreate(privateKey)
        val isOddY = secp.pubKeyCompress(uncompressed)[0] == 0x03.toByte()
        this.privateKey = if (isOddY) secp.privKeyNegate(privateKey) else privateKey
        val finalUncompressed = if (isOddY) secp.pubkeyCreate(this.privateKey) else uncompressed
        this.publicKey = finalUncompressed.copyOfRange(1, 33)
    }

    fun sign(message32: ByteArray): ByteArray {
        require(message32.size == 32) { "Message must be 32 bytes" }
        val secp = Secp256k1.get()
        return secp.signSchnorr(message32, privateKey, ByteArray(32))
    }

    fun agree(peerPublic: ByteArray): ByteArray {
        val secp = Secp256k1.get()
        val peerCompressed = if (peerPublic.size == 32) byteArrayOf(0x02) + peerPublic else peerPublic
        return secp.ecdh(privateKey, peerCompressed)
    }

    companion object {
        fun generate(): DeviceKeys {
            val secp = Secp256k1.get()
            val random = SecureRandom()
            val key = ByteArray(32)
            while (true) {
                random.nextBytes(key)
                if (secp.secKeyVerify(key)) {
                    return DeviceKeys(key)
                }
            }
        }

        fun verify(publicKey: ByteArray, message32: ByteArray, signature: ByteArray): Boolean {
            if (publicKey.size != 32 || message32.size != 32 || signature.size != 64) return false
            val secp = Secp256k1.get()
            return secp.verifySchnorr(signature, message32, publicKey)
        }
    }
}
