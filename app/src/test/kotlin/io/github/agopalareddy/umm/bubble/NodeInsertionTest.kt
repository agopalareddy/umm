package io.github.agopalareddy.umm.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeInsertionTest {
    private fun merge(
        current: CharSequence?,
        showingHint: Boolean,
        selStart: Int,
        selEnd: Int,
        insert: String,
        multiLine: Boolean = false,
    ) = NodeInsertion.merge(current, showingHint, selStart, selEnd, insert, multiLine)

    @Test fun nullTextCountsAsEmpty() {
        assertEquals(Merge("hello", 5), merge(null, false, -1, -1, "hello"))
    }

    @Test fun hintTextIsReplacedNotKept() {
        assertEquals(Merge("hi", 2), merge("Message", true, 0, 0, "hi"))
    }

    @Test fun hintTextIgnoresAnyReportedSelection() {
        assertEquals(Merge("hi", 2), merge("Message", true, 7, 7, "hi"))
        assertEquals(Merge("hi", 2), merge("Message", true, -1, -1, "hi"))
    }

    @Test fun insertsAtTheCursorInsideText() {
        assertEquals(Merge("hello big world", 9), merge("hello world", false, 5, 5, "big"))
    }

    @Test fun replacesTheSelection() {
        assertEquals(Merge("hello there", 11), merge("hello world", false, 6, 11, "there"))
    }

    @Test fun reversedSelectionIsNormalized() {
        assertEquals(Merge("hello there", 11), merge("hello world", false, 11, 6, "there"))
    }

    @Test fun selectionPastTheEndIsClamped() {
        assertEquals(Merge("abc x", 5), merge("abc", false, 50, 50, "x"))
    }

    @Test fun missingSelectionInsertsAtTheEnd() {
        assertEquals(Merge("abc x", 5), merge("abc", false, -1, -1, "x"))
        assertEquals(Merge("abc x", 5), merge("abc", false, -1, 1, "x"))
        assertEquals(Merge("abc x", 5), merge("abc", false, 1, -1, "x"))
    }

    @Test fun selectionPastTheEndReplacesToTheEnd() {
        assertEquals(Merge("a x", 3), merge("abc", false, 1, 50, "x"))
    }

    @Test fun singleLineFieldFlattensNewlines() {
        assertEquals(Merge("a b", 3), merge("", false, 0, 0, "a\nb"))
    }

    @Test fun multiLineFieldKeepsNewlines() {
        assertEquals(Merge("a\nb", 3), merge("", false, 0, 0, "a\nb", multiLine = true))
    }

    @Test fun addsSpaceBeforeFollowingWord() {
        assertEquals(Merge("hello world", 6), merge("world", false, 0, 0, "hello"))
    }

    @Test fun emptyTextInsertsAsIs() {
        assertEquals(Merge("hello", 5), merge("", false, 0, 0, "hello"))
    }

    @Test fun emptyInsertLeavesTextAndPlacesCursor() {
        assertEquals(Merge("hello world", 5), merge("hello world", false, 5, 5, ""))
    }

    @Test fun cursorInsideASurrogatePairMovesBeforeIt() {
        // "a😀b": the emoji occupies UTF-16 indices 1 and 2
        assertEquals(Merge("a x 😀b", 4), merge("a😀b", false, 2, 2, "x"))
    }

    @Test fun selectionEndingInsideASurrogatePairTakesTheWholePair() {
        assertEquals(Merge("x b", 2), merge("a😀b", false, 0, 2, "x"))
    }

    @Test fun selectionStartingInsideASurrogatePairTakesTheWholePair() {
        assertEquals(Merge("a x b", 4), merge("a😀b", false, 2, 3, "x"))
    }

    private fun prepared(
        current: CharSequence?,
        showingHint: Boolean,
        selStart: Int,
        selEnd: Int,
        insert: String,
        multiLine: Boolean = false,
    ) = NodeInsertion.prepared(current, showingHint, selStart, selEnd, insert, multiLine)

    @Test fun preparedIsTheInsertedPartOfTheMerge() {
        val text = "hello world"
        assertEquals(" big ", prepared(text, false, 5, 8, "big"))
        assertEquals(Merge("hello big rld", 10), merge(text, false, 5, 8, "big"))
    }

    @Test fun preparedNormalizesAReversedSelection() {
        assertEquals(" big ", prepared("hello world", false, 8, 5, "big"))
    }

    @Test fun preparedFollowsTheSurrogatePairAdjustment() {
        // "a 😀b": the emoji occupies indices 2 and 3; a cursor between them moves before the pair, after a space
        assertEquals("x ", prepared("a 😀b", false, 3, 3, "x"))
        assertEquals(Merge("a x 😀b", 4), merge("a 😀b", false, 3, 3, "x"))
    }

    @Test fun preparedTreatsHintAndNullTextAsEmpty() {
        assertEquals("hi", prepared("Message", true, 7, 7, "hi"))
        assertEquals("hi", prepared(null, false, -1, -1, "hi"))
    }

    @Test fun preparedFlattensNewlinesInSingleLineFields() {
        assertEquals("a b", prepared("", false, 0, 0, "a\nb"))
        assertEquals("a\nb", prepared("", false, 0, 0, "a\nb", multiLine = true))
    }
}
