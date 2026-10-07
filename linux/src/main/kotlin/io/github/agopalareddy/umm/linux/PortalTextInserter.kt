package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.linux.portal.ClipboardPortal
import io.github.agopalareddy.umm.linux.portal.Portal
import io.github.agopalareddy.umm.linux.portal.PortalException
import io.github.agopalareddy.umm.linux.portal.RemoteDesktop
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.transport.junixsocket.JUnixSocketSocketProvider
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * Types text into the focused app through the RemoteDesktop portal. One session is opened per call (so GNOME's
 * remote-control indicator shows only while text goes in); the permission is remembered by a restore token kept in
 * [tokenStore]. Parts to paste go through the Clipboard portal followed by Ctrl+V.
 */
class PortalTextInserter(
    private val portal: Portal,
    private val tokenStore: KeyValueStore,
    override val capability: TypingCapability,
) : TextInserter {
    override suspend fun insert(parts: List<InsertPart>) {
        if (parts.isEmpty()) return
        withContext(Dispatchers.IO) { Session(parts).run() }
    }

    /** Opens and closes a session without typing, so setup can show the permission dialog; throws [InsertException] if refused. */
    suspend fun grantPermission() {
        withContext(Dispatchers.IO) { Session(emptyList()).run() }
    }

    private inner class Session(private val parts: List<InsertPart>) {
        private val remote = portal.portalObject<RemoteDesktop>()
        private val clipboard = portal.portalObject<ClipboardPortal>()
        private var session: DBusPath? = null
        @Volatile private var pasteText = ""
        @Volatile private var served = CountDownLatch(0)

        fun run() {
            var transfers: AutoCloseable? = null
            try {
                val path = open()
                transfers = portal.onSignal(CLIPBOARD_INTERFACE, "SelectionTransfer") { signal ->
                    val serial = signal.parameters[2] as UInt32
                    Thread { serve(path, serial) }.apply { isDaemon = true }.start()
                }
                parts.forEach { part ->
                    when (part) {
                        is InsertPart.Type -> type(path, part.text)
                        is InsertPart.Paste -> paste(path, part.text)
                    }
                }
            } catch (e: InsertException) {
                throw e
            } catch (e: PortalException) {
                throw InsertException(if (e.code == CANCELLED) "Typing permission was refused" else "The desktop refused to let Umm type", e)
            } catch (e: Exception) {
                throw InsertException(e.message ?: "Couldn't type the text", e)
            } finally {
                transfers?.close()
                session?.let { runCatching { portal.sessionObject(it).Close() } }
            }
        }

        private fun open(): DBusPath {
            val created = portal.request(mapOf("session_handle_token" to Variant(portal.token()))) { remote.CreateSession(it) }
            val path = Portal.sessionPath(created)
            session = path
            // Typing works without the clipboard (KDE never pastes), so a missing Clipboard portal is not fatal;
            // paste() checks clipboard_enabled from Start.
            runCatching { clipboard.RequestClipboard(path, emptyMap()) }
            val select = mutableMapOf<String, Variant<*>>(
                "types" to Variant(UInt32(KEYBOARD)),
                "persist_mode" to Variant(UInt32(PERSIST_UNTIL_REVOKED)),
            )
            tokenStore.getString(TOKEN_KEY)?.let { select["restore_token"] = Variant(it) }
            portal.request(select) { remote.SelectDevices(path, it) }
            val started = portal.request { remote.Start(path, "", it) }
            // The token only saves a dialog next time; the keyring being slow or refusing must not fail this insert.
            started["restore_token"]?.value?.toString()?.let { runCatching { tokenStore.putString(TOKEN_KEY, it) } }
            clipboardEnabled = started["clipboard_enabled"]?.value == true
            return path
        }

        private var clipboardEnabled = false

        private fun type(path: DBusPath, text: String) {
            text.codePoints().forEach { tap(path, Keysyms.forChar(it)) }
        }

        private fun paste(path: DBusPath, text: String) {
            if (!clipboardEnabled) throw InsertException("The desktop doesn't allow Umm to paste")
            pasteText = text
            served = CountDownLatch(1)
            clipboard.SetSelection(path, mapOf("mime_types" to Variant(arrayOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING"))))
            Thread.sleep(PASTE_SETTLE_MS)
            key(path, Keysyms.CONTROL_L, down = true)
            tap(path, 'v'.code)
            key(path, Keysyms.CONTROL_L, down = false)
            // The target asks for the clipboard after the chord; the session must stay open until we have served it.
            served.await(TRANSFER_WAIT_MS, TimeUnit.MILLISECONDS)
            // Toolkits like GTK apply a paste a moment after reading it; keys typed straight away would land first.
            Thread.sleep(AFTER_PASTE_MS)
        }

        private fun tap(path: DBusPath, keysym: Int) {
            key(path, keysym, down = true)
            key(path, keysym, down = false)
        }

        private fun key(path: DBusPath, keysym: Int, down: Boolean) =
            remote.NotifyKeyboardKeysym(path, emptyMap(), keysym, UInt32(if (down) 1 else 0))

        /** Writes the pending paste text to the fd the portal hands over, then closes that fd (the reader waits for EOF). */
        private fun serve(path: DBusPath, serial: UInt32) {
            try {
                val fd: FileDescriptor = clipboard.SelectionWrite(path, serial)
                FileOutputStream(fd.toJavaFileDescriptor(SOCKETS)).use { it.write(pasteText.toByteArray(Charsets.UTF_8)) }
                clipboard.SelectionWriteDone(path, serial, true)
            } catch (e: Exception) {
                runCatching { clipboard.SelectionWriteDone(path, serial, false) }
            } finally {
                served.countDown()
            }
        }
    }

    private companion object {
        const val TOKEN_KEY = "remote_desktop_token"
        const val CLIPBOARD_INTERFACE = "org.freedesktop.portal.Clipboard"
        const val KEYBOARD = 1L
        const val PERSIST_UNTIL_REVOKED = 2L
        const val CANCELLED = 1
        const val PASTE_SETTLE_MS = 150L
        const val TRANSFER_WAIT_MS = 1500L
        const val AFTER_PASTE_MS = 250L
        val SOCKETS = JUnixSocketSocketProvider()
    }
}
