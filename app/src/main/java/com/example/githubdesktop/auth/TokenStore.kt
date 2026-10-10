package com.example.githubdesktop.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the GitHub access token encrypted with the Android Keystore.
 * The PKCE code_verifier is NEVER stored here — it lives only in memory
 * for the short duration of the login handshake.
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
    fun clear() = prefs.edit().clear().apply()
}
