package io.github.agopalareddy.umm.bubble

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.settings.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The floating dictation button. Draws [BubbleView] in an accessibility overlay while the setting is on, and
 * shows it by the focused field. It reads only whether that field is editable or a password field, never its text.
 */
class BubbleService : AccessibilityService() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val focus = FocusTracker()

    private var settings = UmmSettings()
    private var settingsJob: Job? = null
    private var bubble: BubbleView? = null
    private var pipelineJob: Job? = null
    /** Set by onInterrupt, which removes the bubble; the next event brings it back. */
    private var interrupted = false

    private var active = false
    private var lastInteraction = 0L

    private val recheckVisibility = Runnable { applyVisibility() }
    private val dim = Runnable { applyAlpha() }

    /** The window of the app in front, from typeWindowStateChanged; never the bubble's own overlay. */
    @Volatile var currentWindowId: Int = NO_WINDOW
        private set

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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> noteWindow(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> Unit
            else -> return
        }
        if (!settings.bubbleEnabled) return
        if (interrupted) {
            interrupted = false
            addOverlay()
        }
        if (bubble == null) return
        readFocus()
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) noteInteraction()
        applyVisibility()
    }

    override fun onInterrupt() {
        if (bubble != null) interrupted = true
        removeOverlay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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
            interrupted = false
            removeOverlay()
            return
        }
        interrupted = false
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
        val bubble = BubbleView(this)
        params.flags = params.flags or LayoutParams.FLAG_NOT_TOUCHABLE
        layOut(bubble, resources.configuration.orientation, attached = false)
        // The system can refuse the window (for example while the service is being unbound); skip it, don't crash.
        if (runCatching { windowManager.addView(bubble.view, params) }.isFailure) {
            bubble.destroy()
            return
        }
        this.bubble = bubble
        if (currentWindowId == NO_WINDOW) currentWindowId = activeWindowId() ?: NO_WINDOW
        pipelineJob = scope.launch {
            graph.pipeline.state.collect { state ->
                val changed = state::class != bubble.state::class
                bubble.state = state
                active = BubbleAlpha.isActive(state)
                if (changed) noteInteraction() else applyAlpha()
            }
        }
        readFocus()
        applyVisibility()
    }

    private fun removeOverlay() {
        val bubble = bubble ?: return
        this.bubble = null
        pipelineJob?.cancel()
        pipelineJob = null
        handler.removeCallbacks(recheckVisibility)
        handler.removeCallbacks(dim)
        runCatching { windowManager.removeViewImmediate(bubble.view) }
        bubble.destroy()
    }

    /** Sizes and docks the window. The box handed to place() is the touch box; the orb is drawn inside it. */
    private fun layOut(bubble: BubbleView, orientation: Int, attached: Boolean) {
        val density = resources.displayMetrics.density
        val box = BubblePosition.touchPx(settings.bubbleSize, density)
        bubble.orbSizePx = BubblePosition.sizePx(settings.bubbleSize, density)
        val fraction =
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) settings.bubbleYLandscape else settings.bubbleYPortrait
        val (x, y) = BubblePosition.place(usableArea(), box, settings.bubbleEdge, fraction)
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

    private fun readFocus() {
        val node = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        focus.update(editable = node?.isEditable == true, password = node?.isPassword == true, now = SystemClock.uptimeMillis())
    }

    private fun applyVisibility() {
        val bubble = bubble ?: return
        handler.removeCallbacks(recheckVisibility)
        val now = SystemClock.uptimeMillis()
        val show = focus.shouldShow(settings.bubbleVisibility, now)
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

    private fun applyAlpha() {
        val bubble = bubble ?: return
        bubble.view.alpha = BubbleAlpha.of(active, SystemClock.uptimeMillis() - lastInteraction)
    }

    // --- Windows and setup ---

    private fun noteWindow(event: AccessibilityEvent) {
        // The overlay is never focusable, so it is never the active window; any other Umm window (the Try-it
        // field) is recorded like any app's.
        if (event.packageName?.toString() == packageName && event.windowId != activeWindowId()) return
        currentWindowId = event.windowId
    }

    private fun activeWindowId(): Int? = runCatching { rootInActiveWindow?.windowId }.getOrNull()

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
        private const val NO_WINDOW = -1

        private val _connected = MutableStateFlow(false)

        /** True while the system has the service bound, from onServiceConnected until it is unbound. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
