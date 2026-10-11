package io.github.agopalareddy.umm.sync

import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.Snapshot
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SyncAuthException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

interface Wire {
    suspend fun send(message: ByteArray)
    suspend fun receive(): ByteArray
}

@Serializable
sealed interface SessionMessage {
    @Serializable
    @SerialName("Hello")
    data class Hello(
        val deviceId: String,
        val epoch: Int,
        val marks: Map<String, Long>,
        val oldest: Map<String, Long>,
    ) : SessionMessage

    @Serializable
    @SerialName("Ops")
    data class Ops(val list: List<OpEnvelope>) : SessionMessage

    @Serializable
    @SerialName("SnapshotMsg")
    data class SnapshotMsg(val snapshot: Snapshot) : SessionMessage

    @Serializable
    @SerialName("Done")
    data object Done : SessionMessage
}

data class SessionResult(val sent: Int, val received: Int)

class SyncSession(
    private val log: OpLog,
    private val applier: OpApplier,
    private val snapshot: suspend () -> Snapshot,
    private val cipher: () -> GroupCipher,
    val selfId: String? = null,
) {
    var onPeerSynced: (suspend (peerDeviceId: String) -> Unit)? = null

    private val sessionJson = Json {
        ignoreUnknownKeys = true
    }

    private suspend fun sendMsg(wire: Wire, msg: SessionMessage) {
        val jsonString = sessionJson.encodeToString(SessionMessage.serializer(), msg)
        val plain = jsonString.encodeToByteArray()
        val sealed = cipher().seal(plain)
        wire.send(sealed)
    }

    private suspend fun receiveMsg(wire: Wire): SessionMessage {
        val sealed = wire.receive()
        val plain = try {
            cipher().open(sealed)
        } catch (e: Exception) {
            throw SyncAuthException("Failed to open sealed session message", e)
        }
        return try {
            val jsonString = plain.decodeToString()
            sessionJson.decodeFromString(SessionMessage.serializer(), jsonString)
        } catch (e: Exception) {
            throw SyncAuthException("Failed to deserialize session message", e)
        }
    }

    suspend fun run(wire: Wire, initiator: Boolean): SessionResult {
        val deviceId = selfId ?: snapshot().from
        val currentCipher = cipher()
        val marks = log.highWaterMarks()
        val oldest = marks.keys.mapNotNull { origin ->
            log.oldestSeq(origin)?.let { origin to it }
        }.toMap()

        val myHello = SessionMessage.Hello(
            deviceId = deviceId,
            epoch = currentCipher.epoch,
            marks = marks,
            oldest = oldest,
        )

        val peerHello: SessionMessage.Hello
        var sentCount = 0
        var receivedCount = 0

        suspend fun decideOutgoing(peerMarks: Map<String, Long>): SessionMessage {
            val needsSnapshot = marks.isNotEmpty() && (
                peerMarks.isEmpty() ||
                marks.keys.any { origin ->
                    val peerMark = peerMarks[origin] ?: 0L
                    val oldestKept = oldest[origin] ?: 1L
                    oldestKept > 1L && peerMark < oldestKept
                }
            )
            return if (needsSnapshot) {
                SessionMessage.SnapshotMsg(snapshot())
            } else {
                SessionMessage.Ops(log.since(peerMarks))
            }
        }

        suspend fun applyIncoming(msg: SessionMessage): Int {
            return when (msg) {
                is SessionMessage.Ops -> applier.apply(msg.list)
                is SessionMessage.SnapshotMsg -> {
                    applier.applySnapshot(msg.snapshot)
                    msg.snapshot.history.size + msg.snapshot.stats.size
                }
                else -> 0
            }
        }

        if (initiator) {
            sendMsg(wire, myHello)
            val received = receiveMsg(wire)
            if (received !is SessionMessage.Hello) {
                throw SyncAuthException("Expected Hello message from peer, got $received")
            }
            peerHello = received

            val outgoing = decideOutgoing(peerHello.marks)
            sentCount = when (outgoing) {
                is SessionMessage.Ops -> outgoing.list.size
                is SessionMessage.SnapshotMsg -> outgoing.snapshot.history.size + outgoing.snapshot.stats.size
                else -> 0
            }
            sendMsg(wire, outgoing)

            val incomingData = receiveMsg(wire)
            receivedCount = applyIncoming(incomingData)

            sendMsg(wire, SessionMessage.Done)
            val peerDone = receiveMsg(wire)
            if (peerDone !is SessionMessage.Done) {
                throw SyncAuthException("Expected Done message from peer, got $peerDone")
            }
        } else {
            val received = receiveMsg(wire)
            if (received !is SessionMessage.Hello) {
                throw SyncAuthException("Expected Hello message from peer, got $received")
            }
            peerHello = received
            sendMsg(wire, myHello)

            val incomingData = receiveMsg(wire)
            receivedCount = applyIncoming(incomingData)

            val outgoing = decideOutgoing(peerHello.marks)
            sentCount = when (outgoing) {
                is SessionMessage.Ops -> outgoing.list.size
                is SessionMessage.SnapshotMsg -> outgoing.snapshot.history.size + outgoing.snapshot.stats.size
                else -> 0
            }
            sendMsg(wire, outgoing)

            val peerDone = receiveMsg(wire)
            if (peerDone !is SessionMessage.Done) {
                throw SyncAuthException("Expected Done message from peer, got $peerDone")
            }
            sendMsg(wire, SessionMessage.Done)
        }

        onPeerSynced?.invoke(peerHello.deviceId)
        return SessionResult(sent = sentCount, received = receivedCount)
    }
}
