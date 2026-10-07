package io.github.agopalareddy.umm.linux

import kotlinx.coroutines.flow.Flow

sealed interface HotkeyEvent {
    data object Down : HotkeyEvent
    data object Up : HotkeyEvent
}

interface HotkeySource {
    val events: Flow<HotkeyEvent>

    suspend fun bind(): BindResult

    /** Opens the desktop's shortcut dialog; false when the desktop can't. */
    suspend fun configure(): Boolean
}

sealed interface BindResult {
    data class Bound(val trigger: String) : BindResult
    data object Unsupported : BindResult
    data class Failed(val reason: String) : BindResult
}

enum class TypingCapability { ALL, ASCII }

sealed interface InsertPart {
    data class Type(val text: String) : InsertPart
    data class Paste(val text: String) : InsertPart
}

interface TextInserter {
    val capability: TypingCapability

    /** Throws [InsertException] when the text could not be delivered. */
    suspend fun insert(parts: List<InsertPart>)
}

class InsertException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface FocusTracker {
    fun focusedAppId(): String?
}

enum class TrayState { IDLE, RECORDING, BUSY }

enum class TrayAction { OPEN, START, STOP, CANCEL, QUIT }

interface TrayIcon {
    fun show(state: TrayState, onAction: (TrayAction) -> Unit)
    fun update(state: TrayState)
    fun hide()
}

interface Notifier {
    fun notify(title: String, body: String, openAction: Boolean = false, onOpen: () -> Unit = {})
}

/** The non-portal clipboard fallback (AWT). */
interface Clipboard {
    fun setText(text: String)
}
