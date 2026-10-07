package io.github.agopalareddy.umm.linux.portal

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

internal typealias Opts = Map<String, Variant<*>>

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.host.portal.Registry")
internal interface Registry : DBusInterface {
    fun Register(appId: String, options: Opts)
}

internal class Shortcut(@Position(0) @JvmField val id: String, @Position(1) @JvmField val props: Opts) : Struct()

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
internal interface GlobalShortcuts : DBusInterface {
    fun CreateSession(options: Opts): DBusPath
    fun BindShortcuts(session: DBusPath, shortcuts: List<Shortcut>, parentWindow: String, options: Opts): DBusPath
    fun ConfigureShortcuts(session: DBusPath, parentWindow: String, options: Opts)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.portal.RemoteDesktop")
internal interface RemoteDesktop : DBusInterface {
    fun CreateSession(options: Opts): DBusPath
    fun SelectDevices(session: DBusPath, options: Opts): DBusPath
    fun Start(session: DBusPath, parentWindow: String, options: Opts): DBusPath
    fun NotifyKeyboardKeysym(session: DBusPath, options: Opts, keysym: Int, state: UInt32)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.portal.Clipboard")
internal interface ClipboardPortal : DBusInterface {
    fun RequestClipboard(session: DBusPath, options: Opts)
    fun SetSelection(session: DBusPath, options: Opts)
    fun SelectionWrite(session: DBusPath, serial: UInt32): FileDescriptor
    fun SelectionWriteDone(session: DBusPath, serial: UInt32, success: Boolean)
}

@DBusInterfaceName("org.freedesktop.portal.Session")
internal interface Session : DBusInterface {
    fun Close()
}
