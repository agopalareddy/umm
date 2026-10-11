package io.github.agopalareddy.umm.sync.pair

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.auth.SecretCipher
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import io.github.agopalareddy.umm.core.stats.StatsRepository
import io.github.agopalareddy.umm.core.sync.HybridClock
import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.buildSnapshot
import io.github.agopalareddy.umm.sync.SyncSession
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.crypto.Hkdf
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.lan.Discovery
import io.github.agopalareddy.umm.sync.lan.LanPeer
import io.github.agopalareddy.umm.sync.lan.LanTransport
import io.github.agopalareddy.umm.sync.relay.NostrEvent
import io.github.agopalareddy.umm.sync.relay.RelayTransport
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class PairingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val toClose = mutableListOf<() -> Unit>()

    private class FakeKeyValueStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(vararg keys: String) { keys.forEach { map.remove(it) } }
    }

    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(blob: ByteArray): ByteArray = blob
    }

    private class FakeDiscovery : Discovery {
        override fun advertise(port: Int, groupHash: String, deviceId: String): AutoCloseable = AutoCloseable { }
        override fun browse(groupHash: String): Flow<LanPeer> = emptyFlow()
    }

    private class TestNode(
        val name: String,
        tmpFolder: File,
        val now: () -> Long = { 10_000L },
        val okHttpClient: OkHttpClient = OkHttpClient(),
        val relayUrls: List<String> = GroupState.DEFAULT_RELAYS,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = inMemoryUmmDatabase()
        val meta = SyncMeta(db)
        val clock = HybridClock { now() }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmpFolder, "settings_$name.preferences_pb")
        }
        val log = OpLog(db, meta, clock, active = { true })
        val settings = SettingsRepository(dataStore, recorder = log)
        val history = HistoryRepository(db, clock = now, recorder = log)
        val stats = StatsRepository(db, recorder = log)
        var onGroupOpCallback: (suspend (OpEnvelope) -> Unit)? = null
        val applier = OpApplier(db, log, settings, clock, onGroupOp = { onGroupOpCallback?.invoke(it) })

        val kv = FakeKeyValueStore()
        val cipher = FakeCipher()
        val groupStore = GroupStore(kv, cipher)
        val apiKeyStore = ApiKeyStore(kv, cipher)
        val selfId = runBlocking { meta.deviceId() }

        val session = SyncSession(
            log = log,
            applier = applier,
            snapshot = { buildSnapshot(db, log, settings, groupStore.state.value?.members ?: emptyList()) },
            cipher = {
                val st = groupStore.state.value ?: throw IllegalStateException("No group")
                GroupCipher(st.key.encodeToByteArray().copyOf(32), st.epoch)
            },
            selfId = selfId,
        )

        val lanTransport = LanTransport(
            session = session,
            discovery = FakeDiscovery(),
            group = groupStore,
            selfId = selfId,
            scope = scope,
            db = db,
        )

        val relayTransport = RelayTransport(
            group = groupStore,
            log = log,
            applier = applier,
            http = okHttpClient,
            scope = scope,
            clockSeconds = { now() / 1000L },
        )

        val pairing = Pairing(
            group = groupStore,
            apiKey = apiKeyStore,
            lan = lanTransport,
            relay = relayTransport,
            applier = applier,
            snapshot = { buildSnapshot(db, log, settings, groupStore.state.value?.members ?: emptyList()) },
            now = now,
            defaultRelays = relayUrls,
        )

        fun close() {
            lanTransport.stop()
            relayTransport.stop()
            db.close()
            scope.cancel()
        }
    }

    private class FakeRelay : WebSocketListener() {
        val clientSockets = CopyOnWriteArrayList<WebSocket>()
        val subscriptions = ConcurrentHashMap<WebSocket, ConcurrentHashMap<String, JsonObject>>()
        val publishedEvents = CopyOnWriteArrayList<NostrEvent>()
        private val json = Json { ignoreUnknownKeys = true }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            clientSockets.add(webSocket)
            subscriptions[webSocket] = ConcurrentHashMap()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val array = try {
                json.parseToJsonElement(text).jsonArray
            } catch (_: Exception) {
                return
            }
            val type = array.getOrNull(0)?.jsonPrimitive?.content ?: return
            when (type) {
                "EVENT" -> {
                    val eventElement = array.getOrNull(1) ?: return
                    val event = json.decodeFromJsonElement(NostrEvent.serializer(), eventElement)
                    publishedEvents.add(event)
                    val okMsg = buildJsonArray {
                        add("OK")
                        add(event.id)
                        add(true)
                        add("")
                    }
                    webSocket.send(okMsg.toString())

                    for (otherSocket in clientSockets) {
                        val subs = subscriptions[otherSocket] ?: continue
                        for ((subId, filter) in subs) {
                            if (matchesFilter(event, filter)) {
                                val eventMsg = buildJsonArray {
                                    add("EVENT")
                                    add(subId)
                                    add(eventElement)
                                }
                                otherSocket.send(eventMsg.toString())
                            }
                        }
                    }
                }
                "REQ" -> {
                    val subId = array.getOrNull(1)?.jsonPrimitive?.content ?: return
                    val filter = array.getOrNull(2)?.jsonObject ?: buildJsonObject {}
                    subscriptions[webSocket]?.put(subId, filter)
                    val eoseMsg = buildJsonArray {
                        add("EOSE")
                        add(subId)
                    }
                    webSocket.send(eoseMsg.toString())

                    for (event in publishedEvents) {
                        if (matchesFilter(event, filter)) {
                            val eventMsg = buildJsonArray {
                                add("EVENT")
                                add(subId)
                                add(json.encodeToJsonElement(NostrEvent.serializer(), event))
                            }
                            webSocket.send(eventMsg.toString())
                        }
                    }
                }
                "CLOSE" -> {
                    val subId = array.getOrNull(1)?.jsonPrimitive?.content ?: return
                    subscriptions[webSocket]?.remove(subId)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            clientSockets.remove(webSocket)
            subscriptions.remove(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            clientSockets.remove(webSocket)
            subscriptions.remove(webSocket)
        }

        private fun matchesFilter(event: NostrEvent, filter: JsonObject): Boolean {
            val kinds = filter["kinds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
            if (kinds != null && kinds.isNotEmpty() && event.kind !in kinds) return false

            val authors = filter["authors"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
            if (authors != null && authors.isNotEmpty() && event.pubkey.lowercase() !in authors) return false

            val pTags = event.tags.filter { it.getOrNull(0) == "p" }.mapNotNull { it.getOrNull(1)?.lowercase() }
            val filterP = filter["#p"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
            if (filterP != null && filterP.isNotEmpty() && pTags.none { it in filterP }) return false

            val since = filter["since"]?.jsonPrimitive?.longOrNull
            if (since != null && event.created_at < since) return false

            return true
        }
    }

    @After
    fun tearDown() {
        toClose.forEach { runCatching { it() } }
        toClose.clear()
    }

    @Test
    fun lanPairingHandsOverGroupAndKey() = runTest(timeout = 20.seconds) {
        val nodeA = TestNode("NodeA", tmp.newFolder()).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder()).also { toClose.add { it.close() } }

        nodeA.apiKeyStore.set("a".repeat(24))
        val hId = nodeA.history.createPending(
            appLabel = "Example",
            packageName = "com.example.app",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "",
        )
        nodeA.history.markDone(hId, raw = "Hello from node A", clean = "Hello from node A.")

        val offer = nodeA.pairing.offer(sendKey = true)
        val joinerResult = nodeB.pairing.join(offer.code.encode(), sendKey = true)
        val offererResult = offer.result.await()

        val groupA = nodeA.groupStore.state.value
        val groupB = nodeB.groupStore.state.value

        assertNotNull(groupA)
        assertNotNull(groupB)
        assertEquals(groupA!!.groupId, groupB!!.groupId)
        assertEquals(0, groupA.epoch)
        assertEquals(0, groupB.epoch)
        assertEquals(2, groupA.members.size)
        assertEquals(2, groupB.members.size)

        assertEquals("a".repeat(24), nodeB.apiKeyStore.get())
        assertTrue(joinerResult.keyReceived)
        assertFalse(offererResult.keyReceived)

        val bHistory = nodeB.history.observeRecent().first()
        assertEquals(1, bHistory.size)
        assertEquals("Hello from node A.", bHistory[0].cleanText)
    }

    @Test
    fun relayFallback() = runTest(timeout = 20.seconds) {
        val fakeRelay = FakeRelay()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse.Builder().webSocketUpgrade(fakeRelay).build()
            }
        }
        server.start()
        toClose.add {
            fakeRelay.clientSockets.forEach { runCatching { it.close(1000, "close") } }
            server.close()
        }

        val relayUrl = "ws://${server.hostName}:${server.port}/"
        val nodeA = TestNode("NodeA", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }

        nodeA.apiKeyStore.set("b".repeat(24))
        val hId = nodeA.history.createPending(
            appLabel = "RelayApp",
            packageName = "com.relay.app",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "",
        )
        nodeA.history.markDone(hId, raw = "History via relay", clean = "History via relay.")

        val offer = nodeA.pairing.offer(sendKey = true)
        // Make LAN unreachable so it falls back to relay
        val unreachableCode = offer.code.copy(
            lan = listOf("192.0.2.1"),
            port = 54321,
            relays = listOf(relayUrl),
        )

        val joinerResult = nodeB.pairing.join(unreachableCode.encode(), sendKey = true)
        val offererResult = offer.result.await()

        val groupA = nodeA.groupStore.state.value
        val groupB = nodeB.groupStore.state.value

        assertNotNull(groupA)
        assertNotNull(groupB)
        assertEquals(groupA!!.groupId, groupB!!.groupId)
        assertEquals(0, groupA.epoch)
        assertEquals(0, groupB.epoch)
        assertEquals(2, groupA.members.size)
        assertEquals(2, groupB.members.size)

        assertEquals("b".repeat(24), nodeB.apiKeyStore.get())
        assertTrue(joinerResult.keyReceived)

        val bHistory = nodeB.history.observeRecent().first()
        assertEquals(1, bHistory.size)
        assertEquals("History via relay.", bHistory[0].cleanText)
    }

    @Test
    fun codeIsSingleUse() = runTest(timeout = 20.seconds) {
        val nodeA = TestNode("NodeA", tmp.newFolder()).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder()).also { toClose.add { it.close() } }
        val nodeC = TestNode("NodeC", tmp.newFolder()).also { toClose.add { it.close() } }

        val offer = nodeA.pairing.offer(sendKey = true)
        val encoded = offer.code.encode()

        nodeB.pairing.join(encoded, sendKey = true)
        offer.result.await()

        try {
            nodeC.pairing.join(encoded, sendKey = true)
            fail("Expected PairingException(USED)")
        } catch (e: PairingException) {
            assertEquals(PairingError.USED, e.error)
        }
    }

    @Test
    fun wrongSecretFails() = runTest(timeout = 20.seconds) {
        val nodeA = TestNode("NodeA", tmp.newFolder()).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder()).also { toClose.add { it.close() } }

        val offer = nodeA.pairing.offer(sendKey = true)
        val wrongCode = offer.code.copy(secret = "different-secret-value-1234")

        try {
            nodeB.pairing.join(wrongCode.encode(), sendKey = true)
            fail("Expected PairingException(WRONG_SECRET)")
        } catch (e: PairingException) {
            assertEquals(PairingError.WRONG_SECRET, e.error)
        }

        assertNull(nodeA.groupStore.state.value)
        assertNull(nodeB.groupStore.state.value)
        assertNull(nodeB.apiKeyStore.get())
    }

    @Test
    fun keyNotSentWhenUnchecked() = runTest(timeout = 20.seconds) {
        val nodeA = TestNode("NodeA", tmp.newFolder()).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder()).also { toClose.add { it.close() } }

        nodeA.apiKeyStore.set("sk-or-v1-my-key")

        val offer = nodeA.pairing.offer(sendKey = false)
        val joinerResult = nodeB.pairing.join(offer.code.encode(), sendKey = false)
        offer.result.await()

        assertNull(nodeB.apiKeyStore.get())
        assertFalse(joinerResult.keyReceived)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(clean[i], 16)
            val low = Character.digit(clean[i + 1], 16)
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun flippingInGroupInHelloFailsWithWrongSecret() = runTest(timeout = 20.seconds) {
        val nodeA = TestNode("NodeA", tmp.newFolder()).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder()).also { toClose.add { it.close() } }

        val offer = nodeA.pairing.offer(sendKey = true)
        val socket = java.net.Socket("127.0.0.1", offer.code.port)
        toClose.add { runCatching { socket.close() } }

        val myDeviceId = nodeB.selfId
        val peerPubKeyBytes = hexToBytes(offer.code.publicKey)
        val agree = nodeB.groupStore.device.agree(peerPubKeyBytes)
        val salt = offer.code.deviceId.encodeToByteArray() + myDeviceId.encodeToByteArray()
        val k = Hkdf.derive(agree + offer.code.secret.encodeToByteArray(), salt, "umm-pair", 32)

        // Compute MAC for inGroup = false
        val bos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(bos)
        for (f in listOf(
            "hello".encodeToByteArray(),
            offer.code.deviceId.encodeToByteArray(),
            myDeviceId.encodeToByteArray(),
            offer.code.publicKey.encodeToByteArray(),
            nodeB.groupStore.device.publicKey.toHex().encodeToByteArray(),
            byteArrayOf(0), // inGroup = false
            nodeB.groupStore.deviceName.encodeToByteArray(),
            "desktop".encodeToByteArray(),
        )) {
            dos.writeInt(f.size)
            dos.write(f)
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA256"))
        val hmac = mac.doFinal(bos.toByteArray()).toHex()

        val hello = PairMsg.Hello(
            deviceId = myDeviceId,
            name = nodeB.groupStore.deviceName,
            publicKey = nodeB.groupStore.device.publicKey.toHex(),
            platform = "desktop",
            inGroup = true, // flipped in transit
            hmac = hmac,
        )
        val json = Json { ignoreUnknownKeys = true }
        io.github.agopalareddy.umm.sync.lan.FrameCodec.write(
            socket.getOutputStream(),
            json.encodeToString(PairMsg.serializer(), hello).encodeToByteArray()
        )
        val replyBytes = io.github.agopalareddy.umm.sync.lan.FrameCodec.read(socket.getInputStream())
        val reply = json.decodeFromString(PairMsg.serializer(), replyBytes.decodeToString())
        assertTrue("Expected PairMsg.Error(WRONG_SECRET), got $reply", reply is PairMsg.Error && reply.error == PairingError.WRONG_SECRET)
        assertNull(nodeA.groupStore.state.value)
        assertNull(nodeB.groupStore.state.value)
    }

    @Test
    fun relayPairingWithBadSignatureIsIgnored() = runTest(timeout = 20.seconds) {
        val fakeRelay = FakeRelay()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse.Builder().webSocketUpgrade(fakeRelay).build()
            }
        }
        server.start()
        toClose.add {
            fakeRelay.clientSockets.forEach { runCatching { it.close(1000, "close") } }
            server.close()
        }

        val relayUrl = "ws://${server.hostName}:${server.port}/"
        val nodeA = TestNode("NodeA", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }

        val offer = nodeA.pairing.offer(sendKey = true)

        val badEvent = NostrEvent(
            id = "00".repeat(32),
            pubkey = nodeB.groupStore.device.publicKey.toHex(),
            created_at = 10_000L,
            kind = io.github.agopalareddy.umm.sync.relay.KIND_PAIRING,
            tags = listOf(listOf("p", offer.code.publicKey.lowercase())),
            content = """{"type":"Hello","deviceId":"bad","name":"bad","publicKey":"${nodeB.groupStore.device.publicKey.toHex()}","platform":"desktop","inGroup":false,"hmac":"bad"}""",
            sig = "00".repeat(64),
        )
        fakeRelay.publishedEvents.add(badEvent)

        assertFalse(offer.result.isCompleted)

        val unreachableCode = offer.code.copy(lan = listOf("192.0.2.1"), port = 54321, relays = listOf(relayUrl))
        val joinerResult = nodeB.pairing.join(unreachableCode.encode(), sendKey = true)
        val offererResult = offer.result.await()

        assertNotNull(nodeA.groupStore.state.value)
        assertNotNull(nodeB.groupStore.state.value)
        assertEquals(nodeA.groupStore.state.value!!.groupId, nodeB.groupStore.state.value!!.groupId)
    }

    @Test
    fun relayPairingWithMismatchedPubkeyIsIgnored() = runTest(timeout = 20.seconds) {
        val fakeRelay = FakeRelay()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse.Builder().webSocketUpgrade(fakeRelay).build()
            }
        }
        server.start()
        toClose.add {
            fakeRelay.clientSockets.forEach { runCatching { it.close(1000, "close") } }
            server.close()
        }

        val relayUrl = "ws://${server.hostName}:${server.port}/"
        val nodeA = TestNode("NodeA", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }
        val nodeAttacker = TestNode("Attacker", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }

        val offer = nodeA.pairing.offer(sendKey = true)

        val spoofedHello = PairMsg.Hello(
            deviceId = "attacker",
            name = "attacker",
            publicKey = nodeB.groupStore.device.publicKey.toHex(),
            platform = "desktop",
            inGroup = false,
            hmac = "badhmac",
        )
        val json = Json { ignoreUnknownKeys = true }
        val spoofedEvent = NostrEvent.sign(
            keys = nodeAttacker.groupStore.device,
            kind = io.github.agopalareddy.umm.sync.relay.KIND_PAIRING,
            tags = listOf(listOf("p", offer.code.publicKey.lowercase())),
            content = json.encodeToString(PairMsg.serializer(), spoofedHello),
            createdAt = 10_000L,
        )
        fakeRelay.publishedEvents.add(spoofedEvent)

        assertFalse(offer.result.isCompleted)

        val unreachableCode = offer.code.copy(lan = listOf("192.0.2.1"), port = 54321, relays = listOf(relayUrl))
        val joinerResult = nodeB.pairing.join(unreachableCode.encode(), sendKey = true)
        val offererResult = offer.result.await()

        assertNotNull(nodeA.groupStore.state.value)
        assertNotNull(nodeB.groupStore.state.value)
        assertEquals(nodeA.groupStore.state.value!!.groupId, nodeB.groupStore.state.value!!.groupId)
    }

    @Test
    fun unauthenticatedErrorDoesNotAbortPairing() = runTest(timeout = 20.seconds) {
        val fakeRelay = FakeRelay()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse.Builder().webSocketUpgrade(fakeRelay).build()
            }
        }
        server.start()
        toClose.add {
            fakeRelay.clientSockets.forEach { runCatching { it.close(1000, "close") } }
            server.close()
        }

        val relayUrl = "ws://${server.hostName}:${server.port}/"
        val nodeA = TestNode("NodeA", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }
        val nodeAttacker = TestNode("Attacker", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }

        val offer = nodeA.pairing.offer(sendKey = true)
        val unreachableCode = offer.code.copy(lan = listOf("192.0.2.1"), port = 54321, relays = listOf(relayUrl))

        val json = Json { ignoreUnknownKeys = true }
        val fakeErrorEvent = NostrEvent.sign(
            keys = nodeAttacker.groupStore.device,
            kind = io.github.agopalareddy.umm.sync.relay.KIND_PAIRING,
            tags = listOf(listOf("p", nodeB.groupStore.device.publicKey.toHex().lowercase())),
            content = json.encodeToString(PairMsg.serializer(), PairMsg.Error(PairingError.USED)),
            createdAt = 10_000L,
        )
        fakeRelay.publishedEvents.add(fakeErrorEvent)

        val joinerResult = nodeB.pairing.join(unreachableCode.encode(), sendKey = true)
        val offererResult = offer.result.await()

        assertNotNull(nodeA.groupStore.state.value)
        assertNotNull(nodeB.groupStore.state.value)
        assertEquals(nodeA.groupStore.state.value!!.groupId, nodeB.groupStore.state.value!!.groupId)
    }

    @Test
    fun relayPairingKeepsExistingGroup() = runTest(timeout = 20.seconds) {
        val fakeRelay = FakeRelay()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse.Builder().webSocketUpgrade(fakeRelay).build()
            }
        }
        server.start()
        toClose.add {
            fakeRelay.clientSockets.forEach { runCatching { it.close(1000, "close") } }
            server.close()
        }

        val relayUrl = "ws://${server.hostName}:${server.port}/"
        val nodeA = TestNode("NodeA", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }
        val nodeB = TestNode("NodeB", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }
        val nodeC = TestNode("NodeC", tmp.newFolder(), relayUrls = listOf(relayUrl)).also { toClose.add { it.close() } }

        val initialGroupId = "existing-group-bc"
        val initialKey = "0123456789abcdef0123456789abcdef"
        val initialGroup = GroupState(
            groupId = initialGroupId,
            key = initialKey,
            epoch = 0,
            previousKey = null,
            previousUntil = 0L,
            members = listOf(
                Member(nodeB.selfId, nodeB.groupStore.deviceName, "desktop", nodeB.groupStore.device.publicKey.toHex()),
                Member(nodeC.selfId, nodeC.groupStore.deviceName, "desktop", nodeC.groupStore.device.publicKey.toHex()),
            ),
            relays = listOf(relayUrl),
        )
        nodeB.groupStore.save(initialGroup)
        nodeC.groupStore.save(initialGroup)

        val offer = nodeA.pairing.offer(sendKey = false)
        val unreachableCode = offer.code.copy(
            lan = listOf("192.0.2.1"),
            port = 54321,
            relays = listOf(relayUrl),
        )

        val joinerResult = nodeB.pairing.join(unreachableCode.encode(), sendKey = false)
        val offererResult = offer.result.await()

        val bGroup = nodeB.groupStore.state.value
        assertNotNull(bGroup)
        assertEquals(initialGroupId, bGroup!!.groupId)
        assertEquals(initialKey, bGroup.key)

        val aGroup = nodeA.groupStore.state.value
        assertNotNull(aGroup)
        assertEquals(initialGroupId, aGroup!!.groupId)
        assertEquals(initialKey, aGroup.key)
        assertTrue(aGroup.members.any { it.deviceId == nodeA.selfId })
        assertTrue(bGroup.members.any { it.deviceId == nodeA.selfId })

        nodeC.relayTransport.start()
        toClose.add { nodeC.relayTransport.stop() }
        kotlinx.coroutines.delay(200)

        val id = nodeB.history.createPending(
            packageName = "org.test",
            appLabel = "TestApp",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "",
        )
        nodeB.history.markDone(id, raw = "Raw text from B", clean = "Clean text from B")
        nodeB.relayTransport.publishPending()

        kotlinx.coroutines.withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(10.seconds) {
                while (nodeC.history.observeRecent().first().isEmpty()) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }

        val cHistory = nodeC.history.observeRecent().first()
        assertEquals(1, cHistory.size)
        assertEquals("Clean text from B", cHistory.first().cleanText)
    }
}
