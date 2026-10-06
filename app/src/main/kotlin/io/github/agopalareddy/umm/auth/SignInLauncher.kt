package io.github.agopalareddy.umm.auth

import android.app.Activity
import android.content.Context
import android.os.Build
import java.time.LocalDate
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import io.github.agopalareddy.umm.core.auth.AuthUrl
import io.github.agopalareddy.umm.core.auth.Pkce

/** Starts OpenRouter's PKCE sign-in in a Custom Tab; [OAuthCallbackActivity] finishes it. */
object SignInLauncher {
    private const val PREFS = "auth_pending"
    private const val VERIFIER = "verifier"

    fun start(activity: Activity) {
        val verifier = Pkce.newVerifier()
        // Plain prefs so the verifier survives process death while the browser is open.
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(VERIFIER, verifier).apply()
        CustomTabsIntent.Builder().build().launchUrl(activity, AuthUrl.build(Pkce.challengeFor(verifier), keyLabel = keyLabel()).toUri())
    }

    /** Each sign-in creates a new key on OpenRouter, so name it clearly enough to find and delete later. */
    private fun keyLabel(): String = "Umm · ${Build.MODEL} · ${LocalDate.now()}"

    fun takePendingVerifier(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(VERIFIER, null).also { prefs.edit().remove(VERIFIER).apply() }
    }
}
