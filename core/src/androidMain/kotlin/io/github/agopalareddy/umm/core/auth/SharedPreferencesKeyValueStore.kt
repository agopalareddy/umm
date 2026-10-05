package io.github.agopalareddy.umm.core.auth

import android.content.SharedPreferences

class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(vararg keys: String) {
        prefs.edit().apply { keys.forEach(::remove) }.apply()
    }
}
