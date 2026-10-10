package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Portal
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32

@DBusInterfaceName("io.github.agopalareddy.Umm")
interface UmmControl : DBusInterface {
    fun Activate()
}

/**
 * Makes Umm a single-instance app: the first process owns the bus name `io.github.agopalareddy.Umm`; a later launch
 * asks it to show the window and exits.
 */
class SingleInstance(private val portal: Portal) {
    /** True when this process now owns the instance name; [onActivate] then receives forwarded launches. */
    fun claim(onActivate: () -> Unit): Boolean {
        val bus = portal.conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        val reply = try {
            bus.RequestName(NAME, UInt32(DBus.DBUS_NAME_FLAG_DO_NOT_QUEUE.toLong())).toInt()
        } catch (e: Exception) {
            return false
        }
        if (reply != DBus.DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER && reply != DBus.DBUS_REQUEST_NAME_REPLY_ALREADY_OWNER) return false
        portal.conn.exportObject(OBJECT_PATH, object : UmmControl {
            override fun Activate() = onActivate()
            override fun isRemote() = false
            override fun getObjectPath() = OBJECT_PATH
        })
        return true
    }

    /** Asks the running instance to show its window. */
    fun forward() {
        val running = portal.conn.getRemoteObject(NAME, OBJECT_PATH, UmmControl::class.java)
        running.Activate()
    }

    companion object {
        const val NAME = "io.github.agopalareddy.Umm"
        private const val OBJECT_PATH = "/io/github/agopalareddy/Umm"
    }
}
