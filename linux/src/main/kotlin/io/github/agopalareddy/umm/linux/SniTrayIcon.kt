package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Portal
import java.util.concurrent.atomic.AtomicInteger
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

class IconPixmap(
    @Position(0) @JvmField val width: Int,
    @Position(1) @JvmField val height: Int,
    @Position(2) @JvmField val data: ByteArray,
) : Struct()

internal class MenuLayout(
    @Position(0) @JvmField val id: Int,
    @Position(1) @JvmField val properties: Map<String, Variant<*>>,
    @Position(2) @JvmField val children: List<Variant<*>>,
) : Struct()

internal class MenuItemProperties(
    @Position(0) @JvmField val id: Int,
    @Position(1) @JvmField val properties: Map<String, Variant<*>>,
) : Struct()

/** dbus-java only exports a Tuple whose return type is parameterized: the type arguments carry the field types. */
internal class GetLayoutResult<R, L>(
    @Position(0) @JvmField val revision: R,
    @Position(1) @JvmField val layout: L,
) : Tuple()

@JvmSuppressWildcards
@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    @DBusBoundProperty(name = "Category") fun getCategory(): String
    @DBusBoundProperty(name = "Id") fun getId(): String
    @DBusBoundProperty(name = "Title") fun getTitle(): String
    @DBusBoundProperty(name = "Status") fun getStatus(): String
    @DBusBoundProperty(name = "IconName") fun getIconName(): String
    @DBusBoundProperty(name = "IconPixmap") fun getIconPixmap(): List<IconPixmap>
    @DBusBoundProperty(name = "ItemIsMenu") fun getItemIsMenu(): Boolean
    @DBusBoundProperty(name = "Menu") fun getMenu(): DBusPath

    fun ContextMenu(x: Int, y: Int)
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    class NewIcon(path: String) : DBusSignal(path)
    class NewTitle(path: String) : DBusSignal(path)
}

@JvmSuppressWildcards
@DBusInterfaceName("com.canonical.dbusmenu")
internal interface DbusMenu : DBusInterface {
    @DBusBoundProperty(name = "Version") fun getVersion(): UInt32
    @DBusBoundProperty(name = "TextDirection") fun getTextDirection(): String
    @DBusBoundProperty(name = "Status") fun getStatus(): String

    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): GetLayoutResult<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun AboutToShow(id: Int): Boolean

    class LayoutUpdated(path: String, revision: UInt32, parent: Int) : DBusSignal(path, revision, parent)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.kde.StatusNotifierWatcher")
internal interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

/**
 * A StatusNotifierItem with a dbusmenu menu: the tray icon of KDE Plasma and of GNOME with an AppIndicator extension.
 * Where no watcher is running (stock GNOME) it does nothing; the orb and notifications carry the state there.
 */
class SniTrayIcon(private val portal: Portal) : TrayIcon {
    @Volatile private var state = TrayState.IDLE
    @Volatile private var onAction: (TrayAction) -> Unit = {}
    private val menuRevision = AtomicInteger(1)
    private val busName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
    private var shown = false
    private var watcherSignal: AutoCloseable? = null

    private val item = object : StatusNotifierItem {
        override fun getCategory() = "ApplicationStatus"
        override fun getId() = "io.github.agopalareddy.Umm"
        override fun getTitle() = when (state) {
            TrayState.IDLE -> "Umm"
            TrayState.RECORDING -> "Umm: listening"
            TrayState.BUSY -> "Umm: working"
        }
        override fun getStatus() = "Active"
        override fun getIconName() = ""
        override fun getIconPixmap() = listOf(22, 44).map { IconPixmap(it, it, TrayIcons.pixmap(state, it)) }
        override fun getItemIsMenu() = false
        override fun getMenu() = DBusPath(MENU_PATH)
        override fun ContextMenu(x: Int, y: Int) = Unit
        override fun Activate(x: Int, y: Int) = onAction(TrayAction.OPEN)
        override fun SecondaryActivate(x: Int, y: Int) = onAction(TrayAction.OPEN)
        override fun Scroll(delta: Int, orientation: String) = Unit
        override fun isRemote() = false
        override fun getObjectPath() = ITEM_PATH
    }

    private val menu = object : DbusMenu {
        override fun getVersion() = UInt32(3)
        override fun getTextDirection() = "ltr"
        override fun getStatus() = "normal"

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): GetLayoutResult<UInt32, MenuLayout> {
            val children = TrayMenu.items(state).map { Variant(layoutOf(it), "(ia{sv}av)") }
            return GetLayoutResult(UInt32(menuRevision.get().toLong()), MenuLayout(0, mapOf("children-display" to Variant("submenu")), children))
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>) =
            TrayMenu.items(state).filter { it.id in ids }.map { MenuItemProperties(it.id, propertiesOf(it)) }

        override fun GetProperty(id: Int, name: String): Variant<*> =
            propertiesOf(TrayMenu.items(state).first { it.id == id }).getValue(name)

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId == "clicked") TrayMenu.actionFor(id, state)?.let(onAction)
        }

        override fun AboutToShow(id: Int) = false
        override fun isRemote() = false
        override fun getObjectPath() = MENU_PATH
    }

    override fun show(state: TrayState, onAction: (TrayAction) -> Unit) {
        this.state = state
        this.onAction = onAction
        try {
            portal.conn.requestBusName(busName)
            portal.conn.exportObject(ITEM_PATH, item)
            portal.conn.exportObject(MENU_PATH, menu)
            shown = true
        } catch (e: Exception) {
            return // The bus refused us: no tray.
        }
        // Umm often starts at login before the panel's watcher exists, and a restarted panel loses its items, so
        // register now and again each time the watcher appears.
        watcherSignal = portal.onSignal("org.freedesktop.DBus", "NameOwnerChanged") { signal ->
            if (signal.parameters[0] == WATCHER_NAME && (signal.parameters[2] as? String).orEmpty().isNotEmpty()) register()
        }
        register()
    }

    private fun register() {
        try {
            portal.conn.getRemoteObject(WATCHER_NAME, WATCHER_PATH, StatusNotifierWatcher::class.java)
                .RegisterStatusNotifierItem(busName)
        } catch (e: Exception) {
            // No watcher yet (or none on this desktop); a later NameOwnerChanged retries.
        }
    }

    override fun update(state: TrayState) {
        if (this.state == state) return
        this.state = state
        if (!shown) return
        try {
            portal.conn.sendMessage(StatusNotifierItem.NewIcon(ITEM_PATH))
            portal.conn.sendMessage(StatusNotifierItem.NewTitle(ITEM_PATH))
            portal.conn.sendMessage(DbusMenu.LayoutUpdated(MENU_PATH, UInt32(menuRevision.incrementAndGet().toLong()), 0))
        } catch (e: Exception) {
            // The tray is cosmetic.
        }
    }

    override fun hide() {
        if (!shown) return
        shown = false
        watcherSignal?.let { runCatching { it.close() } }
        runCatching { portal.conn.unExportObject(ITEM_PATH) }
        runCatching { portal.conn.unExportObject(MENU_PATH) }
        runCatching { portal.conn.releaseBusName(busName) }
    }

    private fun propertiesOf(entry: MenuItem): Map<String, Variant<*>> = buildMap {
        if (entry.separator) {
            put("type", Variant("separator"))
        } else {
            put("label", Variant(entry.label))
        }
        put("visible", Variant(entry.visible))
        put("enabled", Variant(entry.enabled))
    }

    private fun layoutOf(entry: MenuItem) = MenuLayout(entry.id, propertiesOf(entry), emptyList())

    private companion object {
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val WATCHER_NAME = "org.kde.StatusNotifierWatcher"
        const val WATCHER_PATH = "/StatusNotifierWatcher"
    }
}
