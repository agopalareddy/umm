package io.github.agopalareddy.umm.sync.pair

import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.Snapshot
import io.github.agopalareddy.umm.sync.crypto.Hkdf
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.lan.FrameCodec
import io.github.agopalareddy.umm.sync.lan.LanTransport
import io.github.agopalareddy.umm.sync.relay.KIND_PAIRING
import io.github.agopalareddy.umm.sync.relay.NostrEvent
import io.github.agopalareddy.umm.sync.relay.RelayClient
import io.github.agopalareddy.umm.sync.relay.RelayTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal enum class HandshakePhase {
    IDLE,
    BOUND,
    COMMITTING,
    DONE,
}

internal class PairingCodeCoordinator(
    private val secret: String,
    private val isSecretUsed: (String) -> Boolean,
    private val markSecretUsed: (String) -> Unit,
) {
    val mutex = Mutex()
    var phase: HandshakePhase = HandshakePhase.IDLE
        private set
    var currentHandshakeId: String? = null
        private set

    suspend fun tryBind(handshakeId: String): Boolean = mutex.withLock {
        if (isSecretUsed(secret)) return false
        if (phase != HandshakePhase.IDLE) return false
        phase = HandshakePhase.BOUND
        currentHandshakeId = handshakeId
        true
    }

    suspend fun holdsClaim(handshakeId: String): Boolean = mutex.withLock {
        (phase == HandshakePhase.BOUND || phase == HandshakePhase.COMMITTING) && currentHandshakeId == handshakeId
    }

    suspend fun onDeadlineExpiry(handshakeId: String): Boolean = mutex.withLock {
        if (phase == HandshakePhase.BOUND && currentHandshakeId == handshakeId) {
            phase = HandshakePhase.IDLE
            currentHandshakeId = null
            true
        } else {
            false
        }
    }

    suspend fun release(handshakeId: String) = mutex.withLock {
        if (phase == HandshakePhase.BOUND && currentHandshakeId == handshakeId) {
            phase = HandshakePhase.IDLE
            currentHandshakeId = null
        }
    }

    suspend fun saveAndCommit(
        handshakeId: String,
        preSaveHook: (suspend () -> Unit)? = null,
        saveAction: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (phase != HandshakePhase.BOUND || currentHandshakeId != handshakeId) {
            return false
        }
        phase = HandshakePhase.COMMITTING
        try {
            preSaveHook?.invoke()
            saveAction()
            phase = HandshakePhase.DONE
            currentHandshakeId = null
            markSecretUsed(secret)
            true
        } catch (e: Throwable) {
            phase = HandshakePhase.IDLE
            currentHandshakeId = null
            throw e
        }
    }
}

data class PairedDevice(val name: String, val keyReceived: Boolean)

class PairingOffer(val code: PairingCode, val result: Deferred<PairedDevice>)

@Serializable
sealed interface PairMsg {
    @Serializable
    @SerialName("Hello")
    data class Hello(
        val deviceId: String,
        val name: String,
        val publicKey: String,
        val platform: String = "desktop",
        val inGroup: Boolean = false,
        val hmac: String,
        val handshakeId: String = "",
    ) : PairMsg

    @Serializable
    @SerialName("Confirm")
    data class Confirm(
        val deviceId: String,
        val name: String,
        val hmac: String,
        val sealedWelcome: String? = null,
        val handshakeId: String = "",
    ) : PairMsg

    @Serializable
    @SerialName("Welcome")
    data class Welcome(
        val sealedPayload: String,
        val handshakeId: String = "",
        val hmac: String = "",
    ) : PairMsg

    @Serializable
    @SerialName("Ack")
    data class Ack(
        val success: Boolean = true,
        val handshakeId: String = "",
        val hmac: String = "",
    ) : PairMsg

    @Serializable
    @SerialName("Error")
    data class Error(
        val error: PairingError,
        val message: String = error.name,
        val handshakeId: String = "",
        val hmac: String = "",
    ) : PairMsg
}

@Serializable
data class WelcomePayload(
    val group: GroupState,
    val apiKey: String? = null,
    val snapshot: Snapshot? = null,
)

@OptIn(ExperimentalEncodingApi::class)
class Pairing(
    val group: GroupStore,
    val apiKey: ApiKeyStore,
    val lan: LanTransport?,
    val relay: RelayTransport,
    val applier: OpApplier,
    val snapshot: suspend () -> Snapshot,
    val now: () -> Long,
    val defaultRelays: List<String> = GroupState.DEFAULT_RELAYS,
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 15_000,
    val handshakeDeadlineMs: Long = 30_000L,
    internal var preSaveHook: (suspend () -> Unit)? = null,
) {
    private val scope: CoroutineScope get() = relay.scope

    private val json = Json { ignoreUnknownKeys = true }

    fun offer(sendKey: Boolean): PairingOffer {
        val myDeviceId = getOwnDeviceId()
        val myName = group.deviceName
        val myPubKey = group.device.publicKey.toHex()
        val secret = generateSecret()
        val relays = group.state.value?.relays ?: defaultRelays
        val expiresAt = now() + 10 * 60 * 1000L

        val server = if (lan != null) {
            runCatching { ServerSocket(0) }.getOrNull()
        } else null

        val port = server?.localPort ?: 0
        val lanIps = if (server != null) getLocalIpAddresses() else emptyList()

        val code = PairingCode(
            deviceId = myDeviceId,
            name = myName,
            publicKey = myPubKey,
            secret = secret,
            lan = lanIps,
            port = port,
            relays = relays,
            expiresAt = expiresAt,
        )

        val deferred = CompletableDeferred<PairedDevice>()
        val completed = AtomicBoolean(false)

        val offerJob = scope.launch(Dispatchers.IO) {
            try {
                withTimeout(10 * 60 * 1000L) {
                    val jobs = mutableListOf<Job>()

                    // 1. LAN listener
                    if (server != null) {
                        jobs.add(launch {
                            while (isActive && !server.isClosed && !completed.get()) {
                                val clientSocket = try {
                                    server.accept()
                                } catch (_: Exception) {
                                    break
                                }
                                launch {
                                    handleLanClient(clientSocket, code, sendKey, completed, deferred, server)
                                }
                            }
                        })
                    }

                    // 2. Relay listener
                    if (relays.isNotEmpty()) {
                        for (relayUrl in relays) {
                            jobs.add(launch {
                                handleRelayOffer(relayUrl, code, sendKey, completed, deferred, server)
                            })
                        }
                    }

                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                deferred.cancel(CancellationException("Offer expired after 10 min", e))
            } catch (e: Throwable) {
                if (!deferred.isCompleted) deferred.completeExceptionally(e)
            } finally {
                runCatching { server?.close() }
            }
        }

        deferred.invokeOnCompletion {
            offerJob.cancel()
            runCatching { server?.close() }
        }

        return PairingOffer(code, deferred)
    }

    suspend fun join(code: String, sendKey: Boolean): PairedDevice = withContext(Dispatchers.IO) {
        val pairingCode = PairingCode.decode(code, now())
        val handshakeId = generateHandshakeId()
        val coordinator = getCoordinator(pairingCode.secret)
        if (!coordinator.tryBind(handshakeId)) {
            throw PairingException(PairingError.USED)
        }

        var claimCommitted = false
        try {
            // Try LAN first
            var lanSuccess: PairedDevice? = null
            if (pairingCode.lan.isNotEmpty() && pairingCode.port > 0) {
                for (ip in pairingCode.lan) {
                    try {
                        val result = joinOverLan(ip, pairingCode.port, pairingCode, sendKey, handshakeId)
                        lanSuccess = result
                        break
                    } catch (e: PairingException) {
                        // Protocol error: fail immediately
                        throw e
                    } catch (_: Exception) {
                        // Unreachable on this IP, continue
                    }
                }
            }

            if (lanSuccess != null) {
                claimCommitted = true
                return@withContext lanSuccess
            }

            // Fallback to relay
            if (pairingCode.relays.isNotEmpty()) {
                for (relayUrl in pairingCode.relays) {
                    try {
                        val result = joinOverRelay(relayUrl, pairingCode, sendKey, handshakeId)
                        claimCommitted = true
                        return@withContext result
                    } catch (e: PairingException) {
                        throw e
                    } catch (_: Exception) {
                        // Connection error on this relay, continue
                    }
                }
            }

            throw PairingException(PairingError.UNREACHABLE, "Unable to reach peer over LAN or relay")
        } finally {
            if (!claimCommitted) {
                getCoordinator(pairingCode.secret).release(handshakeId)
            }
        }
    }

    private suspend fun handleLanClient(
        socket: Socket,
        code: PairingCode,
        sendKey: Boolean,
        completed: AtomicBoolean,
        deferred: CompletableDeferred<PairedDevice>,
        server: ServerSocket?,
    ) {
        socket.soTimeout = readTimeoutMs
        var handshakeId: String? = null
        var claimCommitted = false
        try {
            withTimeout(handshakeDeadlineMs) {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val helloBytes = FrameCodec.read(input)
                val decoded = json.decodeFromString(PairMsg.serializer(), helloBytes.decodeToString())
                if (decoded !is PairMsg.Hello) {
                    return@withTimeout
                }
                val hello = decoded

                val hid = hello.handshakeId.ifEmpty { generateHandshakeId() }
                handshakeId = hid
                val coordinator = getCoordinator(code.secret)
                if (!coordinator.tryBind(hid)) {
                    val err = PairMsg.Error(PairingError.USED)
                    runCatching {
                        FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), err).encodeToByteArray())
                    }
                    return@withTimeout
                }

                val peerPubKeyBytes = hexToBytes(hello.publicKey)
                val agree = group.device.agree(peerPubKeyBytes)
                val myDeviceId = getOwnDeviceId()
                val salt = code.deviceId.encodeToByteArray() + hello.deviceId.encodeToByteArray()
                val k = Hkdf.derive(agree + code.secret.encodeToByteArray(), salt, "umm-pair", 32)

                val expectedTranscript = helloTranscript(
                    handshakeId = hid,
                    offererDeviceId = code.deviceId,
                    joinerDeviceId = hello.deviceId,
                    offererPubKey = code.publicKey,
                    joinerPubKey = hello.publicKey,
                    inGroup = hello.inGroup,
                    name = hello.name,
                    platform = hello.platform,
                )
                if (!verifyHmac(k, expectedTranscript, hello.hmac)) {
                    coordinator.release(hid)
                    val err = PairMsg.Error(PairingError.WRONG_SECRET)
                    FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), err).encodeToByteArray())
                    return@withTimeout
                }

                // State handover
                val offererHasGroup = group.state.value != null
                if (shouldJoinerSendWelcome(joinerInGroup = hello.inGroup, offererHasGroup = offererHasGroup)) {
                    // Joiner has group and offerer does not: joiner sends group
                    val myTranscript = confirmTranscript(
                        handshakeId = hid,
                        offererDeviceId = code.deviceId,
                        joinerDeviceId = hello.deviceId,
                        offererPubKey = code.publicKey,
                        joinerPubKey = hello.publicKey,
                        name = group.deviceName,
                        hasWelcome = false,
                    )
                    val myHmac = computeHmac(k, myTranscript)
                    val confirm = PairMsg.Confirm(
                        deviceId = myDeviceId,
                        name = group.deviceName,
                        hmac = myHmac,
                        sealedWelcome = null,
                        handshakeId = hid,
                    )
                    FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), confirm).encodeToByteArray())

                    val welcomeBytes = FrameCodec.read(input)
                    val welcome = json.decodeFromString(PairMsg.serializer(), welcomeBytes.decodeToString())
                    if (welcome !is PairMsg.Welcome) {
                        return@withTimeout
                    }
                    if (welcome.handshakeId.isNotEmpty() && welcome.handshakeId != hid) {
                        return@withTimeout
                    }
                    val expectedWelcomeTranscript = welcomeTranscript(
                        handshakeId = hid,
                        offererDeviceId = code.deviceId,
                        joinerDeviceId = hello.deviceId,
                        sealedPayload = welcome.sealedPayload,
                    )
                    if (welcome.hmac.isNotEmpty() && !verifyHmac(k, expectedWelcomeTranscript, welcome.hmac)) {
                        return@withTimeout
                    }

                    val payload = openPayload(k, welcome.sealedPayload)
                    val saved = coordinator.saveAndCommit(hid, preSaveHook) {
                        group.save(payload.group)
                    }
                    if (!saved) return@withTimeout
                    claimCommitted = true

                    val keyRecv = if (payload.apiKey != null) {
                        apiKey.set(payload.apiKey)
                        true
                    } else false
                    if (payload.snapshot != null) {
                        applier.applySnapshot(payload.snapshot)
                    }

                    val ackTranscriptBytes = ackTranscript(
                        handshakeId = hid,
                        offererDeviceId = code.deviceId,
                        joinerDeviceId = hello.deviceId,
                        success = true,
                    )
                    val ackHmac = computeHmac(k, ackTranscriptBytes)
                    val ack = PairMsg.Ack(success = true, handshakeId = hid, hmac = ackHmac)
                    FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), ack).encodeToByteArray())

                    if (completed.compareAndSet(false, true)) {
                        deferred.complete(PairedDevice(name = hello.name, keyReceived = keyRecv))
                        runCatching { server?.close() }
                    }
                } else {
                    // Offerer sends group (either offerer has group, or neither has group)
                    val joinerMember = Member(deviceId = hello.deviceId, name = hello.name, platform = hello.platform, publicKey = hello.publicKey)
                    val finalGroup = if (offererHasGroup) {
                        val existing = group.state.value!!
                        existing.copy(members = existing.members.filter { it.deviceId != joinerMember.deviceId } + joinerMember)
                    } else {
                        GroupState(
                            groupId = UUID.randomUUID().toString(),
                            key = Base64.UrlSafe.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }),
                            epoch = 0,
                            previousKey = null,
                            previousUntil = 0L,
                            members = listOf(
                                Member(myDeviceId, group.deviceName, "desktop", group.device.publicKey.toHex()),
                                joinerMember
                            ),
                            relays = code.relays,
                        )
                    }

                    val keyToSend = if (sendKey) apiKey.get() else null
                    val snap = snapshot()
                    val payload = WelcomePayload(group = finalGroup, apiKey = keyToSend, snapshot = snap)
                    val sealedWelcome = sealPayload(k, payload)

                    val myTranscript = confirmTranscript(
                        handshakeId = hid,
                        offererDeviceId = code.deviceId,
                        joinerDeviceId = hello.deviceId,
                        offererPubKey = code.publicKey,
                        joinerPubKey = hello.publicKey,
                        name = group.deviceName,
                        hasWelcome = true,
                    )
                    val myHmac = computeHmac(k, myTranscript)
                    val confirm = PairMsg.Confirm(
                        deviceId = myDeviceId,
                        name = group.deviceName,
                        hmac = myHmac,
                        sealedWelcome = sealedWelcome,
                        handshakeId = hid,
                    )
                    FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), confirm).encodeToByteArray())

                    val ackBytes = FrameCodec.read(input)
                    val ack = json.decodeFromString(PairMsg.serializer(), ackBytes.decodeToString())
                    if (ack is PairMsg.Ack) {
                        if (ack.handshakeId.isNotEmpty() && ack.handshakeId != hid) {
                            return@withTimeout
                        }
                        val expectedAckTranscript = ackTranscript(
                            handshakeId = hid,
                            offererDeviceId = code.deviceId,
                            joinerDeviceId = hello.deviceId,
                            success = ack.success,
                        )
                        if (ack.hmac.isNotEmpty() && !verifyHmac(k, expectedAckTranscript, ack.hmac)) {
                            return@withTimeout
                        }

                        val saved = coordinator.saveAndCommit(hid, preSaveHook) {
                            group.save(finalGroup)
                        }
                        if (!saved) return@withTimeout
                        claimCommitted = true

                        if (completed.compareAndSet(false, true)) {
                            deferred.complete(PairedDevice(name = hello.name, keyReceived = false))
                            runCatching { server?.close() }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (!claimCommitted && handshakeId != null) {
                getCoordinator(code.secret).release(handshakeId)
            }
            runCatching { socket.close() }
        }
    }

    private suspend fun joinOverLan(
        host: String,
        port: Int,
        code: PairingCode,
        sendKey: Boolean,
        handshakeId: String,
    ): PairedDevice = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            withTimeout(handshakeDeadlineMs) {
                socket.soTimeout = readTimeoutMs
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)

                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val myDeviceId = getOwnDeviceId()
                val peerPubKeyBytes = hexToBytes(code.publicKey)
                val agree = group.device.agree(peerPubKeyBytes)
                val salt = code.deviceId.encodeToByteArray() + myDeviceId.encodeToByteArray()
                val k = Hkdf.derive(agree + code.secret.encodeToByteArray(), salt, "umm-pair", 32)
                val myPubKey = group.device.publicKey.toHex()
                val myTranscript = helloTranscript(
                    handshakeId = handshakeId,
                    offererDeviceId = code.deviceId,
                    joinerDeviceId = myDeviceId,
                    offererPubKey = code.publicKey,
                    joinerPubKey = myPubKey,
                    inGroup = group.state.value != null,
                    name = group.deviceName,
                    platform = "desktop",
                )
                val myHmac = computeHmac(k, myTranscript)

                val hello = PairMsg.Hello(
                    deviceId = myDeviceId,
                    name = group.deviceName,
                    publicKey = myPubKey,
                    platform = "desktop",
                    inGroup = group.state.value != null,
                    hmac = myHmac,
                    handshakeId = handshakeId,
                )
                FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), hello).encodeToByteArray())

                val replyBytes = FrameCodec.read(input)
                val reply = json.decodeFromString(PairMsg.serializer(), replyBytes.decodeToString())

                when (reply) {
                    is PairMsg.Error -> throw PairingException(reply.error, reply.message)
                    is PairMsg.Confirm -> {
                        if (reply.handshakeId.isNotEmpty() && reply.handshakeId != handshakeId) {
                            throw PairingException(PairingError.WRONG_SECRET)
                        }
                        val expectedTranscript = confirmTranscript(
                            handshakeId = handshakeId,
                            offererDeviceId = code.deviceId,
                            joinerDeviceId = myDeviceId,
                            offererPubKey = code.publicKey,
                            joinerPubKey = myPubKey,
                            name = reply.name,
                            hasWelcome = (reply.sealedWelcome != null),
                        )
                        if (!verifyHmac(k, expectedTranscript, reply.hmac)) {
                            throw PairingException(PairingError.WRONG_SECRET)
                        }

                        if (reply.sealedWelcome != null) {
                            // Offerer sent group
                            val payload = openPayload(k, reply.sealedWelcome)
                            val coordinator = getCoordinator(code.secret)
                            val saved = coordinator.saveAndCommit(handshakeId, preSaveHook) {
                                group.save(payload.group)
                            }
                            if (!saved) throw PairingException(PairingError.UNREACHABLE, "Handshake expired")

                            val keyRecv = if (payload.apiKey != null) {
                                apiKey.set(payload.apiKey)
                                true
                            } else false
                            if (payload.snapshot != null) {
                                applier.applySnapshot(payload.snapshot)
                            }

                            val ackTranscriptBytes = ackTranscript(
                                handshakeId = handshakeId,
                                offererDeviceId = code.deviceId,
                                joinerDeviceId = myDeviceId,
                                success = true,
                            )
                            val ackHmac = computeHmac(k, ackTranscriptBytes)
                            val ack = PairMsg.Ack(success = true, handshakeId = handshakeId, hmac = ackHmac)
                            FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), ack).encodeToByteArray())

                            return@withTimeout PairedDevice(name = code.name, keyReceived = keyRecv)
                        } else {
                            // Joiner sends group
                            val currentState = group.state.value ?: throw IllegalStateException("Joiner has no group")
                            val offererMember = Member(deviceId = reply.deviceId, name = reply.name, platform = "desktop", publicKey = code.publicKey)
                            val updatedGroup = currentState.copy(
                                members = currentState.members.filter { it.deviceId != offererMember.deviceId } + offererMember
                            )
                            val keyToSend = if (sendKey) apiKey.get() else null
                            val snap = snapshot()
                            val payload = WelcomePayload(group = updatedGroup, apiKey = keyToSend, snapshot = snap)
                            val sealedWelcome = sealPayload(k, payload)

                            val welcomeTranscriptBytes = welcomeTranscript(
                                handshakeId = handshakeId,
                                offererDeviceId = code.deviceId,
                                joinerDeviceId = myDeviceId,
                                sealedPayload = sealedWelcome,
                            )
                            val welcomeHmac = computeHmac(k, welcomeTranscriptBytes)
                            val welcomeMsg = PairMsg.Welcome(
                                sealedPayload = sealedWelcome,
                                handshakeId = handshakeId,
                                hmac = welcomeHmac,
                            )
                            FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), welcomeMsg).encodeToByteArray())

                            val ackBytes = FrameCodec.read(input)
                            val ack = json.decodeFromString(PairMsg.serializer(), ackBytes.decodeToString())
                            if (ack !is PairMsg.Ack) throw IOException("Expected Ack")
                            if (ack.handshakeId.isNotEmpty() && ack.handshakeId != handshakeId) {
                                throw IOException("Ack handshakeId mismatch")
                            }
                            val expectedAckTranscript = ackTranscript(
                                handshakeId = handshakeId,
                                offererDeviceId = code.deviceId,
                                joinerDeviceId = myDeviceId,
                                success = ack.success,
                            )
                            if (ack.hmac.isNotEmpty() && !verifyHmac(k, expectedAckTranscript, ack.hmac)) {
                                throw IOException("Ack HMAC mismatch")
                            }

                            val coordinator = getCoordinator(code.secret)
                            val saved = coordinator.saveAndCommit(handshakeId, preSaveHook) {
                                group.save(updatedGroup)
                            }
                            if (!saved) throw PairingException(PairingError.UNREACHABLE, "Handshake expired")
                            return@withTimeout PairedDevice(name = code.name, keyReceived = false)
                        }
                    }
                    else -> throw IOException("Unexpected reply: $reply")
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun handleRelayOffer(
        relayUrl: String,
        code: PairingCode,
        sendKey: Boolean,
        completed: AtomicBoolean,
        deferred: CompletableDeferred<PairedDevice>,
        server: ServerSocket?,
    ) {
        val client = RelayClient(relayUrl, relay.http)
        var boundHandshakeId: String? = null
        var handshakeTimerJob: Job? = null
        try {
            val handshakeStartTime = now() / 1000L
            val sinceTime = handshakeStartTime - 60L
            val myPubKey = group.device.publicKey.toHex()
            val filter = buildJsonObject {
                put("kinds", buildJsonArray { add(KIND_PAIRING) })
                put("#p", buildJsonArray { add(myPubKey.lowercase()) })
                put("since", JsonPrimitive(maxOf(0L, sinceTime)))
            }

            var boundPeerPubKey: String? = null
            var boundPeerName: String? = null
            var boundSessionKey: ByteArray? = null
            var boundJoinerDeviceId: String? = null
            client.subscribe("pair-offer", filter).collect { event ->
                if (event.kind != KIND_PAIRING || completed.get()) return@collect
                if (event.created_at < sinceTime) return@collect
                if (!event.verify()) return@collect
                val msg = try {
                    json.decodeFromString(PairMsg.serializer(), event.content)
                } catch (_: Exception) {
                    return@collect
                }

                if (msg is PairMsg.Error) {
                    if (boundPeerPubKey != null && event.pubkey.equals(boundPeerPubKey, ignoreCase = true) &&
                        boundSessionKey != null && boundHandshakeId != null && boundJoinerDeviceId != null
                    ) {
                        if (msg.handshakeId == boundHandshakeId) {
                            val expectedTranscript = errorTranscript(
                                handshakeId = boundHandshakeId!!,
                                offererDeviceId = code.deviceId,
                                joinerDeviceId = boundJoinerDeviceId!!,
                                error = msg.error.name,
                                message = msg.message,
                            )
                            if (verifyHmac(boundSessionKey!!, expectedTranscript, msg.hmac)) {
                                deferred.completeExceptionally(PairingException(msg.error, msg.message))
                            }
                        }
                    }
                    return@collect
                }

                if (msg is PairMsg.Welcome) {
                    if (boundPeerPubKey != null && event.pubkey.equals(boundPeerPubKey, ignoreCase = true) &&
                        boundSessionKey != null && boundHandshakeId != null && boundJoinerDeviceId != null
                    ) {
                        val hid = boundHandshakeId!!
                        if (msg.handshakeId != hid) return@collect

                        val sessionKey = boundSessionKey!!
                        val joinerDevId = boundJoinerDeviceId!!
                        val expectedWelcomeTranscript = welcomeTranscript(
                            handshakeId = hid,
                            offererDeviceId = code.deviceId,
                            joinerDeviceId = joinerDevId,
                            sealedPayload = msg.sealedPayload,
                        )
                        if (!verifyHmac(sessionKey, expectedWelcomeTranscript, msg.hmac)) {
                            return@collect
                        }

                        val coordinator = getCoordinator(code.secret)
                        val payload = openPayload(sessionKey, msg.sealedPayload)
                        val saved = coordinator.saveAndCommit(hid, preSaveHook) {
                            group.save(payload.group)
                        }
                        if (!saved) return@collect
                        handshakeTimerJob?.cancel()
                        boundHandshakeId = null
                        boundPeerPubKey = null
                        boundPeerName = null
                        boundSessionKey = null
                        boundJoinerDeviceId = null

                        val keyRecv = if (payload.apiKey != null) {
                            apiKey.set(payload.apiKey)
                            true
                        } else false
                        if (payload.snapshot != null) {
                            applier.applySnapshot(payload.snapshot)
                        }

                        val ackTranscriptBytes = ackTranscript(
                            handshakeId = hid,
                            offererDeviceId = code.deviceId,
                            joinerDeviceId = joinerDevId,
                            success = true,
                        )
                        val ackHmac = computeHmac(sessionKey, ackTranscriptBytes)
                        val ack = PairMsg.Ack(success = true, handshakeId = hid, hmac = ackHmac)
                        val ackEvent = NostrEvent.sign(
                            keys = group.device,
                            kind = KIND_PAIRING,
                            tags = listOf(listOf("p", event.pubkey.lowercase())),
                            content = json.encodeToString(PairMsg.serializer(), ack),
                            createdAt = now() / 1000L,
                        )
                        client.publish(ackEvent)

                        if (completed.compareAndSet(false, true)) {
                            deferred.complete(PairedDevice(name = boundPeerName ?: "", keyReceived = keyRecv))
                            runCatching { server?.close() }
                        }
                    }
                    return@collect
                }

                if (msg is PairMsg.Hello) {
                    if (!event.pubkey.equals(msg.publicKey, ignoreCase = true)) {
                        return@collect
                    }
                    if (msg.handshakeId.isEmpty()) {
                        return@collect
                    }

                    val peerPubKeyBytes = runCatching { hexToBytes(msg.publicKey) }.getOrNull() ?: return@collect
                    val agree = runCatching { group.device.agree(peerPubKeyBytes) }.getOrNull() ?: return@collect
                    val myDeviceId = getOwnDeviceId()
                    val salt = code.deviceId.encodeToByteArray() + msg.deviceId.encodeToByteArray()
                    val k = Hkdf.derive(agree + code.secret.encodeToByteArray(), salt, "umm-pair", 32)

                    val expectedTranscript = helloTranscript(
                        handshakeId = msg.handshakeId,
                        offererDeviceId = code.deviceId,
                        joinerDeviceId = msg.deviceId,
                        offererPubKey = code.publicKey,
                        joinerPubKey = msg.publicKey,
                        inGroup = msg.inGroup,
                        name = msg.name,
                        platform = msg.platform,
                    )
                    if (!verifyHmac(k, expectedTranscript, msg.hmac)) {
                        return@collect
                    }

                    val coordinator = getCoordinator(code.secret)
                    val handshakeId = msg.handshakeId
                    if (!coordinator.tryBind(handshakeId)) {
                        val errTranscript = errorTranscript(
                            handshakeId = handshakeId,
                            offererDeviceId = code.deviceId,
                            joinerDeviceId = msg.deviceId,
                            error = PairingError.USED.name,
                            message = PairingError.USED.name,
                        )
                        val errMac = computeHmac(k, errTranscript)
                        val errEvent = NostrEvent.sign(
                            keys = group.device,
                            kind = KIND_PAIRING,
                            tags = listOf(listOf("p", event.pubkey.lowercase())),
                            content = json.encodeToString(
                                PairMsg.serializer(),
                                PairMsg.Error(
                                    error = PairingError.USED,
                                    message = PairingError.USED.name,
                                    handshakeId = handshakeId,
                                    hmac = errMac,
                                ),
                            ),
                            createdAt = now() / 1000L,
                        )
                        client.publish(errEvent)
                        return@collect
                    }

                    val offererHasGroup = group.state.value != null
                    if (shouldJoinerSendWelcome(joinerInGroup = msg.inGroup, offererHasGroup = offererHasGroup)) {
                        boundPeerPubKey = msg.publicKey
                        boundPeerName = msg.name
                        boundSessionKey = k
                        boundHandshakeId = handshakeId
                        boundJoinerDeviceId = msg.deviceId

                        // Joiner has group and offerer does not: joiner sends group
                        val myTranscript = confirmTranscript(
                            handshakeId = handshakeId,
                            offererDeviceId = code.deviceId,
                            joinerDeviceId = msg.deviceId,
                            offererPubKey = code.publicKey,
                            joinerPubKey = msg.publicKey,
                            name = group.deviceName,
                            hasWelcome = false,
                        )
                        val myHmac = computeHmac(k, myTranscript)
                        val confirm = PairMsg.Confirm(
                            deviceId = myDeviceId,
                            name = group.deviceName,
                            hmac = myHmac,
                            sealedWelcome = null,
                            handshakeId = handshakeId,
                        )
                        val confirmEvent = NostrEvent.sign(
                            keys = group.device,
                            kind = KIND_PAIRING,
                            tags = listOf(listOf("p", event.pubkey.lowercase())),
                            content = json.encodeToString(PairMsg.serializer(), confirm),
                            createdAt = now() / 1000L,
                        )
                        client.publish(confirmEvent)

                        handshakeTimerJob?.cancel()
                        handshakeTimerJob = scope.launch {
                            delay(handshakeDeadlineMs)
                            if (!completed.get() && boundHandshakeId == handshakeId) {
                                if (coordinator.onDeadlineExpiry(handshakeId)) {
                                    boundPeerPubKey = null
                                    boundPeerName = null
                                    boundSessionKey = null
                                    boundHandshakeId = null
                                    boundJoinerDeviceId = null
                                }
                            }
                        }
                    } else {
                        // Offerer sends group (either offerer has group, or neither has group)
                        val myTranscript = confirmTranscript(
                            handshakeId = handshakeId,
                            offererDeviceId = code.deviceId,
                            joinerDeviceId = msg.deviceId,
                            offererPubKey = code.publicKey,
                            joinerPubKey = msg.publicKey,
                            name = group.deviceName,
                            hasWelcome = true,
                        )
                        val myHmac = computeHmac(k, myTranscript)

                        val joinerMember = Member(deviceId = msg.deviceId, name = msg.name, platform = msg.platform, publicKey = msg.publicKey)
                        val finalGroup = if (offererHasGroup) {
                            val existing = group.state.value!!
                            existing.copy(members = existing.members.filter { it.deviceId != joinerMember.deviceId } + joinerMember)
                        } else {
                            GroupState(
                                groupId = UUID.randomUUID().toString(),
                                key = Base64.UrlSafe.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }),
                                epoch = 0,
                                previousKey = null,
                                previousUntil = 0L,
                                members = listOf(
                                    Member(myDeviceId, group.deviceName, "desktop", group.device.publicKey.toHex()),
                                    joinerMember
                                ),
                                relays = code.relays,
                            )
                        }

                        val keyToSend = if (sendKey) apiKey.get() else null
                        val snap = snapshot()
                        val payload = WelcomePayload(group = finalGroup, apiKey = keyToSend, snapshot = snap)
                        val sealedWelcome = sealPayload(k, payload)

                        val confirm = PairMsg.Confirm(
                            deviceId = myDeviceId,
                            name = group.deviceName,
                            hmac = myHmac,
                            sealedWelcome = sealedWelcome,
                            handshakeId = handshakeId,
                        )
                        val confirmEvent = NostrEvent.sign(
                            keys = group.device,
                            kind = KIND_PAIRING,
                            tags = listOf(listOf("p", event.pubkey.lowercase())),
                            content = json.encodeToString(PairMsg.serializer(), confirm),
                            createdAt = now() / 1000L,
                        )
                        client.publish(confirmEvent)

                        val coordinator = getCoordinator(code.secret)
                        try {
                            val saved = coordinator.saveAndCommit(handshakeId, preSaveHook) {
                                group.save(finalGroup)
                            }
                            if (saved && completed.compareAndSet(false, true)) {
                                deferred.complete(PairedDevice(name = msg.name, keyReceived = false))
                                runCatching { server?.close() }
                            }
                        } catch (e: Throwable) {
                            coordinator.release(handshakeId)
                            throw e
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            handshakeTimerJob?.cancel()
            boundHandshakeId?.let { getCoordinator(code.secret).release(it) }
            runCatching { client.close() }
        }
    }

    private suspend fun joinOverRelay(
        relayUrl: String,
        code: PairingCode,
        sendKey: Boolean,
        handshakeId: String,
    ): PairedDevice = withContext(Dispatchers.IO) {
        val client = RelayClient(relayUrl, relay.http)
        var resultDevice: PairedDevice? = null
        var pendingUpdatedGroup: GroupState? = null
        try {
            val myDeviceId = getOwnDeviceId()
            val myPubKey = group.device.publicKey.toHex()
            val peerPubKeyBytes = hexToBytes(code.publicKey)
            val agree = group.device.agree(peerPubKeyBytes)
            val salt = code.deviceId.encodeToByteArray() + myDeviceId.encodeToByteArray()
            val k = Hkdf.derive(agree + code.secret.encodeToByteArray(), salt, "umm-pair", 32)
            val myTranscript = helloTranscript(
                handshakeId = handshakeId,
                offererDeviceId = code.deviceId,
                joinerDeviceId = myDeviceId,
                offererPubKey = code.publicKey,
                joinerPubKey = myPubKey,
                inGroup = group.state.value != null,
                name = group.deviceName,
                platform = "desktop",
            )
            val myHmac = computeHmac(k, myTranscript)

            val hello = PairMsg.Hello(
                deviceId = myDeviceId,
                name = group.deviceName,
                publicKey = myPubKey,
                platform = "desktop",
                inGroup = group.state.value != null,
                hmac = myHmac,
                handshakeId = handshakeId,
            )

            val handshakeStartTime = now() / 1000L
            val sinceTime = handshakeStartTime - 60L
            val filter = buildJsonObject {
                put("kinds", buildJsonArray { add(KIND_PAIRING) })
                put("#p", buildJsonArray { add(myPubKey.lowercase()) })
                put("since", JsonPrimitive(maxOf(0L, sinceTime)))
            }

            val helloEvent = NostrEvent.sign(
                keys = group.device,
                kind = KIND_PAIRING,
                tags = listOf(listOf("p", code.publicKey.lowercase())),
                content = json.encodeToString(PairMsg.serializer(), hello),
                createdAt = now() / 1000L,
            )

            withTimeout(15_000) {
                val sub = client.subscribe("pair-join", filter)
                val publishResult = client.publish(helloEvent)
                if (!publishResult.isSuccess) {
                    throw IOException("Failed to publish Hello over relay")
                }

                sub.collect { event ->
                    if (event.kind != KIND_PAIRING) return@collect
                    if (event.created_at < sinceTime) return@collect
                    if (!event.verify()) return@collect
                    if (!event.pubkey.equals(code.publicKey, ignoreCase = true)) return@collect
                    val msg = try {
                        json.decodeFromString(PairMsg.serializer(), event.content)
                    } catch (_: Exception) {
                        return@collect
                    }

                    when (msg) {
                        is PairMsg.Error -> {
                            if (msg.handshakeId == handshakeId) {
                                val expectedTranscript = errorTranscript(
                                    handshakeId = handshakeId,
                                    offererDeviceId = code.deviceId,
                                    joinerDeviceId = myDeviceId,
                                    error = msg.error.name,
                                    message = msg.message,
                                )
                                if (verifyHmac(k, expectedTranscript, msg.hmac)) {
                                    throw PairingException(msg.error, msg.message)
                                }
                            }
                            return@collect
                        }
                        is PairMsg.Confirm -> {
                            if (msg.handshakeId != handshakeId) return@collect
                            val expectedTranscript = confirmTranscript(
                                handshakeId = handshakeId,
                                offererDeviceId = code.deviceId,
                                joinerDeviceId = myDeviceId,
                                offererPubKey = code.publicKey,
                                joinerPubKey = myPubKey,
                                name = msg.name,
                                hasWelcome = (msg.sealedWelcome != null),
                            )
                            if (!verifyHmac(k, expectedTranscript, msg.hmac)) {
                                throw PairingException(PairingError.WRONG_SECRET)
                            }
                            if (msg.sealedWelcome != null) {
                                val coordinator = getCoordinator(code.secret)
                                val payload = openPayload(k, msg.sealedWelcome)
                                val saved = coordinator.saveAndCommit(handshakeId, preSaveHook) {
                                    group.save(payload.group)
                                }
                                if (!saved) return@collect

                                val keyRecv = if (payload.apiKey != null) {
                                    apiKey.set(payload.apiKey)
                                    true
                                } else false
                                if (payload.snapshot != null) {
                                    applier.applySnapshot(payload.snapshot)
                                }

                                val ackTranscriptBytes = ackTranscript(
                                    handshakeId = handshakeId,
                                    offererDeviceId = code.deviceId,
                                    joinerDeviceId = myDeviceId,
                                    success = true,
                                )
                                val ackHmac = computeHmac(k, ackTranscriptBytes)
                                val ack = PairMsg.Ack(success = true, handshakeId = handshakeId, hmac = ackHmac)
                                val ackEvent = NostrEvent.sign(
                                    keys = group.device,
                                    kind = KIND_PAIRING,
                                    tags = listOf(listOf("p", code.publicKey.lowercase())),
                                    content = json.encodeToString(PairMsg.serializer(), ack),
                                    createdAt = now() / 1000L,
                                )
                                client.publish(ackEvent)

                                resultDevice = PairedDevice(name = code.name, keyReceived = keyRecv)
                                throw CancellationException("Done")
                            } else {
                                val currentState = group.state.value ?: throw IllegalStateException("Joiner has no group")
                                val offererMember = Member(deviceId = msg.deviceId, name = msg.name, platform = "desktop", publicKey = code.publicKey)
                                val updatedGroup = currentState.copy(
                                    members = currentState.members.filter { it.deviceId != offererMember.deviceId } + offererMember
                                )
                                val keyToSend = if (sendKey) apiKey.get() else null
                                val snap = snapshot()
                                val payload = WelcomePayload(group = updatedGroup, apiKey = keyToSend, snapshot = snap)
                                val sealedWelcome = sealPayload(k, payload)

                                val welcomeTranscriptBytes = welcomeTranscript(
                                    handshakeId = handshakeId,
                                    offererDeviceId = code.deviceId,
                                    joinerDeviceId = myDeviceId,
                                    sealedPayload = sealedWelcome,
                                )
                                val welcomeHmac = computeHmac(k, welcomeTranscriptBytes)
                                val welcomeMsg = PairMsg.Welcome(
                                    sealedPayload = sealedWelcome,
                                    handshakeId = handshakeId,
                                    hmac = welcomeHmac,
                                )
                                val welcomeEvent = NostrEvent.sign(
                                    keys = group.device,
                                    kind = KIND_PAIRING,
                                    tags = listOf(listOf("p", code.publicKey.lowercase())),
                                    content = json.encodeToString(PairMsg.serializer(), welcomeMsg),
                                    createdAt = now() / 1000L,
                                )
                                pendingUpdatedGroup = updatedGroup
                                client.publish(welcomeEvent)
                            }
                        }
                        is PairMsg.Ack -> {
                            val updated = pendingUpdatedGroup
                            if (updated != null) {
                                if (msg.handshakeId != handshakeId) return@collect
                                val expectedTranscript = ackTranscript(
                                    handshakeId = handshakeId,
                                    offererDeviceId = code.deviceId,
                                    joinerDeviceId = myDeviceId,
                                    success = msg.success,
                                )
                                if (!verifyHmac(k, expectedTranscript, msg.hmac)) {
                                    return@collect
                                }
                                val coordinator = getCoordinator(code.secret)
                                val saved = coordinator.saveAndCommit(handshakeId, preSaveHook) {
                                    group.save(updated)
                                }
                                if (!saved) return@collect
                                resultDevice = PairedDevice(name = code.name, keyReceived = false)
                                throw CancellationException("Done")
                            }
                        }
                        else -> Unit
                    }
                }
            }
            resultDevice ?: throw IOException("Did not receive Welcome or Ack over relay")
        } catch (e: CancellationException) {
            if (e.message == "Done") {
                // Succeeded
            } else throw e
        } finally {
            runCatching { client.close() }
        }

        resultDevice ?: throw IOException("Pairing did not complete state")
    }

    internal fun shouldJoinerSendWelcome(joinerInGroup: Boolean, offererHasGroup: Boolean): Boolean {
        return joinerInGroup && !offererHasGroup
    }

    private fun sealPayload(k: ByteArray, payload: WelcomePayload): String {
        val jsonStr = json.encodeToString(WelcomePayload.serializer(), payload)
        val plain = jsonStr.encodeToByteArray()
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plain)
        return Base64.UrlSafe.encode(nonce + ct)
    }

    private fun openPayload(k: ByteArray, sealed: String): WelcomePayload {
        val bytes = Base64.UrlSafe.decode(sealed)
        require(bytes.size >= 12 + 16) { "Sealed payload too short" }
        val nonce = bytes.copyOfRange(0, 12)
        val ct = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, nonce))
        val plain = cipher.doFinal(ct)
        return json.decodeFromString(WelcomePayload.serializer(), plain.decodeToString())
    }

    private fun getOwnDeviceId(): String {
        return lan?.selfId ?: runBlocking { snapshot().from }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.toHex()
    }

    private fun getLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isLoopback && iface.isUp) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            addr.hostAddress?.let { ips.add(it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        if ("127.0.0.1" !in ips) {
            ips.add("127.0.0.1")
        }
        return ips
    }

    private val coordinators = ConcurrentHashMap<String, PairingCodeCoordinator>()

    internal fun getCoordinator(secret: String): PairingCodeCoordinator {
        return coordinators.computeIfAbsent(secret) {
            PairingCodeCoordinator(it, ::isSecretUsed, ::markSecretUsed)
        }
    }

    internal fun tryClaim(secret: String, handshakeId: String): Boolean = runBlocking {
        getCoordinator(secret).tryBind(handshakeId)
    }

    internal fun holdsClaim(secret: String, handshakeId: String): Boolean = runBlocking {
        getCoordinator(secret).holdsClaim(handshakeId)
    }

    internal fun releaseClaim(secret: String, handshakeId: String) = runBlocking {
        getCoordinator(secret).release(handshakeId)
    }

    internal fun commitClaim(secret: String, handshakeId: String) = runBlocking {
        getCoordinator(secret).saveAndCommit(handshakeId) {}
    }

    private fun isSecretUsed(secret: String): Boolean {
        val until = usedSecrets[secret] ?: return false
        return now() <= until
    }

    private fun markSecretUsed(secret: String) {
        usedSecrets[secret] = now() + 10 * 60 * 1000L
    }

    private fun generateHandshakeId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.toHex()
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    internal fun lengthPrefixed(vararg fields: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        for (f in fields) {
            dos.writeInt(f.size)
            dos.write(f)
        }
        return bos.toByteArray()
    }

    internal fun helloTranscript(
        handshakeId: String,
        offererDeviceId: String,
        joinerDeviceId: String,
        offererPubKey: String,
        joinerPubKey: String,
        inGroup: Boolean,
        name: String,
        platform: String,
    ): ByteArray = lengthPrefixed(
        "hello".encodeToByteArray(),
        handshakeId.encodeToByteArray(),
        offererDeviceId.encodeToByteArray(),
        joinerDeviceId.encodeToByteArray(),
        offererPubKey.encodeToByteArray(),
        joinerPubKey.encodeToByteArray(),
        byteArrayOf(if (inGroup) 1 else 0),
        name.encodeToByteArray(),
        platform.encodeToByteArray(),
    )

    internal fun confirmTranscript(
        handshakeId: String,
        offererDeviceId: String,
        joinerDeviceId: String,
        offererPubKey: String,
        joinerPubKey: String,
        name: String,
        hasWelcome: Boolean,
    ): ByteArray = lengthPrefixed(
        "confirm".encodeToByteArray(),
        handshakeId.encodeToByteArray(),
        offererDeviceId.encodeToByteArray(),
        joinerDeviceId.encodeToByteArray(),
        offererPubKey.encodeToByteArray(),
        joinerPubKey.encodeToByteArray(),
        name.encodeToByteArray(),
        byteArrayOf(if (hasWelcome) 1 else 0),
    )

    internal fun welcomeTranscript(
        handshakeId: String,
        offererDeviceId: String,
        joinerDeviceId: String,
        sealedPayload: String,
    ): ByteArray = lengthPrefixed(
        "welcome".encodeToByteArray(),
        handshakeId.encodeToByteArray(),
        offererDeviceId.encodeToByteArray(),
        joinerDeviceId.encodeToByteArray(),
        sealedPayload.encodeToByteArray(),
    )

    internal fun ackTranscript(
        handshakeId: String,
        offererDeviceId: String,
        joinerDeviceId: String,
        success: Boolean,
    ): ByteArray = lengthPrefixed(
        "ack".encodeToByteArray(),
        handshakeId.encodeToByteArray(),
        offererDeviceId.encodeToByteArray(),
        joinerDeviceId.encodeToByteArray(),
        byteArrayOf(if (success) 1 else 0),
    )

    internal fun errorTranscript(
        handshakeId: String,
        offererDeviceId: String,
        joinerDeviceId: String,
        error: String,
        message: String,
    ): ByteArray = lengthPrefixed(
        "error".encodeToByteArray(),
        handshakeId.encodeToByteArray(),
        offererDeviceId.encodeToByteArray(),
        joinerDeviceId.encodeToByteArray(),
        error.encodeToByteArray(),
        message.encodeToByteArray(),
    )

    internal fun computeHmac(key: ByteArray, transcript: ByteArray): String {
        return hmacSha256(key, transcript).toHex()
    }

    internal fun verifyHmac(key: ByteArray, transcript: ByteArray, actualHex: String): Boolean {
        val expected = hmacSha256(key, transcript)
        val actual = runCatching { hexToBytes(actualHex) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    internal fun hexToBytes(hex: String): ByteArray {
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

    companion object {
        private val usedSecrets = ConcurrentHashMap<String, Long>()
    }
}
