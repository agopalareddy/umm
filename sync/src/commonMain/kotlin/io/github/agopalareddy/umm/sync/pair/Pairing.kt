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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
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
    ) : PairMsg

    @Serializable
    @SerialName("Confirm")
    data class Confirm(
        val deviceId: String,
        val name: String,
        val hmac: String,
        val sealedWelcome: String? = null,
    ) : PairMsg

    @Serializable
    @SerialName("Welcome")
    data class Welcome(
        val sealedPayload: String,
    ) : PairMsg

    @Serializable
    @SerialName("Ack")
    data class Ack(
        val success: Boolean = true,
    ) : PairMsg

    @Serializable
    @SerialName("Error")
    data class Error(
        val error: PairingError,
        val message: String = error.name,
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
        if (isSecretUsed(pairingCode.secret)) {
            throw PairingException(PairingError.USED)
        }

        // Try LAN first
        var lanSuccess: PairedDevice? = null
        if (pairingCode.lan.isNotEmpty() && pairingCode.port > 0) {
            for (ip in pairingCode.lan) {
                try {
                    val result = joinOverLan(ip, pairingCode.port, pairingCode, sendKey)
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
            markSecretUsed(pairingCode.secret)
            return@withContext lanSuccess
        }

        // Fallback to relay
        if (pairingCode.relays.isNotEmpty()) {
            for (relayUrl in pairingCode.relays) {
                try {
                    val result = joinOverRelay(relayUrl, pairingCode, sendKey)
                    markSecretUsed(pairingCode.secret)
                    return@withContext result
                } catch (e: PairingException) {
                    throw e
                } catch (_: Exception) {
                    // Connection error on this relay, continue
                }
            }
        }

        throw PairingException(PairingError.UNREACHABLE, "Unable to reach peer over LAN or relay")
    }

    private suspend fun handleLanClient(
        socket: Socket,
        code: PairingCode,
        sendKey: Boolean,
        completed: AtomicBoolean,
        deferred: CompletableDeferred<PairedDevice>,
        server: ServerSocket?,
    ) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val helloBytes = FrameCodec.read(input)
            val hello = json.decodeFromString(PairMsg.serializer(), helloBytes.decodeToString())
            if (hello !is PairMsg.Hello) {
                socket.close()
                return
            }

            if (isSecretUsed(code.secret)) {
                val err = PairMsg.Error(PairingError.USED)
                FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), err).encodeToByteArray())
                socket.close()
                return
            }

            val peerPubKeyBytes = hexToBytes(hello.publicKey)
            val agree = group.device.agree(peerPubKeyBytes)
            val myDeviceId = getOwnDeviceId()
            val salt = code.deviceId.encodeToByteArray() + hello.deviceId.encodeToByteArray()
            val k = Hkdf.derive(agree + code.secret.encodeToByteArray(), salt, "umm-pair", 32)

            val expectedHmac = hmacSha256(k, "confirm".encodeToByteArray() + hello.deviceId.encodeToByteArray()).toHex()
            if (hello.hmac != expectedHmac) {
                val err = PairMsg.Error(PairingError.WRONG_SECRET)
                FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), err).encodeToByteArray())
                socket.close()
                return
            }

            val myHmac = hmacSha256(k, "confirm".encodeToByteArray() + myDeviceId.encodeToByteArray()).toHex()

            // State handover
            val offererHasGroup = group.state.value != null
            if (hello.inGroup && !offererHasGroup) {
                // Joiner has group and offerer does not: joiner sends group
                val confirm = PairMsg.Confirm(deviceId = myDeviceId, name = group.deviceName, hmac = myHmac, sealedWelcome = null)
                FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), confirm).encodeToByteArray())

                val welcomeBytes = FrameCodec.read(input)
                val welcome = json.decodeFromString(PairMsg.serializer(), welcomeBytes.decodeToString())
                if (welcome !is PairMsg.Welcome) {
                    socket.close()
                    return
                }

                val payload = openPayload(k, welcome.sealedPayload)
                group.save(payload.group)
                val keyRecv = if (payload.apiKey != null) {
                    apiKey.set(payload.apiKey)
                    true
                } else false
                if (payload.snapshot != null) {
                    applier.applySnapshot(payload.snapshot)
                }

                val ack = PairMsg.Ack(true)
                FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), ack).encodeToByteArray())

                if (completed.compareAndSet(false, true)) {
                    markSecretUsed(code.secret)
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

                val confirm = PairMsg.Confirm(deviceId = myDeviceId, name = group.deviceName, hmac = myHmac, sealedWelcome = sealedWelcome)
                FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), confirm).encodeToByteArray())

                val ackBytes = FrameCodec.read(input)
                val ack = json.decodeFromString(PairMsg.serializer(), ackBytes.decodeToString())
                if (ack is PairMsg.Ack) {
                    group.save(finalGroup)
                    if (completed.compareAndSet(false, true)) {
                        markSecretUsed(code.secret)
                        deferred.complete(PairedDevice(name = hello.name, keyReceived = false))
                        runCatching { server?.close() }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun joinOverLan(
        host: String,
        port: Int,
        code: PairingCode,
        sendKey: Boolean,
    ): PairedDevice = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 2000)

        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val myDeviceId = getOwnDeviceId()
            val peerPubKeyBytes = hexToBytes(code.publicKey)
            val agree = group.device.agree(peerPubKeyBytes)
            val salt = code.deviceId.encodeToByteArray() + myDeviceId.encodeToByteArray()
            val k = Hkdf.derive(agree + code.secret.encodeToByteArray(), salt, "umm-pair", 32)
            val myHmac = hmacSha256(k, "confirm".encodeToByteArray() + myDeviceId.encodeToByteArray()).toHex()

            val hello = PairMsg.Hello(
                deviceId = myDeviceId,
                name = group.deviceName,
                publicKey = group.device.publicKey.toHex(),
                platform = "desktop",
                inGroup = group.state.value != null,
                hmac = myHmac,
            )
            FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), hello).encodeToByteArray())

            val replyBytes = FrameCodec.read(input)
            val reply = json.decodeFromString(PairMsg.serializer(), replyBytes.decodeToString())

            when (reply) {
                is PairMsg.Error -> throw PairingException(reply.error, reply.message)
                is PairMsg.Confirm -> {
                    val expectedPeerHmac = hmacSha256(k, "confirm".encodeToByteArray() + code.deviceId.encodeToByteArray()).toHex()
                    if (reply.hmac != expectedPeerHmac) {
                        throw PairingException(PairingError.WRONG_SECRET)
                    }

                    if (reply.sealedWelcome != null) {
                        // Offerer sent group
                        val payload = openPayload(k, reply.sealedWelcome)
                        group.save(payload.group)
                        val keyRecv = if (payload.apiKey != null) {
                            apiKey.set(payload.apiKey)
                            true
                        } else false
                        if (payload.snapshot != null) {
                            applier.applySnapshot(payload.snapshot)
                        }

                        val ack = PairMsg.Ack(true)
                        FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), ack).encodeToByteArray())

                        return@withContext PairedDevice(name = code.name, keyReceived = keyRecv)
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

                        val welcomeMsg = PairMsg.Welcome(sealedWelcome)
                        FrameCodec.write(output, json.encodeToString(PairMsg.serializer(), welcomeMsg).encodeToByteArray())

                        val ackBytes = FrameCodec.read(input)
                        val ack = json.decodeFromString(PairMsg.serializer(), ackBytes.decodeToString())
                        if (ack !is PairMsg.Ack) throw IOException("Expected Ack")

                        group.save(updatedGroup)
                        return@withContext PairedDevice(name = code.name, keyReceived = false)
                    }
                }
                else -> throw IOException("Unexpected reply: $reply")
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
        try {
            val myPubKey = group.device.publicKey.toHex()
            val filter = buildJsonObject {
                put("kinds", buildJsonArray { add(KIND_PAIRING) })
                put("#p", buildJsonArray { add(myPubKey.lowercase()) })
            }

            client.subscribe("pair-offer", filter).collect { event ->
                if (event.kind != KIND_PAIRING || completed.get()) return@collect
                val msg = try {
                    json.decodeFromString(PairMsg.serializer(), event.content)
                } catch (_: Exception) {
                    return@collect
                }

                if (msg is PairMsg.Hello) {
                    if (isSecretUsed(code.secret)) {
                        val errEvent = NostrEvent.sign(
                            keys = group.device,
                            kind = KIND_PAIRING,
                            tags = listOf(listOf("p", event.pubkey.lowercase())),
                            content = json.encodeToString(PairMsg.serializer(), PairMsg.Error(PairingError.USED)),
                            createdAt = now() / 1000L,
                        )
                        client.publish(errEvent)
                        return@collect
                    }

                    val peerPubKeyBytes = hexToBytes(msg.publicKey)
                    val agree = group.device.agree(peerPubKeyBytes)
                    val myDeviceId = getOwnDeviceId()
                    val salt = code.deviceId.encodeToByteArray() + msg.deviceId.encodeToByteArray()
                    val k = Hkdf.derive(agree + code.secret.encodeToByteArray(), salt, "umm-pair", 32)

                    val expectedHmac = hmacSha256(k, "confirm".encodeToByteArray() + msg.deviceId.encodeToByteArray()).toHex()
                    if (msg.hmac != expectedHmac) {
                        val errEvent = NostrEvent.sign(
                            keys = group.device,
                            kind = KIND_PAIRING,
                            tags = listOf(listOf("p", event.pubkey.lowercase())),
                            content = json.encodeToString(PairMsg.serializer(), PairMsg.Error(PairingError.WRONG_SECRET)),
                            createdAt = now() / 1000L,
                        )
                        client.publish(errEvent)
                        return@collect
                    }

                    val myHmac = hmacSha256(k, "confirm".encodeToByteArray() + myDeviceId.encodeToByteArray()).toHex()

                    // Send Confirm + Welcome
                    val joinerMember = Member(deviceId = msg.deviceId, name = msg.name, platform = msg.platform, publicKey = msg.publicKey)
                    val offererHasGroup = group.state.value != null
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

                    val confirm = PairMsg.Confirm(deviceId = myDeviceId, name = group.deviceName, hmac = myHmac, sealedWelcome = sealedWelcome)
                    val confirmEvent = NostrEvent.sign(
                        keys = group.device,
                        kind = KIND_PAIRING,
                        tags = listOf(listOf("p", event.pubkey.lowercase())),
                        content = json.encodeToString(PairMsg.serializer(), confirm),
                        createdAt = now() / 1000L,
                    )
                    client.publish(confirmEvent)

                    group.save(finalGroup)
                    if (completed.compareAndSet(false, true)) {
                        markSecretUsed(code.secret)
                        deferred.complete(PairedDevice(name = msg.name, keyReceived = false))
                        runCatching { server?.close() }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun joinOverRelay(
        relayUrl: String,
        code: PairingCode,
        sendKey: Boolean,
    ): PairedDevice = withContext(Dispatchers.IO) {
        val client = RelayClient(relayUrl, relay.http)
        try {
            val myDeviceId = getOwnDeviceId()
            val myPubKey = group.device.publicKey.toHex()
            val peerPubKeyBytes = hexToBytes(code.publicKey)
            val agree = group.device.agree(peerPubKeyBytes)
            val salt = code.deviceId.encodeToByteArray() + myDeviceId.encodeToByteArray()
            val k = Hkdf.derive(agree + code.secret.encodeToByteArray(), salt, "umm-pair", 32)
            val myHmac = hmacSha256(k, "confirm".encodeToByteArray() + myDeviceId.encodeToByteArray()).toHex()

            val hello = PairMsg.Hello(
                deviceId = myDeviceId,
                name = group.deviceName,
                publicKey = myPubKey,
                platform = "desktop",
                inGroup = group.state.value != null,
                hmac = myHmac,
            )

            val filter = buildJsonObject {
                put("kinds", buildJsonArray { add(KIND_PAIRING) })
                put("#p", buildJsonArray { add(myPubKey.lowercase()) })
            }

            val helloEvent = NostrEvent.sign(
                keys = group.device,
                kind = KIND_PAIRING,
                tags = listOf(listOf("p", code.publicKey.lowercase())),
                content = json.encodeToString(PairMsg.serializer(), hello),
                createdAt = now() / 1000L,
            )

            var resultDevice: PairedDevice? = null
            withTimeout(15_000) {
                val sub = client.subscribe("pair-join", filter)
                val publishResult = client.publish(helloEvent)
                if (!publishResult.isSuccess) {
                    throw IOException("Failed to publish Hello over relay")
                }

                sub.collect { event ->
                    if (event.kind != KIND_PAIRING) return@collect
                    val msg = try {
                        json.decodeFromString(PairMsg.serializer(), event.content)
                    } catch (_: Exception) {
                        return@collect
                    }

                    when (msg) {
                        is PairMsg.Error -> throw PairingException(msg.error, msg.message)
                        is PairMsg.Confirm -> {
                            val expectedPeerHmac = hmacSha256(k, "confirm".encodeToByteArray() + code.deviceId.encodeToByteArray()).toHex()
                            if (msg.hmac != expectedPeerHmac) {
                                throw PairingException(PairingError.WRONG_SECRET)
                            }
                            if (msg.sealedWelcome != null) {
                                val payload = openPayload(k, msg.sealedWelcome)
                                group.save(payload.group)
                                val keyRecv = if (payload.apiKey != null) {
                                    apiKey.set(payload.apiKey)
                                    true
                                } else false
                                if (payload.snapshot != null) {
                                    applier.applySnapshot(payload.snapshot)
                                }

                                val ack = PairMsg.Ack(true)
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
                            }
                        }
                        else -> Unit
                    }
                }
            }
            resultDevice ?: throw IOException("Did not receive Welcome over relay")
        } catch (e: CancellationException) {
            if (e.message == "Done") {
                // Succeeded
            } else throw e
        } finally {
            runCatching { client.close() }
        }

        val myPubKey = group.device.publicKey.toHex()
        val st = group.state.value ?: throw IOException("Pairing did not complete state")
        val isKeyRecv = apiKey.get() != null
        PairedDevice(name = code.name, keyReceived = isKeyRecv)
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

    private fun isSecretUsed(secret: String): Boolean {
        val until = usedSecrets[secret] ?: return false
        return now() <= until
    }

    private fun markSecretUsed(secret: String) {
        usedSecrets[secret] = now() + 10 * 60 * 1000L
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
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

    companion object {
        private val usedSecrets = ConcurrentHashMap<String, Long>()
    }
}
