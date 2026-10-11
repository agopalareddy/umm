package io.github.agopalareddy.umm.sync.relay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.auth.SecretCipher
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.HistoryRepository
import io.github.agopalareddy.umm.core.data.HistoryStatus
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.data.inMemoryUmmDatabase
import io.github.agopalareddy.umm.core.stats.StatsRepository
import io.github.agopalareddy.umm.core.sync.HybridClock
import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpApplier
import io.github.agopalareddy.umm.core.sync.OpEnvelope
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.SyncOp
import io.github.agopalareddy.umm.core.sync.SyncedHistory
import io.github.agopalareddy.umm.sync.crypto.DeviceKeys
import io.github.agopalareddy.umm.sync.crypto.GroupCipher
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.lan.groupHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

class RelayTransportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val toClose = mutableListOf<() -> Unit>()
    private lateinit var server: MockWebServer
    private lateinit var fakeRelay: FakeRelay
    private lateinit var wsUrl: String
    private val httpClient = OkHttpClient.Builder().build()

    private class FakeKeyValueStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(vararg keys: String) { keys.forEach { map.remove(it) } }
    }

    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray = blob.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private class FakeRelay : WebSocketListener() {
        val clientSockets = CopyOnWriteArrayList<WebSocket>()
        val subscriptions = ConcurrentHashMap<WebSocket, ConcurrentHashMap<String, JsonObject>>()
        val publishedEvents = CopyOnWriteArrayList<NostrEvent>()

        /** Connections that look open but get nothing any more, like one a sleeping phone left behind. */
        val deadSockets = CopyOnWriteArrayList<WebSocket>()
        private val json = Json { ignoreUnknownKeys = true }

        fun killCurrentConnectionsSilently() {
            deadSockets.addAll(clientSockets)
        }

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
                        if (otherSocket in deadSockets) continue
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
                    if (webSocket in deadSockets) return

                    // NIP-01 order: the stored events first, then EOSE.
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
                    val eoseMsg = buildJsonArray {
                        add("EOSE")
                        add(subId)
                    }
                    webSocket.send(eoseMsg.toString())
                }
                "CLOSE" -> {
                    val subId = array.getOrNull(1)?.jsonPrimitive?.content ?: return
                    subscriptions[webSocket]?.remove(subId)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            clientSockets.remove(webSocket)
            subscriptions.remove(webSocket)
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

            val since = filter["since"]?.jsonPrimitive?.longOrNull
            if (since != null && event.created_at < since) return false

            return true
        }
    }

    private class TestDevice(
        val name: String,
        tmpFolder: File,
        val clockSec: Long = 1_700_000_000L,
    ) {
        val deviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = inMemoryUmmDatabase()
        val meta = SyncMeta(db)
        val clock = HybridClock { clockSec * 1000L }
        val dataStore = PreferenceDataStoreFactory.create(scope = deviceScope) {
            File(tmpFolder, "settings_$name.preferences_pb")
        }
        val log = OpLog(db, meta, clock, active = { true })
        val settings = SettingsRepository(dataStore, recorder = log)
        val history = HistoryRepository(db, clock = { clockSec * 1000L }, recorder = log)
        val stats = StatsRepository(db, recorder = log)
        val applier = OpApplier(db, log, settings, clock, onGroupOp = { })
        val kv = FakeKeyValueStore()
        val groupStore = GroupStore(kv, FakeCipher())
        val selfId = runBlocking { meta.deviceId() }

        fun transport(http: OkHttpClient, expirationSeconds: Long = EXPIRATION_SECONDS): RelayTransport {
            return RelayTransport(
                group = groupStore,
                log = log,
                applier = applier,
                http = http,
                scope = deviceScope,
                clockSeconds = { clockSec },
                expirationSeconds = expirationSeconds,
            )
        }

        fun close() {
            db.close()
            deviceScope.cancel()
        }
    }

    @Before
    fun setUp() {
        fakeRelay = FakeRelay()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse.Builder().webSocketUpgrade(fakeRelay).build()
            }
        }
        server.start()
        wsUrl = "ws://${server.hostName}:${server.port}/"
    }

    @After
    fun tearDown() {
        toClose.forEach { runCatching { it() } }
        toClose.clear()
        fakeRelay.clientSockets.forEach { runCatching { it.close(1000, "tearDown") } }
        fakeRelay.clientSockets.clear()
        runCatching { server.close() }
    }

    @Test
    fun publishesBatchesUnderLimit() = runTest(timeout = 20.seconds) {
        val deviceA = TestDevice("DeviceA", tmp.newFolder(), clockSec = 1_700_000_000L)
        toClose.add { deviceA.close() }

        val groupId = "test-group"
        val sharedKey = "shared-secret-key-32-bytes-test!"
        val state = GroupState(
            groupId = groupId,
            key = sharedKey,
            epoch = 1,
            previousKey = null,
            previousUntil = 0L,
            members = listOf(
                Member(deviceA.selfId, "DeviceA", "desktop", deviceA.groupStore.device.publicKey.toHex())
            ),
            relays = listOf(wsUrl),
        )
        deviceA.groupStore.save(state)

        repeat(500) { i ->
            val id = deviceA.history.createPending(
                packageName = "org.app$i",
                appLabel = "App$i",
                level = CleanupLevel.POLISHED,
                script = ScriptPreference.LATIN,
                language = "en",
                audioPath = "dummy$i.wav",
            )
            deviceA.history.markDone(id, raw = "text$i", clean = "clean$i")
        }

        val transport = deviceA.transport(httpClient)
        toClose.add { transport.stop() }

        transport.publishPending()

        assertTrue(
            "Expected several events for 500 ops, got ${fakeRelay.publishedEvents.size}",
            fakeRelay.publishedEvents.size > 1
        )
        val expectedHash = groupHash(groupId)
        for (event in fakeRelay.publishedEvents) {
            assertTrue("Event content exceeds MAX_CONTENT: ${event.content.length}", event.content.length < MAX_CONTENT)
            assertTrue("Event verification failed", event.verify())
            val dTag = event.tags.find { it.getOrNull(0) == "d" }?.getOrNull(1)
            val expTag = event.tags.find { it.getOrNull(0) == "expiration" }?.getOrNull(1)
            assertTrue("Missing or invalid d tag: $dTag", dTag != null && dTag.startsWith("umm:$expectedHash:"))
            val expectedExp = (1_700_000_000L + EXPIRATION_SECONDS).toString()
            assertEquals("Expiration mismatch", expectedExp, expTag)
        }
    }

    @Test
    fun deliversToOtherMember() = runTest(timeout = 20.seconds) {
        val deviceA = TestDevice("DeviceA", tmp.newFolder(), clockSec = 1_700_000_000L)
        val deviceB = TestDevice("DeviceB", tmp.newFolder(), clockSec = 1_700_000_000L)
        toClose.add { deviceA.close() }
        toClose.add { deviceB.close() }

        val groupId = "test-group"
        val sharedKey = "shared-secret-key-32-bytes-test!"
        val members = listOf(
            Member(deviceA.selfId, "DeviceA", "desktop", deviceA.groupStore.device.publicKey.toHex()),
            Member(deviceB.selfId, "DeviceB", "desktop", deviceB.groupStore.device.publicKey.toHex()),
        )
        val stateA = GroupState(groupId, sharedKey, 1, null, 0L, members, listOf(wsUrl))
        val stateB = GroupState(groupId, sharedKey, 1, null, 0L, members, listOf(wsUrl))
        deviceA.groupStore.save(stateA)
        deviceB.groupStore.save(stateB)

        val transportB = deviceB.transport(httpClient)
        toClose.add { transportB.stop() }
        transportB.start()

        // Wait briefly for B's subscription to connect
        delay(200)

        val id = deviceA.history.createPending(
            packageName = "org.notes",
            appLabel = "Notes",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "dummy.wav",
        )
        deviceA.history.markDone(id, raw = "Meeting notes raw", clean = "Meeting notes cleaned")

        val transportA = deviceA.transport(httpClient)
        toClose.add { transportA.stop() }
        transportA.publishPending()

        withContext(Dispatchers.IO) {
            withTimeout(10.seconds) {
                while (deviceB.history.observeRecent().first().isEmpty()) {
                    delay(50)
                }
            }
        }

        val bHistory = deviceB.history.observeRecent().first()
        assertEquals(1, bHistory.size)
        assertEquals("Meeting notes cleaned", bHistory.first().cleanText)
    }

    private suspend fun pair(vararg devices: TestDevice) {
        val members = devices.map { Member(it.selfId, it.name, "desktop", it.groupStore.device.publicKey.toHex()) }
        for (device in devices) {
            device.groupStore.save(GroupState("test-group", "shared-secret-key-32-bytes-test!", 1, null, 0L, members, listOf(wsUrl)))
        }
    }

    private suspend fun TestDevice.dictate(text: String) {
        val id = history.createPending(
            packageName = "org.notes",
            appLabel = "Notes",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "dummy.wav",
        )
        history.markDone(id, raw = text, clean = text)
    }

    private fun newDevice(name: String) = TestDevice(name, tmp.newFolder()).also { device -> toClose.add { device.close() } }

    private fun TestDevice.startedTransport() = transport(httpClient).also { transport ->
        toClose.add { transport.stop() }
        transport.start()
    }

    @Test
    fun catchUpReplacesAConnectionThatDiedSilently() = runTest(timeout = 30.seconds) {
        val deviceA = newDevice("DeviceA")
        val deviceB = newDevice("DeviceB")
        pair(deviceA, deviceB)

        val transportB = deviceB.startedTransport()
        withContext(Dispatchers.IO) {
            assertEquals(mapOf(wsUrl to true), transportB.catchUp(4_000))
        }

        fakeRelay.killCurrentConnectionsSilently()
        deviceA.dictate("Sent while B's connection was dead")
        deviceA.transport(httpClient).also { toClose.add { it.stop() } }.publishPending()

        withContext(Dispatchers.IO) {
            delay(300)
            assertTrue("nothing can arrive on the dead connection", deviceB.history.observeRecent().first().isEmpty())

            // The stored events come before the relay's end marker, so the row is there when catchUp returns.
            assertEquals(mapOf(wsUrl to true), transportB.catchUp(4_000))
            assertEquals(
                listOf("Sent while B's connection was dead"),
                deviceB.history.observeRecent().first().map { it.cleanText },
            )
        }
    }

    @Test
    fun aBurstOfStoredEventsIsAppliedInFull() = runTest(timeout = 60.seconds) {
        val deviceA = newDevice("DeviceA")
        val deviceB = newDevice("DeviceB")
        pair(deviceA, deviceB)

        // One event per op, so the relay replays far more events at once than the old 64-event buffer held.
        val transportA = deviceA.transport(httpClient).also { toClose.add { it.stop() } }
        withContext(Dispatchers.IO) {
            for (originId in 1L..300L) {
                deviceA.log.record(SyncOp.HistoryDelete(deviceA.selfId, originId))
                transportA.publishPending()
            }
            assertEquals(300, fakeRelay.publishedEvents.size)

            val transportB = deviceB.startedTransport()
            assertEquals(mapOf(wsUrl to true), transportB.catchUp(20_000))
            assertEquals(300L, deviceB.log.highWaterMarks()[deviceA.selfId])
            assertEquals(300, deviceB.log.since(emptyMap()).count { it.origin == deviceA.selfId })
        }
    }

    @Test
    fun aMemberAddedLaterIsHeardWithoutARestart() = runTest(timeout = 30.seconds) {
        val deviceA = newDevice("DeviceA")
        val deviceB = newDevice("DeviceB")
        val deviceC = newDevice("DeviceC")
        pair(deviceA, deviceB)

        val transportB = deviceB.startedTransport()
        withContext(Dispatchers.IO) {
            assertEquals(mapOf(wsUrl to true), transportB.catchUp(4_000))

            pair(deviceA, deviceB, deviceC)
            deviceC.dictate("From the device that joined later")
            deviceC.transport(httpClient).also { toClose.add { it.stop() } }.publishPending()

            withTimeout(10.seconds) {
                while (deviceB.history.observeRecent().first().isEmpty()) delay(50)
            }
            assertEquals(
                listOf("From the device that joined later"),
                deviceB.history.observeRecent().first().map { it.cleanText },
            )
        }
    }

    /**
     * Talks to the real default relays with throwaway keys and one tiny encrypted event that expires in two minutes.
     * Fakes cannot show a relay's rate limits or refusals. Run with `-PrelayCheck`; skipped otherwise.
     */
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun publicRelaysStoreAndDeliver() = runTest(timeout = 180.seconds) {
        assumeTrue("run with -PrelayCheck", System.getProperty("umm.relayCheck") == "true")
        val failures = mutableListOf<String>()

        withContext(Dispatchers.IO) {
            for (relay in GroupState.DEFAULT_RELAYS) {
                val now = System.currentTimeMillis() / 1000
                val sender = TestDevice("Sender", tmp.newFolder(), clockSec = now).also { device -> toClose.add { device.close() } }
                val listener = TestDevice("Listener", tmp.newFolder(), clockSec = now).also { device -> toClose.add { device.close() } }
                val latecomer = TestDevice("Latecomer", tmp.newFolder(), clockSec = now).also { device -> toClose.add { device.close() } }
                val devices = listOf(sender, listener, latecomer)
                val members = devices.map { Member(it.selfId, it.name, "desktop", it.groupStore.device.publicKey.toHex()) }
                val key = Base64.UrlSafe.encode(java.security.SecureRandom().generateSeed(32))
                val groupId = java.util.UUID.randomUUID().toString()
                devices.forEach { it.groupStore.save(GroupState(groupId, key, 1, null, 0L, members, listOf(relay))) }
                fun TestDevice.live() = transport(httpClient, expirationSeconds = 120).also { transport -> toClose.add { transport.stop() } }

                val listening = listener.live().also { it.start() }
                val listenerReady = listening.catchUp(15_000)[relay] == true

                sender.dictate("relay check")
                val published = sender.live().publishPending().single()
                val accepted = published.error == null && published.opsPublished > 0

                val pushed = withTimeoutOrNull(15.seconds) {
                    while (listener.history.observeRecent().first().isEmpty()) delay(100)
                    true
                } == true

                val late = latecomer.live().also { it.start() }
                val lateCaughtUp = late.catchUp(15_000)[relay] == true
                val stored = latecomer.history.observeRecent().first().isNotEmpty()

                println(
                    "RELAY CHECK $relay: subscribe=${if (listenerReady) "ok" else "NO ANSWER"} " +
                        "publish=${if (accepted) "accepted" else "REFUSED (${published.error})"} " +
                        "live delivery=${if (pushed) "ok" else "NOTHING IN 15 s"} " +
                        "stored delivery=${if (stored) "ok" else if (lateCaughtUp) "NOT STORED" else "NO ANSWER"}",
                )
                if (!(listenerReady && accepted && pushed && stored)) failures += relay
            }
        }
        assertTrue("relays that failed the check: $failures", failures.isEmpty())
    }

    @Test
    fun ignoresNonMembers() = runTest(timeout = 20.seconds) {
        val deviceB = TestDevice("DeviceB", tmp.newFolder(), clockSec = 1_700_000_000L)
        toClose.add { deviceB.close() }

        val groupId = "test-group"
        val sharedKey = "shared-secret-key-32-bytes-test!"
        val members = listOf(
            Member(deviceB.selfId, "DeviceB", "desktop", deviceB.groupStore.device.publicKey.toHex())
        )
        val stateB = GroupState(groupId, sharedKey, 1, null, 0L, members, listOf(wsUrl))
        deviceB.groupStore.save(stateB)

        val transportB = deviceB.transport(httpClient)
        toClose.add { transportB.stop() }
        transportB.start()

        delay(200)

        // Non-member attacker signs an event
        val attackerKeys = DeviceKeys.generate()
        val fakeHistory = SyncedHistory(
            originDevice = "attacker-dev",
            originId = 1L,
            appLabel = "Malicious",
            createdAt = 1_700_000_000L,
            packageName = "org.bad",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            rawText = "bad",
            cleanText = "bad clean",
            status = HistoryStatus.DONE,
        )
        val op = SyncOp.HistoryUpsert(fakeHistory)
        val envelope = OpEnvelope("attacker-dev", 1L, 1000L, op)

        val cipher = GroupCipher(sharedKey.encodeToByteArray().copyOf(32), 1)
        val json = Json { ignoreUnknownKeys = true }
        val jsonBytes = json.encodeToString(listOf(envelope)).encodeToByteArray()
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(jsonBytes) }
        val sealed = cipher.seal(bos.toByteArray())
        @OptIn(ExperimentalEncodingApi::class)
        val content = Base64.encode(sealed)

        val event = NostrEvent.sign(
            keys = attackerKeys,
            kind = KIND_APP_DATA,
            tags = listOf(listOf("d", "umm:${groupHash(groupId)}:1-1")),
            content = content,
            createdAt = 1_700_000_000L
        )

        val client = RelayClient(wsUrl, httpClient)
        toClose.add { client.close() }
        client.publish(event)

        delay(500)
        assertTrue("Attacker's row should be ignored", deviceB.history.observeRecent().first().isEmpty())
    }

    @Test
    fun oneRelayDownStillPublishes() = runTest(timeout = 20.seconds) {
        val deviceA = TestDevice("DeviceA", tmp.newFolder(), clockSec = 1_700_000_000L)
        toClose.add { deviceA.close() }

        val deadRelayUrl = "ws://127.0.0.1:1/"
        val groupId = "test-group"
        val sharedKey = "shared-secret-key-32-bytes-test!"
        val state = GroupState(
            groupId = groupId,
            key = sharedKey,
            epoch = 1,
            previousKey = null,
            previousUntil = 0L,
            members = listOf(
                Member(deviceA.selfId, "DeviceA", "desktop", deviceA.groupStore.device.publicKey.toHex())
            ),
            relays = listOf(deadRelayUrl, wsUrl),
        )
        deviceA.groupStore.save(state)

        val id = deviceA.history.createPending(
            packageName = "org.app",
            appLabel = "App",
            level = CleanupLevel.POLISHED,
            script = ScriptPreference.LATIN,
            language = "en",
            audioPath = "dummy.wav",
        )
        deviceA.history.markDone(id, raw = "raw text", clean = "clean text")

        val transport = deviceA.transport(httpClient)
        toClose.add { transport.stop() }

        transport.publishPending()

        assertEquals(1, fakeRelay.publishedEvents.size)
        assertTrue("relayErrors should name failing URL", transport.relayErrors.value.containsKey(deadRelayUrl))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
