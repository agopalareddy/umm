package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Portal
import java.io.File
import java.nio.file.Files
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface

@DBusInterfaceName("io.github.agopalareddy.Umm.Focus")
interface FocusReport : DBusInterface {
    fun Report(appId: String)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.kde.kwin.Scripting")
internal interface KWinScripting : DBusInterface {
    fun loadScript(path: String, pluginName: String): Int
    fun unloadScript(pluginName: String): Boolean
}

@DBusInterfaceName("org.kde.kwin.Script")
internal interface KWinScript : DBusInterface {
    fun run()
}

/**
 * On KDE, learns which app has focus from a small KWin script that reports each window activation to Umm over
 * D-Bus. Elsewhere (GNOME has no such API for other apps) there is no tracker.
 */
class KWinFocusTracker private constructor(
    private val portal: Portal,
    private val scripting: KWinScripting,
    private val scriptFile: File,
) : FocusTracker, AutoCloseable {
    @Volatile private var current: String? = null

    private val receiver = object : FocusReport {
        override fun Report(appId: String) {
            current = appId.takeIf { it.isNotBlank() }
        }
        override fun isRemote() = false
        override fun getObjectPath() = OBJECT_PATH
    }

    init {
        portal.conn.requestBusName(BUS_NAME)
        portal.conn.exportObject(OBJECT_PATH, receiver)
    }

    override fun focusedAppId(): String? = current

    override fun close() {
        runCatching { scripting.unloadScript(PLUGIN_NAME) }
        scriptFile.delete()
        runCatching { portal.conn.unExportObject(OBJECT_PATH) }
    }

    companion object {
        private const val BUS_NAME = "io.github.agopalareddy.Umm.Focus"
        private const val OBJECT_PATH = "/Focus"
        private const val PLUGIN_NAME = "umm-focus"

        private val SCRIPT = """
            function report(window) {
                callDBus("$BUS_NAME", "$OBJECT_PATH", "io.github.agopalareddy.Umm.Focus", "Report",
                    window ? (window.desktopFileName || window.resourceClass || "") : "");
            }
            workspace.windowActivated.connect(report);
            report(workspace.activeWindow);
        """.trimIndent()

        /** The tracker on KDE Plasma, or null on other desktops or when KWin scripting is unavailable. */
        fun createOrNull(portal: Portal, env: Map<String, String> = System.getenv()): KWinFocusTracker? {
            if (!env["XDG_CURRENT_DESKTOP"].orEmpty().contains("KDE", ignoreCase = true)) return null
            return try {
                val scripting = portal.conn.getRemoteObject("org.kde.KWin", "/Scripting", KWinScripting::class.java)
                val file = Files.createTempFile("umm-focus", ".js").toFile().apply { writeText(SCRIPT) }
                scripting.unloadScript(PLUGIN_NAME)
                val tracker = KWinFocusTracker(portal, scripting, file)
                val id = scripting.loadScript(file.path, PLUGIN_NAME)
                portal.conn.getRemoteObject("org.kde.KWin", "/Scripting/Script$id", KWinScript::class.java).run()
                tracker
            } catch (e: Exception) {
                null
            }
        }
    }
}
