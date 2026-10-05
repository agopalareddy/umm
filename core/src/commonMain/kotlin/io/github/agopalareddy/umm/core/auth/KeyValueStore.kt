package io.github.agopalareddy.umm.core.auth

/** Small string store behind [ApiKeyStore]: SharedPreferences on Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(vararg keys: String)
}
