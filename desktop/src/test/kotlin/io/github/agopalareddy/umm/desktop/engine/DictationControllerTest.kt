package io.github.agopalareddy.umm.desktop.engine

import io.github.agopalareddy.umm.core.audio.AudioSource
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.openrouter.Completion
import io.github.agopalareddy.umm.core.openrouter.KeyInfo
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import io.github.agopalareddy.umm.core.openrouter.Transcription
import io.github.agopalareddy.umm.core.pipeline.DictationPipeline
import io.github.agopalareddy.umm.core.pipeline.DictationRequest
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.platform.XdgPaths
import io.github.agopalareddy.umm.desktop.DesktopGraph
import io.github.agopalareddy.umm.linux.BindResult
import io.github.agopalareddy.umm.linux.Clipboard
import io.github.agopalareddy.umm.linux.FocusTracker
import io.github.agopalareddy.umm.linux.HotkeyEvent
import io.github.agopalareddy.umm.linux.HotkeySource
import io.github.agopalareddy.umm.linux.InsertException
import io.github.agopalareddy.umm.linux.InsertPart
import io.github.agopalareddy.umm.linux.Notifier
import io.github.agopalareddy.umm.linux.TextInserter
import io.github.agopalareddy.umm.linux.TypingCapability
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictationControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeAudio : AudioSource {
        override val format = "wav"
        val recordings = AtomicInteger()
        @Volatile private var stopped = false
        override fun record(file: File): Flow<Int> = flow {
            recordings.incrementAndGet()
            stopped = false
            file.writeBytes(byteArrayOf(1, 2, 3))
            while (!stopped) {
                emit(8000)
                delay(10)
            }
        }
        override fun stop() { stopped = true }
    }

    private class FakeApi : OpenRouterApi {
        @Volatile var transcript = "um hello"
        @Volatile var cleaned = "Hello"
        @Volatile var cleanupError: Exception? = null
        @Volatile var transcribeGate: CompletableDeferred<Unit>? = null
        override suspend fun transcribe(model: String, audio: ByteArray, format: String, language: String?): Transcription {
            transcribeGate?.await()
            return Transcription(transcript, 0.0)
        }
        override suspend fun complete(model: String, system: String, user: String, temperature: Double): Completion {
            cleanupError?.let { throw it }
            return Completion(cleaned, 0.0)
        }
        override suspend fun listModels(outputModalities: String?): List<ModelInfo> = emptyList()
        override suspend fun exchangeAuthCode(code: String, codeVerifier: String): String = error("unused")
        override suspend fun keyInfo(): KeyInfo = error("unused")
        override suspend fun zdrModels(): Set<String> = emptySet()
        override suspend fun blockedByDataPolicy(sttModel: String): Boolean? = null
    }

    /** Every collector gets every event, and one that subscribes late first gets the ones it missed. */
    private class FakeHotkey : HotkeySource {
        private val history = CopyOnWriteArrayList<HotkeyEvent>()
        private val subscribers = CopyOnWriteArrayList<Channel<HotkeyEvent>>()

        // Sending and subscribing exclude each other, so no event falls between a late subscriber's replay and its join.
        private val lock = Any()

        fun send(event: HotkeyEvent) = synchronized(lock) {
            history += event
            subscribers.forEach { it.trySend(event) }
        }

        override val events: Flow<HotkeyEvent> = flow {
            val channel = Channel<HotkeyEvent>(Channel.UNLIMITED)
            synchronized(lock) {
                history.forEach { channel.trySend(it) }
                subscribers += channel
            }
            try {
                for (event in channel) emit(event)
            } finally {
                subscribers -= channel
            }
        }

        override suspend fun bind() = BindResult.Bound("Super+Alt+Space")
        override suspend fun configure() = false
    }

    private class FakeInserter : TextInserter {
        @Volatile override var capability = TypingCapability.ALL
        val inserted = CopyOnWriteArrayList<List<InsertPart>>()
        val started = AtomicInteger()
        @Volatile var failWith: Exception? = null
        @Volatile var gate: CompletableDeferred<Unit>? = null
        override suspend fun insert(parts: List<InsertPart>) {
            started.incrementAndGet()
            gate?.await()
            failWith?.let { throw it }
            inserted += parts
        }
    }

    private class FakeClipboard : Clipboard {
        @Volatile var copied: String? = null
        override fun setText(text: String) { copied = text }
    }

    private data class Notice(val title: String, val body: String, val openAction: Boolean, val onOpen: () -> Unit)

    private class FakeNotifier : Notifier {
        val notices = CopyOnWriteArrayList<Notice>()
        override fun notify(title: String, body: String, openAction: Boolean, onOpen: () -> Unit) {
            notices += Notice(title, body, openAction, onOpen)
        }
    }

    private class FakeFocus : FocusTracker {
        @Volatile var app: String? = null
        override fun focusedAppId() = app
    }

    private val audio = FakeAudio()
    private val api = FakeApi()
    private val hotkey = FakeHotkey()
    private val inserter = FakeInserter()
    private val clipboard = FakeClipboard()
    private val notifier = FakeNotifier()
    private val focus = FakeFocus()
    private val requestedPackages = CopyOnWriteArrayList<String>()
    @Volatile private var hasKey = true
    @Volatile private var micAvailable = true
    @Volatile private var now = 0L
    private val opened = AtomicInteger()

    private lateinit var graph: DesktopGraph
    private lateinit var scope: CoroutineScope
    private lateinit var pipeline: DictationPipeline
    private lateinit var controller: DictationController

    @Before fun setUp() {
        graph = DesktopGraph(XdgPaths(env = emptyMap(), home = tmp.root), emptyMap(), api, recommendationsUrl = UNREACHABLE)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pipeline = graph.pipeline(audio)
        controller = DictationController(
            scope = scope,
            hotkey = hotkey,
            pipeline = pipeline,
            releaseWaitMs = 1500,
            onOpenApp = { opened.incrementAndGet() },
            inserter = inserter,
            clipboard = clipboard,
            notifier = notifier,
            focus = focus,
            hasKey = { hasKey },
            micAvailable = { micAvailable },
            request = { pkg ->
                requestedPackages += pkg
                DictationRequest(pkg, CleanupLevel.LIGHT, ScriptPreference.LATIN, LanguageChoice.Auto, silenceTimeoutSec = null)
            },
            clock = { now },
        )
    }

    @After fun tearDown() {
        scope.cancel()
        graph.close()
    }

    private fun eventually(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for $what; state=${controller.state.value}")
            Thread.sleep(10)
        }
    }

    private fun press(event: HotkeyEvent, atMs: Long) {
        now = atMs
        hotkey.send(event)
    }

    private fun isListening() = controller.state.value is DesktopState.Listening

    private fun isFinal() = controller.state.value.let {
        it is DesktopState.Inserted || it is DesktopState.Copied || it is DesktopState.Failed || it == DesktopState.NoSpeech
    }

    /** Starts from the orb, stops from the orb, and waits for the outcome. */
    private fun dictateFromOrb() {
        val before = audio.recordings.get()
        controller.start()
        eventually("recording to start") { audio.recordings.get() > before && isListening() }
        controller.stop()
        eventually("a final state") { isFinal() }
    }

    @Test fun tapDictation_insertsCleanText() {
        press(HotkeyEvent.Down, 0)
        eventually("listening") { isListening() }
        press(HotkeyEvent.Up, 120)
        Thread.sleep(200)
        assertTrue("a tap keeps recording", isListening())
        press(HotkeyEvent.Down, 3000)
        press(HotkeyEvent.Up, 3100)
        eventually("inserted") { controller.state.value is DesktopState.Inserted }
        assertEquals(listOf(listOf<InsertPart>(InsertPart.Type("Hello"))), inserter.inserted.toList())
        assertEquals(DesktopState.Inserted(pastedOnAsciiDesktop = false), controller.state.value)
    }

    @Test fun hotkeyIgnoredWhileBusy() {
        val transcribeGate = CompletableDeferred<Unit>()
        val insertGate = CompletableDeferred<Unit>()
        api.transcribeGate = transcribeGate
        inserter.gate = insertGate

        press(HotkeyEvent.Down, 0)
        eventually("listening") { isListening() }
        press(HotkeyEvent.Up, 600)
        eventually("processing") { controller.state.value == DesktopState.Processing }

        press(HotkeyEvent.Down, 700)
        press(HotkeyEvent.Up, 800)
        Thread.sleep(300)
        assertEquals(1, audio.recordings.get())
        assertEquals(DesktopState.Processing, controller.state.value)

        transcribeGate.complete(Unit)
        eventually("inserting") { inserter.started.get() == 1 }
        press(HotkeyEvent.Down, 2000)
        press(HotkeyEvent.Up, 2100)
        Thread.sleep(300)
        assertEquals(1, audio.recordings.get())
        assertEquals(1, inserter.started.get())

        insertGate.complete(Unit)
        eventually("inserted") { controller.state.value is DesktopState.Inserted }
        assertEquals(1, inserter.inserted.size)
        assertEquals(1, audio.recordings.get())
    }

    @Test fun waitsForHotkeyRelease() {
        press(HotkeyEvent.Down, 0)
        eventually("listening") { isListening() }
        controller.stop()
        eventually("processing") { controller.state.value == DesktopState.Processing }
        Thread.sleep(500)
        assertTrue("nothing is typed while the hotkey is held", inserter.inserted.isEmpty())
        assertEquals(0, inserter.started.get())

        press(HotkeyEvent.Up, 900)
        eventually("inserted") { controller.state.value is DesktopState.Inserted }
        assertEquals(1, inserter.inserted.size)
    }

    @Test fun noKey_promptsAndDoesNotRecord() {
        hasKey = false
        press(HotkeyEvent.Down, 0)
        eventually("needs key") { controller.state.value == DesktopState.NeedsKey }
        assertEquals(0, audio.recordings.get())
        assertEquals(1, notifier.notices.size)
        assertTrue(notifier.notices.single().openAction)
    }

    @Test fun noMicrophone_reportsUnavailable() {
        micAvailable = false
        press(HotkeyEvent.Down, 0)
        eventually("failure") { controller.state.value is DesktopState.Failed }
        assertEquals(DesktopState.Failed("Microphone unavailable"), controller.state.value)
        assertEquals(0, audio.recordings.get())
        assertTrue(notifier.notices.single().title.contains("Microphone unavailable"))
    }

    @Test fun insertFailure_copiesAndReports() {
        inserter.failWith = InsertException("Typing permission was refused")
        dictateFromOrb()
        assertEquals(DesktopState.Copied("Typing permission was refused"), controller.state.value)
        assertEquals("Hello", clipboard.copied)
        assertTrue(notifier.notices.single().body.contains("Typing permission was refused"))
    }

    @Test fun cleanupFailed_insertsRawAndNotifies() {
        api.cleanupError = OpenRouterException.Timeout
        dictateFromOrb()
        assertEquals(listOf(listOf<InsertPart>(InsertPart.Type("um hello"))), inserter.inserted.toList())
        assertTrue(notifier.notices.single().let { "Cleanup failed" in it.title + it.body })
    }

    @Test fun orbStopAndCancel() {
        controller.start()
        eventually("listening") { isListening() }
        controller.cancel()
        eventually("idle") { controller.state.value == DesktopState.Idle }
        Thread.sleep(300)
        assertTrue("a cancelled dictation inserts nothing", inserter.inserted.isEmpty())
        assertEquals(DesktopState.Idle, controller.state.value)

        dictateFromOrb()
        assertEquals(2, audio.recordings.get())
        assertEquals(1, inserter.inserted.size)
    }

    @Test fun packageName_usesFocusedAppOrDesktop() {
        focus.app = "org.kde.kate"
        dictateFromOrb()
        focus.app = null
        dictateFromOrb()
        assertEquals(listOf("org.kde.kate", "desktop"), requestedPackages.toList())
    }

    @Test fun pasteOnAsciiDesktop_flagged() {
        inserter.capability = TypingCapability.ASCII
        api.cleaned = "Sounds good 👍"
        dictateFromOrb()
        assertEquals(DesktopState.Inserted(pastedOnAsciiDesktop = true), controller.state.value)
        assertEquals(
            listOf<InsertPart>(InsertPart.Type("Sounds good "), InsertPart.Paste("👍")),
            inserter.inserted.single(),
        )
        assertEquals("the dictated text stays on the clipboard for a manual paste", "Sounds good 👍", clipboard.copied)
    }

    @Test fun disabledHotkey_startsNothingUntilEnabledAgain() {
        controller.hotkeyEnabled = false
        press(HotkeyEvent.Down, 0)
        press(HotkeyEvent.Up, 600)
        Thread.sleep(300)
        assertEquals(0, audio.recordings.get())
        assertEquals(DesktopState.Idle, controller.state.value)

        controller.hotkeyEnabled = true
        press(HotkeyEvent.Down, 1000)
        eventually("listening") { isListening() }
        assertEquals(1, audio.recordings.get())
    }

    @Test fun hotkeyStillHeldAfterTheReleaseWait_copiesInsteadOfTyping() {
        press(HotkeyEvent.Down, 0)
        eventually("listening") { isListening() }
        controller.stop()
        // The key is never released: typing now would send keys with Super+Alt down.
        eventually("copied", timeoutMs = 8_000) { controller.state.value is DesktopState.Copied }
        assertEquals(0, inserter.started.get())
        assertEquals("Hello", clipboard.copied)
        assertTrue(notifier.notices.single().body.contains("Release"))
    }

    @Test fun holdingTheKeySwitchesToContinuousRecording() {
        press(HotkeyEvent.Down, 0)
        eventually("listening") { isListening() }
        assertEquals(false, (pipeline.state.value as DictationState.Listening).continuous)
        // A held key means hold-to-talk: a pause while talking must not end the recording.
        eventually("continuous", timeoutMs = 5_000) { (pipeline.state.value as? DictationState.Listening)?.continuous == true }
    }

    @Test fun aTapKeepsTheSilenceTimeout() {
        press(HotkeyEvent.Down, 0)
        eventually("listening") { isListening() }
        press(HotkeyEvent.Up, 50)
        // Longer than the hold threshold, shorter than the fake audio's (sped-up) no-speech grace.
        Thread.sleep(650)
        assertEquals(false, (pipeline.state.value as DictationState.Listening).continuous)
    }

    @Test fun startingRightAfterCancelStillRecords() {
        controller.start()
        eventually("listening") { isListening() }
        controller.cancel()
        controller.start()
        eventually("a second recording", timeoutMs = 5_000) { audio.recordings.get() == 2 && isListening() }
        controller.stop()
        eventually("a final state") { isFinal() }
        assertEquals(1, inserter.inserted.size)
    }

    @Test fun notificationOpenButtonOpensTheApp() {
        hasKey = false
        press(HotkeyEvent.Down, 0)
        eventually("needs key") { controller.state.value == DesktopState.NeedsKey }
        notifier.notices.single().onOpen()
        assertEquals(1, opened.get())
    }

    @Test fun shortcutTest_seesThePressAndStartsNothing() {
        val result = CompletableDeferred<Boolean>()
        scope.launch { result.complete(controller.testHotkey(5_000)) }
        eventually("test running") { !controller.hotkeyEnabled }
        press(HotkeyEvent.Down, 0)
        press(HotkeyEvent.Up, 80)
        assertTrue(runBlocking { withTimeout(5_000) { result.await() } })
        Thread.sleep(200)
        assertEquals(0, audio.recordings.get())
        assertTrue(controller.hotkeyEnabled)
        press(HotkeyEvent.Down, 1000)
        eventually("a real dictation after the test") { isListening() }
    }

    @Test fun shortcutTest_timesOutWithoutAPressAndReenablesTheHotkey() {
        assertEquals(false, runBlocking { controller.testHotkey(200) })
        assertTrue(controller.hotkeyEnabled)
    }

    @Test fun shortcutTest_cancelledStillReenablesTheHotkey() {
        val job = scope.launch { controller.testHotkey(30_000) }
        eventually("test running") { !controller.hotkeyEnabled }
        job.cancel()
        eventually("hotkey back on") { controller.hotkeyEnabled }
    }

    @Test fun typedTextLeavesTheClipboardAlone() {
        dictateFromOrb()
        assertEquals(null, clipboard.copied)
    }

    private companion object {
        val UNREACHABLE = "http://127.0.0.1:9/recommended.json".toHttpUrl()
    }
}
