package com.example.githubdesktop.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE = Proof Key for Code Exchange (RFC 7636, method S256).
 *
 * Flow:
 *   1. Generate random code_verifier (43-128 chars, base64url).
 *   2. code_challenge = BASE64URL( SHA256( code_verifier ) ).
 *   3. Send code_challenge to GitHub's /authorize.
 *   4. GitHub redirects back with ?code=...
 *   5. Exchange code + original code_verifier at /access_token.
 *
 * The verifier never leaves the device. GitHub still requires an OAuth App
 * client secret at token exchange; PKCE is an additional protection, not a
 * replacement for that credential.
 */
object Pkce {

    private const val VERIFIER_BYTES = 32 // 43-char verifier after base64url

    fun generateVerifier(): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        SecureRandom().nextBytes(bytes)
        return base64UrlNoPad(bytes)
    }

    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPad(digest)
    }

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
