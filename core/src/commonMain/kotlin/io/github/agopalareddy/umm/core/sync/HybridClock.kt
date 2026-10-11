package io.github.agopalareddy.umm.core.sync

class HybridClock(private val wall: () -> Long) {
    private var last = 0L

    @Synchronized
    fun tick(): Long {
        val next = maxOf(wall(), last + 1)
        last = next
        return next
    }

    @Synchronized
    fun observe(remote: Long) {
        last = maxOf(last, remote)
    }
}
