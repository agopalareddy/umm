package io.github.agopalareddy.umm.core.auth

import android.net.Uri

object AuthUrl {
    const val CALLBACK = "umm://oauth"

    /** OpenRouter's PKCE authorization page. A null [callback] selects headless mode (user copies the code). */
    fun build(challenge: String, callback: String? = CALLBACK, keyLabel: String = "Umm"): Uri =
        Uri.parse("https://openrouter.ai/auth").buildUpon()
            .apply { if (callback != null) appendQueryParameter("callback_url", callback) }
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("key_label", keyLabel)
            .build()
}
