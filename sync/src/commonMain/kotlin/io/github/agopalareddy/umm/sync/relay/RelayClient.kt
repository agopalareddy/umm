package io.github.agopalareddy.umm.sync.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class RelayClient(
    val url: String,
    http: OkHttpClient,
) : AutoCloseable {

    /** Pings notice a socket that died silently (a sleeping phone, a NAT timeout); without them it would wait forever. */
    val http: OkHttpClient = http.newBuilder().pingInterval(PING_SECONDS, TimeUnit.SECONDS).build()

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var isClosed = false

    val isConnected: Boolean
        get() = synchronized(lock) {
            !isClosed && webSocket != null && connectDeferred?.isCompleted == true && connectDeferred?.getCompletionExceptionOrNull() == null
        }

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureConnected()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val pendingPublishes = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    private val subscriptions = ConcurrentHashMap<String, ProducerScope<RelayItem>>()
    private val subscriptionFilters = ConcurrentHashMap<String, JsonObject>()

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ensureConnected(): WebSocket = withContext(Dispatchers.IO) {
        val (ws, deferred) = synchronized(lock) {
            if (isClosed) throw IOException("RelayClient is closed")
            val existing = webSocket
            val def = connectDeferred
            if (existing != null && def?.isCompleted == true && def.getCompletionExceptionOrNull() == null) {
                return@synchronized Pair(existing, null)
            }

            val newDef = CompletableDeferred<Unit>()
            connectDeferred = newDef
            val request = Request.Builder().url(url).build()
            val socket = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    newDef.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    newDef.completeExceptionally(t)
                    onConnectionFailed(t)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val ex = IOException("WebSocket closed: $code $reason")
                    newDef.completeExceptionally(ex)
                    onConnectionFailed(ex)
                }
            })
            webSocket = socket
            Pair(socket, newDef)
        }

        if (deferred != null) {
            try {
                withTimeout(15_000) {
                    deferred.await()
                }
            } catch (e: Throwable) {
                synchronized(lock) {
                    ws.cancel()
                    if (webSocket === ws) {
                        webSocket = null
                        connectDeferred = null
                    }
                }
                throw e
            }
        }
        ws
    }

    private fun onConnectionFailed(t: Throwable) {
        synchronized(lock) {
            webSocket = null
            connectDeferred = null
        }
        for ((_, deferred) in pendingPublishes) {
            deferred.complete(Result.failure(t))
        }
        pendingPublishes.clear()
        for ((_, scope) in subscriptions) {
            scope.close(t)
        }
        subscriptions.clear()
    }

    private fun handleMessage(text: String) {
        val array = try {
            json.parseToJsonElement(text).jsonArray
        } catch (_: Exception) {
            return
        }
        val type = array.getOrNull(0)?.jsonPrimitive?.content ?: return
        when (type) {
            "OK" -> {
                val eventId = array.getOrNull(1)?.jsonPrimitive?.content ?: return
                val success = array.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false
                val message = array.getOrNull(3)?.jsonPrimitive?.content ?: ""
                val deferred = pendingPublishes.remove(eventId)
                if (success) {
                    deferred?.complete(Result.success(Unit))
                } else {
                    deferred?.complete(Result.failure(IOException("Relay rejected event $eventId: $message")))
                }
            }
            "EVENT" -> {
                val subId = array.getOrNull(1)?.jsonPrimitive?.content ?: return
                val eventElement = array.getOrNull(2) ?: return
                val event = try {
                    json.decodeFromJsonElement(NostrEvent.serializer(), eventElement)
                } catch (_: Exception) {
                    return
                }
                subscriptions[subId]?.trySend(RelayItem.Event(event))
            }
            "EOSE" -> {
                val subId = array.getOrNull(1)?.jsonPrimitive?.content ?: return
                subscriptions[subId]?.trySend(RelayItem.StoredEventsEnd)
            }
            "CLOSED" -> {
                val subId = array.getOrNull(1)?.jsonPrimitive?.content ?: return
                val message = array.getOrNull(2)?.jsonPrimitive?.content ?: ""
                val scope = subscriptions.remove(subId)
                subscriptionFilters.remove(subId)
                scope?.close(IOException("Subscription $subId closed by relay: $message"))
            }
            "NOTICE" -> {
                // Notice received
            }
        }
    }

    suspend fun publish(event: NostrEvent): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val ws = ensureConnected()
            val deferred = CompletableDeferred<Result<Unit>>()
            pendingPublishes[event.id] = deferred

            val eventJson = json.encodeToJsonElement(NostrEvent.serializer(), event)
            val msg = buildJsonArray {
                add("EVENT")
                add(eventJson)
            }
            val sent = ws.send(msg.toString())
            if (!sent) {
                pendingPublishes.remove(event.id)
                return@withContext Result.failure(IOException("Failed to send EVENT message to WebSocket"))
            }

            withTimeout(15_000) {
                deferred.await()
            }
        } catch (e: Exception) {
            pendingPublishes.remove(event.id)
            Result.failure(e)
        }
    }

    fun subscribe(id: String, filter: JsonObject): Flow<NostrEvent> =
        subscribeItems(id, filter).filterIsInstance<RelayItem.Event>().map { it.event }

    /**
     * Sends the subscription's REQ again on the live socket, so the relay replays what it stores and ends with a new
     * [RelayItem.StoredEventsEnd]. False when there is no live subscription with this id.
     */
    fun refresh(id: String): Boolean {
        val filter = subscriptionFilters[id] ?: return false
        val ws = synchronized(lock) { webSocket } ?: return false
        if (!subscriptions.containsKey(id)) return false
        val reqMsg = buildJsonArray {
            add("REQ")
            add(id)
            add(filter)
        }
        return ws.send(reqMsg.toString())
    }

    /** Drops the socket without closing the client; subscriptions end with an error and the owner reconnects. */
    fun dropConnection() {
        val ws = synchronized(lock) { webSocket }
        ws?.cancel()
    }

    /** Events in the relay's order, with a marker where the stored events end. Nothing is dropped under a burst. */
    fun subscribeItems(id: String, filter: JsonObject): Flow<RelayItem> = callbackFlow {
        val ws = try {
            withContext(Dispatchers.IO) { ensureConnected() }
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }
        subscriptions[id] = this
        subscriptionFilters[id] = filter

        val reqMsg = buildJsonArray {
            add("REQ")
            add(id)
            add(filter)
        }
        val sent = ws.send(reqMsg.toString())
        if (!sent) {
            subscriptions.remove(id)
            subscriptionFilters.remove(id)
            close(IOException("Failed to send REQ message to WebSocket"))
            return@callbackFlow
        }

        awaitClose {
            subscriptions.remove(id)
            subscriptionFilters.remove(id)
            val closeMsg = buildJsonArray {
                add("CLOSE")
                add(id)
            }
            ws.send(closeMsg.toString())
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    override fun close() {
        synchronized(lock) {
            isClosed = true
            webSocket?.close(1000, "Normal closure")
            webSocket = null
            connectDeferred = null
        }
        onConnectionFailed(IOException("Client closed"))
    }

    private companion object {
        const val PING_SECONDS = 30L
    }
}

sealed interface RelayItem {
    data class Event(val event: NostrEvent) : RelayItem

    /** The relay's EOSE: everything it had stored for the subscription has been sent. */
    data object StoredEventsEnd : RelayItem
}
