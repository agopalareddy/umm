package io.github.agopalareddy.umm.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.agopalareddy.umm.core.auth.KeySource
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.settings.MainActivity
import kotlinx.coroutines.launch

/** Receives umm://oauth?code=… from OpenRouter and exchanges the code for an API key. */
class OAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.data?.getQueryParameter("code")
        val verifier = SignInLauncher.takePendingVerifier(this)
        if (code == null || verifier == null) {
            Toast.makeText(this, "Sign-in was cancelled or expired. Try again.", Toast.LENGTH_LONG).show()
            finishToMain()
            return
        }
        lifecycleScope.launch {
            runCatching { graph.openRouter.exchangeAuthCode(code, verifier) }
                .onSuccess { graph.apiKeyStore.set(it, KeySource.SIGNED_IN) }
                .onFailure { Toast.makeText(this@OAuthCallbackActivity, "Couldn't finish sign-in. Try again.", Toast.LENGTH_LONG).show() }
            finishToMain()
        }
    }

    private fun finishToMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
