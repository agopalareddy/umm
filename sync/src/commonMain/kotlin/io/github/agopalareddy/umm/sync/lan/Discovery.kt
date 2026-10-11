package io.github.agopalareddy.umm.sync.lan

import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

data class LanPeer(val deviceId: String, val host: InetAddress, val port: Int)

interface Discovery {
    fun advertise(port: Int, groupHash: String, deviceId: String): AutoCloseable
    fun browse(groupHash: String): Flow<LanPeer>
}

fun groupHash(groupId: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(groupId.encodeToByteArray())
    return digest.take(8).joinToString("") { "%02x".format(it) }
}
