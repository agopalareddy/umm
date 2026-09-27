package io.github.agopalareddy.umm.ime

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SafeLifecycleTest {
    private lateinit var registry: LifecycleRegistry
    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun setUp() {
        registry = LifecycleRegistry.createUnsafe(owner)
    }

    @Test fun eventsAfterDestroyAreIgnored() {
        registry.moveIfAlive(Lifecycle.Event.ON_CREATE)
        registry.moveIfAlive(Lifecycle.Event.ON_DESTROY)
        // InputMethodService.onDestroy() calls onFinishInputView() after we have destroyed the lifecycle.
        registry.moveIfAlive(Lifecycle.Event.ON_PAUSE)
        assertEquals(Lifecycle.State.DESTROYED, registry.currentState)
    }
}
