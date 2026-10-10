package com.example.githubdesktop.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.example.githubdesktop.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub OAuth 2.0 Authorization Code + PKCE (S256).
 *
 * - GitHub requires the OAuth App client secret when exchanging the code, even
 *   when PKCE is used. A native APK is a public client, so that value can be
 *   extracted; PKCE helps protect intercepted authorization codes.
 * - Each user authorizes with their own GitHub account.
 * - code_verifier is held in memory only; never written to disk.
 * - `state` is generated per attempt and verified on redirect (CSRF protection).
 */
object OAuthManager {

    val REDIRECT_URI: String = BuildConfig.GITHUB_REDIRECT_URI

    private const val AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val SCOPES = "repo user delete_repo"

    /** Held between /authorize and /access_token. Memory only. */
    @Volatile
    var pendingVerifier: String? = null
        private set

    /** CSRF nonce for the in-flight attempt. Memory only. */
    @Volatile
    var pendingState: String? = null
        private set

    /**
     * GitHub requires both values for the authorization-code token exchange.
     * The UI explains the missing build configuration instead of failing later.
     */
    val isConfigured: Boolean
        get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank() &&
            BuildConfig.GITHUB_CLIENT_SECRET.isNotBlank()

    // A single shared client: constructing one per call leaks pools/sockets.
    // Deliberately separate from GitHubClient's, which injects the user token.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Thrown when a sign-in attempt was started before we could finish it. */
    class LoginException(message: String) : Exception(message)

    fun launchLogin(context: Context) {
        if (!isConfigured) {
            throw LoginException(
                "GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET are not configured for this build. " +
                    "See the README to set both values for your OAuth App."
            )
        }

        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.challengeS256(verifier)
        val state = Pkce.generateVerifier() // 256 bits of nonce, same generator
        pendingVerifier = verifier
        pendingState = state

        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.GITHUB_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    }

    /**
     * Verifies the CSRF nonce, then exchanges ?code=... + code_verifier for a token.
     *
     * @param returnedState the `state` query parameter echoed back by GitHub.
     * @throws LoginException if the nonce does not match or the exchange fails.
     */
    suspend fun exchangeCode(code: String, returnedState: String?): String =
        withContext(Dispatchers.IO) {
            val verifier = pendingVerifier
            val expectedState = pendingState

            // One-shot, success or failure: neither is reusable.
            pendingVerifier = null
            pendingState = null

            if (verifier == null) {
                // Usually means the process was restarted between /authorize and
                // the redirect, so the in-memory verifier is gone.
                throw LoginException(
                    "Sign-in timed out. Please try again - the app was restarted " +
                        "before authorization finished."
                )
            }
            if (returnedState.isNullOrBlank() || returnedState != expectedState) {
                throw LoginException("Sign-in failed: security check did not match. Please try again.")
            }

            val body = FormBody.Builder()
                .add("client_id", BuildConfig.GITHUB_CLIENT_ID)
                .add("client_secret", BuildConfig.GITHUB_CLIENT_SECRET)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val req = Request.Builder()
                .url(TOKEN_URL)
                .header("Accept", "application/json")
                .post(body)
                .build()

            val raw = try {
                http.newCall(req).execute().use { it.body?.string() }
            } catch (e: Exception) {
                throw LoginException("Could not reach GitHub: ${e.message ?: "network error"}")
            } ?: throw LoginException("GitHub returned an empty response.")

            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw LoginException("Unexpected response from GitHub.")
            }

            json.optString("access_token").takeIf { it.isNotBlank() }
                ?: run {
                    val error = json.optString("error")
                    val message = when (error) {
                        "incorrect_client_credentials" ->
                            "GitHub rejected the OAuth credentials. Check that the Client ID " +
                                "and Client Secret belong to the same OAuth App."
                        "bad_verification_code" ->
                            "GitHub rejected the authorization code. It may have expired or " +
                                "already been used; check that the callback URL matches exactly."
                        else -> json.optString("error_description")
                            .ifBlank { error.ifBlank { "GitHub rejected the sign-in." } }
                    }
                    throw LoginException(message)
                }
        }

    fun cancel() {
        pendingVerifier = null
        pendingState = null
    }
}