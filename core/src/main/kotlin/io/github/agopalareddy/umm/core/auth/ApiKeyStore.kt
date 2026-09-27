package io.github.agopalareddy.umm.core.auth

import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the user's OpenRouter key, encrypted at rest. */
class ApiKeyStore(private val prefs: SharedPreferences, private val cipher: SecretCipher) {
    private val _key = MutableStateFlow(load())
    val key: StateFlow<String?> = _key.asStateFlow()

    fun get(): String? = _key.value

    fun set(key: String) {
        val blob = cipher.encrypt(key.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(PREF, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        _key.value = key
    }

    fun clear() {
        prefs.edit().remove(PREF).apply()
        _key.value = null
    }

    private fun load(): String? {
        val stored = prefs.getString(PREF, null) ?: return null
        return runCatching {
            String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val PREF = "openrouter_key"
    }
}
