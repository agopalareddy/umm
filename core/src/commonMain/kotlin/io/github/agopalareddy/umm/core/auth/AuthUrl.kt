package io.github.agopalareddy.umm.core.auth

import java.net.URLEncoder

object AuthUrl {
    /**
     * OpenRouter only accepts web callbacks (a umm:// callback lands on its home page), so it returns to a page
     * in this repo's GitHub Pages site (docs/oauth/index.html), which hands the code to the app as umm://oauth.
     */
    const val CALLBACK = "https://agopalareddy.github.io/umm/oauth/"

    /** OpenRouter's PKCE authorization page. A null [callback] selects headless mode (user copies the code). */
    fun build(challenge: String, callback: String? = CALLBACK, keyLabel: String = "Umm"): String {
        val params = buildList {
            if (callback != null) add("callback_url" to callback)
            add("code_challenge" to challenge)
            add("code_challenge_method" to "S256")
            add("key_label" to keyLabel)
        }
        return "https://openrouter.ai/auth?" + params.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
    }

    /** Spaces as %20, matching the android.net.Uri output of earlier versions. */
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
