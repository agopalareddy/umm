package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.core.data.BubbleShowMode

/**
 * What the bubble knows about the focused input field, fed from accessibility events. Only the latest node
 * counts: a password flag never outlives the field it came from.
 */
class FocusTracker {
    var editable = false
        private set
    var password = false
        private set

    /** When focus last left a field the bubble was shown for; null while one has focus or none ever did. */
    private var lostAt: Long? = null

    /** [editable] and [password] describe the node holding input focus now; both false when none does. */
    fun update(editable: Boolean, password: Boolean, now: Long) {
        val wasShownFor = this.editable && !this.password
        this.editable = editable
        this.password = editable && password
        lostAt = when {
            editable -> null
            wasShownFor -> now
            // Leaving a password field (or nothing) starts no grace period: the bubble was hidden there.
            else -> lostAt
        }
    }

    fun shouldShow(mode: BubbleShowMode, now: Long, ownsDictation: Boolean = false): Boolean =
        BubbleVisibility.shouldShow(mode, editable, password, lostAt?.let { now - it }, ownsDictation)

    /** Milliseconds until the hide grace period ends, when [shouldShow] may change with no new event. */
    fun recheckIn(now: Long): Long? =
        lostAt?.let { it + BubbleVisibility.HIDE_DELAY_MS - now }?.takeIf { it > 0 }
}
