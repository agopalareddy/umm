package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.GlobalShortcuts
import io.github.agopalareddy.umm.linux.portal.Portal
import io.github.agopalareddy.umm.linux.portal.PortalException
import io.github.agopalareddy.umm.linux.portal.Shortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.types.Variant

/** Binds one global shortcut through the GlobalShortcuts portal and reports its presses and releases. */
class GlobalShortcutsHotkey(
    private val portal: Portal,
    private val preferredTrigger: String = DEFAULT_TRIGGER,
) : HotkeySource {
    private val _events = MutableSharedFlow<HotkeyEvent>(extraBufferCapacity = 64)
    override val events: Flow<HotkeyEvent> = _events.asSharedFlow()

    private var session: DBusPath? = null
    private var version = 0
    private var signalHandlers = emptyList<AutoCloseable>()

    override suspend fun bind(): BindResult = withContext(Dispatchers.IO) {
        version = portal.interfaceVersion(INTERFACE) ?: return@withContext BindResult.Unsupported
        try {
            val shortcuts = portal.portalObject<GlobalShortcuts>()
            val created = portal.request(mapOf("session_handle_token" to Variant(portal.token()))) { shortcuts.CreateSession(it) }
            val sessionPath = Portal.sessionPath(created)
            listen()
            val shortcut = Shortcut(
                SHORTCUT_ID,
                mapOf(
                    "description" to Variant("Dictate with Umm"),
                    "preferred_trigger" to Variant(preferredTrigger),
                ),
            )
            val bound = portal.request { shortcuts.BindShortcuts(sessionPath, listOf(shortcut), "", it) }
            session = sessionPath
            BindResult.Bound(TriggerLabel.format(findTriggerDescription(bound["shortcuts"]).orEmpty()))
        } catch (e: PortalException) {
            BindResult.Failed(if (e.code == CANCELLED) "The shortcut was not approved" else "The desktop refused the shortcut")
        } catch (e: Exception) {
            BindResult.Failed(e.message ?: "Couldn't set up the shortcut")
        }
    }

    override suspend fun configure(): Boolean = withContext(Dispatchers.IO) {
        val current = session ?: return@withContext false
        if (version < CONFIGURE_SINCE_VERSION) return@withContext false
        try {
            portal.portalObject<GlobalShortcuts>().ConfigureShortcuts(current, "", emptyMap())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun listen() {
        signalHandlers.forEach(AutoCloseable::close)
        fun forwardTo(event: HotkeyEvent) = { signal: org.freedesktop.dbus.messages.DBusSignal ->
            if (signal.parameters[1] == SHORTCUT_ID) _events.tryEmit(event)
            Unit
        }
        signalHandlers = listOf(
            portal.onSignal(INTERFACE, "Activated", forwardTo(HotkeyEvent.Down)),
            portal.onSignal(INTERFACE, "Deactivated", forwardTo(HotkeyEvent.Up)),
        )
    }

    /** The response lists `(id, properties)` pairs; the properties carry the user-readable `trigger_description`. */
    private fun findTriggerDescription(value: Any?): String? = when (value) {
        is Variant<*> -> findTriggerDescription(value.value)
        is Map<*, *> -> (value["trigger_description"] as? Variant<*>)?.value?.toString()
            ?: value.values.firstNotNullOfOrNull(::findTriggerDescription)
        is Struct -> value.parameters.firstNotNullOfOrNull(::findTriggerDescription)
        is Iterable<*> -> value.firstNotNullOfOrNull(::findTriggerDescription)
        is Array<*> -> value.firstNotNullOfOrNull(::findTriggerDescription)
        else -> null
    }

    companion object {
        /** "LOGO" is the shortcuts spec's name for the Super key. */
        const val DEFAULT_TRIGGER = "LOGO+ALT+space"
        const val DEFAULT_TRIGGER_LABEL = "Super+Alt+Space"
        private const val GNOME_TRIGGER = "CTRL+ALT+space"

        /**
         * The trigger to suggest on this desktop. GNOME 50's portal never fires a shortcut that includes Super
         * (measured: Super+Alt+Space and Super+Alt+D stay silent while Ctrl+Alt+U fires), so elsewhere than KDE the
         * default is Ctrl+Alt+Space.
         */
        fun defaultTriggerFor(env: Map<String, String>): String =
            if (env["XDG_CURRENT_DESKTOP"].orEmpty().contains("KDE", ignoreCase = true)) DEFAULT_TRIGGER else GNOME_TRIGGER
        const val SHORTCUT_ID = "dictate"
        private const val INTERFACE = "org.freedesktop.portal.GlobalShortcuts"
        private const val CONFIGURE_SINCE_VERSION = 2
        private const val CANCELLED = 1
    }
}
