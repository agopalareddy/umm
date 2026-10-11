package io.github.agopalareddy.umm.sync.relay

import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.SyncLog
import io.github.agopalareddy.umm.sync.lan.groupHash
import io.github.agopalareddy.umm.sync.relayHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

data class RelaySyncResult(
    val url: String,
    val connected: Boolean,
    val opsPublished: Int = 0,
    val error: String? = null,
    /** The relay replayed what it stores during this sync; false when it did not answer in time or was not asked. */
    val caughtUp: Boolean = false,
)

open class RelayTransport(
    val group: GroupStore,
    val log: OpLog,
    val applier: OpApplier,
    val http: OkHttpClient,
    val scope: CoroutineScope,
    val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val expirationSeconds: Long = EXPIRATION_SECONDS,
    private val syncLog: SyncLog = SyncLog.None,
) {
    private val _relayErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val relayErrors: StateFlow<Map<String, String>> = _relayErrors.asStateFlow()

    private val clients = ConcurrentHashMap<String, RelayClient>()
    private val relayJobs = ConcurrentHashMap<String, Job>()
    private val lastSeenPerRelay = ConcurrentHashMap<String, Long>()
    private var groupWatchJob: Job? = null
    private val publishMutex = Mutex()
    private val storedEventsEnds = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    private val reconnectSignals = ConcurrentHashMap<String, Channel<Unit>>()

    private val json = Json { ignoreUnknownKeys = true }

    open fun isConnected(relayUrl: String): Boolean = clients[relayUrl]?.isConnected == true

    open fun start() {
        stop()
        groupWatchJob = scope.launch {
            group.state.collect { state ->
                syncRelays(state)
            }
        }
    }

    open fun stop() {
        groupWatchJob?.cancel()
        groupWatchJob = null
        relayJobs.values.forEach { it.cancel() }
        relayJobs.clear()
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    open suspend fun publishPending(): List<RelaySyncResult> = withContext(Dispatchers.IO) {
        publishMutex.withLock {
            val state = group.state.value ?: return@withLock emptyList()
            if (!state.relayEnabled || state.relays.isEmpty()) return@withLock emptyList()

            val myPubkey = group.device.publicKey.toHex()
            val selfMember = state.members.find { it.publicKey.equals(myPubkey, ignoreCase = true) }
            val selfDeviceId = selfMember?.deviceId ?: return@withLock emptyList()

            val cipher = GroupCipher(
                key = decodeKey(state.key),
                epoch = state.epoch,
                previousKey = state.previousKey?.let { decodeKey(it) },
                previousUntil = state.previousUntil,
                clock = { clockSeconds() * 1000L },
            )

            val results = mutableListOf<RelaySyncResult>()

            for (relayUrl in state.relays) {
                val lastSeqKey = getLastSeqKey(relayUrl)
                val lastSeq = group.store.getString(lastSeqKey)?.toLongOrNull() ?: 0L
                val pendingOps = log.since(mapOf(selfDeviceId to lastSeq))
                    .filter { it.origin == selfDeviceId && it.seq > lastSeq }

                val client = getOrCreateClient(relayUrl)

                if (pendingOps.isEmpty()) {
                    if (client.isConnected) {
                        results.add(RelaySyncResult(url = relayUrl, connected = true, opsPublished = 0, error = null))
                    } else {
                        val connectRes = client.connect()
                        if (connectRes.isSuccess) {
                            _relayErrors.update { it - relayUrl }
                            results.add(RelaySyncResult(url = relayUrl, connected = true, opsPublished = 0, error = null))
                        } else {
                            val err = connectRes.exceptionOrNull()?.message ?: "Not connected"
                            _relayErrors.update { it + (relayUrl to err) }
                            results.add(RelaySyncResult(url = relayUrl, connected = false, opsPublished = 0, error = err))
                        }
                    }
                    continue
                }

                val batches = batchOps(pendingOps, cipher)
                var anyFailed = false
                var totalPublished = 0
                var firstErr: String? = null

                for (batch in batches) {
                    val content = encodeBatch(batch, cipher)
                    val firstSeq = batch.first().seq
                    val lastBatchSeq = batch.last().seq
                    val groupHashStr = groupHash(state.groupId)
                    val tags = listOf(
                        listOf("d", "umm:$groupHashStr:$firstSeq-$lastBatchSeq"),
                        listOf("expiration", (clockSeconds() + expirationSeconds).toString())
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
                        totalPublished += batch.size
                        syncLog.log("relay ${relayHost(relayUrl)}: published seq $firstSeq-$lastBatchSeq")
                    } else {
                        anyFailed = true
                        val err = res.exceptionOrNull()?.message ?: "Publish failed"
                        syncLog.log("relay ${relayHost(relayUrl)}: publish of seq $firstSeq-$lastBatchSeq failed: $err")
                        firstErr = err
                        _relayErrors.update { it + (relayUrl to err) }
                        break
                    }
                }
                if (!anyFailed) {
                    _relayErrors.update { it - relayUrl }
                    results.add(RelaySyncResult(url = relayUrl, connected = true, opsPublished = totalPublished, error = null))
                } else {
                    results.add(RelaySyncResult(url = relayUrl, connected = client.isConnected, opsPublished = totalPublished, error = firstErr))
                }
            }
            results
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

    /**
     * One subscription per relay for as long as the relay stays in the group's list. It is rebuilt when the other
     * members change (the filter names their keys), and after a lost connection with a growing pause that
     * [catchUp] can cut short.
     */
    private suspend fun runRelaySubscription(relayUrl: String) {
        val myPubkey = group.device.publicKey.toHex()
        group.state
            .map { state ->
                if (state == null || !state.relayEnabled || relayUrl !in state.relays) {
                    null
                } else {
                    state.members.map { it.publicKey.lowercase() }.filter { it != myPubkey.lowercase() }.sorted()
                }
            }
            .distinctUntilChanged()
            .collectLatest { authors ->
                if (authors.isNullOrEmpty()) return@collectLatest
                var backoffMs = 5_000L
                while (true) {
                    val client = getOrCreateClient(relayUrl)
                    try {
                        val filter = buildJsonObject {
                            put("kinds", buildJsonArray { add(KIND_APP_DATA) })
                            put("authors", buildJsonArray { authors.forEach { add(it) } })
                            val lastSeen = lastSeenPerRelay[relayUrl]
                            if (lastSeen != null && lastSeen > 0L) {
                                put("since", (lastSeen - 3600L).coerceAtLeast(0L))
                            }
                        }

                        client.subscribeItems(SUBSCRIPTION_ID, filter).collect { item ->
                            when (item) {
                                is RelayItem.Event -> try {
                                    group.state.value?.let { handleIncomingEvent(item.event, it, relayUrl) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // One event that cannot be applied must not end the subscription for the rest.
                                }
                                RelayItem.StoredEventsEnd -> storedEventsEnds(relayUrl).update { it + 1 }
                            }
                            _relayErrors.update { it - relayUrl }
                            backoffMs = 5_000L
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _relayErrors.update { it + (relayUrl to (e.message ?: "Connection error")) }
                        syncLog.log("relay ${relayHost(relayUrl)}: connection lost (${e.message}); retry within ${backoffMs / 1000} s")
                    }
                    withTimeoutOrNull(backoffMs) { reconnectNow(relayUrl).receive() }
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
    }

    /**
     * Pulls what each relay stores, so that a sync also receives: asks every relay to replay its stored events and
     * waits until it says they are all sent. A relay whose connection is dead (no answer in half the time) is
     * reconnected at once. Returns, per relay, whether it answered within [timeoutMs].
     */
    open suspend fun catchUp(timeoutMs: Long = CATCH_UP_MS): Map<String, Boolean> = coroutineScope {
        val state = group.state.value
        if (state == null || !state.relayEnabled || groupWatchJob == null) return@coroutineScope emptyMap()
        val myPubkey = group.device.publicKey.toHex()
        if (state.members.none { !it.publicKey.equals(myPubkey, ignoreCase = true) }) return@coroutineScope emptyMap()

        state.relays.map { url ->
            async {
                val ends = storedEventsEnds(url)
                val before = ends.value
                val client = clients[url]
                val asked = client != null && client.isConnected && client.refresh(SUBSCRIPTION_ID)
                if (!asked) reconnectNow(url).trySend(Unit)
                var answered = withTimeoutOrNull(timeoutMs / 2) { ends.first { it > before } } != null
                if (!answered) {
                    if (asked) client?.dropConnection()
                    reconnectNow(url).trySend(Unit)
                    answered = withTimeoutOrNull(timeoutMs / 2) { ends.first { it > before } } != null
                }
                url to answered
            }
        }.awaitAll().toMap()
    }

    private companion object {
        const val SUBSCRIPTION_ID = "umm-sync"
        const val MAX_BACKOFF_MS = 300_000L
        const val CATCH_UP_MS = 10_000L
    }

    private fun storedEventsEnds(relayUrl: String) = storedEventsEnds.computeIfAbsent(relayUrl) { MutableStateFlow(0L) }

    private fun reconnectNow(relayUrl: String) = reconnectSignals.computeIfAbsent(relayUrl) { Channel(Channel.CONFLATED) }

    internal suspend fun handleIncomingEvent(event: NostrEvent, state: GroupState, relayUrl: String) {
        if (event.kind != KIND_APP_DATA) return
        if (!event.verify()) return

        val myPubkey = group.device.publicKey.toHex()
        if (event.pubkey.equals(myPubkey, ignoreCase = true)) return

        val host = relayHost(relayUrl)
        val member = state.members.find { it.publicKey.equals(event.pubkey, ignoreCase = true) }
        if (member == null) {
            val withinGrace = state.previousKey != null && (clockSeconds() * 1000L <= state.previousUntil)
            if (!withinGrace) {
                syncLog.log("relay $host: dropped an event from a key that is not a member")
                return
            }
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
            syncLog.log("relay $host: could not decrypt an event from ${member?.name ?: "a former member"}")
            return
        }

        val applied = applier.apply(envelopes)
        if (applied > 0) {
            val range = "${envelopes.minOf { it.seq }}-${envelopes.maxOf { it.seq }}"
            syncLog.log("relay $host: received seq $range from ${member?.name ?: "a former member"}, $applied new")
        }
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
