package io.github.agopalareddy.umm.desktop.engine

import io.github.agopalareddy.umm.core.pipeline.DictationPipeline
import io.github.agopalareddy.umm.core.pipeline.DictationRequest
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.pipeline.FailureReason
import io.github.agopalareddy.umm.linux.Clipboard
import io.github.agopalareddy.umm.linux.FocusTracker
import io.github.agopalareddy.umm.linux.HotkeyEvent
import io.github.agopalareddy.umm.linux.HotkeySource
import io.github.agopalareddy.umm.linux.InsertPart
import io.github.agopalareddy.umm.linux.Notifier
import io.github.agopalareddy.umm.linux.TextInserter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects the hotkey, the orb and the tray to the shared [DictationPipeline] and the [TextInserter], one dictation
 * at a time. [start], [stop] and [cancel] are what the orb and tray call; the hotkey drives them through
 * [HotkeyGesture].
 */
class DictationController(
    private val scope: CoroutineScope,
    private val hotkey: HotkeySource,
    private val pipeline: DictationPipeline,
    private val inserter: TextInserter,
    private val clipboard: Clipboard,
    private val notifier: Notifier,
    private val focus: FocusTracker,
    private val hasKey: () -> Boolean,
    private val micAvailable: () -> Boolean,
    private val request: suspend (packageName: String) -> DictationRequest,
    private val clock: () -> Long = System::currentTimeMillis,
    private val holdMs: Long = 500,
    private val releaseWaitMs: Long = 3000,
    private val onOpenApp: () -> Unit = {},
) {
    private enum class Phase { IDLE, RECORDING, PROCESSING, INSERTING }

    private val _state = MutableStateFlow<DesktopState>(DesktopState.Idle)
    val state: StateFlow<DesktopState> = _state.asStateFlow()

    /** False while setup tests the shortcut: presses are seen but start nothing. */
    @Volatile var hotkeyEnabled = true

    private val lock = Any()
    @Volatile private var phase = Phase.IDLE
    @Volatile private var startJob: Job? = null
    private val gesture = HotkeyGesture(holdMs)
    private val keyHeld = MutableStateFlow(false)

    init {
        scope.launch { hotkey.events.collect(::onHotkey) }
        scope.launch { pipeline.state.collect { onPipeline(it) } }
    }

    fun start() {
        synchronized(lock) {
            if (phase != Phase.IDLE) return
            phase = Phase.RECORDING
        }
        startJob = scope.launch {
            when {
                !hasKey() -> {
                    notifier.notify("Connect OpenRouter", "Umm needs an OpenRouter key before it can dictate.", openAction = true, onOpen = onOpenApp)
                    _state.value = DesktopState.NeedsKey
                    phase = Phase.IDLE
                }
                !micAvailable() -> {
                    notifier.notify(MIC_UNAVAILABLE, "Check that a microphone is connected and not in use.")
                    _state.value = DesktopState.Failed(MIC_UNAVAILABLE)
                    phase = Phase.IDLE
                }
                else -> {
                    val packageName = focus.focusedAppId() ?: "desktop"
                    val dictation = request(packageName)
                    ensureActive()
                    if (!startPipeline(dictation)) {
                        notifier.notify(START_FAILED, "Try the shortcut again.")
                        _state.value = DesktopState.Failed(START_FAILED)
                        phase = Phase.IDLE
                    }
                }
            }
        }
    }

    /**
     * Starts the pipeline once it is free. A recording that was just cancelled is still winding down for up to one
     * audio chunk, and the pipeline silently ignores a start until it ends, so wait for that and check it took.
     */
    private suspend fun startPipeline(dictation: DictationRequest): Boolean {
        repeat(START_ATTEMPTS) {
            withTimeoutOrNull(START_ATTEMPT_MS) { pipeline.state.first { it !is DictationState.Listening } }
            pipeline.start(dictation)
            if (withTimeoutOrNull(START_ATTEMPT_MS) { pipeline.state.first { it is DictationState.Listening } } != null) return true
            currentCoroutineContext().ensureActive()
        }
        return false
    }

    /** Ends the recording and processes it. */
    fun stop() {
        if (phase != Phase.RECORDING) return
        scope.launch {
            startJob?.join()
            pipeline.stop()
        }
    }

    /**
     * Setup's "press the shortcut" check: true when a full press and release arrives within [timeoutMs]. Presses are
     * seen but start no dictation, and the hotkey is back on when this returns or is cancelled.
     */
    suspend fun testHotkey(timeoutMs: Long = 10_000): Boolean {
        hotkeyEnabled = false
        var pressed = false
        try {
            withTimeoutOrNull(timeoutMs) {
                hotkey.events.first { event ->
                    if (event == HotkeyEvent.Down) pressed = true
                    pressed && event == HotkeyEvent.Up
                }
            }
            return pressed
        } finally {
            // Let the controller's own collector see the release before presses count again.
            withContext(NonCancellable) {
                delay(REENABLE_DELAY_MS)
                hotkeyEnabled = true
            }
        }
    }

    /** Discards the recording. */
    fun cancel() {
        synchronized(lock) {
            if (phase != Phase.RECORDING) return
            phase = Phase.IDLE
        }
        startJob?.cancel()
        pipeline.cancel()
        _state.value = DesktopState.Idle
    }

    private fun onHotkey(event: HotkeyEvent) {
        keyHeld.value = event == HotkeyEvent.Down
        // Every event reaches the gesture so it keeps track of the key; busy dictations then ignore its commands.
        val command = gesture.onEvent(event, clock(), recording = phase == Phase.RECORDING)
        if (!hotkeyEnabled) return
        when (command) {
            GestureCommand.START -> {
                start()
                switchToHoldToTalk()
            }
            GestureCommand.STOP -> stop()
            GestureCommand.NONE -> Unit
        }
    }

    /**
     * A key still down after [holdMs] means hold-to-talk: the recording then ends on release, not on a pause, so the
     * silence timeout must not cut in while the user thinks.
     */
    private fun switchToHoldToTalk() {
        scope.launch {
            delay(holdMs)
            startJob?.join()
            if (keyHeld.value && phase == Phase.RECORDING) pipeline.setContinuous(true)
        }
    }

    private suspend fun onPipeline(pipelineState: DictationState) {
        if (phase == Phase.IDLE) return
        when (pipelineState) {
            is DictationState.Listening -> if (phase == Phase.RECORDING) _state.value = DesktopState.Listening(pipelineState.amplitude)
            DictationState.Transcribing, DictationState.Cleaning -> {
                phase = Phase.PROCESSING
                _state.value = DesktopState.Processing
            }
            is DictationState.Done -> insert(pipelineState)
            is DictationState.Failed -> {
                val reason = failureText(pipelineState.reason)
                notifier.notify("Dictation failed", reason, openAction = pipelineState.reason.isKeyProblem(), onOpen = onOpenApp)
                _state.value = DesktopState.Failed(reason)
                finish(pipelineState)
            }
            DictationState.NoSpeech, DictationState.EmptyTranscript -> {
                _state.value = DesktopState.NoSpeech
                finish(pipelineState)
            }
            DictationState.Idle -> Unit
        }
    }

    private suspend fun insert(done: DictationState.Done) {
        phase = Phase.INSERTING
        try {
            // Never type while the hotkey's modifiers are still down: Super+Alt plus a typed key can fire other
            // shortcuts (or this one again). If the key is still held after a fair wait, hand over the text instead.
            val released = withTimeoutOrNull(releaseWaitMs) { keyHeld.first { !it } } != null
            if (!released) {
                copyInstead(done.text, "Release the shortcut first")
                return
            }
            val parts = InsertionPlanner.plan(done.text, inserter.capability)
            try {
                inserter.insert(parts)
                val pasted = parts.any { it is InsertPart.Paste }
                // Where text is pasted (GNOME) it may miss, for example in a terminal; leave it on the clipboard.
                if (pasted) clipboard.setText(done.text)
                if (done.cleanupFailed) notifier.notify("Cleanup failed", "Inserted the raw transcript instead.")
                _state.value = DesktopState.Inserted(pastedOnAsciiDesktop = pasted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                copyInstead(done.text, e.message ?: "Couldn't type the text")
            }
        } finally {
            finish(done)
        }
    }

    private fun copyInstead(text: String, reason: String) {
        clipboard.setText(text)
        notifier.notify("Copied to the clipboard", "$reason. Paste it with Ctrl+V.")
        _state.value = DesktopState.Copied(reason)
    }

    private fun finish(pipelineState: DictationState) {
        pipeline.acknowledge(pipelineState)
        phase = Phase.IDLE
    }

    private fun FailureReason.isKeyProblem() = this == FailureReason.UNAUTHORIZED || this == FailureReason.MISSING_KEY

    private fun failureText(reason: FailureReason) = when (reason) {
        FailureReason.UNAUTHORIZED, FailureReason.MISSING_KEY -> "OpenRouter rejected the key"
        FailureReason.NO_CREDITS -> "Your OpenRouter account is out of credits"
        else -> "Couldn't reach OpenRouter"
    }

    private companion object {
        const val MIC_UNAVAILABLE = "Microphone unavailable"
        const val START_FAILED = "Couldn't start recording"
        const val START_ATTEMPTS = 4
        const val START_ATTEMPT_MS = 300L
        const val REENABLE_DELAY_MS = 150L
    }
}
