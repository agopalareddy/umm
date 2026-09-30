package io.github.agopalareddy.umm.bubble

import android.animation.ObjectAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.ime.moveIfAlive
import io.github.agopalareddy.umm.ui.UmmTheme
import io.github.agopalareddy.umm.ui.VoiceOrb

/**
 * The bubble's content: a [VoiceOrb] whose disc is drawn [orbSizePx] wide in the middle of the window, which is
 * [BubblePosition.boxPx] so the orb's rings fit around the disc. It owns the lifecycle and saved state a
 * [ComposeView] needs outside an activity, alive while the overlay is.
 *
 * Every touch on the window goes to [BubbleGesture]; the orb only draws. [pipelineView] is asked on each press,
 * [onCommand] gets what the machine decides, and [onTouched] is called on every press and release.
 */
internal class BubbleView(
    context: Context,
    private val pipelineView: () -> PipelineView,
    private val onCommand: (BubbleCommand) -> Unit,
    private val onTouched: () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    var state: DictationState by mutableStateOf(DictationState.Idle)
    var orbSizePx: Int by mutableIntStateOf(0)

    /** For a recording the bubble started: true after a double-tap, false for a held or single tap; else null. */
    var doubleTap: Boolean? by mutableStateOf(null)

    private val gesture = BubbleGesture(slopPx = DRAG_SLOP_DP * context.resources.displayMetrics.density)
    private val pointer = PrimaryPointer()
    private var shaking: ObjectAnimator? = null

    private val compose: ComposeView

    /** The window's root view. */
    val view: View

    /** Starts hidden. */
    var shown: Boolean = false
        set(value) {
            field = value
            view.visibility = if (value) View.VISIBLE else View.GONE
            syncLifecycle()
        }

    /** Set while the bubble sits dimmed and untouched. */
    var dimmed: Boolean = false
        set(value) {
            field = value
            syncLifecycle()
        }

    // A stopped lifecycle pauses Compose's frame clock, so the orb's endless animations only run while it is
    // shown and not dimmed; the next interaction undims it and they resume.
    private fun syncLifecycle() {
        lifecycleRegistry.moveIfAlive(if (shown && !dimmed) Lifecycle.Event.ON_RESUME else Lifecycle.Event.ON_STOP)
    }

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.moveIfAlive(Lifecycle.Event.ON_CREATE)
        compose = ComposeView(context).apply {
            setContent {
                UmmTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // VoiceOrb has a fixed layout size, so scale its drawing until the disc is orbSizePx wide.
                        VoiceOrb(
                            state,
                            onClick = {},
                            onDoubleClick = {},
                            modifier = Modifier.requiredSize(ORB_SLOT).graphicsLayer {
                                val scale = orbSizePx / ORB_DISC.toPx()
                                scaleX = scale
                                scaleY = scale
                            },
                            continuousRing = BubbleControl.continuousRing(state, doubleTap),
                            clickable = false,
                        )
                    }
                }
            }
        }
        view = TouchFrame(context, ::onTouch).apply {
            visibility = View.GONE
            setViewTreeLifecycleOwner(this@BubbleView)
            setViewTreeSavedStateRegistryOwner(this@BubbleView)
            addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // Raw screen coordinates, so moving the window during a drag doesn't shift the finger's position under it.
    private fun onTouch(event: MotionEvent) {
        for (step in pointer.onEvent(event.actionMasked, event.getPointerId(event.actionIndex))) {
            val commands = when (step) {
                PrimaryPointer.Step.DOWN -> {
                    onTouched()
                    val i = event.actionIndex
                    gesture.onDown(event.eventTime, event.getRawX(i), event.getRawY(i), pipelineView())
                }
                PrimaryPointer.Step.MOVE -> {
                    val i = pointer.id?.let(event::findPointerIndex) ?: -1
                    if (i < 0) emptyList() else gesture.onMove(event.eventTime, event.getRawX(i), event.getRawY(i))
                }
                PrimaryPointer.Step.UP -> {
                    onTouched()
                    gesture.onUp(event.eventTime)
                }
            }
            commands.forEach(onCommand)
        }
    }

    /** A short sideways shake: this press did nothing. */
    fun shake() {
        val d = SHAKE_DP * view.resources.displayMetrics.density
        shaking?.cancel()
        shaking = ObjectAnimator.ofFloat(compose, View.TRANSLATION_X, 0f, d, -d, d * 0.6f, -d * 0.6f, 0f).apply {
            duration = SHAKE_MS
            start()
        }
    }

    fun haptic(feedback: Int) {
        runCatching { view.performHapticFeedback(feedback) }
    }

    fun destroy() {
        shaking?.cancel()
        compose.disposeComposition()
        lifecycleRegistry.moveIfAlive(Lifecycle.Event.ON_DESTROY)
    }
}

/** Takes every touch before the Compose content sees it. */
private class TouchFrame(context: Context, private val onTouch: (MotionEvent) -> Unit) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onTouch(event)
        return true
    }
}

private const val DRAG_SLOP_DP = 12f
private const val SHAKE_DP = 4f
private const val SHAKE_MS = 300L

// VoiceOrb's own slot and disc sizes (private in VoiceOrb.kt).
private val ORB_SLOT = 92.dp
private val ORB_DISC = 88.dp
