package io.github.agopalareddy.umm.ime

import io.github.agopalareddy.umm.core.pipeline.DictationState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The text field a dictation started in, as long as the keyboard is still attached to it. */
interface InsertionTarget {
    val origin: Long

    /** Returns false if the field could not take the text. */
    fun commit(text: String): Boolean

    fun onInserted()
}

/**
 * Sends each finished dictation to the field it started in, or to the clipboard when that field is gone.
 * Lives for the whole process, so results still arrive after the keyboard has been closed.
 *
 * Targets are kept per origin, so the keyboard's field and the bubble's never displace each other. Each front
 * end detaches its own targets when they end; a target left attached is only ever reached by its own origin.
 */
class DeliveryRouter(private val toClipboard: (String) -> Unit) {
    private val origins = AtomicLong()
    private val targets = ConcurrentHashMap<Long, InsertionTarget>()

    fun newOrigin(): Long = origins.incrementAndGet()

    fun attach(target: InsertionTarget) {
        targets[target.origin] = target
    }

    /** Removes [target] only if it is still the one attached for its origin. */
    fun detach(target: InsertionTarget) {
        targets.remove(target.origin, target)
    }

    fun isCurrent(origin: Long): Boolean = targets.containsKey(origin)

    fun deliver(done: DictationState.Done) {
        val current = targets[done.origin]
        if (current != null && current.commit(done.text)) {
            current.onInserted()
        } else {
            toClipboard(done.text)
        }
    }
}
