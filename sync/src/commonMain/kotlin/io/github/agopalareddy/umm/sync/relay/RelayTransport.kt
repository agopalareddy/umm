package io.github.agopalareddy.umm.sync.relay

import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.lan.groupHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class RelayTransport(
    val group: GroupStore,
    val log: OpLog,
    val applier: OpApplier,
    val http: OkHttpClient,
    val scope: CoroutineScope,
    val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _relayErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val relayErrors: StateFlow<Map<String, String>> = _relayErrors.asStateFlow()

    private val clients = ConcurrentHashMap<String, RelayClient>()
    private val relayJobs = ConcurrentHashMap<String, Job>()
    private val lastSeenPerRelay = ConcurrentHashMap<String, Long>()
    private var groupWatchJob: Job? = null
    private val publishMutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        stop()
        groupWatchJob = scope.launch {
            group.state.collect { state ->
                syncRelays(state)
            }
        }
    }

    fun stop() {
        groupWatchJob?.cancel()
        groupWatchJob = null
        relayJobs.values.forEach { it.cancel() }
        relayJobs.clear()
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    suspend fun publishPending() = withContext(Dispatchers.IO) {
        publishMutex.withLock {
            val state = group.state.value ?: return@withLock
            if (!state.relayEnabled || state.relays.isEmpty()) return@withLock

            val myPubkey = group.device.publicKey.toHex()
            val selfMember = state.members.find { it.publicKey.equals(myPubkey, ignoreCase = true) }
            val selfDeviceId = selfMember?.deviceId ?: return@withLock

            val cipher = GroupCipher(
                key = decodeKey(state.key),
                epoch = state.epoch,
                previousKey = state.previousKey?.let { decodeKey(it) },
                previousUntil = state.previousUntil,
                clock = { clockSeconds() * 1000L },
            )

            for (relayUrl in state.relays) {
                val lastSeqKey = getLastSeqKey(relayUrl)
                val lastSeq = group.store.getString(lastSeqKey)?.toLongOrNull() ?: 0L
                val pendingOps = log.since(mapOf(selfDeviceId to lastSeq))
                    .filter { it.origin == selfDeviceId && it.seq > lastSeq }

                if (pendingOps.isEmpty()) continue

                val batches = batchOps(pendingOps, cipher)
                val client = getOrCreateClient(relayUrl)
                var anyFailed = false

                for (batch in batches) {
                    val content = encodeBatch(batch, cipher)
                    val firstSeq = batch.first().seq
                    val lastBatchSeq = batch.last().seq
                    val groupHashStr = groupHash(state.groupId)
                    val tags = listOf(
                        listOf("d", "umm:$groupHashStr:$firstSeq-$lastBatchSeq"),
                        listOf("expiration", (clockSeconds() + EXPIRATION_SECONDS).toString())
                    )
                    val event = NostrEvent.sign(
                        keys = group.device,
                        kind = KIND_APP_DATA,
                        tags = tags,
                        content = content,
                        createdAt = clockSeconds()
                    )
                    val res = client.publish(event)
                    if (res.isSuccess) {
                        group.store.putString(lastSeqKey, lastBatchSeq.toString())
                    } else {
                        anyFailed = true
                        val err = res.exceptionOrNull()?.message ?: "Publish failed"
                        _relayErrors.update { it + (relayUrl to err) }
                        break
                    }
                }
                if (!anyFailed) {
                    _relayErrors.update { it - relayUrl }
                }
            }
        }
    }

    private fun syncRelays(state: GroupState?) {
        if (state == null || !state.relayEnabled) {
            relayJobs.values.forEach { it.cancel() }
            relayJobs.clear()
            clients.values.forEach { runCatching { it.close() } }
            clients.clear()
            return
        }

        val currentRelaySet = state.relays.toSet()
        val removed = relayJobs.keys.filter { it !in currentRelaySet }
        for (url in removed) {
            relayJobs.remove(url)?.cancel()
            clients.remove(url)?.close()
            _relayErrors.update { it - url }
        }

        for (url in state.relays) {
            if (!relayJobs.containsKey(url)) {
                val job = scope.launch(Dispatchers.IO) {
                    runRelaySubscription(url)
                }
                relayJobs[url] = job
            }
        }
    }

    private suspend fun runRelaySubscription(relayUrl: String) {
        var backoffMs = 5_000L
        val maxBackoffMs = 300_000L

        while (scope.isActive) {
            val state = group.state.value
            if (state == null || !state.relayEnabled || !state.relays.contains(relayUrl)) {
                break
            }

            val myPubkey = group.device.publicKey.toHex()
            val otherMembers = state.members.filter {
                !it.publicKey.equals(myPubkey, ignoreCase = true)
            }

            if (otherMembers.isEmpty()) {
                delay(1000)
                continue
            }

            val client = getOrCreateClient(relayUrl)
            try {
                val filter = buildJsonObject {
                    put("kinds", buildJsonArray { add(KIND_APP_DATA) })
                    put("authors", buildJsonArray {
                        otherMembers.forEach { add(it.publicKey.lowercase()) }
                    })
                    val lastSeen = lastSeenPerRelay[relayUrl]
                    if (lastSeen != null && lastSeen > 0L) {
                        put("since", (lastSeen - 3600L).coerceAtLeast(0L))
                    }
                }

                client.subscribe("umm-sync", filter).collect { event ->
                    handleIncomingEvent(event, state, relayUrl)
                    _relayErrors.update { it - relayUrl }
                    backoffMs = 5_000L
                }
            } catch (e: Exception) {
                if (!scope.isActive) break
                val err = e.message ?: "Connection error"
                _relayErrors.update { it + (relayUrl to err) }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }
    }

    internal suspend fun handleIncomingEvent(event: NostrEvent, state: GroupState, relayUrl: String) {
        if (event.kind != KIND_APP_DATA) return
        if (!event.verify()) return

        val myPubkey = group.device.publicKey.toHex()
        if (event.pubkey.equals(myPubkey, ignoreCase = true)) return

        val member = state.members.find { it.publicKey.equals(event.pubkey, ignoreCase = true) }
        if (member == null) {
            val withinGrace = state.previousKey != null && (clockSeconds() * 1000L <= state.previousUntil)
            if (!withinGrace) return
        }

        val cipher = GroupCipher(
            key = decodeKey(state.key),
            epoch = state.epoch,
            previousKey = state.previousKey?.let { decodeKey(it) },
            previousUntil = state.previousUntil,
            clock = { clockSeconds() * 1000L },
        )

        val envelopes = try {
            decodeBatch(event.content, cipher)
        } catch (_: Exception) {
            return
        }

        applier.apply(envelopes)
        lastSeenPerRelay[relayUrl] = maxOf(lastSeenPerRelay[relayUrl] ?: 0L, event.created_at)
    }

    private fun getOrCreateClient(relayUrl: String): RelayClient {
        return clients.compute(relayUrl) { _, existing ->
            existing ?: RelayClient(relayUrl, http)
        }!!
    }

    private fun getLastSeqKey(relayUrl: String): String = "relay_seq_${relayUrl}"

    private fun batchOps(ops: List<OpEnvelope>, cipher: GroupCipher): List<List<OpEnvelope>> {
        val batches = mutableListOf<List<OpEnvelope>>()
        var currentBatch = mutableListOf<OpEnvelope>()

        for (op in ops) {
            currentBatch.add(op)
            val content = encodeBatch(currentBatch, cipher)
            if (content.length >= MAX_CONTENT || currentBatch.size > 50) {
                currentBatch.removeAt(currentBatch.size - 1)
                if (currentBatch.isEmpty()) {
                    batches.add(listOf(op))
                } else {
                    batches.add(currentBatch)
                    currentBatch = mutableListOf(op)
                }
            }
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }
        return batches
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeBatch(batch: List<OpEnvelope>, cipher: GroupCipher): String {
        val jsonString = json.encodeToString(ListSerializer(OpEnvelope.serializer()), batch)
        val gzipped = gzip(jsonString.encodeToByteArray())
        val sealed = cipher.seal(gzipped)
        return Base64.encode(sealed)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBatch(content: String, cipher: GroupCipher): List<OpEnvelope> {
        val sealed = runCatching { Base64.decode(content) }
            .getOrElse { Base64.UrlSafe.decode(content) }
        val gzipped = cipher.open(sealed)
        val plainJson = gunzip(gzipped).decodeToString()
        return json.decodeFromString(ListSerializer(OpEnvelope.serializer()), plainJson)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(data)
        return GZIPInputStream(bis).use { it.readBytes() }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeKey(str: String): ByteArray {
        return runCatching { Base64.UrlSafe.decode(str) }.getOrNull()
            ?: runCatching { Base64.decode(str) }.getOrNull()
            ?: runCatching { hexToBytes(str) }.getOrNull()
            ?: str.encodeToByteArray().copyOf(32)
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
