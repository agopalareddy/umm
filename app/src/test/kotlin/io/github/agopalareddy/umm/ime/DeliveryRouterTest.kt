package io.github.agopalareddy.umm.ime

import io.github.agopalareddy.umm.core.pipeline.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun keyboardAndBubbleTargetsCoexist() {
        val keyboard = FakeTarget(origin = 1)
        val bubble = FakeTarget(origin = 2)
        router.attach(keyboard)
        router.attach(bubble)
        assertTrue(router.isCurrent(1))
        assertTrue(router.isCurrent(2))
        router.detach(bubble)
        // The bubble finishing leaves the keyboard's field in place, so its mic still starts.
        assertTrue(router.isCurrent(1))
        assertFalse(router.isCurrent(2))
    }

    @Test fun deliveringToOneOriginNeverTouchesAnother() {
        val keyboard = FakeTarget(origin = 1)
        val bubble = FakeTarget(origin = 2)
        router.attach(keyboard)
        router.attach(bubble)
        router.deliver(done("Bubble.", origin = 2))
        assertEquals(listOf("Bubble."), bubble.committed)
        assertEquals(1, bubble.inserted)
        assertTrue(keyboard.committed.isEmpty())
        assertEquals(0, keyboard.inserted)
        router.deliver(done("Keys.", origin = 1))
        assertEquals(listOf("Keys."), keyboard.committed)
        assertEquals(listOf("Bubble."), bubble.committed)
        assertTrue(clipboard.isEmpty())
    }

    @Test fun keyboardRestartMidDictationKeepsTheBubbleTarget() {
        val bubble = FakeTarget(origin = 2)
        router.attach(FakeTarget(origin = 1))
        router.attach(bubble)
        // The keyboard's input restarts: a new field target replaces its old one.
        router.detach(FakeTarget(origin = 1))
        router.attach(FakeTarget(origin = 3))
        router.deliver(done("Bubble.", origin = 2))
        assertEquals(listOf("Bubble."), bubble.committed)
        assertTrue(clipboard.isEmpty())
    }

    @Test fun detachingAStaleInstanceKeepsTheNewerOne() {
        val stale = FakeTarget(origin = 4)
        val newer = FakeTarget(origin = 4)
        router.attach(stale)
        router.attach(newer)
        router.detach(stale)
        assertTrue(router.isCurrent(4))
        router.deliver(done("Hi.", origin = 4))
        assertEquals(listOf("Hi."), newer.committed)
        assertTrue(stale.committed.isEmpty())
    }

    @Test fun missingOriginFallsBackToClipboardWhileOthersAreAttached() {
        val keyboard = FakeTarget(origin = 1)
        router.attach(keyboard)
        router.deliver(done("Hi.", origin = 9))
        assertEquals(listOf("Hi."), clipboard)
        assertTrue(keyboard.committed.isEmpty())
    }

    @Test fun refusedCommitFallsBackToClipboardAndKeepsOthers() {
        val bubble = FakeTarget(origin = 2, accepts = false)
        val keyboard = FakeTarget(origin = 1)
        router.attach(keyboard)
        router.attach(bubble)
        router.deliver(done("Hi.", origin = 2))
        assertEquals(listOf("Hi."), clipboard)
        assertEquals(0, bubble.inserted)
        assertTrue(keyboard.committed.isEmpty())
        assertTrue(router.isCurrent(1))
    }

    @Test fun newSessionsAreUnique() {
        assertTrue(router.newOrigin() != router.newOrigin())
    }
}
