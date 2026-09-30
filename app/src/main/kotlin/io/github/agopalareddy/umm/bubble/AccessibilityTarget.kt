package io.github.agopalareddy.umm.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.agopalareddy.umm.ime.InsertionTarget
import io.github.agopalareddy.umm.ime.TextInsertion

/** The text field the bubble dictates into; [NodeField] is the real one, tests use a fake. */
interface FocusedField {
    val packageName: String

    /** False if the node is gone or no longer focused and editable. */
    fun refresh(): Boolean

    val text: CharSequence?
    val showingHint: Boolean
    val selectionStart: Int
    val selectionEnd: Int
    val multiLine: Boolean

    fun setText(text: String): Boolean
    fun setSelection(position: Int): Boolean

    /** Puts [text] on the clipboard and performs ACTION_PASTE. */
    fun paste(text: String): Boolean
}

/** Inserts each finished dictation into the field that had focus when the bubble was pressed. */
class AccessibilityTarget(
    override val origin: Long,
    private val field: FocusedField,
    private val inserted: () -> Unit,
) : InsertionTarget {
    override fun commit(text: String): Boolean {
        // A stale node, or focus that moved elsewhere, must not fall through to paste: paste goes to
        // whatever is focused now, which may be the wrong field. The router copies to the clipboard instead.
        if (!field.refresh()) return false
        val merged = NodeInsertion.merge(
            field.text, field.showingHint, field.selectionStart, field.selectionEnd, text, field.multiLine,
        )
        if (field.setText(merged.text)) {
            field.setSelection(merged.cursor)
            return true
        }
        // The node is still the focused editable field but refused ACTION_SET_TEXT, so paste is safe.
        return field.paste(prepareForPaste(text))
    }

    override fun onInserted() = inserted()

    private fun prepareForPaste(text: String): String {
        val current = if (field.showingHint) "" else field.text ?: ""
        val start = field.selectionStart
        val end = field.selectionEnd
        val before: CharSequence
        val after: CharSequence
        if (start < 0 || end < 0) {
            before = current
            after = ""
        } else {
            before = current.subSequence(0, minOf(start, end).coerceAtMost(current.length))
            after = current.subSequence(maxOf(start, end).coerceAtMost(current.length), current.length)
        }
        return TextInsertion.prepare(text, before, after, field.multiLine)
    }
}

/** Thin mapping onto the framework node; every decision lives in [AccessibilityTarget]. Device-verified only. */
class NodeField(
    private val node: AccessibilityNodeInfo,
    private val clipboard: ClipboardManager,
) : FocusedField {
    override val packageName: String get() = node.packageName?.toString().orEmpty()

    override fun refresh(): Boolean = node.refresh() && node.isFocused && node.isEditable

    override val text: CharSequence? get() = node.text
    override val showingHint: Boolean get() = node.isShowingHintText
    override val selectionStart: Int get() = node.textSelectionStart
    override val selectionEnd: Int get() = node.textSelectionEnd
    override val multiLine: Boolean get() = node.isMultiLine

    override fun setText(text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun setSelection(position: Int): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    override fun paste(text: String): Boolean {
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
