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
 * - Credentials resolve as: BuildConfig (baked in at build time) first, then
 *   user-supplied values entered in the app and kept in [TokenStore].
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
     * User-supplied OAuth App credentials, loaded from [TokenStore] at startup
     * and updated when the user saves new values in the login screen.
     * Only used when the build did not bake credentials in.
     */
    @Volatile
    var runtimeClientId: String = ""
        private set

    @Volatile
    var runtimeClientSecret: String = ""
        private set

    fun setRuntimeCredentials(clientId: String, clientSecret: String) {
        runtimeClientId = clientId.trim()
        runtimeClientSecret = clientSecret.trim()
    }

    /** Build-time credentials win; runtime (user-entered) values are fallback. */
    val activeClientId: String
        get() = BuildConfig.GITHUB_CLIENT_ID.ifBlank { runtimeClientId }

    val activeClientSecret: String
        get() = BuildConfig.GITHUB_CLIENT_SECRET.ifBlank { runtimeClientSecret }

    /** True when the build baked credentials in (user cannot / need not change them). */
    val hasBuildTimeCredentials: Boolean
        get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank() &&
            BuildConfig.GITHUB_CLIENT_SECRET.isNotBlank()

    /**
     * GitHub requires both values for the authorization-code token exchange.
     * When false, the login screen lets the user paste their own OAuth App
     * credentials or sign in with a personal access token instead — sign-in
     * itself is never disabled.
     */
    val isConfigured: Boolean
        get() = activeClientId.isNotBlank() && activeClientSecret.isNotBlank()

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
        val clientId = activeClientId
        if (!isConfigured) {
            throw LoginException(
                "OAuth is not configured yet. Enter your OAuth App's Client ID and " +
                    "Client Secret below, or sign in with a personal access token."
            )
        }

        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.challengeS256(verifier)
        val state = Pkce.generateVerifier() // 256 bits of nonce, same generator
        pendingVerifier = verifier
        pendingState = state

        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
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
                .add("client_id", activeClientId)
                .add("client_secret", activeClientSecret)
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
