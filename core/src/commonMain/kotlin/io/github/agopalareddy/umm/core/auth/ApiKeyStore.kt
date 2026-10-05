package io.github.agopalareddy.umm.core.auth

import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the key got here, shown on the Account page. */
enum class KeySource { SIGNED_IN, PASTED, DEVELOPER }

/** Holds the user's OpenRouter key, encrypted at rest. */
class ApiKeyStore(private val store: KeyValueStore, private val cipher: SecretCipher) {
    private val _key = MutableStateFlow(load())
    val key: StateFlow<String?> = _key.asStateFlow()

    private val _source = MutableStateFlow(if (_key.value == null) null else loadSource())
    val source: StateFlow<KeySource?> = _source.asStateFlow()

    fun get(): String? = _key.value

    fun set(key: String, source: KeySource = KeySource.PASTED) {
        val blob = cipher.encrypt(key.toByteArray(Charsets.UTF_8))
        store.putString(PREF, Base64.getEncoder().encodeToString(blob))
        store.putString(SOURCE, source.name)
        _key.value = key
        _source.value = source
    }

    fun clear() {
        store.remove(PREF, SOURCE)
        _key.value = null
        _source.value = null
    }

    private fun loadSource(): KeySource =
        store.getString(SOURCE)?.let { runCatching { KeySource.valueOf(it) }.getOrNull() } ?: KeySource.PASTED

    private fun load(): String? {
        val stored = store.getString(PREF) ?: return null
        return runCatching {
            String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        private const val PREF = "openrouter_key"
        private const val SOURCE = "openrouter_key_source"

        /** "sk-or-v1-…cdef": enough to recognize a key, not enough to use it. */
        fun mask(key: String): String {
            val prefix = Regex("^sk-or-v\\d+-").find(key)?.value.orEmpty()
            val tail = if (key.length - prefix.length > 8) key.takeLast(4) else ""
            return "$prefix…$tail"
        }
    }
}
