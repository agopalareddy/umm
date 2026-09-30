package io.github.agopalareddy.umm.bubble

import io.github.agopalareddy.umm.ime.TextInsertion

/** The full new text for a field and where its cursor goes (a UTF-16 index, as Android uses). */
data class Merge(val text: String, val cursor: Int)

/** Merges dictated text into the text of the focused field, for setting it through an accessibility node. */
object NodeInsertion {
    /**
     * Replaces the selection ([selStart], [selEnd]) of [current] with [insert]. Null text and placeholder
     * ([showingHint]) text count as empty; reversed or oversized selections are normalized and clamped, and a
     * negative index means the field reported no selection, so the text goes at the end. A selection edge inside
     * a surrogate pair moves to the pair's boundary so an emoji is never split.
     */
    fun merge(
        current: CharSequence?,
        showingHint: Boolean,
        selStart: Int,
        selEnd: Int,
        insert: String,
        multiLine: Boolean,
    ): Merge {
        val plan = plan(current, showingHint, selStart, selEnd, insert, multiLine)
        return Merge(plan.before.toString() + plan.prepared + plan.after, plan.before.length + plan.prepared.length)
    }

    /** The part of [merge]'s text that is the dictation, adapted to its surroundings: what a paste must insert. */
    fun prepared(
        current: CharSequence?,
        showingHint: Boolean,
        selStart: Int,
        selEnd: Int,
        insert: String,
        multiLine: Boolean,
    ): String = plan(current, showingHint, selStart, selEnd, insert, multiLine).prepared

    private class Plan(val before: CharSequence, val prepared: String, val after: CharSequence)

    private fun plan(
        current: CharSequence?,
        showingHint: Boolean,
        selStart: Int,
        selEnd: Int,
        insert: String,
        multiLine: Boolean,
    ): Plan {
        val text: CharSequence = if (showingHint) "" else current ?: ""
        var start: Int
        var end: Int
        if (selStart < 0 || selEnd < 0) {
            start = text.length
            end = text.length
        } else {
            start = minOf(selStart, selEnd).coerceAtMost(text.length)
            end = maxOf(selStart, selEnd).coerceAtMost(text.length)
        }
        val collapsed = start == end
        if (splitsPair(text, start)) start--
        if (splitsPair(text, end)) end = if (collapsed) end - 1 else end + 1
        val before = text.subSequence(0, start)
        val after = text.subSequence(end, text.length)
        return Plan(before, TextInsertion.prepare(insert, before, after, multiLine), after)
    }

    private fun splitsPair(text: CharSequence, index: Int): Boolean =
        index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
}
