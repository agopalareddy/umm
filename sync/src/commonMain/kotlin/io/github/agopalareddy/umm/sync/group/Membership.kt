package io.github.agopalareddy.umm.sync.group

import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.SyncOp
import io.github.agopalareddy.umm.sync.crypto.MemberSeal
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class Membership(
    val group: GroupStore,
    val log: OpLog,
    val now: () -> Long,
) {
    suspend fun remove(deviceId: String) {
        val state = group.state.value ?: return
        val remaining = state.members.filter { it.deviceId != deviceId }
        log.record(SyncOp.MemberRemove(deviceId))
        rotateForRemaining(remaining, state)
    }

    suspend fun leave() {
        val state = group.state.value ?: return
        val myPubkey = group.device.publicKey.toHex()
        val selfMember = state.members.find { it.publicKey.equals(myPubkey, ignoreCase = true) }
        val selfDeviceId = selfMember?.deviceId ?: return
        log.record(SyncOp.MemberRemove(selfDeviceId))
        group.clear()
    }

    suspend fun rename(name: String) {
        group.deviceName = name
        val state = group.state.value ?: return
        val myPubkey = group.device.publicKey.toHex()
        val selfMember = state.members.find { it.publicKey.equals(myPubkey, ignoreCase = true) } ?: return
        val updatedMember = selfMember.copy(name = name)
        val updatedMembers = state.members.map { if (it.deviceId == selfMember.deviceId) updatedMember else it }
        group.save(state.copy(members = updatedMembers))
        log.record(SyncOp.MemberUpsert(updatedMember))
    }

    suspend fun onGroupOp(envelope: OpEnvelope) {
        when (val op = envelope.op) {
            is SyncOp.MemberUpsert -> {
                val state = group.state.value ?: return
                val filtered = state.members.filter { it.deviceId != op.member.deviceId }
                group.save(state.copy(members = filtered + op.member))
            }
            is SyncOp.MemberRemove -> {
                val state = group.state.value ?: return
                val myPubkey = group.device.publicKey.toHex()
                val selfMember = state.members.find { it.publicKey.equals(myPubkey, ignoreCase = true) }
                if (selfMember?.deviceId == op.deviceId) {
                    group.clear()
                } else {
                    val remaining = state.members.filter { it.deviceId != op.deviceId }
                    if (envelope.origin == op.deviceId) {
                        // Voluntary leave: remaining members rotate
                        val coordinator = remaining.minByOrNull { it.deviceId }?.deviceId
                        if (coordinator == selfMember?.deviceId) {
                            rotateForRemaining(remaining, state)
                        } else {
                            group.save(state.copy(members = remaining))
                        }
                    } else {
                        group.save(state.copy(members = remaining))
                    }
                }
            }
            is SyncOp.KeyRotation -> {
                val state = group.state.value ?: return
                if (op.epoch <= state.epoch) return
                val myPubkey = group.device.publicKey.toHex()
                val selfMember = state.members.find { it.publicKey.equals(myPubkey, ignoreCase = true) } ?: return
                val sealed = op.sealed[selfMember.deviceId] ?: op.sealed[myPubkey]
                if (sealed != null) {
                    val senderMember = state.members.find { it.deviceId == envelope.origin }
                    val senderPubKey = senderMember?.let { hexToBytes(it.publicKey) }
                    if (senderPubKey != null) {
                        val decryptedKey = MemberSeal.open(recipient = group.device, sender = senderPubKey, sealed = sealed)
                        val newKeyBase64 = Base64.UrlSafe.encode(decryptedKey)
                        val remaining = state.members.filter { it.deviceId in op.sealed.keys || it.publicKey in op.sealed.keys }
                        val updated = state.copy(
                            key = newKeyBase64,
                            epoch = op.epoch,
                            previousKey = state.key,
                            previousUntil = now() + 24 * 3600 * 1000L,
                            members = if (remaining.isNotEmpty()) remaining else state.members,
                        )
                        group.save(updated)
                    }
                }
            }
            else -> Unit
        }
    }

    private suspend fun rotateForRemaining(remaining: List<Member>, state: GroupState) {
        val nextEpoch = state.epoch + 1
        val newKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val newKeyBase64 = Base64.UrlSafe.encode(newKeyBytes)
        val sealedMap = mutableMapOf<String, String>()
        for (m in remaining) {
            val sealedKey = MemberSeal.seal(sender = group.device, recipient = hexToBytes(m.publicKey), plain = newKeyBytes)
            sealedMap[m.deviceId] = sealedKey
        }
        log.record(SyncOp.KeyRotation(nextEpoch, sealedMap))
        val updated = state.copy(
            key = newKeyBase64,
            epoch = nextEpoch,
            previousKey = state.key,
            previousUntil = now() + 24 * 3600 * 1000L,
            members = remaining,
        )
        group.save(updated)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        require(len % 2 == 0) { "Invalid hex length: $len" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(clean[i], 16)
            val low = Character.digit(clean[i + 1], 16)
            require(high != -1 && low != -1) { "Invalid hex char at $i" }
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }
}
