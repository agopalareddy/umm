package io.github.agopalareddy.umm.bubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.pipeline.DictationRequest
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.policy.LevelResolver
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.ime.InsertionTarget
import io.github.agopalareddy.umm.settings.MainActivity
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The floating dictation button. Draws [BubbleView] in an accessibility overlay while the setting is on, and
 * shows it by the focused field. To decide that, it reads only whether the focused field is editable or a
 * password field. It reads the field's text and selection only at delivery time, to place the dictated text
 * ([NodeField]), and never stores or logs them.
 * Presses go through [BubbleGesture]; this class carries out its commands on the shared pipeline.
 */
class BubbleService : AccessibilityService() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val pipeline by lazy { graph.pipeline }
    private val delivery by lazy { graph.delivery }
    private var focus = FocusTracker()

    private var settings = UmmSettings()
    private var settingsJob: Job? = null
    private var bubble: BubbleView? = null
    private var pipelineJob: Job? = null

    private var active = false
    private var lastInteraction = 0L

    // The dictation the bubble started. startJob runs while its app category is read, before the pipeline
    // starts; target is attached from the press until its result is delivered or the dictation ends.
    private val latch = PipelineLatch()
    private var startJob: Job? = null
    private var target: BubbleTarget? = null

    /**
     * The origin of the bubble's last start or retry while that dictation runs or waits for a retry. Unlike
     * [target], which goes as soon as delivery is tried or the field changes, it says the bubble must stay on
     * screen to control the dictation.
     */
    private var ownedOrigin: Long? = null

    /** The failure a press last sent to setup or the credits page; the next press on it retries. */
    private var redirectedFor: DictationState.Failed? = null

    /** Silence detection asked for while startJob still runs: a quick tap's release can come first. */
    private var silenceOn = false

    private var dragging = false
    private var dragArea: Area? = null
    private var dragX = 0f
    private var dragY = 0f

    private val recheckVisibility = Runnable { applyVisibility() }
    private val dim = Runnable { applyAlpha() }

    // Never focusable, so the overlay can't take input focus from the field it types into. Laid out in screen
    // coordinates: x and y come from the usable area, which already leaves out system bars and the cutout.
    private val params = LayoutParams(
        0, 0,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _connected.value = true
        if (BubbleSetup.awaitingEnable) {
            BubbleSetup.awaitingEnable = false
            returnToApp()
        }
        settingsJob?.cancel()
        settingsJob = scope.launch { graph.settings.settings.collect(::applySettings) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        when (type) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Unit
            else -> return
        }
        if (!settings.bubbleEnabled || bubble == null) return
        readFocus()
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) noteInteraction()
        applyVisibility()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A drag in progress keeps going but clamps to the new screen's area from its next step.
        dragArea = null
        bubble?.let { layOut(it, newConfig.orientation, attached = true) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _connected.value = false
        settingsJob?.cancel()
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _connected.value = false
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    /** Touches (from the bubble), focus moves and pipeline changes restart the dimming clock. */
    internal fun noteInteraction() {
        lastInteraction = SystemClock.uptimeMillis()
        applyAlpha()
        handler.removeCallbacks(dim)
        handler.postDelayed(dim, BubbleAlpha.DIM_AFTER_MS)
    }

    private fun applySettings(next: UmmSettings) {
        settings = next
        if (!next.bubbleEnabled) {
            removeOverlay()
            return
        }
        val bubble = bubble
        if (bubble == null) {
            addOverlay()
        } else {
            layOut(bubble, resources.configuration.orientation, attached = true)
            applyVisibility()
        }
    }

    // --- Overlay ---

    private fun addOverlay() {
        if (bubble != null) return
        val bubble = BubbleView(this, ::pipelineView, ::onCommand, ::noteInteraction)
        params.flags = params.flags or LayoutParams.FLAG_NOT_TOUCHABLE
        layOut(bubble, resources.configuration.orientation, attached = false)
        // The system can refuse the window (for example while the service is being unbound); skip it, don't crash.
        if (runCatching { windowManager.addView(bubble.view, params) }.isFailure) {
            bubble.destroy()
            return
        }
        this.bubble = bubble
        pipelineJob = scope.launch {
            pipeline.state.collect { state ->
                bubble.state = state
                active = BubbleAlpha.isActive(state)
                val view = latch.view(state, SystemClock.uptimeMillis()) // the expectation ends once the pipeline moves
                onPipelineState(bubble, state)
                ownedOrigin = BubbleOwnership.keep(startJob?.isActive == true, ownedOrigin, view, state)
                noteInteraction() // also undims, so the orb's frame clock runs to show the new state
                applyVisibility() // a finished dictation no longer holds the bubble on screen
            }
        }
        readFocus()
        applyVisibility()
    }

    private fun removeOverlay() {
        val bubble = bubble ?: return
        cancelSnapAnimation()
        // Nothing may keep recording without a bubble to stop it. A dictation already processing is copied to
        // the clipboard instead, since its target goes too.
        if (startJob?.isActive == true) {
            abortStart()
        } else if (target != null && pipeline.state.value is DictationState.Listening) {
            pipeline.cancel()
        }
        releaseTarget()
        ownedOrigin = null
        latch.clear()
        dragging = false
        dragArea = null
        this.bubble = null
        pipelineJob?.cancel()
        pipelineJob = null
        handler.removeCallbacks(recheckVisibility)
        handler.removeCallbacks(dim)
        runCatching { windowManager.removeViewImmediate(bubble.view) }
        bubble.destroy()
        // A later overlay starts from the next focus read, not from what this one last saw.
        focus = FocusTracker()
    }

    /**
     * Sizes and docks the window. Window and docking box are boxPx, big enough for the orb's rings; the disc is
     * drawn sizePx wide in its middle.
     */
    private fun layOut(bubble: BubbleView, orientation: Int, attached: Boolean) {
        if (dragging) return // the finger owns the position until the drag ends and docks it
        val density = resources.displayMetrics.density
        val box = BubblePosition.boxPx(settings.bubbleSize, density)
        bubble.orbSizePx = BubblePosition.sizePx(settings.bubbleSize, density)
        val fraction = BubbleControl.yFraction(settings, orientation == Configuration.ORIENTATION_LANDSCAPE)
        val (x, y) = BubblePosition.place(usableArea(), box, settings.bubbleEdge, fraction)
        val snappingTo = snapTarget.takeIf { snapAnimator?.isRunning == true }
        if (params.width == box && BubbleControl.snapCovers(snappingTo, x to y)) return
        cancelSnapAnimation()
        if (params.width == box && params.height == box && params.x == x && params.y == y) return
        params.width = box
        params.height = box
        params.x = x
        params.y = y
        if (attached) updateWindow(bubble)
    }

    /** The screen minus system bars and the display cutout, in the screen coordinates the window uses. */
    private fun usableArea(): Area {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val bounds = metrics.bounds
            return Area(bounds.left + insets.left, bounds.top + insets.top, bounds.right - insets.right, bounds.bottom - insets.bottom)
        }
        // Android 10 has no window metrics. The app-sized display already leaves out the navigation bar.
        val display = resources.displayMetrics
        return Area(0, statusBarHeight(), display.widthPixels, display.heightPixels)
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun updateWindow(bubble: BubbleView) {
        runCatching { windowManager.updateViewLayout(bubble.view, params) }
    }

    // --- Visibility and opacity ---

    private fun focusedNode(): AccessibilityNodeInfo? =
        runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()

    private fun readFocus() {
        val node = focusedNode()
        focus.update(editable = node?.isEditable == true, password = node?.isPassword == true, now = SystemClock.uptimeMillis())
    }

    private fun applyVisibility() {
        val bubble = bubble ?: return
        handler.removeCallbacks(recheckVisibility)
        val now = SystemClock.uptimeMillis()
        val show = focus.shouldShow(settings.bubbleVisibility, now, ownsDictation())
        focus.recheckIn(now)?.let { handler.postDelayed(recheckVisibility, it) }
        if (show == bubble.shown) return
        bubble.shown = show
        // A hidden bubble's window must not swallow touches meant for the app underneath.
        params.flags = if (show) {
            params.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or LayoutParams.FLAG_NOT_TOUCHABLE
        }
        updateWindow(bubble)
        if (show) noteInteraction()
    }

    /** While true the bubble stays on screen whatever has focus, so its dictation can be stopped or retried. */
    private fun ownsDictation(): Boolean {
        val state = pipeline.state.value
        return BubbleOwnership.owns(startJob?.isActive == true, ownedOrigin, latch.view(state, SystemClock.uptimeMillis()), state)
    }

    private fun applyAlpha() {
        val bubble = bubble ?: return
        val alpha = BubbleAlpha.of(active, SystemClock.uptimeMillis() - lastInteraction)
        bubble.view.alpha = alpha
        bubble.dimmed = alpha == BubbleAlpha.DIMMED
    }

    // --- Gestures ---

    /** What the gesture machine sees: a start or retry just asked for counts before the pipeline shows it. */
    private fun pipelineView(): PipelineView =
        if (startJob?.isActive == true) {
            PipelineView.RECORDING
        } else {
            latch.view(pipeline.state.value, SystemClock.uptimeMillis())
        }

    private fun onCommand(command: BubbleCommand) {
        val bubble = bubble ?: return
        if (!BubbleControl.applies(command, pipelineView())) return
        val starting = startJob?.isActive == true
        when (command) {
            BubbleCommand.Start -> start(bubble)
            BubbleCommand.Stop ->
                if (starting) {
                    abortStart() // released before recording began: there is nothing to process
                } else {
                    pipeline.stop()
                    bubble.haptic(STOP_HAPTIC)
                }
            is BubbleCommand.SetSilenceDetection -> {
                bubble.doubleTap = !command.on
                if (starting) silenceOn = command.on else pipeline.setContinuous(!command.on)
            }
            BubbleCommand.Cancel -> cancel(bubble)
            BubbleCommand.Retry -> retry(bubble)
            BubbleCommand.Reject -> {
                bubble.shake()
                noteInteraction()
            }
            is BubbleCommand.DragBy -> dragBy(bubble, command.dx, command.dy)
            is BubbleCommand.DragEnd -> dragEnd(bubble, command.vx, command.vy)
        }
        applyVisibility() // a start, retry or cancel changes whether the bubble owns a dictation
    }

    /**
     * Starts recording into the field focused right now, with silence detection off; the level and script come
     * from the app's category, as on the keyboard.
     */
    private fun start(bubble: BubbleView) {
        if (!setupDone()) {
            openSetup()
            return
        }
        val node = focusedNode()
        if (node == null || !BubbleVisibility.canRecord(node.isEditable, node.isPassword)) {
            refuse(bubble)
            return
        }
        val packageName = node.packageName?.toString().orEmpty()
        val origin = attachTarget(node)
        ownedOrigin = origin
        val settings = settings
        silenceOn = false
        bubble.doubleTap = false
        startJob = scope.launch {
            val config = try {
                graph.categories.configFor(packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            ensureActive() // aborted during a read that didn't notice: abortStart already cleaned up
            // The category read failed, or the bubble's own target was released meanwhile.
            if (config == null || !delivery.isCurrent(origin)) {
                releaseTarget()
                ownedOrigin = null
                bubble.doubleTap = null
                startJob = null // over: the visibility check below must not count it as starting
                applyVisibility()
                return@launch
            }
            // Only now is the recording really starting: a start aborted during the read (a drag, a stop, a
            // failed read) must not buzz.
            bubble.haptic(HapticFeedbackConstants.CONTEXT_CLICK)
            val style = LevelResolver.resolve(null, config, settings.defaultLevel)
            pipeline.reset()
            pipeline.start(
                DictationRequest(
                    packageName, style.level, style.script, LanguageChoice.decode(settings.defaultLanguage),
                    settings.silenceTimeoutSec, origin, continuous = !silenceOn,
                ),
            )
            latch.expect(PipelineView.RECORDING, SystemClock.uptimeMillis())
        }
    }

    /** A press turned into a drag: the recording it started is discarded. */
    private fun cancel(bubble: BubbleView) {
        if (startJob?.isActive == true) {
            abortStart()
            return
        }
        // Only the bubble's own recording; a refused Start left nothing to cancel.
        if (target != null) pipeline.cancel()
        releaseTarget()
        ownedOrigin = null
        latch.clear()
        bubble.doubleTap = null
    }

    private fun abortStart() {
        startJob?.cancel()
        startJob = null
        releaseTarget()
        ownedOrigin = null
        latch.clear()
        bubble?.doubleTap = null
    }

    /**
     * Like the keyboard's Retry: processes the failed recording again, into the field focused now. A key or
     * credit failure first sends the user to fix it, as the keyboard's buttons do.
     */
    private fun retry(bubble: BubbleView) {
        val failed = pipeline.state.value as? DictationState.Failed ?: return
        if (!setupDone()) {
            openSetup()
            return
        }
        when (BubbleControl.failedPress(failed.reason, redirected = redirectedFor == failed)) {
            FailedPress.SETUP -> {
                redirectedFor = failed
                openSetup()
                return
            }
            FailedPress.CREDITS -> {
                redirectedFor = failed
                openCredits()
                return
            }
            FailedPress.RETRY -> Unit
        }
        val node = focusedNode()
        if (node == null || !BubbleVisibility.canRecord(node.isEditable, node.isPassword)) {
            refuse(bubble)
            return
        }
        val origin = attachTarget(node)
        ownedOrigin = origin
        pipeline.reset()
        pipeline.retry(failed.historyId, origin)
        latch.expect(PipelineView.BUSY, SystemClock.uptimeMillis())
    }

    /** Nowhere to put text: no editable field has focus, it is a password field, or focus just left. */
    private fun refuse(bubble: BubbleView) {
        bubble.shake()
        noteInteraction()
        runCatching { Toast.makeText(this, "Tap a text field first", Toast.LENGTH_SHORT).show() }
    }

    private fun onPipelineState(bubble: BubbleView, state: DictationState) {
        if (state !is DictationState.Listening && state != DictationState.Idle) bubble.doubleTap = null
        // Nothing will be delivered for these. A Done releases its target itself once the router has tried it.
        if (startJob?.isActive != true &&
            (state is DictationState.Failed || state == DictationState.NoSpeech || state == DictationState.EmptyTranscript)
        ) {
            releaseTarget()
        }
    }

    private var snapAnimator: ValueAnimator? = null

    /** Where [snapAnimator] is taking the window. */
    private var snapTarget: Pair<Int, Int>? = null

    private fun cancelSnapAnimation() {
        snapAnimator?.cancel()
        snapAnimator = null
        snapTarget = null
    }

    private fun dragBy(bubble: BubbleView, dx: Float, dy: Float) {
        cancelSnapAnimation()
        val area = dragArea ?: usableArea().also { dragArea = it }
        if (!dragging) {
            dragging = true
            dragX = params.x.toFloat()
            dragY = params.y.toFloat()
        }
        val (x, y) = BubbleControl.clamp(area, boxPx(), dragX + dx, dragY + dy)
        dragX = x
        dragY = y
        params.x = x.roundToInt()
        params.y = y.roundToInt()
        updateWindow(bubble)
        noteInteraction()
    }

    /** Docks on the flicked or nearer edge with momentum, and smoothly animates into place. */
    private fun dragEnd(bubble: BubbleView, vx: Float = 0f, vy: Float = 0f) {
        dragging = false
        dragArea = null
        val area = usableArea()
        val box = boxPx()
        val snap = BubblePosition.snap(
            area, box,
            params.x + box / 2f, params.y + box / 2f,
            vx, vy,
            flingThresholdPx = BubblePosition.flingThresholdPx(resources.displayMetrics.density),
            currentEdge = settings.bubbleEdge,
        )
        val (targetX, targetY) = BubblePosition.place(area, box, snap.edge, snap.yFraction)
        animateTo(bubble, targetX, targetY)
        noteInteraction()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        settings = BubbleControl.docked(settings, snap, landscape)
        // A failed write (DataStore IOException) only loses the remembered dock; it must not crash the service.
        scope.launch { runCatching { graph.settings.update { BubbleControl.docked(it, snap, landscape) } } }
    }

    private fun animateTo(bubble: BubbleView, targetX: Int, targetY: Int) {
        cancelSnapAnimation()
        val startX = params.x
        val startY = params.y
        if (startX == targetX && startY == targetY) return

        snapTarget = targetX to targetY
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_ANIMATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                params.x = (startX + f * (targetX - startX)).roundToInt()
                params.y = (startY + f * (targetY - startY)).roundToInt()
                updateWindow(bubble)
            }
            start()
        }
    }

    /** The window and docking box, the same one [layOut] gives the window. */
    private fun boxPx(): Int = BubblePosition.boxPx(settings.bubbleSize, resources.displayMetrics.density)

    // --- Delivery ---

    /** Registers a fresh target for [node] under a new origin, replacing the bubble's previous one. */
    private fun attachTarget(node: AccessibilityNodeInfo): Long {
        releaseTarget()
        val origin = delivery.newOrigin()
        val clipboard = getSystemService(ClipboardManager::class.java)
        // The live active window, not one tracked from events: the keyboard's window is never the active one,
        // while switching apps changes it.
        val field = NodeField(node, clipboard, windowIsCurrent = {
            runCatching { rootInActiveWindow?.windowId }.getOrNull() == node.windowId
        })
        val target = BubbleTarget(origin, field)
        this.target = target
        delivery.attach(target)
        return origin
    }

    private fun releaseTarget() {
        target?.let(delivery::detach)
        target = null
    }

    /** Detaches once the router has tried it, so it never outlives its dictation, inserted or copied. */
    private inner class BubbleTarget(override val origin: Long, field: FocusedField) : InsertionTarget {
        private val insertion = AccessibilityTarget(origin, field, inserted = ::release)

        override fun commit(text: String): Boolean = try {
            insertion.commit(text)
        } finally {
            release()
        }

        override fun onInserted() = insertion.onInserted()

        private fun release() {
            delivery.detach(this)
            if (target === this) target = null
        }
    }

    // --- Setup ---

    private fun setupDone(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            graph.apiKeyStore.get() != null

    /** Like the keyboard's "Finish setup": the key or the microphone permission is missing. */
    private fun openSetup() {
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Like the keyboard's "Add credits on OpenRouter". */
    private fun openCredits() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CREDITS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Brings Umm back to the Bubble page after the user turned the service on from it. */
    private fun returnToApp() {
        // A bound accessibility service is allowed to start activities from the background.
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    .putExtra(BubbleSetup.EXTRA_OPEN_BUBBLE_PAGE, true),
            )
        }
    }

    companion object {
        private val STOP_HAPTIC =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK

        private const val SNAP_ANIMATION_MS = 250L

        // The page the keyboard's "Add credits on OpenRouter" button opens.
        private const val CREDITS_URL = "https://openrouter.ai/settings/credits"

        private val _connected = MutableStateFlow(false)

        /** True while the system has the service bound, from onServiceConnected until it is unbound. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
