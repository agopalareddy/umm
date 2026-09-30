package io.github.agopalareddy.umm.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTargetTest {
    private class FakeField(
        override var text: CharSequence? = "",
        override var showingHint: Boolean = false,
        override var selectionStart: Int = -1,
        override var selectionEnd: Int = -1,
        override val multiLine: Boolean = false,
        var live: Boolean = true,
        var setTextWorks: Boolean = true,
        var pasteWorks: Boolean = true,
    ) : FocusedField {
        override val packageName = "com.example.app"
        val calls = mutableListOf<String>()

        override fun refresh(): Boolean {
            calls += "refresh"
            return live
        }

        override fun setText(text: String): Boolean {
            calls += "setText($text)"
            return setTextWorks
        }

        override fun setSelection(position: Int): Boolean {
            calls += "setSelection($position)"
            return false
        }

        override fun paste(text: String): Boolean {
            calls += "paste($text)"
            return pasteWorks
        }
    }

    @Test fun commitSetsMergedTextAndCursor() {
        val field = FakeField(text = "hello", selectionStart = 5, selectionEnd = 5)
        val target = AccessibilityTarget(1, field) {}
        assertTrue(target.commit("there"))
        assertEquals(listOf("refresh", "setText(hello there)", "setSelection(11)"), field.calls)
    }

    @Test fun staleFieldReturnsFalseAndTouchesNothing() {
        val field = FakeField(text = "hello", selectionStart = 5, selectionEnd = 5, live = false)
        val target = AccessibilityTarget(1, field) {}
        assertFalse(target.commit("there"))
        // No setText, setSelection or paste: paste would land in whatever is focused now.
        assertEquals(listOf("refresh"), field.calls)
    }

    @Test fun setTextFailureFallsBackToPaste() {
        val field = FakeField(text = "hello", selectionStart = 5, selectionEnd = 5, setTextWorks = false)
        val target = AccessibilityTarget(1, field) {}
        assertTrue(target.commit("there"))
        assertEquals(listOf("refresh", "setText(hello there)", "paste( there)"), field.calls)
        field.pasteWorks = false
        assertFalse(target.commit("there"))
    }

    @Test fun bothFailReturnsFalse() {
        val field = FakeField(text = "hello", selectionStart = 5, selectionEnd = 5, setTextWorks = false, pasteWorks = false)
        val target = AccessibilityTarget(1, field) {}
        assertFalse(target.commit("there"))
        assertFalse(field.calls.any { it.startsWith("setSelection") })
    }

    @Test fun hintTextIsTreatedAsEmpty() {
        val field = FakeField(text = "Message", showingHint = true, selectionStart = 7, selectionEnd = 7)
        val target = AccessibilityTarget(1, field) {}
        assertTrue(target.commit("hi"))
        assertEquals(listOf("refresh", "setText(hi)", "setSelection(2)"), field.calls)
    }

    @Test fun hintTextFallbackPastesWithoutLeadingSpace() {
        val field = FakeField(text = "Message", showingHint = true, selectionStart = 7, selectionEnd = 7, setTextWorks = false)
        AccessibilityTarget(1, field) {}.commit("hi")
        assertEquals("paste(hi)", field.calls.last())
    }

    @Test fun pasteFallbackUsesTheSameInsertionAsTheMerge() {
        // reversed selection over "5..8" of "hello world"
        val reversed = FakeField(text = "hello world", selectionStart = 8, selectionEnd = 5, setTextWorks = false)
        AccessibilityTarget(1, reversed) {}.commit("big")
        assertEquals(listOf("refresh", "setText(hello big rld)", "paste( big )"), reversed.calls)

        // a cursor inside a surrogate pair moves before the pair, so no leading space after "a "
        val pair = FakeField(text = "a 😀b", selectionStart = 3, selectionEnd = 3, setTextWorks = false)
        AccessibilityTarget(1, pair) {}.commit("x")
        assertEquals(listOf("refresh", "setText(a x 😀b)", "paste(x )"), pair.calls)
    }

    @Test fun nullTextAndMissingSelectionInsertAtTheEnd() {
        val field = FakeField(text = null)
        assertTrue(AccessibilityTarget(1, field) {}.commit("hi"))
        assertEquals(listOf("refresh", "setText(hi)", "setSelection(2)"), field.calls)
    }

    @Test fun onInsertedCallsTheCallback() {
        var count = 0
        val target = AccessibilityTarget(7, FakeField()) { count++ }
        assertEquals(7L, target.origin)
        target.onInserted()
        assertEquals(1, count)
    }
}
