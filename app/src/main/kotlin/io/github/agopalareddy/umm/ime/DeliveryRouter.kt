package io.github.agopalareddy.umm.ime

import io.github.agopalareddy.umm.core.pipeline.DictationState
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
 */
class DeliveryRouter(private val toClipboard: (String) -> Unit) {
    private val origins = AtomicLong()
    @Volatile private var target: InsertionTarget? = null

    fun newOrigin(): Long = origins.incrementAndGet()

    fun attach(target: InsertionTarget) {
        this.target = target
    }

    fun detach(target: InsertionTarget) {
        if (this.target === target) this.target = null
    }

    fun isCurrent(origin: Long): Boolean = target?.origin == origin

    fun deliver(done: DictationState.Done) {
        val current = target
        if (current != null && current.origin == done.origin && current.commit(done.text)) {
            current.onInserted()
        } else {
            toClipboard(done.text)
        }
    }
}
