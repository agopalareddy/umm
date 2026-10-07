package io.github.agopalareddy.umm.linux

internal data class MenuItem(
    val id: Int,
    val label: String,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val separator: Boolean = false,
)

/** The tray menu for each state, and what activating an item does. */
internal object TrayMenu {
    const val OPEN = 1
    const val START_STOP = 2
    const val CANCEL = 3
    const val SEPARATOR = 4
    const val QUIT = 5

    fun items(state: TrayState) = listOf(
        MenuItem(OPEN, "Open Umm"),
        MenuItem(
            START_STOP,
            if (state == TrayState.RECORDING) "Stop dictation" else "Start dictation",
            enabled = state != TrayState.BUSY,
        ),
        MenuItem(CANCEL, "Cancel", visible = state == TrayState.RECORDING),
        MenuItem(SEPARATOR, "", separator = true),
        MenuItem(QUIT, "Quit"),
    )

    fun actionFor(id: Int, state: TrayState): TrayAction? = when (id) {
        OPEN -> TrayAction.OPEN
        START_STOP -> when (state) {
            TrayState.IDLE -> TrayAction.START
            TrayState.RECORDING -> TrayAction.STOP
            TrayState.BUSY -> null
        }
        CANCEL -> TrayAction.CANCEL.takeIf { state == TrayState.RECORDING }
        QUIT -> TrayAction.QUIT
        else -> null
    }
}
