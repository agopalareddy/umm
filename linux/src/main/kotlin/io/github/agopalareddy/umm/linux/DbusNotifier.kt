package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Portal
import java.util.concurrent.ConcurrentHashMap
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Notifications")
internal interface Notifications : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        icon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        timeoutMs: Int,
    ): UInt32
}

/** Desktop notifications over `org.freedesktop.Notifications`, optionally with an "Open Umm" button. */
class DbusNotifier(portal: Portal) : Notifier {
    private val notifications = portal.conn.getRemoteObject(BUS_NAME, OBJECT_PATH, Notifications::class.java)
    private val onOpen = ConcurrentHashMap<Long, () -> Unit>()

    init {
        portal.onSignal(BUS_NAME, "ActionInvoked") { signal ->
            val id = (signal.parameters[0] as UInt32).toLong()
            if (signal.parameters[1] == OPEN_ACTION) onOpen.remove(id)?.invoke()
        }
        portal.onSignal(BUS_NAME, "NotificationClosed") { signal -> onOpen.remove((signal.parameters[0] as UInt32).toLong()) }
    }

    override fun notify(title: String, body: String, openAction: Boolean, onOpen: () -> Unit) {
        try {
            val actions = if (openAction) listOf(OPEN_ACTION, "Open Umm") else emptyList()
            val hints = mapOf<String, Variant<*>>("desktop-entry" to Variant(Autostart.APP_ID))
            val id = notifications.Notify("Umm", UInt32(0), Autostart.APP_ID, title, body, actions, hints, -1)
            if (openAction) this.onOpen[id.toLong()] = onOpen
        } catch (e: Exception) {
            // A missing notification daemon must never break a dictation.
        }
    }

    private companion object {
        const val BUS_NAME = "org.freedesktop.Notifications"
        const val OBJECT_PATH = "/org/freedesktop/Notifications"
        const val OPEN_ACTION = "open"
    }
}
