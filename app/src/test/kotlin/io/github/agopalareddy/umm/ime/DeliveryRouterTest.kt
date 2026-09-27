package io.github.agopalareddy.umm.ime

import io.github.agopalareddy.umm.core.pipeline.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryRouterTest {
    private val clipboard = mutableListOf<String>()
    private val router = DeliveryRouter { clipboard += it }

    private class FakeTarget(override val origin: Long, private val accepts: Boolean = true) : InsertionTarget {
        val committed = mutableListOf<String>()
        var inserted = 0
        override fun commit(text: String): Boolean {
            if (accepts) committed += text
            return accepts
        }
        override fun onInserted() { inserted++ }
    }

    private fun done(text: String, origin: Long) = DictationState.Done(1, text, cleanupFailed = false, origin = origin)

    @Test fun matchingTargetGetsTheText() {
        val target = FakeTarget(origin = 5)
        router.attach(target)
        router.deliver(done("Hi.", origin = 5))
        assertEquals(listOf("Hi."), target.committed)
        assertEquals(1, target.inserted)
        assertTrue(clipboard.isEmpty())
    }

    @Test fun otherFieldNeverReceivesText() {
        val target = FakeTarget(origin = 6)
        router.attach(target)
        router.deliver(done("Hi.", origin = 5))
        assertTrue(target.committed.isEmpty())
        assertEquals(listOf("Hi."), clipboard)
    }

    @Test fun noKeyboardMeansClipboard() {
        router.deliver(done("Hi.", origin = 5))
        assertEquals(listOf("Hi."), clipboard)
    }

    @Test fun detachedTargetMeansClipboard() {
        val target = FakeTarget(origin = 5)
        router.attach(target)
        router.detach(target)
        router.deliver(done("Hi.", origin = 5))
        assertEquals(listOf("Hi."), clipboard)
    }

    @Test fun failedCommitFallsBackToClipboard() {
        val target = FakeTarget(origin = 5, accepts = false)
        router.attach(target)
        router.deliver(done("Hi.", origin = 5))
        assertEquals(listOf("Hi."), clipboard)
        assertEquals(0, target.inserted)
    }

    @Test fun newSessionsAreUnique() {
        assertTrue(router.newOrigin() != router.newOrigin())
    }
}
