package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.auth.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A keyring-backed store that never throws: when the keyring refuses a write (locked, prompt dismissed, no default
 * collection) the value is kept in memory instead and [failed] turns true, so the app can say the key won't survive a
 * restart. Reads prefer the in-memory value, which is always the newer one.
 */
internal class ResilientKeyValueStore(
    private val keyring: KeyValueStore,
    private val memory: KeyValueStore = InMemoryKeyValueStore(),
) : KeyValueStore {
    private val _failed = MutableStateFlow(false)

    /** True once a keyring write has failed and its value is only in memory. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    override fun getString(key: String): String? =
        memory.getString(key) ?: runCatching { keyring.getString(key) }.getOrNull()

    override fun putString(key: String, value: String) {
        try {
            keyring.putString(key, value)
            memory.remove(key)
        } catch (e: Exception) {
            memory.putString(key, value)
            _failed.value = true
        }
    }

    override fun remove(vararg keys: String) {
        memory.remove(*keys)
        try {
            keyring.remove(*keys)
        } catch (e: Exception) {
            _failed.value = true
        }
    }
}
