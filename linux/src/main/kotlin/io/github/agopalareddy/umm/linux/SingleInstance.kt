package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Portal
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32

@DBusInterfaceName("io.github.agopalareddy.Umm")
interface UmmControl : DBusInterface {
    fun Activate()
    fun OpenUrl(url: String)
}

/**
 * Makes Umm a single-instance app: the first process owns the bus name `io.github.agopalareddy.Umm`; a later launch
 * forwards what it was asked to do (show the window, or open a `umm://` link) to it and exits.
 */
class SingleInstance(private val portal: Portal) {
    sealed interface Message {
        data object Activate : Message
        data class OpenUrl(val url: String) : Message
    }

    /** True when this process now owns the instance name; [onActivate] and [onUrl] then receive forwarded launches. */
    fun claim(onActivate: () -> Unit, onUrl: (String) -> Unit): Boolean {
        val bus = portal.conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        val reply = try {
            bus.RequestName(NAME, UInt32(DBus.DBUS_NAME_FLAG_DO_NOT_QUEUE.toLong())).toInt()
        } catch (e: Exception) {
            return false
        }
        if (reply != DBus.DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER && reply != DBus.DBUS_REQUEST_NAME_REPLY_ALREADY_OWNER) return false
        portal.conn.exportObject(OBJECT_PATH, object : UmmControl {
            override fun Activate() = onActivate()
            override fun OpenUrl(url: String) = onUrl(url)
            override fun isRemote() = false
            override fun getObjectPath() = OBJECT_PATH
        })
        return true
    }

    /** Hands a second launch's arguments to the running instance. */
    fun forward(args: Array<String>) {
        val running = portal.conn.getRemoteObject(NAME, OBJECT_PATH, UmmControl::class.java)
        when (val message = messageFor(args)) {
            Message.Activate -> running.Activate()
            is Message.OpenUrl -> running.OpenUrl(message.url)
        }
    }

    companion object {
        const val NAME = "io.github.agopalareddy.Umm"
        private const val OBJECT_PATH = "/io/github/agopalareddy/Umm"

        fun messageFor(args: Array<String>): Message =
            args.firstOrNull { it.startsWith("umm://") }?.let(Message::OpenUrl) ?: Message.Activate
    }
}
