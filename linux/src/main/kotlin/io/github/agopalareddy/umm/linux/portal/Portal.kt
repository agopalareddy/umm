package io.github.agopalareddy.umm.linux.portal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.freedesktop.dbus.DBusMatchRule
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** A portal request answered with a non-success code; 1 means the user cancelled. */
class PortalException(val code: Int) : Exception("portal response code $code")

/** A connection to the session bus and the helpers every portal call needs. */
class Portal internal constructor(internal val conn: DBusConnection) : AutoCloseable {
    private val sender = conn.uniqueName.removePrefix(":").replace('.', '_')
    private val counter = AtomicInteger()
    private val pending = ConcurrentHashMap<String, LinkedBlockingQueue<Array<Any>>>()
    private val responses = conn.addGenericSigHandler(DBusMatchRule("signal", "org.freedesktop.portal.Request", "Response")) { signal ->
        pending.remove(signal.path)?.put(signal.parameters)
    }

    internal fun token() = "umm${ProcessHandle.current().pid()}_${counter.incrementAndGet()}"

    /** Calls a portal method that returns a Request and waits for its Response; throws [PortalException] unless it succeeds. */
    @Suppress("UNCHECKED_CAST")
    internal fun request(extra: Opts = emptyMap(), call: (Opts) -> Unit): Map<String, Variant<*>> {
        val token = token()
        val queue = LinkedBlockingQueue<Array<Any>>()
        pending["$OBJECT_PATH/request/$sender/$token"] = queue
        try {
            call(extra + ("handle_token" to Variant(token)))
            val params = queue.poll(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: throw PortalException(TIMED_OUT)
            val code = (params[0] as UInt32).toInt()
            if (code != 0) throw PortalException(code)
            return params[1] as Map<String, Variant<*>>
        } finally {
            pending.remove("$OBJECT_PATH/request/$sender/$token")
        }
    }

    internal fun onSignal(iface: String, member: String, handler: (DBusSignal) -> Unit): AutoCloseable =
        conn.addGenericSigHandler(DBusMatchRule("signal", iface, member)) { handler(it) }

    internal inline fun <reified T : org.freedesktop.dbus.interfaces.DBusInterface> portalObject(): T =
        conn.getRemoteObject(BUS_NAME, OBJECT_PATH, T::class.java)

    internal fun sessionObject(session: DBusPath): Session = conn.getRemoteObject(BUS_NAME, session.path, Session::class.java)

    /** The portal interface's `version` property, or null when the portal (or that interface) is not there. */
    internal fun interfaceVersion(iface: String): Int? = try {
        val properties = conn.getRemoteObject(BUS_NAME, OBJECT_PATH, Properties::class.java)
        (properties.Get<UInt32>(iface, "version")).toInt()
    } catch (e: Exception) {
        null
    }

    /**
     * Portals identify an app by its `.desktop` id; unsandboxed apps must register one (KDE has no registry and
     * works without). Returns the portal's complaint, or null when registered. Failure is not fatal.
     */
    fun register(appId: String): String? = try {
        conn.getRemoteObject(BUS_NAME, OBJECT_PATH, Registry::class.java).Register(appId, emptyMap())
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    override fun close() {
        responses.close()
        conn.close()
    }

    companion object {
        const val BUS_NAME = "org.freedesktop.portal.Desktop"
        const val OBJECT_PATH = "/org/freedesktop/portal/desktop"
        private const val REQUEST_TIMEOUT_SECONDS = 180L
        private const val TIMED_OUT = -1

        fun connect(): Portal = Portal(DBusConnectionBuilder.forSessionBus().build())

        internal fun sessionPath(results: Map<String, Variant<*>>): DBusPath =
            when (val handle = results.getValue("session_handle").value) {
                is DBusPath -> handle
                else -> DBusPath(handle.toString())
            }
    }
}
