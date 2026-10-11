package io.github.agopalareddy.umm.sync

import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.Snapshot
import io.github.agopalareddy.umm.core.sync.SyncOp
import io.github.agopalareddy.umm.core.sync.SyncedHistory
import io.github.agopalareddy.umm.core.sync.SyncedStats
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SyncAuthException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

interface Wire {
    suspend fun send(message: ByteArray)
    suspend fun receive(): ByteArray
}

/**
 * A digest of every synced field of the rows one device holds for one origin, so that two devices whose rows differ
 * in any field notice it. The rows' own serialized form keeps the field order fixed.
 */
internal fun computeOriginWatermark(history: List<SyncedHistory>, stats: List<SyncedStats>): String {
    val md = MessageDigest.getInstance("SHA-256")
    val sortedHistory = history.sortedBy { it.originId }
    md.update("h:${sortedHistory.size};".encodeToByteArray())
    for (h in sortedHistory) {
        md.update(watermarkJson.encodeToString(SyncedHistory.serializer(), h).encodeToByteArray())
        md.update(';'.code.toByte())
    }
    val sortedStats = stats.sortedBy { it.historyId }
    md.update("s:${sortedStats.size};".encodeToByteArray())
    for (s in sortedStats) {
        md.update(watermarkJson.encodeToString(SyncedStats.serializer(), s).encodeToByteArray())
        md.update(';'.code.toByte())
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private val watermarkJson = Json { encodeDefaults = true }

@Serializable
sealed interface SessionMessage {
    @Serializable
    @SerialName("Hello")
    data class Hello(
        val deviceId: String,
        val epoch: Int,
        val marks: Map<String, Long>,
        val oldest: Map<String, Long>,
        val watermarks: Map<String, String> = emptyMap(),
    ) : SessionMessage

    @Serializable
    @SerialName("Ops")
    data class Ops(val list: List<OpEnvelope>) : SessionMessage

    @Serializable
    @SerialName("SnapshotMsg")
    data class SnapshotMsg(val snapshot: Snapshot) : SessionMessage

    @Serializable
    @SerialName("SyncData")
    data class SyncData(
        val snapshot: Snapshot? = null,
        val ops: List<OpEnvelope> = emptyList(),
    ) : SessionMessage

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
        val snap = snapshot()
        val deviceId = selfId ?: snap.from
        val currentCipher = cipher()
        val marks = log.highWaterMarks()
        val oldest = marks.keys.mapNotNull { origin ->
            log.oldestSeq(origin)?.let { origin to it }
        }.toMap()

        val allOrigins = (snap.history.map { it.originDevice } + snap.stats.map { it.originDevice }).toSet()
        val originWatermarks = allOrigins.associateWith { origin ->
            val h = snap.history.filter { it.originDevice == origin }
            val s = snap.stats.filter { it.originDevice == origin }
            computeOriginWatermark(h, s)
        }

        val myHello = SessionMessage.Hello(
            deviceId = deviceId,
            epoch = currentCipher.epoch,
            marks = marks,
            oldest = oldest,
            watermarks = originWatermarks,
        )

        val peerHello: SessionMessage.Hello
        var sentCount = 0
        var receivedCount = 0

        suspend fun decideOutgoing(peerHello: SessionMessage.Hello): SessionMessage {
            val peerMarks = peerHello.marks
            val peerWatermarks = peerHello.watermarks

            val isNewPeer = peerMarks.isEmpty() && peerWatermarks.isEmpty()
            val hasPrunedMarks = marks.isNotEmpty() && marks.keys.any { origin ->
                val peerMark = peerMarks[origin] ?: 0L
                val oldestKept = oldest[origin] ?: 1L
                oldestKept > 1L && peerMark < oldestKept
            }

            val needsFullSnapshot = isNewPeer || hasPrunedMarks
            val outgoingOps = log.since(peerMarks)

            if (needsFullSnapshot) {
                return SessionMessage.SyncData(snapshot = snap, ops = emptyList())
            }

            // An origin whose rows the outgoing ops add or change differs for that reason; those ops settle it, and
            // the session the engine runs right after this one sends the rows if a difference is still left.
            val originsWithOutgoingRows = outgoingOps.mapNotNull { envelope ->
                when (val op = envelope.op) {
                    is SyncOp.HistoryUpsert -> op.row.originDevice
                    is SyncOp.StatsUpsert -> op.row.originDevice
                    else -> null
                }
            }.toSet()
            val missingOrigins = originWatermarks.filter { (origin, wm) ->
                origin != peerHello.deviceId && (
                    peerWatermarks[origin] == null || (
                        peerWatermarks[origin] != wm && origin !in originsWithOutgoingRows
                    )
                )
            }.keys

            val snapshotToSend = if (missingOrigins.isNotEmpty()) {
                snap.copy(
                    history = snap.history.filter { it.originDevice in missingOrigins },
                    stats = snap.stats.filter { it.originDevice in missingOrigins },
                    settings = emptyMap(),
                    categories = emptyList(),
                    apps = emptyList(),
                    clears = snap.clears,
                    members = emptyList(),
                )
            } else {
                null
            }

            return SessionMessage.SyncData(snapshot = snapshotToSend, ops = outgoingOps)
        }

        suspend fun applyIncoming(msg: SessionMessage): Int {
            return when (msg) {
                is SessionMessage.Ops -> applier.apply(msg.list)
                is SessionMessage.SnapshotMsg -> {
                    applier.applySnapshot(msg.snapshot)
                    msg.snapshot.history.size + msg.snapshot.stats.size
                }
                is SessionMessage.SyncData -> {
                    var count = 0
                    if (msg.snapshot != null) {
                        applier.applySnapshot(msg.snapshot)
                        count += msg.snapshot.history.size + msg.snapshot.stats.size
                    }
                    if (msg.ops.isNotEmpty()) {
                        count += applier.apply(msg.ops)
                    }
                    count
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

            val outgoing = decideOutgoing(peerHello)
            sentCount = when (outgoing) {
                is SessionMessage.Ops -> outgoing.list.size
                is SessionMessage.SnapshotMsg -> outgoing.snapshot.history.size + outgoing.snapshot.stats.size
                is SessionMessage.SyncData -> {
                    val snapCount = outgoing.snapshot?.let { it.history.size + it.stats.size } ?: 0
                    snapCount + outgoing.ops.size
                }
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

            val outgoing = decideOutgoing(peerHello)
            sentCount = when (outgoing) {
                is SessionMessage.Ops -> outgoing.list.size
                is SessionMessage.SnapshotMsg -> outgoing.snapshot.history.size + outgoing.snapshot.stats.size
                is SessionMessage.SyncData -> {
                    val snapCount = outgoing.snapshot?.let { it.history.size + it.stats.size } ?: 0
                    snapCount + outgoing.ops.size
                }
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
