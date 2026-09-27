package io.github.agopalareddy.umm.ime

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry

/** Moves the lifecycle unless it is already destroyed; InputMethodService calls view callbacks during onDestroy. */
fun LifecycleRegistry.moveIfAlive(event: Lifecycle.Event) {
    if (currentState != Lifecycle.State.DESTROYED) handleLifecycleEvent(event)
}
