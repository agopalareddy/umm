package io.github.agopalareddy.umm.linux

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** The clipboard fallback for when typing is refused; it only needs the toolkit, not a portal session. */
class AwtClipboard : Clipboard {
    override fun setText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
