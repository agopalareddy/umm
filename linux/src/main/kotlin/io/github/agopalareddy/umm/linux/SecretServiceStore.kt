package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.linux.portal.Portal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

internal class Secret(
    @Position(0) @JvmField val session: DBusPath,
    @Position(1) @JvmField val parameters: ByteArray,
    @Position(2) @JvmField val value: ByteArray,
    @Position(3) @JvmField val contentType: String,
) : Struct()

internal class OpenSessionResult(
    @Position(0) @JvmField val output: Variant<*>,
    @Position(1) @JvmField val session: DBusPath,
) : Tuple()

internal class SearchResult(
    @Position(0) @JvmField val unlocked: List<DBusPath>,
    @Position(1) @JvmField val locked: List<DBusPath>,
) : Tuple()

internal class UnlockResult(
    @Position(0) @JvmField val unlocked: List<DBusPath>,
    @Position(1) @JvmField val prompt: DBusPath,
) : Tuple()

internal class CreateItemResult(
    @Position(0) @JvmField val item: DBusPath,
    @Position(1) @JvmField val prompt: DBusPath,
) : Tuple()

internal class CreateCollectionResult(
    @Position(0) @JvmField val collection: DBusPath,
    @Position(1) @JvmField val prompt: DBusPath,
) : Tuple()

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Service")
internal interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): OpenSessionResult
    fun SearchItems(attributes: Map<String, String>): SearchResult
    fun Unlock(objects: List<DBusPath>): UnlockResult
    fun ReadAlias(name: String): DBusPath
    fun CreateCollection(properties: Map<String, Variant<*>>, alias: String): CreateCollectionResult
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Collection")
internal interface SecretCollection : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): CreateItemResult
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Item")
internal interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
    fun Delete(): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Prompt")
internal interface SecretPrompt : DBusInterface {
    fun Prompt(windowId: String)
}

/**
 * Keeps values in the system keyring (Secret Service: KWallet, GNOME Keyring), each as an item tagged
 * `application=io.github.agopalareddy.Umm` and `key=<name>`. Items are stored over the plain session, which is safe
 * because the bus is local.
 */
class SecretServiceStore private constructor(
    private val portal: Portal,
    private val service: SecretService,
    private val session: DBusPath,
) : KeyValueStore {
    override fun getString(key: String): String? = try {
        val found = service.SearchItems(attributes(key))
        val path = found.unlocked.firstOrNull() ?: found.locked.firstOrNull()?.also { unlock(it) }
        path?.let { item(it).GetSecret(session).value.toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    override fun putString(key: String, value: String) {
        val collection = writableCollection()
        val properties = mapOf<String, Variant<*>>(
            "org.freedesktop.Secret.Item.Label" to Variant("Umm $key"),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attributes(key), "a{ss}"),
        )
        val secret = Secret(session, ByteArray(0), value.toByteArray(Charsets.UTF_8), "text/plain")
        val created = portal.conn.getRemoteObject(BUS_NAME, collection.path, SecretCollection::class.java)
            .CreateItem(properties, secret, true)
        awaitPrompt(created.prompt)
    }

    override fun remove(vararg keys: String) {
        keys.forEach { key ->
            val found = service.SearchItems(attributes(key))
            (found.unlocked + found.locked).forEach { path ->
                unlock(path)
                awaitPrompt(item(path).Delete())
            }
        }
    }

    /**
     * The collection to save into. Normally the default keyring, unlocked. A fresh autologin session or a live image
     * has none (ReadAlias answers "/"), so ask the keyring to create one, as libsecret does; if that is refused, use
     * the session collection, which lasts until logout but keeps things like the typing permission for that long.
     */
    private fun writableCollection(): DBusPath {
        service.ReadAlias(DEFAULT_ALIAS).takeUnless { it.path == NO_PROMPT }?.let {
            unlock(it)
            return it
        }
        return runCatching { createDefaultCollection() }.getOrNull() ?: DBusPath(SESSION_COLLECTION_PATH)
    }

    private fun createDefaultCollection(): DBusPath {
        val created = service.CreateCollection(mapOf("org.freedesktop.Secret.Collection.Label" to Variant("Default keyring")), DEFAULT_ALIAS)
        val path = if (created.collection.path != NO_PROMPT) created.collection.path else awaitPrompt(created.prompt)
        return DBusPath(checkNotNull(path?.takeUnless { it == NO_PROMPT }) { "the keyring created no collection" })
    }

    private fun item(path: DBusPath) = portal.conn.getRemoteObject(BUS_NAME, path.path, SecretItem::class.java)

    private fun attributes(key: String) = mapOf("application" to Autostart.APP_ID, "key" to key)

    private fun unlock(path: DBusPath) {
        awaitPrompt(service.Unlock(listOf(path)).prompt)
    }

    /**
     * Runs the keyring's own dialog (for example an unlock password prompt) when the call needs one, and returns what
     * the prompt produced (such as a new collection's path); null when there was no prompt.
     */
    private fun awaitPrompt(prompt: DBusPath): String? {
        if (prompt.path == NO_PROMPT) return null
        val done = CountDownLatch(1)
        val dismissed = AtomicBoolean(false)
        val produced = AtomicReference<String?>(null)
        val handler = portal.conn.addGenericSigHandler(
            org.freedesktop.dbus.DBusMatchRule("signal", "org.freedesktop.Secret.Prompt", "Completed", prompt.path),
        ) { signal ->
            dismissed.set(signal.parameters.firstOrNull() == true)
            produced.set((signal.parameters.getOrNull(1) as? Variant<*>)?.value?.toString())
            done.countDown()
        }
        try {
            portal.conn.getRemoteObject(BUS_NAME, prompt.path, SecretPrompt::class.java).Prompt("")
            check(done.await(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "keyring prompt timed out" }
            // A dismissed prompt did not do what it was for: the item was not saved or the keyring not unlocked.
            check(!dismissed.get()) { "keyring prompt dismissed" }
            return produced.get()
        } finally {
            handler.close()
        }
    }

    companion object {
        private const val BUS_NAME = "org.freedesktop.secrets"
        private const val SERVICE_PATH = "/org/freedesktop/secrets"
        private const val DEFAULT_ALIAS = "default"
        private const val SESSION_COLLECTION_PATH = "/org/freedesktop/secrets/collection/session"
        private const val NO_PROMPT = "/"
        private const val PROMPT_TIMEOUT_SECONDS = 120L

        /** The keyring store, or null when no Secret Service is running (or it can't be reached). */
        fun openOrNull(portal: Portal): SecretServiceStore? = try {
            val service = portal.conn.getRemoteObject(BUS_NAME, SERVICE_PATH, SecretService::class.java)
            val opened = service.OpenSession("plain", Variant(""))
            SecretServiceStore(portal, service, opened.session)
        } catch (e: Exception) {
            null
        }
    }
}
