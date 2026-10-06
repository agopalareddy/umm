package io.github.agopalareddy.umm.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 PKCE helpers for the OpenRouter sign-in flow. */
object Pkce {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val random = SecureRandom()

    fun newVerifier(): String = buildString(64) {
        repeat(64) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
