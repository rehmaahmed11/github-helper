package com.example.githubdesktop.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the GitHub access token encrypted with the Android Keystore.
 * The PKCE code_verifier is NEVER stored here — it lives only in memory
 * for the short duration of the login handshake.
 *
 * Also stores user-supplied OAuth App credentials (Client ID + Secret) so
 * sign-in via the browser works without rebuilding the app. Build-time
 * values from BuildConfig always take precedence when present.
 */
class TokenStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "gh_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(token: String, login: String) {
        prefs.edit()
            .putString("token", token)
            .putString("login", login)
            .apply()
    }

    fun getToken(): String? = prefs.getString("token", null)
    fun getLogin(): String? = prefs.getString("login", null)

    /**
     * Clears only the session (token + login). User-supplied OAuth App
     * credentials are kept so the user does not have to re-enter them
     * after every logout.
     */
    fun clear() {
        prefs.edit()
            .remove("token")
            .remove("login")
            .apply()
    }

    fun saveOAuthCredentials(clientId: String, clientSecret: String) {
        prefs.edit()
            .putString("oauth_client_id", clientId.trim())
            .putString("oauth_client_secret", clientSecret.trim())
            .apply()
    }

    fun getOAuthClientId(): String? =
        prefs.getString("oauth_client_id", null)?.takeIf { it.isNotBlank() }

    fun getOAuthClientSecret(): String? =
        prefs.getString("oauth_client_secret", null)?.takeIf { it.isNotBlank() }

    fun clearOAuthCredentials() {
        prefs.edit()
            .remove("oauth_client_id")
            .remove("oauth_client_secret")
            .apply()
    }
}
