package io.github.agopalareddy.umm.bubble

import android.content.Context
import android.view.View
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
 */
internal class BubbleView(context: Context) : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    var state: DictationState by mutableStateOf(DictationState.Idle)
    var orbSizePx: Int by mutableIntStateOf(0)

    val view: ComposeView

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
        view = ComposeView(context).apply {
            visibility = View.GONE
            setViewTreeLifecycleOwner(this@BubbleView)
            setViewTreeSavedStateRegistryOwner(this@BubbleView)
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
                        )
                    }
                }
            }
        }
    }

    fun destroy() {
        view.disposeComposition()
        lifecycleRegistry.moveIfAlive(Lifecycle.Event.ON_DESTROY)
    }
}

// VoiceOrb's own slot and disc sizes (private in VoiceOrb.kt).
private val ORB_SLOT = 92.dp
private val ORB_DISC = 88.dp
