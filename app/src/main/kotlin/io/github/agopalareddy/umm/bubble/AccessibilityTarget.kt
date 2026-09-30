package io.github.agopalareddy.umm.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.agopalareddy.umm.ime.InsertionTarget

/** The text field the bubble dictates into; [NodeField] is the real one, tests use a fake. */
interface FocusedField {
    val packageName: String

    /**
     * False if the node is gone, no longer focused and editable, or no longer in the window where dictation
     * started.
     */
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
        val prepared = NodeInsertion.prepared(
            field.text, field.showingHint, field.selectionStart, field.selectionEnd, text, field.multiLine,
        )
        return field.paste(prepared)
    }

    override fun onInserted() = inserted()
}

/** Thin mapping onto the framework node; every decision lives in [AccessibilityTarget]. Device-verified only. */
class NodeField(
    private val node: AccessibilityNodeInfo,
    private val clipboard: ClipboardManager,
    // isFocused only means focus inside the node's own window, so it stays true after the user switches apps.
    // node.window is null without flagRetrieveInteractiveWindows, so the service supplies the window check.
    private val windowIsCurrent: () -> Boolean,
) : FocusedField {
    override val packageName: String get() = node.packageName?.toString().orEmpty()

    override fun refresh(): Boolean = node.refresh() && node.isFocused && node.isEditable && windowIsCurrent()

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

    // A clipboard failure must not escape commit and skip the router's own clipboard fallback.
    override fun paste(text: String): Boolean = runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText("", text))
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }.getOrDefault(false)
}
